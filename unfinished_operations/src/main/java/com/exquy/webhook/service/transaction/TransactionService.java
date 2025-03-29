package com.exquy.webhook.service.transaction;

import com.company.transactionrecovery.api.dto.TransactionRequest;
import com.company.transactionrecovery.domain.enums.TransactionStatus;
import com.company.transactionrecovery.domain.model.Transaction;
import com.company.transactionrecovery.domain.model.TransactionHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service interface for transaction-related operations.
 * Defines methods for processing, retrieving, and managing transactions.
 */
public interface TransactionService {

    /**
     * Processes a new transaction or retries an existing one.
     * This method implements idempotency - if a transaction with the same ID
     * already exists, it will handle the retry logic appropriately.
     *
     * @param request The transaction request data
     * @return The processed transaction
     */
    Transaction processTransaction(TransactionRequest request);

    /**
     * Retrieves a transaction by its ID.
     *
     * @param transactionId The transaction ID
     * @return The transaction
     * @throws com.company.transactionrecovery.api.exception.TransactionNotFoundException if not found
     */
    Transaction getTransaction(UUID transactionId);

    /**
     * Retrieves a transaction by its ID and origin system.
     *
     * @param transactionId The transaction ID
     * @param originSystem The origin system identifier
     * @return The transaction
     * @throws com.company.transactionrecovery.api.exception.TransactionNotFoundException if not found
     */
    Transaction getTransactionByIdAndOriginSystem(UUID transactionId, String originSystem);

    /**
     * Retrieves the history of a transaction.
     *
     * @param transactionId The transaction ID
     * @return List of history entries for the transaction
     */
    List<TransactionHistory> getTransactionHistory(UUID transactionId);

    /**
     * Updates the status of a transaction.
     *
     * @param transactionId The transaction ID
     * @param status The new status
     * @param reason The reason for the status change
     * @param changedBy Identifier of who/what changed the status
     * @return The updated transaction
     */
    Transaction updateTransactionStatus(UUID transactionId, TransactionStatus status, 
                                        String reason, String changedBy);

    /**
     * Records a new attempt for a transaction.
     *
     * @param transactionId The transaction ID
     * @return The updated transaction
     */
    Transaction recordTransactionAttempt(UUID transactionId);

    /**
     * Searches for transactions based on various criteria.
     *
     * @param originSystem Origin system identifier (optional)
     * @param status       Transaction status (optional)
     * @param startDate    Start date for date range (optional)
     * @param endDate      End date for date range (optional)
     * @param pageable     Pagination information
     * @return Page of transactions matching the criteria
     */
    Page<com.exquy.webhook.domain.model.Transaction> searchTransactions(String originSystem, TransactionStatus status,
                                                                        LocalDateTime startDate, LocalDateTime endDate,
                                                                        Pageable pageable);

    /**
     * Finds transactions that have been in a specific status for longer than a threshold.
     *
     * @param status The status to look for
     * @param thresholdMinutes Minutes threshold
     * @return List of transactions that have been in the specified status for too long
     */
    List<Transaction> findTransactionsInStatusLongerThan(TransactionStatus status, 
                                                        int thresholdMinutes);

    /**
     * Completes a transaction by setting its status to COMPLETED and storing the response.
     *
     * @param transactionId The transaction ID
     * @param response The response data
     * @param changedBy Identifier of who/what completed the transaction
     * @return The updated transaction
     */
    Transaction completeTransaction(UUID transactionId, Map<String, Object> response, 
                                   String changedBy);

    /**
     * Fails a transaction by setting its status to FAILED and storing error details.
     *
     * @param transactionId The transaction ID
     * @param errorDetails The error details
     * @param reason The reason for the failure
     * @param changedBy Identifier of who/what failed the transaction
     * @return The updated transaction
     */
    Transaction failTransaction(UUID transactionId, Map<String, Object> errorDetails, 
                               String reason, String changedBy);

    /**
     * Gets system status information about transactions.
     *
     * @return Map containing transaction status counts and other metrics
     */
    Map<String, Object> getTransactionStats();

    /**
     * Reconciles a transaction after a system failure.
     * This is typically used when the system needs to determine the final state
     * of a transaction that was interrupted.
     *
     * @param transactionId The transaction ID
     * @return The reconciled transaction
     */
    Transaction reconcileTransaction(UUID transactionId);

    /**
     * Manually handles a transaction that is in an inconsistent state.
     * This is typically used when automatic reconciliation cannot determine
     * the correct state.
     *
     * @param transactionId The transaction ID
     * @param targetStatus The target status to set
     * @param notes Administrative notes about the resolution
     * @param adminUser Administrator who performed the manual handling
     * @return The updated transaction
     */
    Transaction manuallyHandleTransaction(UUID transactionId, TransactionStatus targetStatus,
                                         String notes, String adminUser);
}
