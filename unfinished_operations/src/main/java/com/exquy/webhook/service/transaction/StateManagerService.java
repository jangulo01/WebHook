package com.exquy.webhook.service.transaction;

import com.company.transactionrecovery.domain.enums.TransactionStatus;
import com.company.transactionrecovery.domain.model.Transaction;
import com.company.transactionrecovery.domain.model.TransactionHistory;
import com.company.transactionrecovery.domain.repository.TransactionHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Service responsible for managing transaction states.
 * Provides logic for determining the actual state of a transaction,
 * especially during reconciliation after system failures.
 */
@Service
public class StateManagerService {

    private static final Logger logger = LoggerFactory.getLogger(StateManagerService.class);

    private final TransactionHistoryRepository historyRepository;

    /**
     * Maximum time a transaction should remain in PENDING state.
     */
    @Value("${transaction.timeout.pending-minutes:5}")
    private int pendingTimeoutMinutes;

    /**
     * Maximum time a transaction should remain in PROCESSING state.
     */
    @Value("${transaction.timeout.processing-minutes:10}")
    private int processingTimeoutMinutes;

    @Autowired
    public StateManagerService(TransactionHistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    /**
     * Determines the actual state of a transaction based on its history and timing.
     * Used during reconciliation to resolve the state of transactions that might be
     * in an ambiguous or inconsistent state due to system failures.
     *
     * @param transaction The transaction to analyze
     * @return The determined actual state
     */
    public TransactionStatus determineActualState(Transaction transaction) {
        // Already in a terminal state - no need to determine
        if (transaction.getStatus() == TransactionStatus.COMPLETED || 
            transaction.getStatus() == TransactionStatus.FAILED) {
            return transaction.getStatus();
        }

        // Get transaction history
        List<TransactionHistory> history = historyRepository
                .findByTransactionIdOrderByChangedAtAsc(transaction.getId());

        // Check if timeout has occurred
        if (isTimedOut(transaction)) {
            logger.info("Transaction {} has timed out in state {}", 
                    transaction.getId(), transaction.getStatus());
            return TransactionStatus.TIMEOUT;
        }

        // For PENDING transactions
        if (transaction.getStatus() == TransactionStatus.PENDING) {
            // If no processing has started, keep it PENDING
            return TransactionStatus.PENDING;
        }

        // For PROCESSING transactions
        if (transaction.getStatus() == TransactionStatus.PROCESSING) {
            // Check for evidence of completion in history
            if (hasCompletionEvidence(history)) {
                logger.info("Found evidence of completion for transaction {}", transaction.getId());
                return TransactionStatus.COMPLETED;
            }
            
            // Check for evidence of failure in history
            if (hasFailureEvidence(history)) {
                logger.info("Found evidence of failure for transaction {}", transaction.getId());
                return TransactionStatus.FAILED;
            }
            
            // No clear evidence - keep as PROCESSING
            return TransactionStatus.PROCESSING;
        }

        // For TIMEOUT transactions
        if (transaction.getStatus() == TransactionStatus.TIMEOUT) {
            // Check if there's any evidence of completion or failure
            if (hasCompletionEvidence(history)) {
                return TransactionStatus.COMPLETED;
            }
            
            if (hasFailureEvidence(history)) {
                return TransactionStatus.FAILED;
            }
            
            return TransactionStatus.TIMEOUT;
        }

        // For INCONSISTENT transactions
        if (transaction.getStatus() == TransactionStatus.INCONSISTENT) {
            // Analyze history to find the most probable state
            TransactionStatus probableState = analyzeProbableState(transaction, history);
            
            if (probableState != TransactionStatus.INCONSISTENT) {
                logger.info("Determined probable state {} for inconsistent transaction {}", 
                        probableState, transaction.getId());
                return probableState;
            }
            
            // Still inconsistent - needs manual resolution
            return TransactionStatus.INCONSISTENT;
        }

        // Default - return current status if we can't determine anything better
        logger.warn("Could not determine a better state for transaction {} in state {}", 
                transaction.getId(), transaction.getStatus());
        return transaction.getStatus();
    }

    /**
     * Checks if a transaction has timed out based on its current state and timing.
     *
     * @param transaction The transaction to check
     * @return true if the transaction has timed out, false otherwise
     */
    public boolean isTimedOut(Transaction transaction) {
        LocalDateTime now = LocalDateTime.now();

        if (transaction.getStatus() == TransactionStatus.PENDING) {
            Duration pendingDuration = Duration.between(transaction.getCreatedAt(), now);
            return pendingDuration.toMinutes() > pendingTimeoutMinutes;
        }

        if (transaction.getStatus() == TransactionStatus.PROCESSING) {
            // Use last attempt time if available, otherwise creation time
            LocalDateTime referenceTime = transaction.getLastAttemptAt() != null 
                    ? transaction.getLastAttemptAt() 
                    : transaction.getCreatedAt();
            
            Duration processingDuration = Duration.between(referenceTime, now);
            return processingDuration.toMinutes() > processingTimeoutMinutes;
        }

        return false;
    }

    /**
     * Checks for evidence that a transaction has been completed successfully.
     *
     * @param history The transaction history
     * @return true if there's evidence of completion, false otherwise
     */
    private boolean hasCompletionEvidence(List<TransactionHistory> history) {
        // Check for COMPLETED entries in history
        for (TransactionHistory entry : history) {
            if (entry.getNewStatus() == TransactionStatus.COMPLETED) {
                return true;
            }
        }
        
        // Look for other evidence of completion (e.g., in context or reason)
        for (TransactionHistory entry : history) {
            String context = entry.getContext();
            String reason = entry.getReason();
            
            if (context != null && context.toLowerCase().contains("complet")) {
                return true;
            }
            
            if (reason != null && reason.toLowerCase().contains("complet")) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Checks for evidence that a transaction has failed.
     *
     * @param history The transaction history
     * @return true if there's evidence of failure, false otherwise
     */
    private boolean hasFailureEvidence(List<TransactionHistory> history) {
        // Check for FAILED entries in history
        for (TransactionHistory entry : history) {
            if (entry.getNewStatus() == TransactionStatus.FAILED) {
                return true;
            }
        }
        
        // Look for other evidence of failure (e.g., in context or reason)
        for (TransactionHistory entry : history) {
            String context = entry.getContext();
            String reason = entry.getReason();
            
            if (context != null && 
                (context.toLowerCase().contains("fail") || 
                 context.toLowerCase().contains("error"))) {
                return true;
            }
            
            if (reason != null && 
                (reason.toLowerCase().contains("fail") || 
                 reason.toLowerCase().contains("error"))) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Analyzes the probable state of a transaction based on its history and other factors.
     *
     * @param transaction The transaction to analyze
     * @param history The transaction history
     * @return The probable state
     */
    private TransactionStatus analyzeProbableState(Transaction transaction, 
                                                  List<TransactionHistory> history) {
        // If there's response data, likely COMPLETED
        if (transaction.getResponse() != null && !transaction.getResponse().isEmpty()) {
            return TransactionStatus.COMPLETED;
        }
        
        // If there's error data, likely FAILED
        if (transaction.getErrorDetails() != null && !transaction.getErrorDetails().isEmpty()) {
            return TransactionStatus.FAILED;
        }
        
        // If reached max retries, likely FAILED
        if (transaction.getAttemptCount() >= 3) { // Assuming 3 is max retries
            return TransactionStatus.FAILED;
        }
        
        // If newly created (less than 1 minute), set back to PENDING
        Duration sinceCreation = Duration.between(transaction.getCreatedAt(), LocalDateTime.now());
        if (sinceCreation.toMinutes() < 1) {
            return TransactionStatus.PENDING;
        }
        
        // If very old (more than 30 minutes), likely needs manual review
        if (sinceCreation.toMinutes() > 30) {
            return TransactionStatus.INCONSISTENT;
        }
        
        // Check most recent status before INCONSISTENT
        if (!history.isEmpty()) {
            TransactionStatus previousStatus = null;
            
            for (int i = history.size() - 1; i >= 0; i--) {
                TransactionHistory entry = history.get(i);
                if (entry.getNewStatus() != TransactionStatus.INCONSISTENT) {
                    previousStatus = entry.getNewStatus();
                    break;
                }
            }
            
            if (previousStatus != null) {
                if (previousStatus == TransactionStatus.PROCESSING) {
                    // If was processing, but timed out, mark as TIMEOUT
                    if (isTimedOut(transaction)) {
                        return TransactionStatus.TIMEOUT;
                    }
                    return TransactionStatus.PROCESSING;
                }
                
                return previousStatus;
            }
        }
        
        // If still can't determine, leave as INCONSISTENT for manual review
        return TransactionStatus.INCONSISTENT;
    }

    /**
     * Determines if a transaction should be automatically retried.
     *
     * @param transaction The transaction to check
     * @return true if the transaction should be retried, false otherwise
     */
    public boolean shouldRetry(Transaction transaction) {
        // Don't retry transactions in terminal states
        if (transaction.getStatus() == TransactionStatus.COMPLETED || 
            transaction.getStatus() == TransactionStatus.FAILED ||
            transaction.getStatus() == TransactionStatus.PERMANENTLY_FAILED) {
            return false;
        }
        
        // Don't retry if max attempts reached
        if (transaction.getAttemptCount() >= 3) { // Assuming 3 is max retries
            return false;
        }
        
        // For timeout transactions, retry if less than 30 minutes old
        if (transaction.getStatus() == TransactionStatus.TIMEOUT) {
            Duration sinceCreation = Duration.between(transaction.getCreatedAt(), LocalDateTime.now());
            return sinceCreation.toMinutes() < 30;
        }
        
        // Retry PENDING transactions
        if (transaction.getStatus() == TransactionStatus.PENDING) {
            return true;
        }
        
        // For PROCESSING, check timing
        if (transaction.getStatus() == TransactionStatus.PROCESSING) {
            return isTimedOut(transaction);
        }
        
        // Don't automatically retry INCONSISTENT transactions
        return false;
    }
}
