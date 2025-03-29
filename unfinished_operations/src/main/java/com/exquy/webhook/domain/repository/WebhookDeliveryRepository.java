package com.exquy.webhook.domain.repository;

import com.company.transactionrecovery.domain.enums.WebhookDeliveryStatus;
import com.company.transactionrecovery.domain.enums.WebhookEventType;
import com.company.transactionrecovery.domain.model.WebhookDelivery;
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
 * Repository interface for WebhookDelivery entities.
 * Provides methods to interact with the webhook_deliveries table in the database.
 */
@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    /**
     * Finds all webhook deliveries for a specific webhook configuration.
     *
     * @param webhookId The webhook configuration ID
     * @param pageable Pagination information
     * @return Page of webhook deliveries
     */
    Page<WebhookDelivery> findByWebhookId(UUID webhookId, Pageable pageable);

    /**
     * Finds all webhook deliveries for a specific transaction.
     *
     * @param transactionId The transaction ID
     * @param pageable Pagination information
     * @return Page of webhook deliveries
     */
    Page<WebhookDelivery> findByTransactionId(UUID transactionId, Pageable pageable);

    /**
     * Finds webhook deliveries by their current status.
     *
     * @param status The delivery status
     * @param pageable Pagination information
     * @return Page of webhook deliveries with the specified status
     */
    Page<WebhookDelivery> findByDeliveryStatus(WebhookDeliveryStatus status, Pageable pageable);

    /**
     * Finds webhook deliveries by webhook ID and status.
     *
     * @param webhookId The webhook configuration ID
     * @param status The delivery status
     * @param pageable Pagination information
     * @return Page of webhook deliveries
     */
    Page<WebhookDelivery> findByWebhookIdAndDeliveryStatus(
            UUID webhookId, 
            WebhookDeliveryStatus status,
            Pageable pageable);

    /**
     * Finds webhook deliveries for a specific event type.
     *
     * @param eventType The event type
     * @param pageable Pagination information
     * @return Page of webhook deliveries for the specified event type
     */
    Page<WebhookDelivery> findByEventType(WebhookEventType eventType, Pageable pageable);

    /**
     * Finds failed webhook deliveries that are scheduled for retry.
     *
     * @param now Current time
     * @return List of webhook deliveries scheduled for retry
     */
    @Query("SELECT wd FROM WebhookDelivery wd WHERE " +
           "wd.deliveryStatus = 'RETRY_SCHEDULED' AND " +
           "wd.nextRetryAt <= :now")
    List<WebhookDelivery> findDeliveriesDueForRetry(@Param("now") LocalDateTime now);

    /**
     * Finds webhook deliveries that are hanging (status is still processing for too long).
     *
     * @param thresholdTime Time threshold for considering a delivery as hanging
     * @return List of hanging webhook deliveries
     */
    @Query("SELECT wd FROM WebhookDelivery wd WHERE " +
           "wd.deliveryStatus = 'PROCESSING' AND " +
           "wd.lastAttemptAt < :thresholdTime")
    List<WebhookDelivery> findHangingDeliveries(@Param("thresholdTime") LocalDateTime thresholdTime);

    /**
     * Finds webhook deliveries that have failed and exceed the maximum retry count.
     *
     * @param maxRetries Maximum number of retry attempts
     * @param pageable Pagination information
     * @return Page of webhook deliveries that have failed permanently
     */
    @Query("SELECT wd FROM WebhookDelivery wd WHERE " +
           "wd.deliveryStatus IN ('FAILED', 'RETRY_SCHEDULED') AND " +
           "wd.attemptCount >= :maxRetries AND " +
           "wd.deliveryStatus <> 'PERMANENTLY_FAILED'")
    Page<WebhookDelivery> findDeliveriesExceedingMaxRetries(
            @Param("maxRetries") int maxRetries,
            Pageable pageable);

    /**
     * Counts webhook deliveries by status.
     *
     * @return List of count results by status
     */
    @Query("SELECT wd.deliveryStatus as status, COUNT(wd) as count " +
           "FROM WebhookDelivery wd GROUP BY wd.deliveryStatus")
    List<StatusCount> countByDeliveryStatus();

    /**
     * Interface to hold status count results.
     */
    interface StatusCount {
        WebhookDeliveryStatus getStatus();
        Long getCount();
    }

    /**
     * Gets the latest delivery for each webhook and transaction combination.
     *
     * @param transactionId The transaction ID
     * @return List of latest webhook deliveries
     */
    @Query("SELECT wd FROM WebhookDelivery wd WHERE " +
           "wd.transactionId = :transactionId AND " +
           "wd.id IN (SELECT MAX(wd2.id) FROM WebhookDelivery wd2 " +
           "          WHERE wd2.transactionId = :transactionId " +
           "          GROUP BY wd2.webhookId)")
    List<WebhookDelivery> findLatestDeliveriesForTransaction(@Param("transactionId") UUID transactionId);

    /**
     * Finds webhook deliveries that have been acknowledged.
     *
     * @param pageable Pagination information
     * @return Page of acknowledged webhook deliveries
     */
    Page<WebhookDelivery> findByIsAcknowledgedTrue(Pageable pageable);

    /**
     * Finds webhook deliveries that have not been acknowledged after successful delivery.
     *
     * @param thresholdTime Time threshold for considering a delivery as needing acknowledgment
     * @return List of unacknowledged webhook deliveries
     */
    @Query("SELECT wd FROM WebhookDelivery wd WHERE " +
           "wd.deliveryStatus = 'DELIVERED' AND " +
           "wd.isAcknowledged = false AND " +
           "wd.lastAttemptAt < :thresholdTime")
    List<WebhookDelivery> findUnacknowledgedDeliveries(@Param("thresholdTime") LocalDateTime thresholdTime);

    /**
     * Gets statistics on webhook delivery durations.
     *
     * @param startDate Start of the analysis period
     * @param endDate End of the analysis period
     * @return List of average durations by event type
     */
    @Query("SELECT wd.eventType as eventType, " +
           "AVG(FUNCTION('TIMESTAMPDIFF', SECOND, wd.createdAt, " +
           "    CASE WHEN wd.deliveryStatus = 'DELIVERED' THEN wd.lastAttemptAt " +
           "         ELSE NULL END)) as avgDurationSeconds, " +
           "COUNT(wd) as count " +
           "FROM WebhookDelivery wd " +
           "WHERE wd.deliveryStatus = 'DELIVERED' AND " +
           "wd.createdAt BETWEEN :startDate AND :endDate " +
           "GROUP BY wd.eventType")
    List<Object[]> getDeliveryDurationStatistics(
            @Param("startDate") LocalDateTime startDate, 
            @Param("endDate") LocalDateTime endDate);

    /**
     * Gets the delivery success rates by webhook.
     *
     * @param startDate Start of the analysis period
     * @param endDate End of the analysis period
     * @return List of success rates by webhook
     */
    @Query("SELECT wd.webhookId as webhookId, " +
           "COUNT(CASE WHEN wd.deliveryStatus = 'DELIVERED' THEN 1 ELSE NULL END) as successCount, " +
           "COUNT(wd) as totalCount, " +
           "(COUNT(CASE WHEN wd.deliveryStatus = 'DELIVERED' THEN 1 ELSE NULL END) * 100.0 / COUNT(wd)) as successRate " +
           "FROM WebhookDelivery wd " +
           "WHERE wd.createdAt BETWEEN :startDate AND :endDate " +
           "GROUP BY wd.webhookId")
    List<Object[]> getDeliverySuccessRates(
            @Param("startDate") LocalDateTime startDate, 
            @Param("endDate") LocalDateTime endDate);
}
