package com.exquy.webhook.domain.model;

import com.company.transactionrecovery.domain.enums.WebhookDeliveryStatus;
import com.company.transactionrecovery.domain.enums.WebhookEventType;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;
import org.hibernate.annotations.TypeDefs;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Entity representing a webhook delivery attempt in the system.
 * Tracks the delivery status, retry attempts, and responses for webhook notifications.
 */
@Entity
@Table(name = "webhook_deliveries")
@TypeDefs({
    @TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class WebhookDelivery {

    /**
     * Unique identifier for the webhook delivery.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    /**
     * Reference to the webhook configuration used for this delivery.
     */
    @Column(name = "webhook_id", nullable = false)
    private UUID webhookId;

    /**
     * Reference to the transaction that triggered this webhook notification.
     * May be null for system events not related to a specific transaction.
     */
    @Column(name = "transaction_id")
    private UUID transactionId;

    /**
     * Type of event that triggered this webhook notification.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private WebhookEventType eventType;

    /**
     * Current delivery status of the webhook notification.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false)
    private WebhookDeliveryStatus deliveryStatus;

    /**
     * Payload that was sent or will be sent in the webhook notification.
     * Stored as a JSONB object in the database.
     */
    @Type(type = "jsonb")
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload;

    /**
     * Number of delivery attempts made so far.
     */
    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    /**
     * Timestamp of the last delivery attempt.
     */
    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    /**
     * HTTP response code received from the last delivery attempt.
     */
    @Column(name = "response_code")
    private Integer responseCode;

    /**
     * Response body received from the last delivery attempt.
     */
    @Column(name = "response_body", length = 4000)
    private String responseBody;

    /**
     * Details about any errors that occurred during the delivery.
     * Stored as a JSONB object in the database.
     */
    @Type(type = "jsonb")
    @Column(name = "error_details", columnDefinition = "jsonb")
    private Map<String, Object> errorDetails;

    /**
     * Timestamp when the webhook delivery was created.
     * Automatically set by the JPA auditing listener.
     */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Timestamp when the webhook delivery was last updated.
     * Automatically updated by the JPA auditing listener.
     */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Whether the delivery was acknowledged by the recipient.
     */
    @Column(name = "is_acknowledged")
    @Builder.Default
    private Boolean isAcknowledged = false;

    /**
     * Timestamp when the delivery was acknowledged, if applicable.
     */
    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    /**
     * Status message received during acknowledgment.
     */
    @Column(name = "acknowledgment_status")
    private String acknowledgmentStatus;

    /**
     * Timestamp when the next retry is scheduled, if applicable.
     */
    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    /**
     * Records a delivery attempt with the given response details.
     *
     * @param status The delivery status
     * @param responseCode The HTTP response code (may be null)
     * @param responseBody The response body (may be null)
     */
    public void recordAttempt(WebhookDeliveryStatus status, Integer responseCode, String responseBody) {
        this.attemptCount++;
        this.lastAttemptAt = LocalDateTime.now();
        this.deliveryStatus = status;
        this.responseCode = responseCode;
        this.responseBody = responseBody;
        
        // Clear next retry if successful
        if (status == WebhookDeliveryStatus.DELIVERED) {
            this.nextRetryAt = null;
        }
    }

    /**
     * Records an error during delivery attempt.
     *
     * @param status The delivery status
     * @param errorDetails The error details
     */
    public void recordError(WebhookDeliveryStatus status, Map<String, Object> errorDetails) {
        this.attemptCount++;
        this.lastAttemptAt = LocalDateTime.now();
        this.deliveryStatus = status;
        this.errorDetails = errorDetails;
    }

    /**
     * Schedules the next retry attempt.
     *
     * @param nextRetryAt The timestamp for the next retry
     */
    public void scheduleRetry(LocalDateTime nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
        this.deliveryStatus = WebhookDeliveryStatus.RETRY_SCHEDULED;
    }

    /**
     * Marks the delivery as acknowledged by the recipient.
     *
     * @param status The acknowledgment status
     */
    public void markAcknowledged(String status) {
        this.isAcknowledged = true;
        this.acknowledgedAt = LocalDateTime.now();
        this.acknowledgmentStatus = status;
    }

    /**
     * Checks if this delivery is in a terminal state (success or permanent failure).
     *
     * @return true if the delivery is in a terminal state, false otherwise
     */
    public boolean isTerminalState() {
        return this.deliveryStatus == WebhookDeliveryStatus.DELIVERED ||
               this.deliveryStatus == WebhookDeliveryStatus.PERMANENTLY_FAILED;
    }

    /**
     * Checks if this delivery is eligible for retry.
     *
     * @param maxRetries The maximum number of retry attempts allowed
     * @return true if the delivery is eligible for retry, false otherwise
     */
    public boolean isEligibleForRetry(int maxRetries) {
        return !isTerminalState() && 
               this.attemptCount < maxRetries && 
               this.deliveryStatus != WebhookDeliveryStatus.RETRY_SCHEDULED;
    }
}
