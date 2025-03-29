package com.exquy.webhook.infrastructure.kafka.consumer;

import com.company.transactionrecovery.domain.enums.TransactionStatus;
import com.company.transactionrecovery.domain.enums.WebhookEventType;
import com.company.transactionrecovery.domain.model.Transaction;
import com.company.transactionrecovery.domain.repository.TransactionRepository;
import com.company.transactionrecovery.domain.service.transaction.StateManagerService;
import com.company.transactionrecovery.domain.service.transaction.TransactionService;
import com.company.transactionrecovery.domain.service.webhook.WebhookService;
import com.company.transactionrecovery.infrastructure.kafka.dto.TransactionEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Consumer for transaction events from Kafka.
 * This component listens for transaction event messages and processes them
 * to perform asynchronous operations on transactions.
 */
@Component
public class TransactionEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(TransactionEventConsumer.class);

    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;
    private final StateManagerService stateManagerService;
    private final WebhookService webhookService;

    @Autowired
    public TransactionEventConsumer(
            TransactionRepository transactionRepository,
            TransactionService transactionService,
            StateManagerService stateManagerService,
            WebhookService webhookService) {
        this.transactionRepository = transactionRepository;
        this.transactionService = transactionService;
        this.stateManagerService = stateManagerService;
        this.webhookService = webhookService;
    }

    /**
     * Listens for transaction event messages from Kafka and processes them.
     *
     * @param message The transaction event message
     */
    @KafkaListener(
            topics = "${spring.kafka.topics.transaction-events}",
            groupId = "${spring.kafka.consumer.group-id}-transaction",
            containerFactory = "transactionKafkaListenerContainerFactory")
    @Transactional
    public void consumeTransactionEvent(TransactionEventMessage message) {
        logger.info("Received transaction event: {}, type: {}, transaction: {}",
                message.getEventId(),
                message.getEventType(),
                message.getTransactionId());

        try {
            // Look up the transaction
            Transaction transaction = transactionRepository.findById(message.getTransactionId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Transaction not found: " + message.getTransactionId()));

            // Process the event based on type
            switch (message.getEventType()) {
                case "TRANSACTION_CREATED":
                    processTransactionCreated(transaction, message);
                    break;
                case "TRANSACTION_STATUS_CHANGED":
                    processTransactionStatusChanged(transaction, message);
                    break;
                case "TRANSACTION_RETRY":
                    processTransactionRetry(transaction, message);
                    break;
                case "TRANSACTION_RECOVERY":
                    processTransactionRecovery(transaction, message);
                    break;
                case "TRANSACTION_MANUALLY_RESOLVED":
                    processTransactionManuallyResolved(transaction, message);
                    break;
                case "TRANSACTION_RECONCILED":
                    processTransactionReconciled(transaction, message);
                    break;
                default:
                    logger.warn("Unknown transaction event type: {}", message.getEventType());
            }
        } catch (Exception e) {
            logger.error("Error processing transaction event: {}", message.getEventId(), e);
        }
    }

    /**
     * Processes a TRANSACTION_CREATED event.
     *
     * @param transaction The transaction
     * @param message The event message
     */
    private void processTransactionCreated(Transaction transaction, TransactionEventMessage message) {
        logger.info("Processing TRANSACTION_CREATED for transaction: {}", transaction.getId());

        // Process the transaction if it's still pending
        if (transaction.getStatus() == TransactionStatus.PENDING) {
            try {
                // Update status to PROCESSING
                transactionService.updateTransactionStatus(
                        transaction.getId(),
                        TransactionStatus.PROCESSING,
                        "Processing started",
                        "SYSTEM");

                // Simulate processing - in a real implementation, this would
                // perform the actual business logic for the transaction
                simulateTransactionProcessing(transaction);

                // Complete the transaction
                Map<String, Object> response = new HashMap<>();
                response.put("status", "success");
                response.put("processed_at", System.currentTimeMillis());
                response.put("reference", "TX-" + transaction.getId().toString().substring(0, 8));

                transactionService.completeTransaction(
                        transaction.getId(),
                        response,
                        "SYSTEM");

                // Send webhook notification for transaction completed
                Transaction updatedTransaction = transactionRepository.findById(transaction.getId()).orElse(transaction);
                webhookService.sendTransactionEventNotification(
                        updatedTransaction,
                        WebhookEventType.TRANSACTION_COMPLETED,
                        null);

            } catch (Exception e) {
                logger.error("Error processing transaction: {}", transaction.getId(), e);

                // Fail the transaction
                Map<String, Object> errorDetails = new HashMap<>();
                errorDetails.put("error", e.getMessage());
                errorDetails.put("error_type", e.getClass().getName());

                transactionService.failTransaction(
                        transaction.getId(),
                        errorDetails,
                        "Error during processing: " + e.getMessage(),
                        "SYSTEM");

                // Send webhook notification for transaction failed
                Transaction updatedTransaction = transactionRepository.findById(transaction.getId()).orElse(transaction);
                webhookService.sendTransactionEventNotification(
                        updatedTransaction,
                        WebhookEventType.TRANSACTION_FAILED,
                        errorDetails);
            }
        } else {
            logger.info("Transaction {} is not in PENDING state, skipping processing", transaction.getId());
        }
    }

    /**
     * Processes a TRANSACTION_STATUS_CHANGED event.
     *
     * @param transaction The transaction
     * @param message The event message
     */
    private void processTransactionStatusChanged(Transaction transaction, TransactionEventMessage message) {
        logger.info("Processing TRANSACTION_STATUS_CHANGED for transaction: {}, from {} to {}",
                transaction.getId(),
                message.getPreviousStatus(),
                message.getCurrentStatus());

        // Send webhook notification if applicable
        if (transaction.hasWebhookEnabled()) {
            WebhookEventType eventType;

            // Determine the appropriate event type
            if (transaction.getStatus() == TransactionStatus.COMPLETED) {
                eventType = WebhookEventType.TRANSACTION_COMPLETED;
            } else if (transaction.getStatus() == TransactionStatus.FAILED) {
                eventType = WebhookEventType.TRANSACTION_FAILED;
            } else if (transaction.getStatus() == TransactionStatus.TIMEOUT) {
                eventType = WebhookEventType.TRANSACTION_TIMEOUT;
            } else if (transaction.getStatus() == TransactionStatus.INCONSISTENT) {
                eventType = WebhookEventType.TRANSACTION_INCONSISTENT;
            } else {
                eventType = WebhookEventType.TRANSACTION_STATUS_CHANGED;
            }

            Map<String, Object> additionalData = new HashMap<>();
            if (message.getPreviousStatus() != null) {
                additionalData.put("previous_status", message.getPreviousStatus().toString());
            }

            webhookService.sendTransactionEventNotification(
                    transaction,
                    eventType,
                    additionalData);
        }
    }

    /**
     * Processes a TRANSACTION_RETRY event.
     *
     * @param transaction The transaction
     * @param message The event message
     */
    private void processTransactionRetry(Transaction transaction, TransactionEventMessage message) {
        logger.info("Processing TRANSACTION_RETRY for transaction: {}, attempt: {}",
                transaction.getId(), transaction.getAttemptCount());

        // Process the retry similarly to a new transaction
        processTransactionCreated(transaction, message);

        // Send webhook notification for retry
        if (transaction.hasWebhookEnabled()) {
            Map<String, Object> additionalData = new HashMap<>();
            additionalData.put("attempt", transaction.getAttemptCount());

            webhookService.sendTransactionEventNotification(
                    transaction,
                    WebhookEventType.TRANSACTION_RETRY,
                    additionalData);
        }
    }

    /**
     * Processes a TRANSACTION_RECOVERY event.
     *
     * @param transaction The transaction
     * @param message The event message
     */
    private void processTransactionRecovery(Transaction transaction, TransactionEventMessage message) {
        logger.info("Processing TRANSACTION_RECOVERY for transaction: {}", transaction.getId());

        // Let the state manager determine the actual state
        if (transaction.getStatus() == TransactionStatus.TIMEOUT || 
            transaction.getStatus() == TransactionStatus.INCONSISTENT) {
            
            TransactionStatus actualState = stateManagerService.determineActualState(transaction);
            
            if (actualState != transaction.getStatus()) {
                transaction = transactionService.updateTransactionStatus(
                        transaction.getId(),
                        actualState,
                        "Recovery - determined actual state",
                        "SYSTEM_RECOVERY");
                
                // Send webhook notification for the state change
                webhookService.sendTransactionEventNotification(
                        transaction,
                        WebhookEventType.TRANSACTION_STATUS_CHANGED,
                        null);
            }
        }
        
        // If still in a non-terminal state, retry processing
        if (transaction.getStatus() == TransactionStatus.PENDING) {
            processTransactionCreated(transaction, message);
        }
    }

    /**
     * Processes a TRANSACTION_MANUALLY_RESOLVED event.
     *
     * @param transaction The transaction
     * @param message The event message
     */
    private void processTransactionManuallyResolved(Transaction transaction, TransactionEventMessage message) {
        logger.info("Processing TRANSACTION_MANUALLY_RESOLVED for transaction: {}", transaction.getId());

        // Send webhook notification for manual resolution
        if (transaction.hasWebhookEnabled()) {
            Map<String, Object> additionalData = new HashMap<>();
            additionalData.put("manually_resolved", true);
            
            if (message.getPreviousStatus() != null) {
                additionalData.put("previous_status", message.getPreviousStatus().toString());
            }

            webhookService.sendTransactionEventNotification(
                    transaction,
                    WebhookEventType.TRANSACTION_MANUAL_RESOLUTION,
                    additionalData);
        }
    }

    /**
     * Processes a TRANSACTION_RECONCILED event.
     *
     * @param transaction The transaction
     * @param message The event message
     */
    private void processTransactionReconciled(Transaction transaction, TransactionEventMessage message) {
        logger.info("Processing TRANSACTION_RECONCILED for transaction: {}", transaction.getId());

        // Mark as reconciled if not already
        if (!transaction.getIsReconciled()) {
            transaction.setIsReconciled(true);
            transactionRepository.save(transaction);
        }

        // Send webhook notification for reconciliation
        if (transaction.hasWebhookEnabled()) {
            webhookService.sendTransactionEventNotification(
                    transaction,
                    WebhookEventType.TRANSACTION_RECONCILED,
                    null);
        }
    }

    /**
     * Simulates actual transaction processing.
     * In a real implementation, this would contain the actual business logic.
     *
     * @param transaction The transaction to process
     * @throws Exception If processing fails
     */
    private void simulateTransactionProcessing(Transaction transaction) throws Exception {
        logger.info("Simulating processing for transaction: {}", transaction.getId());
        
        // In a real implementation, this would perform the actual business logic
        // such as making API calls, updating databases, etc.
        
        // For demonstration, we'll just add a small delay
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // For demonstration, we'll randomly fail some transactions
        if (Math.random() < 0.1) {  // 10% chance of failure
            throw new Exception("Simulated random processing failure");
        }
    }
}
