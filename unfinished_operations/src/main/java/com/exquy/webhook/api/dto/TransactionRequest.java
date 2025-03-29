package com.exquy.webhook.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

/**
 * Data Transfer Object for transaction creation requests.
 * Contains all the information needed to create or retry a transaction.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TransactionRequest {

    /**
     * Unique identifier for the transaction.
     * Must be generated by the originator to ensure idempotency.
     */
    @NotNull(message = "Transaction ID is required")
    private UUID transactionId;

    /**
     * Identifier of the system originating the transaction.
     */
    @NotBlank(message = "Origin system is required")
    private String originSystem;

    /**
     * Detailed payload of the transaction.
     * Contains all the business data required for processing.
     */
    @NotNull(message = "Transaction payload is required")
    private Map<String, Object> payload;

    /**
     * Flag indicating if this is a retry of a previous request.
     */
    private boolean retry;

    /**
     * Count of retry attempts.
     * Only present if the retry flag is true.
     */
    private Integer retryCount;

    /**
     * URL to receive webhook notifications for this transaction.
     * If provided, the system will send notifications to this URL
     * when the transaction status changes.
     */
    private String webhookUrl;

    /**
     * The security token to use for signing webhook notifications.
     * Required if webhookUrl is provided.
     */
    private String webhookSecurityToken;

    /**
     * List of event types that should trigger webhook notifications.
     * If not specified, all transaction status changes will be notified.
     */
    private String[] webhookEvents;
}
