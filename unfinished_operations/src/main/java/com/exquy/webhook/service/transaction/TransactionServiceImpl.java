package com.exquy.webhook.service.transaction;

import com.company.transactionrecovery.api.dto.TransactionRequest;
import com.company.transactionrecovery.api.exception.DuplicateTransactionException;
import com.company.transactionrecovery.api.exception.TransactionNotFoundException;
import com.company.transactionrecovery.domain.enums.TransactionStatus;
import com.company.transactionrecovery.domain.model.Transaction;
import com.company.transactionrecovery.domain.model.TransactionHistory;
import com.company.transactionrecovery.domain.repository.TransactionHistoryRepository;
import com.company.transactionrecovery.domain.repository.TransactionRepository;
import com.company.transactionrecovery.infrastructure.kafka.producer.TransactionEventProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implementation of the TransactionService interface.
 * Provides the business logic for processing and managing transactions.
 */
@Service
public class TransactionServiceImpl implements TransactionService {

    private static final Logger logger = LoggerFactory.getLogger(TransactionServiceImpl.class);

    private final TransactionRepository transactionRepository;
    private final TransactionHistoryRepository historyRepository;
    private final IdempotencyService idempotencyService;
    private final StateManagerService stateManagerService;
    private final TransactionEventProducer eventProducer;

    @Value("${transaction.retry.max-attempts:3}")
    private int maxRetryAttempts;

    @Autowired
    public TransactionServiceImpl(
            TransactionRepository transactionRepository,
            TransactionHistoryRepository historyRepository,
            IdempotencyService idempotencyService,
            StateManagerService stateManagerService,
            TransactionEventProducer eventProducer) {
        this.transactionRepository = transactionRepository;
        this.historyRepository = historyRepository;
        this.idempotencyService = idempotencyService;
        this.stateManagerService = stateManagerService;
        this.eventProducer = eventProducer;
    }

    @Override
    @Transactional
    public Transaction processTransaction(TransactionRequest request) {
        logger.info("Processing transaction with ID: {}, retry: {}", 
                request.getTransactionId(), request.isRetry());

        // Check if transaction already exists
        Transaction existingTransaction = transactionRepository
                .findById(request.getTransactionId())
                .orElse(null);

        if (existingTransaction != null) {
            return handleExistingTransaction(existingTransaction, request);
        } else {
            return createNewTransaction(request);
        }
    }

    /**
     * Handles the case where a transaction with the same ID already exists.
     */
    private Transaction handleExistingTransaction(Transaction existingTransaction, 
                                                 TransactionRequest request) {
        // Check for idempotency - ensure this is truly a retry and not a different transaction
        if (!request.isRetry() && !idempotencyService.isIdempotent(existingTransaction, request)) {
            logger.warn("Duplicate transaction detected with ID: {}", request.getTransactionId());
            throw new DuplicateTransactionException(
                    existingTransaction.getId(),
                    existingTransaction.getStatus());
        }

        // Handle based on current status
        switch (existingTransaction.getStatus()) {
            case COMPLETED:
            case FAILED:
                logger.info("Transaction {} already in terminal state: {}", 
                        existingTransaction.getId(), existingTransaction.getStatus());
                return existingTransaction;

            case PENDING:
            case PROCESSING:
                if (request.isRetry()) {
                    logger.info("Retrying transaction: {}", existingTransaction.getId());
                    return retryTransaction(existingTransaction);
                }
                return existingTransaction;

            case TIMEOUT:
            case INCONSISTENT:
                logger.info("Transaction {} in problematic state: {}. Attempting recovery.", 
                        existingTransaction.getId(), existingTransaction.getStatus());
                return recoverTransaction(existingTransaction, request);

            default:
                logger.warn("Unexpected status for transaction {}: {}", 
                        existingTransaction.getId(), existingTransaction.getStatus());
                return existingTransaction;
        }
    }

    /**
     * Creates a new transaction from the request.
     */
    private Transaction createNewTransaction(TransactionRequest request) {
        Transaction transaction = Transaction.builder()
                .id(request.getTransactionId())
                .originSystem(request.getOriginSystem())
                .status(TransactionStatus.PENDING)
                .payload(request.getPayload())
                .attemptCount(1)
                .webhookUrl(request.getWebhookUrl())
                .webhookSecurityToken(request.getWebhookSecurityToken())
                .build();

        transaction = transactionRepository.save(transaction);

        // Create initial history entry
        TransactionHistory history = TransactionHistory.createInitialEntry(
                transaction.getId(), 
                TransactionStatus.PENDING, 
                request.getOriginSystem());
        
        historyRepository.save(history);

        // Publish event for async processing
        eventProducer.sendTransactionCreatedEvent(transaction);

        return transaction;
    }

