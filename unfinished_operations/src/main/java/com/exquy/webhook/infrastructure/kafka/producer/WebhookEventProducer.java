package com.exquy.webhook.infrastructure.kafka.producer;

import com.company.transactionrecovery.domain.enums.WebhookEventType;
import com.company.transactionrecovery.domain.model.Transaction;
import com.company.transactionrecovery.domain.model.WebhookConfig;
import com.company.transactionrecovery.infrastructure.kafka.dto.WebhookEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Producer for webhook events to be sent through Kafka.
 * This component publishes webhook event messages to Kafka topics,
 * which are then consumed by the webhook delivery service.
 */
@Component
public class WebhookEventProducer {

    private static final Logger logger = LoggerFactory.getLogger(WebhookEventProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${spring.kafka.topics.webhook-events}")
    private String webhookEventsTopic;

    @Autowired
    public WebhookEventProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Sends a webhook event message for a transaction.
     *
     * @param webhookConfig The webhook configuration to send to
     * @param transaction The transaction that triggered the event
     * @param eventType The type of event
     * @param additionalData Additional data to include in the event
     * @return The ID of the sent event
     */
    public UUID sendTransactionEvent(
            WebhookConfig webhookConfig,
            Transaction transaction,
            WebhookEventType eventType,
            Map<String, Object> additionalData) {
        
        UUID eventId = UUID.randomUUID();
        
        Map<String, Object> payload = createTransactionEventPayload(
                transaction, eventType, additionalData);
        
        WebhookEventMessage message = WebhookEventMessage.builder()
                .eventId(eventId)
                .webhookId(webhookConfig.getId())
                .transactionId(transaction.getId())
                .eventType(eventType)
                .payload(payload)
                .timestamp(LocalDateTime.now())
                .build();
        
        sendWebhookEventMessage(message, eventId);
        
        logger.info("Sent webhook event of type {} for transaction {} to webhook {}",
                eventType, transaction.getId(), webhookConfig.getId());
        
        return eventId;
    }

    /**
     * Sends a system webhook event (not related to a specific transaction).
     *
     * @param webhookConfig The webhook configuration to send to
     * @param eventType The type of event
     * @param payload The event payload
     * @return The ID of the sent event
     */
    public UUID sendSystemEvent(
            WebhookConfig webhookConfig,
            WebhookEventType eventType,
            Map<String, Object> payload) {
        
        UUID eventId = UUID.randomUUID();
        
        WebhookEventMessage message = WebhookEventMessage.builder()
                .eventId(eventId)
                .webhookId(webhookConfig.getId())
                .eventType(eventType)
                .payload(payload)
                .timestamp(LocalDateTime.now())
                .build();
        
        sendWebhookEventMessage(message, eventId);
        
        logger.info("Sent system webhook event of type {} to webhook {}",
                eventType, webhookConfig.getId());
        
        return eventId;
    }

    /**
     * Sends a test webhook event.
     *
     * @param webhookConfig The webhook configuration to send to
     * @return The ID of the sent event
     */
    public UUID sendTestEvent(WebhookConfig webhookConfig) {
        UUID eventId = UUID.randomUUID();
        
        Map<String, Object> payload = new HashMap<>();
        payload.put("event", "test");
        payload.put("timestamp", LocalDateTime.now().toString());
        payload.put("webhook_id", webhookConfig.getId().toString());
        payload.put("message", "This is a test webhook event");
        
        WebhookEventMessage message = WebhookEventMessage.builder()
                .eventId(eventId)
                .webhookId(webhookConfig.getId())
                .eventType(WebhookEventType.TEST)
                .payload(payload)
                .timestamp(LocalDateTime.now())
                .build();
        
        sendWebhookEventMessage(message, eventId);
        
        logger.info("Sent test webhook event to webhook {}", webhookConfig.getId());
        
        return eventId;
    }

    /**
     * Sends a batch of transaction events to multiple webhooks.
     *
     * @param webhookConfigs List of webhook configurations to send to
     * @param transaction The transaction that triggered the event
     * @param eventType The type of event
     * @param additionalData Additional data to include in the event
     * @return Map of webhook IDs to sent event IDs
     */
    public Map<UUID, UUID> sendTransactionEventBatch(
            List<WebhookConfig> webhookConfigs,
            Transaction transaction,
            WebhookEventType eventType,
            Map<String, Object> additionalData) {
        
        Map<UUID, UUID> eventIds = new HashMap<>();
        
        for (WebhookConfig webhookConfig : webhookConfigs) {
            UUID eventId = sendTransactionEvent(
                    webhookConfig, transaction, eventType, additionalData);
            eventIds.put(webhookConfig.getId(), eventId);
        }
        
        return eventIds;
    }

    /**
     * Creates the payload for a transaction event.
     *
     * @param transaction The transaction
     * @param eventType The event type
     * @param additionalData Additional data to include
     * @return The payload map
     */
    private Map<String, Object> createTransactionEventPayload(
            Transaction transaction,
            WebhookEventType eventType,
            Map<String, Object> additionalData) {
        
        Map<String, Object> payload = new HashMap<>();
        
        // Basic event information
        payload.put("event_type", eventType.toString());
        payload.put("timestamp", LocalDateTime.now().toString());
        
        // Transaction data
        Map<String, Object> transactionData = new HashMap<>();
        transactionData.put("id", transaction.getId().toString());
        transactionData.put("origin_system", transaction.getOriginSystem());
        transactionData.put("status", transaction.getStatus().toString());
        transactionData.put("created_at", transaction.getCreatedAt().toString());
        transactionData.put("updated_at", transaction.getUpdatedAt().toString());
        
        if (transaction.getCompletionAt() != null) {
            transactionData.put("completed_at", transaction.getCompletionAt().toString());
        }
        
        transactionData.put("attempt_count", transaction.getAttemptCount());
        
        // Include appropriate details based on status and event type
        if ((transaction.getStatus().toString().equals("COMPLETED") || 
             eventType == WebhookEventType.TRANSACTION_COMPLETED) && 
            transaction.getResponse() != null) {
            transactionData.put("response", transaction.getResponse());
        }
        
        if ((transaction.getStatus().toString().equals("FAILED") || 
             eventType == WebhookEventType.TRANSACTION_FAILED) && 
            transaction.getErrorDetails() != null) {
            transactionData.put("error_details", transaction.getErrorDetails());
        }
        
        payload.put("transaction", transactionData);
        
        // Add any additional data
        if (additionalData != null && !additionalData.isEmpty()) {
            additionalData.forEach((key, value) -> {
                if (!payload.containsKey(key)) {
                    payload.put(key, value);
                }
            });
        }
        
        return payload;
    }

    /**
     * Sends a webhook event message to Kafka.
     *
     * @param message The message to send
     * @param eventId The event ID for logging
     */
    private void sendWebhookEventMessage(WebhookEventMessage message, UUID eventId) {
        try {
            String key = message.getWebhookId().toString();
            
            ListenableFuture<SendResult<String, Object>> future = 
                    kafkaTemplate.send(webhookEventsTopic, key, message);
            
            future.addCallback(new ListenableFutureCallback<SendResult<String, Object>>() {
                @Override
                public void onSuccess(SendResult<String, Object> result) {
                    logger.debug("Sent webhook event message {} to topic {} with offset {}",
                            eventId, 
                            result.getRecordMetadata().topic(),
                            result.getRecordMetadata().offset());
                }
                
                @Override
                public void onFailure(Throwable ex) {
                    logger.error("Unable to send webhook event message {} to topic {}",
                            eventId, webhookEventsTopic, ex);
                }
            });
        } catch (Exception e) {
            logger.error("Error during webhook event message production", e);
            throw e;
        }
    }
}
