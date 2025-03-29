package com.company.transactionrecovery.domain.service.webhook;

import com.company.transactionrecovery.domain.enums.WebhookEventType;
import com.company.transactionrecovery.domain.model.Transaction;
import com.company.transactionrecovery.domain.model.WebhookDelivery;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service interface for webhook-related operations.
 * Defines methods for sending notifications, managing deliveries, 
 * and handling retries.
 */
public interface WebhookService {

    /**
     * Sends a webhook notification for a transaction event.
     *
     * @param transaction The transaction that triggered the event
     * @param eventType The type of event
     * @param additionalData Additional data to include in the notification
     * @return List of created webhook deliveries
     */
    List<WebhookDelivery> sendTransactionEventNotification(
            Transaction transaction, 
            WebhookEventType eventType, 
            Map<String, Object> additionalData);

    /**
     * Sends a webhook notification to a specific URL.
     *
     * @param webhookId The webhook configuration ID
     * @param transactionId The transaction ID (may be null for system events)
     * @param eventType The type of event
     * @param payload The payload to send
     * @return The created webhook delivery
     */
    WebhookDelivery sendWebhookNotification(
            UUID webhookId, 
            UUID transactionId, 
            WebhookEventType eventType, 
            Map<String, Object> payload);

    /**
     * Sends a test event to a webhook configuration.
     *
     * @param webhookId The webhook configuration ID
     * @return The created webhook delivery
     */
    WebhookDelivery sendTestEvent(UUID webhookId);

    /**
     * Acknowledges receipt of a webhook delivery.
     *
     * @param deliveryId The delivery ID
     * @param status The acknowledgment status
     * @return The updated webhook delivery
     */
    WebhookDelivery acknowledgeDelivery(UUID deliveryId, String status);

    /**
     * Manually retries a failed webhook delivery.
     *
     * @param deliveryId The delivery ID
     * @return The updated webhook delivery
     */
    WebhookDelivery retryDelivery(UUID deliveryId);

    /**
     * Processes webhook deliveries that are due for retry.
     *
     * @return Number of deliveries processed
     */
    int processScheduledRetries();

    /**
     * Gets webhook deliveries for a specific webhook configuration.
     *
     * @param webhookId The webhook configuration ID
     * @return List of webhook deliveries
     */
    List<WebhookDelivery> getDeliveriesByWebhookId(UUID webhookId);

    /**
     * Gets webhook deliveries for a specific transaction.
     *
     * @param transactionId The transaction ID
     * @return List of webhook deliveries
     */
    List<WebhookDelivery> getDeliveriesByTransactionId(UUID transactionId);

    /**
     * Gets delivery statistics.
     *
     * @return Map containing statistics about webhook deliveries
     */
    Map<String, Object> getDeliveryStatistics();

    /**
     * Handles a failed delivery.
     *
     * @param delivery The webhook delivery that failed
     * @param error The error that occurred
     * @return The updated webhook delivery
     */
    WebhookDelivery handleFailedDelivery(WebhookDelivery delivery, Throwable error);

    /**
     * Computes the next retry time for a failed delivery.
     *
     * @param delivery The webhook delivery
     * @return The number of seconds until the next retry
     */
    int computeNextRetryDelay(WebhookDelivery delivery);

    /**
     * Marks a delivery as permanently failed after exceeding retry attempts.
     *
     * @param delivery The webhook delivery
     * @return The updated webhook delivery
     */
    WebhookDelivery markAsPermanentlyFailed(WebhookDelivery delivery);

    /**
     * Generates a signature for a webhook payload.
     *
     * @param payload The payload to sign
     * @param secret The secret key to use for signing
     * @return The generated signature
     */
    String generateSignature(String payload, String secret);

    /**
     * Gets the status of a webhook delivery.
     *
     * @param deliveryId The delivery ID
     * @return The webhook delivery
     */
    WebhookDelivery getDeliveryStatus(UUID deliveryId);
}
