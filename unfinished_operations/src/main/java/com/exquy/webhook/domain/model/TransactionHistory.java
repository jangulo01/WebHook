package com.exquy.webhook.domain.model;

import com.company.transactionrecovery.domain.enums.TransactionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity representing the history of transaction status changes.
 * Provides an audit trail of all state transitions for a transaction.
 */
@Entity
@Table(name = "transaction_history")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class TransactionHistory {

    /**
     * Unique identifier for the history record.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Reference to the transaction this history record belongs to.
     */
    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    /**
     * The previous status of the transaction.
     * May be null if this is the initial status.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status")
    private TransactionStatus previousStatus;

    /**
     * The new status of the transaction.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false)
    private TransactionStatus newStatus;

    /**
     * Timestamp when the status change occurred.
     * Automatically set by the JPA auditing listener.
     */
    @CreatedDate
    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    /**
     * The reason for the status change.
     */
    @Column(name = "reason", length = 255)
    private String reason;

    /**
     * Identifier of the user or system that initiated the change.
     */
    @Column(name = "changed_by", nullable = false)
    private String changedBy;

    /**
     * Additional context information about the change.
     */
    @Column(name = "context", length = 1000)
    private String context;

    /**
     * The attempt number associated with this status change.
     */
    @Column(name = "attempt_number")
    private Integer attemptNumber;

    /**
     * Boolean flag indicating if this was an automatic or manual change.
     */
    @Column(name = "is_automatic")
    @Builder.Default
    private Boolean isAutomatic = true;

    /**
     * Static factory method to create a history entry for a new transaction.
     *
     * @param transactionId The ID of the transaction
     * @param status The initial status
     * @param changedBy Identifier of who/what created the transaction
     * @return A new TransactionHistory instance
     */
    public static TransactionHistory createInitialEntry(UUID transactionId, TransactionStatus status, String changedBy) {
        return TransactionHistory.builder()
                .transactionId(transactionId)
                .previousStatus(null)
                .newStatus(status)
                .changedBy(changedBy)
                .reason("Transaction created")
                .attemptNumber(1)
                .isAutomatic(true)
                .build();
    }

    /**
     * Static factory method to create a history entry for a status change.
     *
     * @param transactionId The ID of the transaction
     * @param previousStatus The previous status
     * @param newStatus The new status
     * @param reason The reason for the change
     * @param changedBy Identifier of who/what changed the status
     * @return A new TransactionHistory instance
     */
    public static TransactionHistory createStatusChangeEntry(
            UUID transactionId,
            TransactionStatus previousStatus,
            TransactionStatus newStatus,
            String reason,
            String changedBy) {
        
        return TransactionHistory.builder()
                .transactionId(transactionId)
                .previousStatus(previousStatus)
                .newStatus(newStatus)
                .reason(reason)
                .changedBy(changedBy)
                .isAutomatic(true)
                .build();
    }

    /**
     * Static factory method to create a history entry for a retry attempt.
     *
     * @param transactionId The ID of the transaction
     * @param attemptNumber The attempt number
     * @param changedBy Identifier of who/what initiated the retry
     * @return A new TransactionHistory instance
     */
    public static TransactionHistory createRetryEntry(
            UUID transactionId,
            TransactionStatus status,
            Integer attemptNumber,
            String changedBy) {
        
        return TransactionHistory.builder()
                .transactionId(transactionId)
                .previousStatus(status)
                .newStatus(status)
                .reason("Retry attempt")
                .changedBy(changedBy)
                .attemptNumber(attemptNumber)
                .isAutomatic(true)
                .build();
    }

    /**
     * Static factory method to create a history entry for a manual resolution.
     *
     * @param transactionId The ID of the transaction
     * @param previousStatus The previous status
     * @param newStatus The new status
     * @param reason The reason for the manual resolution
     * @param changedBy Identifier of who performed the resolution
     * @param context Additional context information
     * @return A new TransactionHistory instance
     */
    public static TransactionHistory createManualResolutionEntry(
            UUID transactionId,
            TransactionStatus previousStatus,
            TransactionStatus newStatus,
            String reason,
            String changedBy,
            String context) {
        
        return TransactionHistory.builder()
                .transactionId(transactionId)
                .previousStatus(previousStatus)
                .newStatus(newStatus)
                .reason(reason)
                .changedBy(changedBy)
                .context(context)
                .isAutomatic(false)
                .build();
    }
}
