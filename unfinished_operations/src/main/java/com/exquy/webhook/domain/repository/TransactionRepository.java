package com.exquy.webhook.domain.repository;

import com.company.transactionrecovery.domain.enums.TransactionStatus;
import com.company.transactionrecovery.domain.model.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for Transaction entities.
 * Provides methods to interact with the transactions table in the database.
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /**
     * Finds a transaction by its ID and origin system.
     *
     * @param id The transaction ID
     * @param originSystem The origin system identifier
     * @return An Optional containing the transaction if found, or empty if not found
     */
    Optional<Transaction> findByIdAndOriginSystem(UUID id, String originSystem);

    /**
     * Finds all transactions for a specific origin system.
     *
     * @param originSystem The origin system identifier
     * @param pageable Pagination information
     * @return Page of transactions from the specified origin system
     */
    Page<Transaction> findByOriginSystem(String originSystem, Pageable pageable);

    /**
     * Finds transactions by their current status.
     *
     * @param status The transaction status to find
     * @param pageable Pagination information
     * @return Page of transactions with the specified status
     */
    Page<Transaction> findByStatus(TransactionStatus status, Pageable pageable);

    /**
     * Finds transactions by their status and creation date range.
     *
     * @param status The transaction status
     * @param startDate Start of the date range (inclusive)
     * @param endDate End of the date range (inclusive)
     * @param pageable Pagination information
     * @return Page of transactions matching the criteria
     */
    Page<Transaction> findByStatusAndCreatedAtBetween(
            TransactionStatus status,
            LocalDateTime startDate,
            LocalDateTime endDate,
            Pageable pageable);

    /**
     * Finds pending transactions that have been in that state for longer than specified.
     * This is used by the transaction monitor to detect stalled transactions.
     *
     * @param status Usually PENDING or PROCESSING status
     * @param thresholdTime Transactions created before this time are considered stalled
     * @return List of potentially stalled transactions
     */
    List<Transaction> findByStatusAndCreatedAtBefore(
            TransactionStatus status,
            LocalDateTime thresholdTime);

    /**
     * Searches for transactions based on various criteria.
     * All parameters are optional - if null, that criterion is not applied.
     *
     * @param originSystem Origin system identifier (optional)
     * @param status Transaction status (optional)
     * @param startDate Start date for date range (optional)
     * @param endDate End date for date range (optional)
     * @param pageable Pagination information
     * @return Page of transactions matching the criteria
     */
    @Query("SELECT t FROM Transaction t WHERE " +
           "(:originSystem IS NULL OR t.originSystem = :originSystem) AND " +
           "(:status IS NULL OR t.status = :status) AND " +
           "(:startDate IS NULL OR t.createdAt >= :startDate) AND " +
           "(:endDate IS NULL OR t.createdAt <= :endDate)")
    Page<Transaction> searchTransactions(
            @Param("originSystem") String originSystem,
            @Param("status") TransactionStatus status,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable);

    /**
     * Counts transactions by status.
     *
     * @return List of count results by status
     */
    @Query("SELECT t.status as status, COUNT(t) as count FROM Transaction t GROUP BY t.status")
    List<StatusCount> countByStatus();

    /**
     * Interface to hold status count results.
     */
    interface StatusCount {
        TransactionStatus getStatus();
        Long getCount();
    }

    /**
     * Finds transactions that might be in an inconsistent state based on various criteria.
     * This is used by the anomaly detection service.
     *
     * @return List of potentially anomalous transactions
     */
    @Query("SELECT t FROM Transaction t WHERE " +
           "(t.status = 'PENDING' AND t.createdAt < :threshold) OR " +
           "(t.status = 'PROCESSING' AND t.updatedAt < :threshold) OR " +
           "(t.status = 'TIMEOUT' AND t.isReconciled = false) OR " +
           "(t.status = 'INCONSISTENT')")
    List<Transaction> findAnomalousTransactions(@Param("threshold") LocalDateTime threshold);

    /**
     * Finds transactions with webhook enabled that need notification.
     * Used to ensure webhooks are sent for transactions that have changed state.
     *
     * @param status The status that triggers notification
     * @return List of transactions needing webhook notification
     */
    @Query("SELECT t FROM Transaction t WHERE " +
           "t.status = :status AND " +
           "t.webhookUrl IS NOT NULL AND " +
           "t.webhookUrl <> ''")
    List<Transaction> findTransactionsNeedingWebhookNotification(
            @Param("status") TransactionStatus status);

    /**
     * Gets transaction statistics for a time period.
     *
     * @param startDate Start of the period
     * @param endDate End of the period
     * @return List of transaction count by date and status
     */
    @Query("SELECT DATE(t.createdAt) as date, t.status as status, COUNT(t) as count " +
           "FROM Transaction t " +
           "WHERE t.createdAt BETWEEN :startDate AND :endDate " +
           "GROUP BY DATE(t.createdAt), t.status " +
           "ORDER BY DATE(t.createdAt)")
    List<Object[]> getTransactionStatistics(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);
}
