/**
 * Enumeration of possible transaction states.
 * These states represent the various phases a transaction can go through
 * during its lifecycle in the system.
 */
public enum TransactionStatus {
    /**
     * Transaction has been received but not yet processed.
     */
    PENDING,
    
    /**
     * Transaction is currently being processed.
     */
    PROCESSING,
    
    /**
     * Transaction has been successfully completed.
     * This is a terminal state.
     */
    COMPLETED,
    
    /**
     * Transaction has failed.
     * This is a terminal state.
     */
    FAILED,
    
    /**
     * Transaction processing time has exceeded the configured threshold.
     * This state indicates that the final outcome could not be determined
     * within the expected timeframe.
     */
    TIMEOUT,
    
    /**
     * Transaction is in an inconsistent state that cannot be automatically resolved.
     * This state indicates that manual intervention is required.
     */
    INCONSISTENT,
    
    /**
     * Transaction has been permanently failed after exceeding the maximum 
     * number of retry attempts.
     * This is a terminal state.
     */
    PERMANENTLY_FAILED;
    
    /**
     * Checks if this status is a terminal state.
     * Terminal states indicate that a transaction has reached its final outcome
     * and will not change state further.
     *
     * @return true if this is a terminal state, false otherwise
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == PERMANENTLY_FAILED;
    }
    
    /**
     * Checks if this status is a transient state.
     * Transient states indicate that a transaction is still in progress or
     * waiting for processing.
     *
     * @return true if this is a transient state, false otherwise
     */
    public boolean isTransient() {
        return this == PENDING || this == PROCESSING;
    }
    
    /**
     * Checks if this status is a problematic state.
     * Problematic states indicate that some issue has occurred and the transaction
     * may require special handling or manual intervention.
     *
     * @return true if this is a problematic state, false otherwise
     */
    public boolean isProblematic() {
        return this == TIMEOUT || this == INCONSISTENT;
    }
    
    /**
     * Checks if a retry is possible from this state.
     *
     * @return true if retry is possible, false otherwise
     */
    public boolean canRetry() {
        return this == PENDING || this == TIMEOUT || this == INCONSISTENT;
    }
    
    /**
     * Gets a human-readable description of this status.
     *
     * @return A description of the status
     */
    public String getDescription() {
        switch (this) {
            case PENDING:
                return "Transaction is pending processing";
            case PROCESSING:
                return "Transaction is currently being processed";
            case COMPLETED:
                return "Transaction has been successfully completed";
            case FAILED:
                return "Transaction has failed";
            case TIMEOUT:
                return "Transaction processing has timed out";
            case INCONSISTENT:
                return "Transaction is in an inconsistent state that requires manual intervention";
            case PERMANENTLY_FAILED:
                return "Transaction has permanently failed after exceeding retry attempts";
            default:
                return "Unknown status";
        }
    }
}
