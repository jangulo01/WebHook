package com.exquy.webhook.domain.model;

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
import java.util.Set;
import java.util.UUID;

/**
 * Entity representing a webhook configuration in the system.
 * Each webhook configuration defines how and when notifications are sent
 * to external systems.
 */
@Entity
@Table(name = "webhooks")
@TypeDefs({
    @TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class WebhookConfig {

    /**
     * Unique identifier for the webhook configuration.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    /**
     * Identifier of the system that registered this webhook.
     */
    @Column(name = "origin_system", nullable = false)
    private String originSystem;

    /**
     * URL where webhook notifications will be sent.
     */
    @Column(name = "callback_url", nullable = false)
    private String callbackUrl;

    /**
     * Set of event types that should trigger webhook notifications.
     * Stored as a JSONB array in the database.
     */
    @Type(type = "jsonb")
    @Column(name = "events", columnDefinition = "jsonb", nullable = false)
    private Set<WebhookEventType> events;

    /**
     * Secret token used to sign webhook payloads for verification.
     * This token is used to create a signature that the receiver can validate.
     */
    @Column(name = "security_token", nullable = false)
    private String securityToken;

    /**
     * Indicates whether this webhook is currently active.
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /**
     * Maximum number of retry attempts for failed webhook deliveries.
     * If null, the system default will be used.
     */
    @Column(name = "max_retries")
    private Integer maxRetries;

    /**
     * Description of the webhook for administrative purposes.
     */
    @Column(name = "description")
    private String description;

    /**
     * Contact email for notifications about webhook delivery issues.
     */
    @Column(name = "contact_email")
    private String contactEmail;

    /**
     * Timestamp when the webhook was registered.
     * Automatically set by the JPA auditing listener.
     */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Timestamp when the webhook configuration was last updated.
     * Automatically updated by the JPA auditing listener.
     */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Last time a successful webhook delivery was made.
     */
    @Column(name = "last_success_at")
    private LocalDateTime lastSuccessAt;

    /**
     * Last time a webhook delivery failed.
     */
    @Column(name = "last_failure_at")
    private LocalDateTime lastFailureAt;

    /**
     * Count of successful webhook deliveries.
     */
    @Column(name = "success_count")
    @Builder.Default
    private Long successCount = 0L;

    /**
     * Count of failed webhook deliveries.
     */
    @Column(name = "failure_count")
    @Builder.Default
    private Long failureCount = 0L;

    /**
     * Lock version for optimistic locking.
     * Prevents concurrent updates from overwriting each other.
     */
    @Version
    private Long version;

    /**
     * Checks if this webhook is subscribed to a specific event type.
     *
     * @param eventType The event type to check
     * @return true if this webhook is subscribed to the event, false otherwise
     */
    public boolean isSubscribedToEvent(WebhookEventType eventType) {
        return events != null && events.contains(eventType);
    }

    /**
     * Records a successful webhook delivery.
     */
    public void recordSuccess() {
        this.lastSuccessAt = LocalDateTime.now();
        this.successCount++;
    }

    /**
     * Records a failed webhook delivery.
     */
    public void recordFailure() {
        this.lastFailureAt = LocalDateTime.now();
        this.failureCount++;
    }

    /**
     * Gets the effective maximum retry count.
     * Returns the configured value or the system default if not set.
     *
     * @param systemDefault The system default value
     * @return The effective maximum retry count
     */
    public int getEffectiveMaxRetries(int systemDefault) {
        return maxRetries != null ? maxRetries : systemDefault;
    }
}
