package com.exquy.webhook.infrastructure.kafka.producer;

import com.company.transactionrecovery.domain.enums.TransactionStatus;
import com.company.transactionrecovery.domain.model.Transaction;
import com.company.transactionrecovery.infrastructure.kafka.dto.TransactionEventMessage;
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
 * Producer for transaction events to be sent through Kafka.
 * This component publishes transaction-related event messages to Kafka topics,
 * which are then consumed by various services for asynchronous processing.
 */
@Component
public class TransactionEventProducer {

    private static final Logger logger = LoggerFactory.getLogger(TransactionEventProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${spring.kafka.topics.transaction-events}")
    private String transactionEventsTopic;

    @Autowired
    public TransactionEventProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Sends an event when a new transaction is created.
     *
     * @param transaction The newly created transaction
     */
    public void sendTransactionCreatedEvent(Transaction transaction) {
        TransactionEventMessage message = TransactionEventMessage.builder()
                .eventId(UUID.randomUUID())
                .eventType("TRANSACTION_CREATED")
                .transactionId(transaction.getId())
                .originSystem(transaction.getOriginSystem())
                .currentStatus(transaction.getStatus())
                .previousStatus(null)
                .timestamp(LocalDateTime.now())
                .payload(createPayload(transaction, null))
                .build();

        sendTransactionEventMessage(message);
        
        logger.info("Sent TRANSACTION_CREATED event for transaction: {}", 
                transaction.getId());
    }

    /**
     * Sends an event when a transaction's status changes.
     *
     * @param transaction The transaction whose status changed
     * @param previousStatus The previous status of the transaction
     */
    public void sendTransactionStatusChangedEvent(
            Transaction transaction, TransactionStatus previousStatus) {
        
        TransactionEventMessage message = TransactionEventMessage.builder()
                .eventId(UUID.randomUUID())
                .eventType("TRANSACTION_STATUS_CHANGED")
                .transactionId(transaction.getId())
                .originSystem(transaction.getOriginSystem())
                .currentStatus(transaction.getStatus())
                .previousStatus(previousStatus)
                .timestamp(LocalDateTime.now())
                .payload(createPayload(transaction, previousStatus))
                .build();

        sendTransactionEventMessage(message);
        
        logger.info("Sent TRANSACTION_STATUS_CHANGED event for transaction: {} (from {} to {})", 
                transaction.getId(), previousStatus, transaction.getStatus());
    }

    /**
     * Sends an event when a transaction is retried.
     *
     * @param transaction The transaction being retried
     */
    public void sendTransactionRetryEvent(Transaction transaction) {
        TransactionEventMessage message = TransactionEventMessage.builder()
                .eventId(UUID.randomUUID())
                .eventType("TRANSACTION_RETRY")
                .transactionId(transaction.getId())
                .originSystem(transaction.getOriginSystem())
                .currentStatus(transaction.getStatus())
                .timestamp(LocalDateTime.now())
                .payload(createRetryPayload(transaction))
                .build();

        sendTransactionEventMessage(message);
        
        logger.info("Sent TRANSACTION_RETRY event for transaction: {} (attempt: {})", 
                transaction.getId(), transaction.getAttemptCount());
    }

    /**
     * Sends an event when a transaction recovery is attempted.
     *
     * @param transaction The transaction being recovered
     */
    public void sendTransactionRecoveryEvent(Transaction transaction) {
        TransactionEventMessage message = TransactionEventMessage.builder()
                .eventId(UUID.randomUUID())
                .eventType("TRANSACTION_RECOVERY")
                .transactionId(transaction.getId())
                .originSystem(transaction.getOriginSystem())
                .currentStatus(transaction.getStatus())
                .timestamp(LocalDateTime.now())
                .payload(createPayload(transaction, null))
                .build();

        sendTransactionEventMessage(message);
        
        logger.info("Sent TRANSACTION_RECOVERY event for transaction: {}", 
                transaction.getId());
    }

    /**
     * Sends an event when a transaction is manually resolved.
     *
     * @param transaction The transaction that was manually resolved
     * @param previousStatus The previous status of the transaction
     */
    public void sendTransactionManuallyResolvedEvent(
            Transaction transaction, TransactionStatus previousStatus) {
        
        TransactionEventMessage message = TransactionEventMessage.builder()
                .eventId(UUID.randomUUID())
                .eventType("TRANSACTION_MANUALLY_RESOLVED")
                .transactionId(transaction.getId())
                .originSystem(transaction.getOriginSystem())
                .currentStatus(transaction.getStatus())
                .previousStatus(previousStatus)
                .timestamp(LocalDateTime.now())
                .payload(createPayload(transaction, previousStatus))
                .build();

        sendTransactionEventMessage(message);
        
        logger.info("Sent TRANSACTION_MANUALLY_RESOLVED event for transaction: {} (from {} to {})", 
                transaction.getId(), previousStatus, transaction.getStatus());
    }

    /**
     * Sends an event when a transaction is reconciled.
     *
     * @param transaction The transaction that was reconciled
     */
    public void sendTransactionReconciledEvent(Transaction transaction) {
        TransactionEventMessage message = TransactionEventMessage.builder()
                .eventId(UUID.randomUUID())
                .eventType("TRANSACTION_RECONCILED")
                .transactionId(transaction.getId())
                .originSystem(transaction.getOriginSystem())
                .currentStatus(transaction.getStatus())
                .timestamp(LocalDateTime.now())
                .payload(createPayload(transaction, null))
                .build();

        sendTransactionEventMessage(message);
        
        logger.info("Sent TRANSACTION_RECONCILED event for transaction: {}", 
                transaction.getId());
    }

    /**
     * Creates a payload map for a transaction event.
     *
     * @param transaction The transaction
     * @param previousStatus The previous status (can be null)
     * @return The payload map
     */
    private Map<String, Object> createPayload(
            Transaction transaction, TransactionStatus previousStatus) {
        
        Map<String, Object> payload = new HashMap<>();
        
        // Add transaction data
        payload.put("transaction_id", transaction.getId().toString());
        payload.put("origin_system", transaction.getOriginSystem());
        payload.put("current_status", transaction.getStatus().toString());
        
        if (previousStatus != null) {
            payload.put("previous_status", previousStatus.toString());
        }
        
        payload.put("created_at", transaction.getCreatedAt().toString());
        payload.put("updated_at", transaction.getUpdatedAt().toString());
        payload.put("attempt_count", transaction.getAttemptCount());
        
        if (transaction.getLastAttemptAt() != null) {
            payload.put("last_attempt_at", transaction.getLastAttemptAt().toString());
        }
        
        if (transaction.getCompletionAt() != null) {
            payload.put("completion_at", transaction.getCompletionAt().toString());
        }
        
        // Add payload, response, or error details based on status
        if (transaction.getPayload() != null) {
            payload.put("request_payload", transaction.getPayload());
        }
        
        if (transaction.getStatus() == TransactionStatus.COMPLETED && 
            transaction.getResponse() != null) {
            payload.put("response", transaction.getResponse());
        }
        
        if (transaction.getStatus() == TransactionStatus.FAILED && 
            transaction.getErrorDetails() != null) {
            payload.put("error_details", transaction.getErrorDetails());
        }
        
        // Add webhook info if present
        if (transaction.hasWebhookEnabled()) {
            payload.put("webhook_url", transaction.getWebhookUrl());
            payload.put("webhooks_enabled", true);
        }
        
        return payload;
    }

    /**
     * Creates a payload map specifically for retry events.
     *
     * @param transaction The transaction being retried
     * @return The payload map
     */
    private Map<String, Object> createRetryPayload(Transaction transaction) {
        Map<String, Object> payload = createPayload(transaction, null);
        
        // Add retry-specific information
        payload.put("retry_count", transaction.getAttemptCount());
        payload.put("previous_attempts", transaction.getAttemptCount() - 1);
        
        return payload;
    }

    /**
     * Sends a transaction event message to Kafka.
     *
     * @param message The message to send
     */
    private void sendTransactionEventMessage(TransactionEventMessage message) {
        try {
            // Use transaction ID as key for partitioning
            String key = message.getTransactionId().toString();
            
            ListenableFuture<SendResult<String, Object>> future = 
                    kafkaTemplate.send(transactionEventsTopic, key, message);
            
            future.addCallback(new ListenableFutureCallback<SendResult<String, Object>>() {
                @Override
                public void onSuccess(SendResult<String, Object> result) {
                    logger.debug("Sent transaction event message {} to topic {} with offset {}",
                            message.getEventId(), 
                            result.getRecordMetadata().topic(),
                            result.getRecordMetadata().offset());
                }
                
                @Override
                public void onFailure(Throwable ex) {
                    logger.error("Unable to send transaction event message {} to topic {}",
                            message.getEventId(), transactionEventsTopic, ex);
                }
            });
        } catch (Exception e) {
            logger.error("Error during transaction event message production", e);
            throw e;
        }
    }
}