    /**
     * Handles retrying a transaction.
     */
    private Transaction retryTransaction(Transaction transaction) {
        if (transaction.getAttemptCount() >= maxRetryAttempts) {
            logger.warn("Maximum retry attempts ({}) reached for transaction: {}", 
                    maxRetryAttempts, transaction.getId());
            
            return failTransaction(
                    transaction.getId(),
                    Map.of("reason", "Maximum retry attempts reached"),
                    "Maximum retry attempts reached",
                    "SYSTEM");
        }

        // Record the retry attempt
        transaction.recordAttempt();
        Transaction updatedTransaction = transactionRepository.save(transaction);

        // Create history entry for retry
        TransactionHistory history = TransactionHistory.createRetryEntry(
                transaction.getId(),
                transaction.getStatus(),
                transaction.getAttemptCount(),
                "SYSTEM_RETRY");
        
        historyRepository.save(history);

        // Trigger processing (will be done asynchronously)
        eventProducer.sendTransactionRetryEvent(updatedTransaction);

        return updatedTransaction;
    }

    /**
     * Attempts to recover a transaction from a problematic state.
     */
    private Transaction recoverTransaction(Transaction transaction, TransactionRequest request) {
        logger.info("Attempting to recover transaction {} from state {}", 
                transaction.getId(), transaction.getStatus());

        // If the transaction is in TIMEOUT or INCONSISTENT state, we'll reset it to PENDING
        // and let it be processed again
        transaction.updateStatus(TransactionStatus.PENDING);
        transaction.recordAttempt();
        Transaction updatedTransaction = transactionRepository.save(transaction);

        // Create history entry for recovery
        TransactionHistory history = TransactionHistory.createStatusChangeEntry(
                transaction.getId(),
                transaction.getStatus(),
                TransactionStatus.PENDING,
                "Recovery attempt from problematic state",
                "SYSTEM_RECOVERY");
        
        historyRepository.save(history);

        // Trigger processing (will be done asynchronously)
        eventProducer.sendTransactionRecoveryEvent(updatedTransaction);

        return updatedTransaction;
    }

    @Override
    @Transactional(readOnly = true)
    public Transaction getTransaction(UUID transactionId) {
        return transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    }

