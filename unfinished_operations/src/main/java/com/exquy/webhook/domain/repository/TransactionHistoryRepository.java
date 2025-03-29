package com.exquy.webhook.domain.repository;

import com.company.transactionrecovery.domain.enums.TransactionStatus;
import com.company.transactionrecovery.domain.model.TransactionHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Repository interface for TransactionHistory entities.
 * Provides methods to interact with the transaction_history table in the database.
 */
@Repository
public interface TransactionHistoryRepository extends JpaRepository<TransactionHistory, Long> {

    /**
     * Finds all history entries for a specific transaction, ordered by change time.
     *
     * @param transactionId The transaction ID
     * @return List of history entries for the transaction
     */
    List<TransactionHistory> findByTransactionIdOrderByChangedAtAsc(UUID transactionId);

    /**
     * Finds all history entries for a specific transaction with pagination.
     *
     * @param transactionId The transaction ID
     * @param pageable Pagination information
     * @return Page of history entries for the transaction
     */
    Page<TransactionHistory> findByTransactionId(UUID transactionId, Pageable pageable);

    /**
     * Finds the most recent history entry for a transaction.
     *
     * @param transactionId The transaction ID
     * @return The most recent history entry
     */
    @Query("SELECT h FROM TransactionHistory h WHERE h.transactionId = :transactionId " +
           "ORDER BY h.changedAt DESC LIMIT 1")
    TransactionHistory findMostRecentHistoryForTransaction(@Param("transactionId") UUID transactionId);

    /**
     * Finds history entries for transitions to a specific status.
     *
     * @param newStatus The status to find transitions to
     * @param pageable Pagination information
     * @return Page of history entries matching the criteria
     */
    Page<TransactionHistory> findByNewStatus(TransactionStatus newStatus, Pageable pageable);

    /**
     * Finds history entries for status changes between the specified statuses.
     *
     * @param previousStatus The previous status
     * @param newStatus The new status
     * @param pageable Pagination information
     * @return Page of history entries matching the criteria
     */
    Page<TransactionHistory> findByPreviousStatusAndNewStatus(
            TransactionStatus previousStatus, 
            TransactionStatus newStatus, 
            Pageable pageable);

    /**
     * Finds history entries created within a specific time range.
     *
     * @param startDate Start of the time range
     * @param endDate End of the time range
     * @param pageable Pagination information
     * @return Page of history entries within the time range
     */
    Page<TransactionHistory> findByChangedAtBetween(
            LocalDateTime startDate, 
            LocalDateTime endDate, 
            Pageable pageable);

    /**
     * Finds history entries for manual status changes.
     *
     * @param pageable Pagination information
     * @return Page of history entries for manual changes
     */
    Page<TransactionHistory> findByIsAutomaticFalse(Pageable pageable);

    /**
     * Finds history entries for retry attempts for a specific transaction.
     *
     * @param transactionId The transaction ID
     * @return List of retry history entries
     */
    @Query("SELECT h FROM TransactionHistory h WHERE " +
           "h.transactionId = :transactionId AND " +
           "h.reason = 'Retry attempt' " +
           "ORDER BY h.changedAt ASC")
    List<TransactionHistory> findRetryAttemptsForTransaction(@Param("transactionId") UUID transactionId);

    /**
     * Counts the number of transitions to each status in a given time period.
     *
     * @param startDate Start of the time period
     * @param endDate End of the time period
     * @return List of counts by status
     */
    @Query("SELECT h.newStatus as status, COUNT(h) as count FROM TransactionHistory h " +
           "WHERE h.changedAt BETWEEN :startDate AND :endDate " +
           "GROUP BY h.newStatus")
    List<StatusCount> countStatusTransitionsByPeriod(
            @Param("startDate") LocalDateTime startDate, 
            @Param("endDate") LocalDateTime endDate);

    /**
     * Interface to hold status count results.
     */
    interface StatusCount {
        TransactionStatus getStatus();
        Long getCount();
    }

    /**
     * Calculates the average time spent in each status.
     *
     * @param startDate Start of the analysis period
     * @param endDate End of the analysis period
     * @return List of average durations by status
     */
    @Query(value = 
           "WITH status_periods AS ( " +
           "    SELECT " +
           "        t1.transaction_id, " +
           "        t1.previous_status, " +
           "        t1.new_status, " +
           "        t1.changed_at as start_time, " +
           "        MIN(t2.changed_at) as end_time " +
           "    FROM " +
           "        transaction_history t1 " +
           "    LEFT JOIN transaction_history t2 ON " +
           "        t1.transaction_id = t2.transaction_id AND " +
           "        t1.new_status = t2.previous_status AND " +
           "        t1.changed_at < t2.changed_at " +
           "    WHERE " +
           "        t1.changed_at BETWEEN :startDate AND :endDate " +
           "    GROUP BY " +
           "        t1.transaction_id, t1.previous_status, t1.new_status, t1.changed_at " +
           ") " +
           "SELECT " +
           "    sp.new_status as status, " +
           "    AVG(EXTRACT(EPOCH FROM (COALESCE(sp.end_time, CURRENT_TIMESTAMP) - sp.start_time))) as average_seconds " +
           "FROM " +
           "    status_periods sp " +
           "GROUP BY " +
           "    sp.new_status", 
           nativeQuery = true)
    List<Object[]> calculateAverageTimeInStatus(
            @Param("startDate") LocalDateTime startDate, 
            @Param("endDate") LocalDateTime endDate);

    /**
     * Gets the history of status changes for audit reporting.
     *
     * @param startDate Start of the reporting period
     * @param endDate End of the reporting period
     * @param pageable Pagination information
     * @return Page of history entries for the period
     */
    @Query("SELECT h FROM TransactionHistory h " +
           "WHERE h.changedAt BETWEEN :startDate AND :endDate " +
           "ORDER BY h.changedAt DESC")
    Page<TransactionHistory> getStatusChangesForAudit(
            @Param("startDate") LocalDateTime startDate, 
            @Param("endDate") LocalDateTime endDate, 
            Pageable pageable);
}
