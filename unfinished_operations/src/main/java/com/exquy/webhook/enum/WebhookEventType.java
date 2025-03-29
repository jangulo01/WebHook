/**
 * Enumeration of webhook event types.
 * These events represent various actions or state changes in the system
 * that can trigger a webhook notification.
 */
public enum WebhookEventType {
    /**
     * Triggered when a transaction is created.
     */
    TRANSACTION_CREATED,
    
    /**
     * Triggered when a transaction's status changes.
     */
    TRANSACTION_STATUS_CHANGED,
    
    /**
     * Triggered when a transaction is completed successfully.
     */
    TRANSACTION_COMPLETED,
    
    /**
     * Triggered when a transaction fails.
     */
    TRANSACTION_FAILED,
    
    /**
     * Triggered when a transaction times out.
     */
    TRANSACTION_TIMEOUT,
    
    /**
     * Triggered when a transaction is automatically retried.
     */
    TRANSACTION_RETRY,
    
    /**
     * Triggered when a transaction is manually resolved.
     */
    TRANSACTION_MANUAL_RESOLUTION,
    
    /**
     * Triggered when a transaction is reconciled.
     */
    TRANSACTION_RECONCILED,
    
    /**
     * Triggered when a transaction is marked as inconsistent.
     */
    TRANSACTION_INCONSISTENT,
    
    /**
     * Triggered when a system-wide alert is generated.
     */
    SYSTEM_ALERT,
    
    /**
     * Triggered when a system-wide reconciliation process starts.
     */
    SYSTEM_RECONCILIATION_START,
    
    /**
     * Triggered when a system-wide reconciliation process completes.
     */
    SYSTEM_RECONCILIATION_COMPLETE,
    
    /**
     * Event for testing webhook connectivity.
     */
    TEST;
    
    /**
     * Checks if this event type is related to a transaction.
     *
     * @return true if this is a transaction-related event, false otherwise
     */
    public boolean isTransactionEvent() {
        return this.name().startsWith("TRANSACTION_");
    }
    
    /**
     * Checks if this event type is related to a system-wide event.
     *
     * @return true if this is a system-related event, false otherwise
     */
    public boolean isSystemEvent() {
        return this.name().startsWith("SYSTEM_");
    }
    
    /**
     * Checks if this event type indicates a terminal state.
     *
     * @return true if this event indicates a terminal state, false otherwise
     */
    public boolean isTerminalEvent() {
        return this == TRANSACTION_COMPLETED || 
               this == TRANSACTION_FAILED;
    }
    
    /**
     * Checks if this event type indicates a problem.
     *
     * @return true if this event indicates a problem, false otherwise
     */
    public boolean isProblemEvent() {
        return this == TRANSACTION_FAILED || 
               this == TRANSACTION_TIMEOUT || 
               this == TRANSACTION_INCONSISTENT || 
               this == SYSTEM_ALERT;
    }
    
    /**
     * Gets a human-readable description of this event type.
     *
     * @return A description of the event type
     */
    public String getDescription() {
        switch (this) {
            case TRANSACTION_CREATED:
                return "A new transaction has been created";
            case TRANSACTION_STATUS_CHANGED:
                return "A transaction's status has changed";
            case TRANSACTION_COMPLETED:
                return "A transaction has been completed successfully";
            case TRANSACTION_FAILED:
                return "A transaction has failed";
            case TRANSACTION_TIMEOUT:
                return "A transaction has timed out";
            case TRANSACTION_RETRY:
                return "A transaction is being retried";
            case TRANSACTION_MANUAL_RESOLUTION:
                return "A transaction has been manually resolved";
            case TRANSACTION_RECONCILED:
                return "A transaction has been reconciled";
            case TRANSACTION_INCONSISTENT:
                return "A transaction has been marked as inconsistent";
            case SYSTEM_ALERT:
                return "A system-wide alert has been generated";
            case SYSTEM_RECONCILIATION_START:
                return "A system-wide reconciliation process has started";
            case SYSTEM_RECONCILIATION_COMPLETE:
                return "A system-wide reconciliation process has completed";
            case TEST:
                return "Test event for webhook connectivity";
            default:
                return "Unknown event type";
        }
    }
    
    /**
     * Gets the default status code for this event type.
     * This is used as a guideline for webhook receivers to understand
     * the nature of the event.
     *
     * @return The suggested HTTP status code for this event
     */
    public int getDefaultStatusCode() {
        switch (this) {
            case TRANSACTION_CREATED:
                return 201; // Created
            case TRANSACTION_STATUS_CHANGED:
            case TRANSACTION_RETRY:
            case TRANSACTION_RECONCILED:
            case SYSTEM_RECONCILIATION_START:
            case SYSTEM_RECONCILIATION_COMPLETE:
            case TEST:
                return 200; // OK
            case TRANSACTION_COMPLETED:
                return 200; // OK
            case TRANSACTION_FAILED:
            case TRANSACTION_TIMEOUT:
            case TRANSACTION_INCONSISTENT:
                return 400; // Bad Request
            case TRANSACTION_MANUAL_RESOLUTION:
                return 200; // OK
            case SYSTEM_ALERT:
                return 500; // Internal Server Error
            default:
                return 200; // OK
        }
    }
}