    @Override
    @Transactional(readOnly = true)
    public Transaction getTransactionByIdAndOriginSystem(UUID transactionId, String originSystem) {
        return transactionRepository.findByIdAndOriginSystem(transactionId, originSystem)
                .orElseThrow(() -> new TransactionNotFoundException(
                        "Transaction not found with ID: " + transactionId + 
                        " and origin system: " + originSystem, 
                        transactionId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransactionHistory> getTransactionHistory(UUID transactionId) {
        // Verify the transaction exists
        if (!transactionRepository.existsById(transactionId)) {
            throw new TransactionNotFoundException(transactionId);
        }
        
        return historyRepository.findByTransactionIdOrderByChangedAtAsc(transactionId);
    }

    @Override
    @Transactional
    public Transaction updateTransactionStatus(UUID transactionId, TransactionStatus status, 
                                              String reason, String changedBy) {
        Transaction transaction = getTransaction(transactionId);
        TransactionStatus previousStatus = transaction.getStatus();
        
        // Check if the status is actually changing
        if (previousStatus == status) {
            logger.debug("Transaction {} status is already {}, no update needed", 
                    transactionId, status);
            return transaction;
        }
        
        logger.info("Updating transaction {} status from {} to {}", 
                transactionId, previousStatus, status);
        
        // Update the transaction
        transaction.updateStatus(status);
        Transaction updatedTransaction = transactionRepository.save(transaction);
        
        // Create history entry
        TransactionHistory history = TransactionHistory.createStatusChangeEntry(
                transactionId, 
                previousStatus, 
                status, 
                reason, 
                changedBy);
        
        historyRepository.save(history);
        
        // Publish event for the status change
        eventProducer.sendTransactionStatusChangedEvent(updatedTransaction, previousStatus);
        
        return updatedTransaction;
    }

    @Override
    @Transactional
    public Transaction recordTransactionAttempt(UUID transactionId) {
        Transaction transaction = getTransaction(transactionId);
        transaction.recordAttempt();
        return transactionRepository.save(transaction);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<com.exquy.webhook.domain.model.Transaction> searchTransactions(String originSystem, TransactionStatus status,
                                                                               LocalDateTime startDate, LocalDateTime endDate,
                                                                               Pageable pageable) {
        return transactionRepository.searchTransactions(
                originSystem, status, startDate, endDate, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Transaction> findTransactionsInStatusLongerThan(TransactionStatus status, 
                                                              int thresholdMinutes) {
        LocalDateTime threshold = LocalDateTime.now().minus(thresholdMinutes, ChronoUnit.MINUTES);
        return transactionRepository.findByStatusAndCreatedAtBefore(status, threshold);
    }

    @Override
    @Transactional
    public Transaction completeTransaction(UUID transactionId, Map<String, Object> response, 
                                          String changedBy) {
        Transaction transaction = getTransaction(transactionId);
        
        // Set completion data
        transaction.setResponse(response);
        transaction.setCompletionAt(LocalDateTime.now());
        
        // Update status (this also updates the completion timestamp internally)
        return updateTransactionStatus(
                transactionId, 
                TransactionStatus.COMPLETED, 
                "Transaction processed successfully", 
                changedBy);
    }

    @Override
    @Transactional
    public Transaction failTransaction(UUID transactionId, Map<String, Object> errorDetails, 
                                      String reason, String changedBy) {
        Transaction transaction = getTransaction(transactionId);
        
        // Set failure data
        transaction.setErrorDetails(errorDetails);
        transaction.setCompletionAt(LocalDateTime.now());
        
        // Save before status update to persist error details
        transactionRepository.save(transaction);
        
        // Update status
        return updateTransactionStatus(
                transactionId, 
                TransactionStatus.FAILED, 
                reason, 
                changedBy);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getTransactionStats() {
        Map<String, Object> stats = new HashMap<>();
        
        // Get counts by status
        List<TransactionRepository.StatusCount> statusCounts = transactionRepository.countByStatus();
        Map<String, Long> countsByStatus = new HashMap<>();
        
        statusCounts.forEach(sc -> 
            countsByStatus.put(sc.getStatus().toString(), sc.getCount())
        );
        
        stats.put("countsByStatus", countsByStatus);
        
        // Add more stats as needed
        stats.put("totalTransactions", transactionRepository.count());
        
        return stats;
    }

    @Override
    @Transactional
    public Transaction reconcileTransaction(UUID transactionId) {
        Transaction transaction = getTransaction(transactionId);
        
        // Let the state manager service handle the reconciliation logic
        TransactionStatus newStatus = stateManagerService.determineActualState(transaction);
        
        if (newStatus != transaction.getStatus()) {
            transaction = updateTransactionStatus(
                    transactionId, 
                    newStatus, 
                    "Automatic reconciliation", 
                    "SYSTEM_RECONCILIATION");
        }
        
        // Mark as reconciled
        transaction.setIsReconciled(true);
        transaction = transactionRepository.save(transaction);
        
        logger.info("Transaction {} reconciled to status {}", transactionId, transaction.getStatus());
        
        return transaction;
    }

    @Override
    @Transactional
    public Transaction manuallyHandleTransaction(UUID transactionId, TransactionStatus targetStatus,
                                               String notes, String adminUser) {
        Transaction transaction = getTransaction(transactionId);
        TransactionStatus currentStatus = transaction.getStatus();
        
        logger.info("Manual handling of transaction {} by {}: changing status from {} to {}", 
                transactionId, adminUser, currentStatus, targetStatus);
        
        // Update notes
        transaction.setNotes(notes);
        transaction = transactionRepository.save(transaction);
        
        // Create manual resolution history entry
        TransactionHistory history = TransactionHistory.createManualResolutionEntry(
                transactionId, 
                currentStatus, 
                targetStatus, 
                "Manual resolution by administrator", 
                adminUser, 
                notes);
        
        historyRepository.save(history);
        
        // Update status
        transaction.updateStatus(targetStatus);
        
        // If moving to a terminal state, set completion time
        if (targetStatus == TransactionStatus.COMPLETED || 
            targetStatus == TransactionStatus.FAILED) {
            transaction.setCompletionAt(LocalDateTime.now());
        }
        
        transaction = transactionRepository.save(transaction);
        
        // Publish event for the manual resolution
        eventProducer.sendTransactionManuallyResolvedEvent(transaction, currentStatus);
        
        return transaction;
    }
}
