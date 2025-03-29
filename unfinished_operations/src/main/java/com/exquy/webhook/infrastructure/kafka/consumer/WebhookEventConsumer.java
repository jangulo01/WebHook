package com.exquy.webhook.infrastructure.kafka.consumer;

import com.company.transactionrecovery.domain.enums.WebhookDeliveryStatus;
import com.company.transactionrecovery.domain.model.WebhookConfig;
import com.company.transactionrecovery.domain.model.WebhookDelivery;
import com.company.transactionrecovery.domain.repository.WebhookConfigRepository;
import com.company.transactionrecovery.domain.repository.WebhookDeliveryRepository;
import com.company.transactionrecovery.domain.service.webhook.WebhookService;
import com.company.transactionrecovery.infrastructure.http.WebhookClient;
import com.company.transactionrecovery.infrastructure.kafka.dto.WebhookEventMessage;
import com.company.transactionrecovery.util.SignatureUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Consumer for webhook events from Kafka.
 * This component listens for webhook event messages and processes them
 * by sending the webhook notifications to the configured endpoints.
 */
@Component
public class WebhookEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(WebhookEventConsumer.class);

    private final WebhookConfigRepository webhookConfigRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookService webhookService;
    private final WebhookClient webhookClient;
    private final ObjectMapper objectMapper;

    @Value("${webhook.retry.max-attempts:5}")
    private int maxRetryAttempts;

    @Value("${webhook.signature.algorithm:HmacSHA256}")
    private String signatureAlgorithm;

    @Autowired
    public WebhookEventConsumer(
            WebhookConfigRepository webhookConfigRepository,
            WebhookDeliveryRepository deliveryRepository,
            WebhookService webhookService,
            WebhookClient webhookClient,
            ObjectMapper objectMapper) {
        this.webhookConfigRepository = webhookConfigRepository;
        this.deliveryRepository = deliveryRepository;
        this.webhookService = webhookService;
        this.webhookClient = webhookClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Listens for webhook event messages from Kafka and processes them.
     *
     * @param message The webhook event message
     */
    @KafkaListener(
            topics = "${spring.kafka.topics.webhook-events}",
            groupId = "${spring.kafka.consumer.group-id}-webhook",
            containerFactory = "webhookKafkaListenerContainerFactory")
    @Transactional
    public void consumeWebhookEvent(WebhookEventMessage message) {
        logger.info("Received webhook event: {}, type: {}, webhook: {}",
                message.getEventId(),
                message.getEventType(),
                message.getWebhookId());

        try {
            // Get webhook configuration
            WebhookConfig webhookConfig = webhookConfigRepository.findById(message.getWebhookId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Webhook configuration not found: " + message.getWebhookId()));

            // Check if webhook is active
            if (!webhookConfig.getIsActive()) {
                logger.warn("Skipping event {} for inactive webhook: {}",
                        message.getEventId(), message.getWebhookId());
                return;
            }

            // Check if existing delivery exists
            WebhookDelivery existingDelivery = deliveryRepository.findById(message.getEventId()).orElse(null);

            // If delivery already exists, skip processing
            if (existingDelivery != null) {
                logger.info("Delivery already exists for event {}, current status: {}",
                        message.getEventId(), existingDelivery.getDeliveryStatus());
                return;
            }

            // Create new delivery record
            WebhookDelivery delivery = createWebhookDelivery(message);
            delivery = deliveryRepository.save(delivery);

            // Process the delivery
            processDelivery(delivery, webhookConfig);

        } catch (Exception e) {
            logger.error("Error processing webhook event: {}", message.getEventId(), e);
        }
    }

    /**
     * Creates a webhook delivery entity from a message.
     *
     * @param message The webhook event message
     * @return The webhook delivery entity
     */
    private WebhookDelivery createWebhookDelivery(WebhookEventMessage message) {
        return WebhookDelivery.builder()
                .id(message.getEventId()) // Use event ID as delivery ID for idempotence
                .webhookId(message.getWebhookId())
                .transactionId(message.getTransactionId())
                .eventType(message.getEventType())
                .deliveryStatus(WebhookDeliveryStatus.PENDING)
                .payload(message.getPayload())
                .attemptCount(0)
                .build();
    }

    /**
     * Processes a webhook delivery by sending the notification.
     *
     * @param delivery The webhook delivery to process
     * @param webhookConfig The webhook configuration
     */
    private void processDelivery(WebhookDelivery delivery, WebhookConfig webhookConfig) {
        logger.info("Processing webhook delivery: {}", delivery.getId());

        try {
            // Update status to PROCESSING
            delivery.setDeliveryStatus(WebhookDeliveryStatus.PROCESSING);
            delivery.recordAttempt(WebhookDeliveryStatus.PROCESSING, null, null);
            delivery = deliveryRepository.save(delivery);

            // Convert payload to JSON
            String payloadJson = objectMapper.writeValueAsString(delivery.getPayload());

            // Generate signature
            String signature = webhookService.generateSignature(payloadJson, webhookConfig.getSecurityToken());

            // Prepare headers
            Map<String, Object> headers = new HashMap<>();
            headers.put("X-Webhook-Signature", signature);
            headers.put("X-Webhook-ID", webhookConfig.getId().toString());
            headers.put("X-Delivery-ID", delivery.getId().toString());
            headers.put("X-Event-Type", delivery.getEventType().toString());
            headers.put("Content-Type", "application/json");

            // Add replay protection
            String replayProtection = createReplayProtection();
            headers.put("X-Webhook-Timestamp", replayProtection);

            // Send the webhook
            WebhookClient.WebhookResponse response = webhookClient.sendWebhook(
                    webhookConfig.getCallbackUrl(),
                    payloadJson,
                    headers);

            // Update delivery with response
            delivery.recordAttempt(
                    WebhookDeliveryStatus.DELIVERED,
                    response.getStatusCode(),
                    response.getBody());
            deliveryRepository.save(delivery);

            // Update webhook stats
            webhookConfig.recordSuccess();
            webhookConfigRepository.save(webhookConfig);

            logger.info("Webhook delivery successful: {}, status code: {}",
                    delivery.getId(), response.getStatusCode());

        } catch (Exception e) {
            logger.error("Error delivering webhook: {}", delivery.getId(), e);
            handleDeliveryFailure(delivery, e);
        }
    }

    /**
     * Handles a failure in webhook delivery.
     *
     * @param delivery The webhook delivery that failed
     * @param error The error that occurred
     */
    private void handleDeliveryFailure(WebhookDelivery delivery, Exception error) {
        try {
            // Build error details
            Map<String, Object> errorDetails = new HashMap<>();
            errorDetails.put("message", error.getMessage());
            errorDetails.put("type", error.getClass().getName());

            // Update delivery with error details
            delivery.recordError(WebhookDeliveryStatus.FAILED, errorDetails);

            // Determine if max retries reached
            if (delivery.getAttemptCount() >= maxRetryAttempts) {
                delivery = webhookService.markAsPermanentlyFailed(delivery);
                logger.warn("Maximum retry attempts reached for webhook delivery: {}", delivery.getId());
            } else {
                // Schedule retry
                int delaySeconds = webhookService.computeNextRetryDelay(delivery);
                delivery.scheduleRetry(delivery.getLastAttemptAt().plusSeconds(delaySeconds));
                logger.info("Scheduled retry for webhook delivery: {}, next attempt in {} seconds",
                        delivery.getId(), delaySeconds);
            }

            // Save updated delivery
            deliveryRepository.save(delivery);

            // Update webhook config stats
            WebhookConfig webhookConfig = webhookConfigRepository.findById(delivery.getWebhookId())
                    .orElse(null);
            if (webhookConfig != null) {
                webhookConfig.recordFailure();
                webhookConfigRepository.save(webhookConfig);
            }

        } catch (Exception e) {
            logger.error("Error handling webhook delivery failure for {}", delivery.getId(), e);
        }
    }

    /**
     * Creates a replay protection timestamp.
     *
     * @return A timestamp string for replay protection
     */
    private String createReplayProtection() {
        long timestamp = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        return "t=" + timestamp + ",n=" + nonce;
    }
}
