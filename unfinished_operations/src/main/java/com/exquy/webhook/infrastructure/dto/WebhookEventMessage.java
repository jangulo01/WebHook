package com.exquy.webhook.infrastructure.dto;

import com.company.transactionrecovery.domain.enums.WebhookEventType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Data Transfer Object for webhook events sent through Kafka.
 * This class represents the structure of webhook event messages
 * exchanged between the webhook event producer and consumer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookEventMessage {
    
    /**
     * Unique identifier for this event.
     * This ID is also used as the WebhookDelivery ID for idempotence.
     */
    private UUID eventId;
    
    /**
     * Identifier of the webhook configuration this event is for.
     */
    private UUID webhookId;
    
    /**
     * Identifier of the transaction that triggered this webhook, if applicable.
     * May be null for system events not related to a specific transaction.
     */
    private UUID transactionId;
    
    /**
     * Type of webhook event.
     */
    private WebhookEventType eventType;
    
    /**
     * Payload to be sent in the webhook notification.
     */
    private Map<String, Object> payload;
    
    /**
     * Timestamp when this event was created.
     */
    private LocalDateTime timestamp;
    
    /**
     * Number of delivery attempts for this event so far.
     * This is used to track retry attempts across consumer restarts.
     */
    @Builder.Default
    private int attemptCount = 0;
    
    /**
     * Whether this event has high priority.
     * High priority events may be processed before others.
     */
    @Builder.Default
    private boolean highPriority = false;
    
    /**
     * The scheduled time for the next delivery attempt, if applicable.
     */
    private LocalDateTime scheduledTime;
    
    /**
     * Additional metadata about this event.
     */
    private Map<String, Object> metadata;

    /**
     * Increments the attempt count.
     */
    public void incrementAttemptCount() {
        this.attemptCount++;
    }
    
    /**
     * Checks if this event is related to a transaction.
     *
     * @return true if this event is related to a transaction, false otherwise
     */
    public boolean isTransactionEvent() {
        return transactionId != null;
    }
    
    /**
     * Checks if this is a test event.
     *
     * @return true if this is a test event, false otherwise
     */
    public boolean isTestEvent() {
        return eventType == WebhookEventType.TEST;
    }
    
    /**
     * Gets the age of this event in milliseconds.
     *
     * @return The age of this event in milliseconds
     */
    public long getAgeInMillis() {
        return java.time.Duration.between(timestamp, LocalDateTime.now()).toMillis();
    }
}
