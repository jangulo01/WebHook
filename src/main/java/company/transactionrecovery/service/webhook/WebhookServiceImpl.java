package com.company.transactionrecovery.domain.service.webhook;

import com.company.transactionrecovery.api.exception.GlobalExceptionHandler.WebhookDeliveryException;
import com.company.transactionrecovery.api.exception.GlobalExceptionHandler.WebhookNotFoundException;
import com.company.transactionrecovery.domain.enums.WebhookDeliveryStatus;
import com.company.transactionrecovery.domain.enums.WebhookEventType;
import com.company.transactionrecovery.domain.model.Transaction;
import com.company.transactionrecovery.domain.model.WebhookConfig;
import com.company.transactionrecovery.domain.model.WebhookDelivery;
import com.company.transactionrecovery.domain.repository.WebhookConfigRepository;
import com.company.transactionrecovery.domain.repository.WebhookDeliveryRepository;
import com.company.transactionrecovery.infrastructure.http.WebhookClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of the WebhookService interface.
 * Handles webhook notifications, delivery tracking, and retry logic.
 */
@Service
public class WebhookServiceImpl implements WebhookService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookServiceImpl.class);

    private final WebhookConfigRepository webhookConfigRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookClient webhookClient;
    private final ObjectMapper objectMapper;

    @Value("${webhook.retry.max-attempts:5}")
    private int maxRetryAttempts;

    @Value("${webhook.retry.base-delay-seconds:60}")
    private int baseRetryDelaySeconds;

    @Value("${webhook.signature.algorithm:HmacSHA256}")
    private String signatureAlgorithm;

    @Autowired
    public WebhookServiceImpl(
            WebhookConfigRepository webhookConfigRepository,
            WebhookDeliveryRepository deliveryRepository,
            WebhookClient webhookClient,
            ObjectMapper objectMapper) {
        this.webhookConfigRepository = webhookConfigRepository;
        this.deliveryRepository = deliveryRepository;
        this.webhookClient = webhookClient;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public List<WebhookDelivery> sendTransactionEventNotification(
            Transaction transaction,
            WebhookEventType eventType,
            Map<String, Object> additionalData) {
        
        logger.info("Sending {} webhook notifications for transaction: {}", 
                eventType, transaction.getId());

        List<WebhookDelivery> deliveries = new ArrayList<>();

        // First, check if the transaction has a specific webhook URL configured
        if (transaction.hasWebhookEnabled()) {
            Map<String, Object> payload = createTransactionEventPayload(
                    transaction, eventType, additionalData);

            // Create a synthetic webhook config for this specific URL
            WebhookConfig config = webhookConfigRepository.findByCallbackUrl(transaction.getWebhookUrl())
                    .orElseGet(() -> {
                        // If not found, create a temporary config (not saved to DB)
                        return WebhookConfig.builder()
                                .id(UUID.randomUUID())
                                .callbackUrl(transaction.getWebhookUrl())
                                .securityToken(transaction.getWebhookSecurityToken())
                                .originSystem(transaction.getOriginSystem())
                                .isActive(true)
                                .build();
                    });

            WebhookDelivery delivery = createWebhookDelivery(
                    config.getId(), transaction.getId(), eventType, payload);
            
            delivery = deliveryRepository.save(delivery);
            deliveries.add(delivery);
            
            // Send asynchronously
            sendWebhookAsync(delivery);
        }

        // Then, find all active webhooks configured for this event type
        List<WebhookConfig> configs = webhookConfigRepository
                .findActiveWebhooksByEventTypeGeneric(eventType)
                .stream()
                .filter(config -> config.getOriginSystem().equals(transaction.getOriginSystem()))
                .collect(Collectors.toList());

        for (WebhookConfig config : configs) {
            // Skip if this is the same as the transaction-specific webhook
            if (transaction.hasWebhookEnabled() && 
                config.getCallbackUrl().equals(transaction.getWebhookUrl())) {
                continue;
            }

            Map<String, Object> payload = createTransactionEventPayload(
                    transaction, eventType, additionalData);

            WebhookDelivery delivery = createWebhookDelivery(
                    config.getId(), transaction.getId(), eventType, payload);
            
            delivery = deliveryRepository.save(delivery);
            deliveries.add(delivery);
            
            // Send asynchronously
            sendWebhookAsync(delivery);
        }

        return deliveries;
    }

    @Override
    @Transactional
    public WebhookDelivery sendWebhookNotification(
            UUID webhookId,
            UUID transactionId,
            WebhookEventType eventType,
            Map<String, Object> payload) {
        
        WebhookConfig config = webhookConfigRepository.findById(webhookId)
                .orElseThrow(() -> new WebhookNotFoundException("Webhook not found with ID: " + webhookId));

        if (!config.isActive()) {
            logger.warn("Attempting to send notification to inactive webhook: {}", webhookId);
            throw new WebhookDeliveryException("Cannot send notification to inactive webhook");
        }

        WebhookDelivery delivery = createWebhookDelivery(webhookId, transactionId, eventType, payload);
        delivery = deliveryRepository.save(delivery);

        // Send asynchronously
        sendWebhookAsync(delivery);

        return delivery;
    }

    @Override
    @Transactional
    public WebhookDelivery sendTestEvent(UUID webhookId) {
        WebhookConfig config = webhookConfigRepository.findById(webhookId)
                .orElseThrow(() -> new WebhookNotFoundException("Webhook not found with ID: " + webhookId));

        Map<String, Object> testPayload = new HashMap<>();
        testPayload.put("event", "test");
        testPayload.put("timestamp", LocalDateTime.now().toString());
        testPayload.put("webhookId", webhookId.toString());

        WebhookDelivery delivery = createWebhookDelivery(
                webhookId, null, WebhookEventType.TEST, testPayload);
        
        delivery = deliveryRepository.save(delivery);

        // For test events, send synchronously so we can return the result immediately
        try {
            delivery = sendWebhookDelivery(delivery);
        } catch (Exception e) {
            logger.error("Error sending test webhook: {}", e.getMessage());
            delivery = handleFailedDelivery(delivery, e);
        }

        return delivery;
    }

    @Override
    @Transactional
    public WebhookDelivery acknowledgeDelivery(UUID deliveryId, String status) {
        WebhookDelivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new WebhookDeliveryException("Webhook delivery not found: " + deliveryId));

        delivery.markAcknowledged(status);
        return deliveryRepository.save(delivery);
    }

    @Override
    @Transactional
    public WebhookDelivery retryDelivery(UUID deliveryId) {
        WebhookDelivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new WebhookDeliveryException("Webhook delivery not found: " + deliveryId));

        if (delivery.isTerminalState()) {
            throw new WebhookDeliveryException(
                    "Cannot retry delivery in terminal state: " + delivery.getDeliveryStatus());
        }

        // Reset status to prepare for retry
        delivery.setDeliveryStatus(WebhookDeliveryStatus.RETRY_SCHEDULED);
        delivery.setNextRetryAt(LocalDateTime.now());
        delivery = deliveryRepository.save(delivery);

        // Trigger delivery asynchronously
        sendWebhookAsync(delivery);

        return delivery;
    }

    @Override
    @Transactional
    public int processScheduledRetries() {
        List<WebhookDelivery> dueDeliveries = deliveryRepository.findDeliveriesDueForRetry(LocalDateTime.now());
        
        logger.info("Processing {} scheduled webhook retries", dueDeliveries.size());
        
        int processed = 0;
        
        for (WebhookDelivery delivery : dueDeliveries) {
            sendWebhookAsync(delivery);
            processed++;
        }
        
        return processed;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WebhookDelivery> getDeliveriesByWebhookId(UUID webhookId) {
        return deliveryRepository.findByWebhookId(webhookId, null).getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public List<WebhookDelivery> getDeliveriesByTransactionId(UUID transactionId) {
        return deliveryRepository.findByTransactionId(transactionId, null).getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getDeliveryStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        // Get counts by status
        List<WebhookDeliveryRepository.StatusCount> statusCounts = deliveryRepository.countByDeliveryStatus();
        Map<String, Long> countsByStatus = new HashMap<>();
        
        statusCounts.forEach(sc -> 
            countsByStatus.put(sc.getStatus().toString(), sc.getCount())
        );
        
        stats.put("countsByStatus", countsByStatus);
        
        // Calculate success rate
        long totalDeliveries = deliveryRepository.count();
        long successfulDeliveries = countsByStatus.getOrDefault("DELIVERED", 0L);
        
        double successRate = totalDeliveries > 0 
                ? (double) successfulDeliveries / totalDeliveries * 100
                : 0;
        
        stats.put("successRate", Math.round(successRate * 100) / 100.0); // Round to 2 decimal places
        stats.put("totalDeliveries", totalDeliveries);
        
        return stats;
    }

    @Override
    @Transactional
    public WebhookDelivery handleFailedDelivery(WebhookDelivery delivery, Throwable error) {
        logger.warn("Handling failed webhook delivery: {}, attempt: {}, error: {}", 
                delivery.getId(), delivery.getAttemptCount(), error.getMessage());

        // Set error details
        Map<String, Object> errorDetails = new HashMap<>();
        errorDetails.put("message", error.getMessage());
        errorDetails.put("type", error.getClass().getName());
        
        delivery.setErrorDetails(errorDetails);

        // Determine if we should retry
        if (delivery.getAttemptCount() >= maxRetryAttempts) {
            return markAsPermanentlyFailed(delivery);
        } else {
            // Schedule next retry
            int delaySeconds = computeNextRetryDelay(delivery);
            delivery.scheduleRetry(LocalDateTime.now().plusSeconds(delaySeconds));
            
            logger.info("Scheduled retry for webhook delivery: {}, next attempt in {} seconds", 
                    delivery.getId(), delaySeconds);
            
            return deliveryRepository.save(delivery);
        }
    }

    @Override
    public int computeNextRetryDelay(WebhookDelivery delivery) {
        // Exponential backoff with jitter
        int attempt = delivery.getAttemptCount();
        int baseDelay = baseRetryDelaySeconds;
        
        // 2^attempt * baseDelay (e.g., 60s, 120s, 240s, 480s, ...)
        double exponentialDelay = Math.pow(2, attempt - 1) * baseDelay;
        
        // Add up to 25% jitter to avoid thundering herd problem
        double jitterFactor = 1.0 + (Math.random() * 0.25);
        
        int delay = (int) (exponentialDelay * jitterFactor);
        
        // Cap at 1 hour
        return Math.min(delay, 3600);
    }

    @Override
    @Transactional
    public WebhookDelivery markAsPermanentlyFailed(WebhookDelivery delivery) {
        delivery.setDeliveryStatus(WebhookDeliveryStatus.PERMANENTLY_FAILED);
        delivery.setNextRetryAt(null);
        
        WebhookDelivery savedDelivery = deliveryRepository.save(delivery);
        
        // Update webhook stats
        try {
            WebhookConfig config = webhookConfigRepository.findById(delivery.getWebhookId())
                    .orElse(null);
            
            if (config != null) {
                config.recordFailure();
                webhookConfigRepository.save(config);
            }
        } catch (Exception e) {
            logger.error("Error updating webhook stats after permanent failure: {}", e.getMessage());
        }
        
        return savedDelivery;
    }

    @Override
    public String generateSignature(String payload, String secret) {
        try {
            Mac hmac = Mac.getInstance(signatureAlgorithm);
            SecretKeySpec secretKey = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), signatureAlgorithm);
            hmac.init(secretKey);
            
            byte[] hash = hmac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            logger.error("Error generating webhook signature: {}", e.getMessage());
            throw new RuntimeException("Failed to generate webhook signature", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public WebhookDelivery getDeliveryStatus(UUID deliveryId) {
        return deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new WebhookDeliveryException("Webhook delivery not found: " + deliveryId));
    }

    /**
     * Creates a webhook delivery entity.
     */
    private WebhookDelivery createWebhookDelivery(
            UUID webhookId, UUID transactionId, WebhookEventType eventType, Map<String, Object> payload) {
        
        return WebhookDelivery.builder()
                .webhookId(webhookId)
                .transactionId(transactionId)
                .eventType(eventType)
                .deliveryStatus(WebhookDeliveryStatus.PENDING)
                .payload(payload)
                .attemptCount(0)
                .build();
    }

    /**
     * Creates the payload for a transaction event notification.
     */
    private Map<String, Object> createTransactionEventPayload(
            Transaction transaction, WebhookEventType eventType, Map<String, Object> additionalData) {
        
        Map<String, Object> payload = new HashMap<>();
        
        payload.put("event_type", eventType.toString());
        payload.put("transaction_id", transaction.getId().toString());
        payload.put("origin_system", transaction.getOriginSystem());
        payload.put("status", transaction.getStatus().toString());
        payload.put("timestamp", LocalDateTime.now().toString());
        
        // Add transaction data selectively (omit sensitive information)
        Map<String, Object> transactionData = new HashMap<>();
        transactionData.put("created_at", transaction.getCreatedAt().toString());
        transactionData.put("updated_at", transaction.getUpdatedAt().toString());
        
        if (transaction.getCompletionAt() != null) {
            transactionData.put("completed_at", transaction.getCompletionAt().toString());
        }
        
        transactionData.put("attempt_count", transaction.getAttemptCount());
        
        // Include appropriate details based on status
        if (transaction.getStatus().toString().equals("COMPLETED") && transaction.getResponse() != null) {
            transactionData.put("response", transaction.getResponse());
        }
        
        if (transaction.getStatus().toString().equals("FAILED") && transaction.getErrorDetails() != null) {
            transactionData.put("error_details", transaction.getErrorDetails());
        }
        
        payload.put("transaction", transactionData);
        
        // Add any additional data
        if (additionalData != null) {
            payload.put("additional_data", additionalData);
        }
        
        return payload;
    }

    /**
     * Asynchronously sends a webhook notification.
     */
    @Async("webhookExecutor")
    protected void sendWebhookAsync(WebhookDelivery delivery) {
        try {
            sendWebhookDelivery(delivery);
        } catch (Exception e) {
            logger.error("Error in async webhook delivery: {}", e.getMessage());
            handleFailedDelivery(delivery, e);
        }
    }

    /**
     * Sends a webhook delivery and updates its status.
     */
    @Transactional
    protected WebhookDelivery sendWebhookDelivery(WebhookDelivery delivery) throws Exception {
        WebhookConfig config = webhookConfigRepository.findById(delivery.getWebhookId())
                .orElseThrow(() -> new WebhookNotFoundException("Webhook not found: " + delivery.getWebhookId()));

        logger.info("Sending webhook delivery: {}, event: {}, attempt: {}", 
                delivery.getId(), delivery.getEventType(), delivery.getAttemptCount() + 1);

        // Update status to PROCESSING
        delivery.setDeliveryStatus(WebhookDeliveryStatus.PROCESSING);
        delivery.recordAttempt(WebhookDeliveryStatus.PROCESSING, null, null);
        delivery = deliveryRepository.save(delivery);

        try {
            // Serialize payload to JSON
            String payloadJson = objectMapper.writeValueAsString(delivery.getPayload());
            
            // Generate signature
            String signature = generateSignature(payloadJson, config.getSecurityToken());
            
            // Send the webhook
            Map<String, Object> headers = new HashMap<>();
            headers.put("X-Webhook-Signature", signature);
            headers.put("X-Webhook-ID", delivery.getWebhookId().toString());
            headers.put("X-Delivery-ID", delivery.getId().toString());
            headers.put("X-Event-Type", delivery.getEventType().toString());
            headers.put("Content-Type", "application/json");
            
            WebhookClient.WebhookResponse response = webhookClient.sendWebhook(
                    config.getCallbackUrl(), 
                    payloadJson, 
                    headers);
            
            // Update delivery with response
            delivery.recordAttempt(
                    WebhookDeliveryStatus.DELIVERED, 
                    response.getStatusCode(), 
                    response.getBody());
            
            delivery = deliveryRepository.save(delivery);
            
            // Update webhook stats
            config.recordSuccess();
            webhookConfigRepository.save(config);
            
            logger.info("Webhook delivery successful: {}, status code: {}", 
                    delivery.getId(), response.getStatusCode());
            
            return delivery;
        } catch (Exception e) {
            logger.error("Error sending webhook: {}", e.getMessage());
            
            // Handle failure (may schedule retry)
            delivery = handleFailedDelivery(delivery, e);
            
            // Re-throw for synchronous calls (like test webhooks)
            throw e;
        }
    }
}
