/**
 * Enumeration of possible webhook delivery states.
 * These states represent the various phases a webhook delivery can go through
 * during its lifecycle in the system.
 */
public enum WebhookDeliveryStatus {
    /**
     * Delivery has been created but not yet attempted.
     */
    PENDING,
    
    /**
     * Delivery is currently being processed.
     */
    PROCESSING,
    
    /**
     * Delivery has been successfully sent and the recipient responded with a success status.
     * This is a terminal state.
     */
    DELIVERED,
    
    /**
     * Delivery attempt failed, but will be retried.
     */
    FAILED,
    
    /**
     * Delivery has been scheduled for retry.
     */
    RETRY_SCHEDULED,
    
    /**
     * Delivery has permanently failed after exceeding the maximum number of retry attempts.
     * This is a terminal state.
     */
    PERMANENTLY_FAILED,
    
    /**
     * Delivery was canceled.
     * This is a terminal state.
     */
    CANCELED;
    
    /**
     * Checks if this status is a terminal state.
     * Terminal states indicate that a delivery has reached its final outcome
     * and will not change state further.
     *
     * @return true if this is a terminal state, false otherwise
     */
    public boolean isTerminal() {
        return this == DELIVERED || this == PERMANENTLY_FAILED || this == CANCELED;
    }
    
    /**
     * Checks if this status is a transient state.
     * Transient states indicate that a delivery is still in progress or
     * waiting for processing.
     *
     * @return true if this is a transient state, false otherwise
     */
    public boolean isTransient() {
        return this == PENDING || this == PROCESSING || this == RETRY_SCHEDULED;
    }
    
    /**
     * Checks if this status indicates a failure.
     *
     * @return true if this status indicates a failure, false otherwise
     */
    public boolean isFailed() {
        return this == FAILED || this == PERMANENTLY_FAILED;
    }
    
    /**
     * Checks if retry is possible from this state.
     *
     * @return true if retry is possible, false otherwise
     */
    public boolean canRetry() {
        return this == FAILED || this == RETRY_SCHEDULED;
    }
    
    /**
     * Gets a human-readable description of this status.
     *
     * @return A description of the status
     */
    public String getDescription() {
        switch (this) {
            case PENDING:
                return "Webhook delivery is pending";
            case PROCESSING:
                return "Webhook delivery is being processed";
            case DELIVERED:
                return "Webhook was successfully delivered";
            case FAILED:
                return "Webhook delivery failed, will be retried";
            case RETRY_SCHEDULED:
                return "Webhook delivery is scheduled for retry";
            case PERMANENTLY_FAILED:
                return "Webhook delivery permanently failed after maximum retries";
            case CANCELED:
                return "Webhook delivery was canceled";
            default:
                return "Unknown status";
        }
    }
    
    /**
     * Checks if this state requires action from the system.
     *
     * @return true if this state requires action, false otherwise
     */
    public boolean requiresAction() {
        return this == PENDING || this == RETRY_SCHEDULED;
    }
    
    /**
     * Gets the next state after a successful delivery.
     *
     * @return The next state
     */
    public WebhookDeliveryStatus getSuccessNextState() {
        return DELIVERED;
    }
    
    /**
     * Gets the next state after a failed delivery attempt.
     *
     * @param maxRetriesReached Whether the maximum number of retries has been reached
     * @return The next state
     */
    public WebhookDeliveryStatus getFailureNextState(boolean maxRetriesReached) {
        if (maxRetriesReached) {
            return PERMANENTLY_FAILED;
        }
        return FAILED;
    }
}
