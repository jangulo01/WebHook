package com.exquy.webhook.infrastructure.dto;

import com.company.transactionrecovery.domain.enums.TransactionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Data Transfer Object for transaction events sent through Kafka.
 * This class represents the structure of transaction event messages
 * exchanged between the transaction event producer and consumer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionEventMessage {
    
    /**
     * Unique identifier for this event.
     */
    private UUID eventId;
    
    /**
     * The type of event.
     * Examples: TRANSACTION_CREATED, TRANSACTION_STATUS_CHANGED, TRANSACTION_RETRY
     */
    private String eventType;
    
    /**
     * Identifier of the transaction this event is about.
     */
    private UUID transactionId;
    
    /**
     * Identifier of the system that originated the transaction.
     */
    private String originSystem;
    
    /**
     * Current status of the transaction.
     */
    private TransactionStatus currentStatus;
    
    /**
     * Previous status of the transaction (if applicable).
     * Used mainly for status change events.
     */
    private TransactionStatus previousStatus;
    
    /**
     * Timestamp when this event was created.
     */
    private LocalDateTime timestamp;
    
    /**
     * The transaction details.
     * Contains relevant information about the transaction based on the event type.
     */
    private Map<String, Object> payload;
    
    /**
     * Whether this event has high priority.
     * High priority events may be processed before others.
     */
    @Builder.Default
    private boolean highPriority = false;
    
    /**
     * Additional metadata about this event.
     */
    private Map<String, Object> metadata;
    
    /**
     * Checks if this is a status change event.
     *
     * @return true if this is a status change event, false otherwise
     */
    public boolean isStatusChangeEvent() {
        return "TRANSACTION_STATUS_CHANGED".equals(eventType);
    }
    
    /**
     * Checks if this is a transaction creation event.
     *
     * @return true if this is a creation event, false otherwise
     */
    public boolean isCreationEvent() {
        return "TRANSACTION_CREATED".equals(eventType);
    }
    
    /**
     * Checks if this is a retry event.
     *
     * @return true if this is a retry event, false otherwise
     */
    public boolean isRetryEvent() {
        return "TRANSACTION_RETRY".equals(eventType);
    }
    
    /**
     * Checks if this is a recovery event.
     *
     * @return true if this is a recovery event, false otherwise
     */
    public boolean isRecoveryEvent() {
        return "TRANSACTION_RECOVERY".equals(eventType);
    }
    
    /**
     * Checks if this is a manual resolution event.
     *
     * @return true if this is a manual resolution event, false otherwise
     */
    public boolean isManualResolutionEvent() {
        return "TRANSACTION_MANUALLY_RESOLVED".equals(eventType);
    }
    
    /**
     * Checks if this is a reconciliation event.
     *
     * @return true if this is a reconciliation event, false otherwise
     */
    public boolean isReconciliationEvent() {
        return "TRANSACTION_RECONCILED".equals(eventType);
    }
    
    /**
     * Gets the age of this event in milliseconds.
     *
     * @return The age of this event in milliseconds
     */
    public long getAgeInMillis() {
        return java.time.Duration.between(timestamp, LocalDateTime.now()).toMillis();
    }
    
    /**
     * Creates a user-friendly description of this event.
     *
     * @return A string describing this event
     */
    public String getDescription() {
        StringBuilder description = new StringBuilder();
        description.append(eventType);
        description.append(" [").append(transactionId).append("]");
        
        if (isStatusChangeEvent() && previousStatus != null) {
            description.append(": ").append(previousStatus).append(" → ").append(currentStatus);
        } else if (currentStatus != null) {
            description.append(": ").append(currentStatus);
        }
        
        return description.toString();
    }
}
