package com.exquy.webhook.api.exception;

import com.company.transactionrecovery.domain.enums.TransactionStatus;

import java.util.UUID;

/**
 * Exception thrown when a transaction with the same ID already exists but with different data.
 * This represents a violation of the idempotency principle where retrying the same transaction
 * should not create duplicates, but the system has detected inconsistent data between attempts.
 */
public class DuplicateTransactionException extends RuntimeException {

    private final UUID existingTransactionId;
    private final TransactionStatus existingStatus;

    /**
     * Constructs a new DuplicateTransactionException with the specified transaction details.
     *
     * @param transactionId The ID of the existing transaction
     * @param status The current status of the existing transaction
     */
    public DuplicateTransactionException(UUID transactionId, TransactionStatus status) {
        super("Transaction with ID " + transactionId + " already exists with status: " + status + 
              ". The current request has different data than the original request.");
        this.existingTransactionId = transactionId;
        this.existingStatus = status;
    }

    /**
     * Constructs a new DuplicateTransactionException with a custom message.
     *
     * @param message The custom error message
     * @param transactionId The ID of the existing transaction
     * @param status The current status of the existing transaction
     */
    public DuplicateTransactionException(String message, UUID transactionId, TransactionStatus status) {
        super(message);
        this.existingTransactionId = transactionId;
        this.existingStatus = status;
    }

    /**
     * Gets the ID of the existing transaction.
     *
     * @return The transaction ID
     */
    public UUID getExistingTransactionId() {
        return existingTransactionId;
    }

    /**
     * Gets the current status of the existing transaction.
     *
     * @return The transaction status
     */
    public TransactionStatus getExistingStatus() {
        return existingStatus;
    }
}
