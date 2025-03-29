package com.exquy.webhook.api.dto;

import com.company.transactionrecovery.domain.enums.TransactionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Data Transfer Object for transaction responses.
 * Contains the transaction information returned to clients.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TransactionResponse {

    /**
     * Unique identifier of the transaction.
     */
    private UUID transactionId;

    /**
     * Current status of the transaction.
     */
    private TransactionStatus status;

    /**
     * Detailed payload/data of the transaction.
     */
    private Map<String, Object> details;

    /**
     * Response received after processing the transaction.
     * Only present for completed transactions.
     */
    private Map<String, Object> response;

    /**
     * Details about any errors that occurred during processing.
     * Only present for failed transactions.
     */
    private Map<String, Object> errorDetails;

    /**
     * Number of attempts made to process this transaction.
     */
    private Integer attemptCount;

    /**
     * Timestamp when the transaction was created.
     */
    private LocalDateTime createdAt;

    /**
     * Timestamp when the transaction was last updated.
     */
    private LocalDateTime updatedAt;

    /**
     * Timestamp when the transaction was completed.
     * Only present for completed or failed transactions.
     */
    private LocalDateTime completedAt;

    /**
     * Timestamp when the last attempt was made.
     * Only present for transactions with multiple attempts.
     */
    private LocalDateTime lastAttemptAt;

    /**
     * URL where webhook notifications are being sent for this transaction,
     * if applicable.
     */
    private String webhookUrl;

    /**
     * Flag indicating whether webhooks are enabled for this transaction.
     */
    private Boolean webhooksEnabled;
}
