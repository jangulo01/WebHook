package com.exquy.webhook.api.dto;

import com.company.transactionrecovery.domain.enums.WebhookDeliveryStatus;
import com.company.transactionrecovery.domain.enums.WebhookEventType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Data Transfer Object for webhook delivery responses.
 * Contains information about the delivery status and attempts of a webhook notification.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebhookDeliveryResponse {

    /**
     * Unique identifier for the webhook delivery.
     */
    private UUID deliveryId;

    /**
     * Identifier of the webhook configuration used for this delivery.
     */
    private UUID webhookId;

    /**
     * Identifier of the transaction that triggered this webhook notification.
     * May be null for system events not related to a specific transaction.
     */
    private UUID transactionId;

    /**
     * Type of event that triggered this webhook notification.
     */
    private WebhookEventType eventType;

    /**
     * Current delivery status of the webhook notification.
     */
    private WebhookDeliveryStatus status;

    /**
     * Number of delivery attempts made so far.
     */
    private Integer attemptCount;

    /**
     * Timestamp of the last delivery attempt.
     */
    private LocalDateTime lastAttemptAt;

    /**
     * HTTP response code received from the last delivery attempt.
     * May be null if no response was received (e.g., connection timeout).
     */
    private Integer responseCode;

    /**
     * Brief message summarizing the response or error from the last attempt.
     */
    private String responseMessage;

    /**
     * Timestamp when the webhook delivery was created.
     */
    private LocalDateTime createdAt;

    /**
     * Timestamp when the webhook delivery status was last updated.
     */
    private LocalDateTime updatedAt;

    /**
     * Whether the delivery was acknowledged by the recipient.
     */
    private Boolean acknowledged;

    /**
     * Timestamp when the delivery was acknowledged, if applicable.
     */
    private LocalDateTime acknowledgedAt;

    /**
     * Flag indicating whether this delivery will be automatically retried
     * if it failed.
     */
    private Boolean retryScheduled;

    /**
     * Timestamp when the next retry is scheduled, if applicable.
     */
    private LocalDateTime nextRetryAt;
}
