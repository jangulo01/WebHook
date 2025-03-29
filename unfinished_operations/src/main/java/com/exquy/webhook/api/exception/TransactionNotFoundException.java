package com.exquy.webhook.api.exception;

import java.util.UUID;

/**
 * Exception thrown when a transaction cannot be found in the system.
 * This typically occurs when a client attempts to query or modify
 * a transaction that does not exist or has been deleted.
 */
public class TransactionNotFoundException extends RuntimeException {

    private final UUID transactionId;

    /**
     * Constructs a new TransactionNotFoundException with the specified transaction ID.
     *
     * @param transactionId The ID of the transaction that was not found
     */
    public TransactionNotFoundException(UUID transactionId) {
        super("Transaction not found with ID: " + transactionId);
        this.transactionId = transactionId;
    }

    /**
     * Constructs a new TransactionNotFoundException with a custom message.
     *
     * @param message The custom error message
     * @param transactionId The ID of the transaction that was not found
     */
    public TransactionNotFoundException(String message, UUID transactionId) {
        super(message);
        this.transactionId = transactionId;
    }

    /**
     * Gets the ID of the transaction that was not found.
     *
     * @return The transaction ID
     */
    public UUID getTransactionId() {
        return transactionId;
    }
}
