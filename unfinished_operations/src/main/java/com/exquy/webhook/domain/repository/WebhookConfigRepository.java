package com.exquy.webhook.domain.repository;

import com.company.transactionrecovery.domain.enums.WebhookEventType;
import com.company.transactionrecovery.domain.model.WebhookConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for WebhookConfig entities.
 * Provides methods to interact with the webhooks table in the database.
 */
@Repository
public interface WebhookConfigRepository extends JpaRepository<WebhookConfig, UUID> {

    /**
     * Finds all webhook configurations for a specific origin system.
     *
     * @param originSystem The origin system identifier
     * @return List of webhook configurations
     */
    List<WebhookConfig> findByOriginSystem(String originSystem);

    /**
     * Finds all active webhook configurations for a specific origin system.
     *
     * @param originSystem The origin system identifier
     * @return List of active webhook configurations
     */
    List<WebhookConfig> findByOriginSystemAndIsActiveTrue(String originSystem);

    /**
     * Finds a webhook configuration by its callback URL.
     *
     * @param callbackUrl The callback URL
     * @return Optional containing the webhook configuration if found
     */
    Optional<WebhookConfig> findByCallbackUrl(String callbackUrl);

    /**
     * Finds all webhook configurations that are subscribed to a specific event type.
     * This uses a PostgreSQL specific query to search within the JSONB array.
     *
     * @param eventType The event type to look for
     * @return List of webhook configurations subscribed to the event
     */
    @Query("SELECT w FROM WebhookConfig w WHERE " +
           "w.isActive = true AND " +
           "FUNCTION('jsonb_exists', w.events, :eventType) = true")
    List<WebhookConfig> findActiveWebhooksByEventType(@Param("eventType") String eventType);

    /**
     * Finds all webhook configurations subscribed to a specific event type, 
     * with a more database-agnostic approach.
     *
     * @param eventType The event type to look for
     * @return List of webhook configurations subscribed to the event
     */
    @Query("SELECT w FROM WebhookConfig w JOIN CAST(w.events AS java.util.Set) events " +
           "WHERE w.isActive = true AND :eventType MEMBER OF events")
    List<WebhookConfig> findActiveWebhooksByEventTypeGeneric(@Param("eventType") WebhookEventType eventType);

    /**
     * Finds webhook configurations by contact email.
     *
     * @param contactEmail The contact email
     * @return List of webhook configurations with the specified contact email
     */
    List<WebhookConfig> findByContactEmail(String contactEmail);

    /**
     * Finds webhook configurations with high failure counts.
     * This can be used to identify problematic webhooks.
     *
     * @param failureThreshold The minimum failure count to consider
     * @return List of webhook configurations with high failure counts
     */
    List<WebhookConfig> findByFailureCountGreaterThan(Long failureThreshold);

    /**
     * Finds webhook configurations that have not had successful deliveries recently.
     *
     * @param thresholdTime Configurations with no success since this time will be returned
     * @return List of webhook configurations with delivery issues
     */
    @Query("SELECT w FROM WebhookConfig w WHERE " +
           "w.isActive = true AND " +
           "(w.lastSuccessAt IS NULL OR w.lastSuccessAt < :thresholdTime) AND " +
           "w.createdAt < :thresholdTime")
    List<WebhookConfig> findWebhooksWithDeliveryIssues(@Param("thresholdTime") LocalDateTime thresholdTime);

    /**
     * Gets webhook delivery success rate statistics.
     *
     * @return List of webhook configurations with their success rates
     */
    @Query("SELECT w.id as id, w.originSystem as originSystem, w.callbackUrl as callbackUrl, " +
           "w.successCount as successCount, w.failureCount as failureCount, " +
           "(CASE WHEN (w.successCount + w.failureCount) > 0 " +
           "THEN CAST(w.successCount AS float) / (w.successCount + w.failureCount) " +
           "ELSE 0 END) as successRate " +
           "FROM WebhookConfig w " +
           "WHERE w.isActive = true " +
           "ORDER BY successRate DESC")
    List<WebhookSuccessRate> getWebhookSuccessRates();

    /**
     * Interface to hold webhook success rate results.
     */
    interface WebhookSuccessRate {
        UUID getId();
        String getOriginSystem();
        String getCallbackUrl();
        Long getSuccessCount();
        Long getFailureCount();
        Float getSuccessRate();
    }

    /**
     * Finds the most recently updated webhook configurations.
     *
     * @param limit Maximum number of results to return
     * @return List of recently updated webhook configurations
     */
    @Query("SELECT w FROM WebhookConfig w ORDER BY w.updatedAt DESC LIMIT :limit")
    List<WebhookConfig> findMostRecentlyUpdated(@Param("limit") int limit);

    /**
     * Counts the number of webhooks registered per origin system.
     *
     * @return List of counts by origin system
     */
    @Query("SELECT w.originSystem as originSystem, COUNT(w) as count " +
           "FROM WebhookConfig w GROUP BY w.originSystem")
    List<OriginSystemCount> countByOriginSystem();

    /**
     * Interface to hold origin system count results.
     */
    interface OriginSystemCount {
        String getOriginSystem();
        Long getCount();
    }
}
