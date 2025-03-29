-- Flyway migration script for webhook tables
-- Version: 2
-- Description: Creates the tables for webhook configuration and delivery

-- Create an enum type for webhook event types
CREATE TYPE webhook_event_type AS ENUM (
    'TRANSACTION_CREATED',
    'TRANSACTION_STATUS_CHANGED',
    'TRANSACTION_COMPLETED',
    'TRANSACTION_FAILED',
    'TRANSACTION_TIMEOUT',
    'TRANSACTION_RETRY',
    'TRANSACTION_MANUAL_RESOLUTION',
    'TRANSACTION_RECONCILED',
    'TRANSACTION_INCONSISTENT',
    'SYSTEM_ALERT',
    'SYSTEM_RECONCILIATION_START',
    'SYSTEM_RECONCILIATION_COMPLETE',
    'TEST'
);

-- Create an enum type for webhook delivery status
CREATE TYPE webhook_delivery_status AS ENUM (
    'PENDING',
    'PROCESSING',
    'DELIVERED',
    'FAILED',
    'RETRY_SCHEDULED',
    'PERMANENTLY_FAILED',
    'CANCELED'
);

-- Create the webhooks configuration table
CREATE TABLE webhooks (
    id UUID PRIMARY KEY,
    origin_system VARCHAR(50) NOT NULL,
    callback_url VARCHAR(255) NOT NULL,
    events JSONB NOT NULL,
    security_token VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    max_retries INTEGER,
    description TEXT,
    contact_email VARCHAR(100),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    last_success_at TIMESTAMP,
    last_failure_at TIMESTAMP,
    success_count BIGINT NOT NULL DEFAULT 0,
    failure_count BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0
);

-- Create index for quick access by origin system
CREATE INDEX idx_webhooks_origin_system ON webhooks(origin_system);

-- Create index for webhook URL
CREATE UNIQUE INDEX idx_webhooks_callback_url ON webhooks(callback_url);

-- Create index for active webhooks
CREATE INDEX idx_webhooks_active ON webhooks(is_active) WHERE is_active = TRUE;

-- Create the webhook_deliveries table
CREATE TABLE webhook_deliveries (
    id UUID PRIMARY KEY,
    webhook_id UUID NOT NULL REFERENCES webhooks(id),
    transaction_id UUID REFERENCES transactions(id),
    event_type webhook_event_type NOT NULL,
    delivery_status webhook_delivery_status NOT NULL,
    payload JSONB NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMP,
    response_code INTEGER,
    response_body TEXT,
    error_details JSONB,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    is_acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
    acknowledged_at TIMESTAMP,
    acknowledgment_status VARCHAR(50),
    next_retry_at TIMESTAMP
);

-- Create index for finding deliveries by webhook
CREATE INDEX idx_webhook_deliveries_webhook_id ON webhook_deliveries(webhook_id);

-- Create index for finding deliveries by transaction
CREATE INDEX idx_webhook_deliveries_transaction_id ON webhook_deliveries(transaction_id) 
WHERE transaction_id IS NOT NULL;

-- Create index for finding deliveries by status
CREATE INDEX idx_webhook_deliveries_status ON webhook_deliveries(delivery_status);

-- Create index for finding deliveries that need to be retried
CREATE INDEX idx_webhook_deliveries_retry ON webhook_deliveries(next_retry_at) 
WHERE next_retry_at IS NOT NULL;

-- Create index for finding deliveries by event type
CREATE INDEX idx_webhook_deliveries_event_type ON webhook_deliveries(event_type);

-- Create a view to analyze webhook performance
CREATE VIEW webhook_performance AS
SELECT 
    w.id,
    w.origin_system,
    w.callback_url,
    w.is_active,
    w.success_count,
    w.failure_count,
    CASE 
        WHEN (w.success_count + w.failure_count) > 0 
        THEN ROUND((w.success_count::NUMERIC / (w.success_count + w.failure_count)::NUMERIC) * 100, 2) 
        ELSE 0 
    END AS success_rate,
    w.last_success_at,
    w.last_failure_at,
    COUNT(wd.id) AS total_deliveries,
    SUM(CASE WHEN wd.delivery_status = 'DELIVERED' THEN 1 ELSE 0 END) AS delivered_count,
    SUM(CASE WHEN wd.delivery_status = 'PERMANENTLY_FAILED' THEN 1 ELSE 0 END) AS permanently_failed_count,
    SUM(CASE WHEN wd.delivery_status = 'PENDING' OR wd.delivery_status = 'PROCESSING' OR wd.delivery_status = 'RETRY_SCHEDULED' THEN 1 ELSE 0 END) AS in_progress_count,
    AVG(CASE WHEN wd.delivery_status = 'DELIVERED' THEN wd.attempt_count ELSE NULL END) AS avg_attempts_until_success
FROM 
    webhooks w
LEFT JOIN 
    webhook_deliveries wd ON w.id = wd.webhook_id
GROUP BY 
    w.id, w.origin_system, w.callback_url, w.is_active, w.success_count, w.failure_count, 
    w.last_success_at, w.last_failure_at;

-- Create a function to update webhook statistics on delivery status change
CREATE OR REPLACE FUNCTION fn_update_webhook_stats()
RETURNS TRIGGER AS $$
BEGIN
    -- If the delivery status changes to DELIVERED, increment success count
    IF (NEW.delivery_status = 'DELIVERED' AND 
        (OLD.delivery_status IS NULL OR OLD.delivery_status != 'DELIVERED')) THEN
        
        UPDATE webhooks
        SET 
            success_count = success_count + 1,
            last_success_at = CURRENT_TIMESTAMP,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = NEW.webhook_id;
    
    -- If the delivery status changes to a failure state, increment failure count
    ELSIF (NEW.delivery_status IN ('PERMANENTLY_FAILED', 'FAILED') AND 
           (OLD.delivery_status IS NULL OR 
            (OLD.delivery_status != 'PERMANENTLY_FAILED' AND OLD.delivery_status != 'FAILED'))) THEN
        
        UPDATE webhooks
        SET 
            failure_count = failure_count + 1,
            last_failure_at = CURRENT_TIMESTAMP,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = NEW.webhook_id;
    END IF;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Create trigger for automatic webhook stats updates
CREATE TRIGGER trg_update_webhook_stats
AFTER UPDATE OF delivery_status ON webhook_deliveries
FOR EACH ROW
EXECUTE FUNCTION fn_update_webhook_stats();

-- Create a table to store webhook events by origin system
CREATE TABLE webhook_subscriptions (
    id BIGSERIAL PRIMARY KEY,
    origin_system VARCHAR(50) NOT NULL,
    event_type webhook_event_type NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create unique constraint for origin system and event type
CREATE UNIQUE INDEX idx_webhook_subscriptions_unique 
ON webhook_subscriptions(origin_system, event_type);

-- Create function to check if a webhook event is supported by a JSON array of events
CREATE OR REPLACE FUNCTION jsonb_contains_event(events JSONB, event TEXT)
RETURNS BOOLEAN AS $$
BEGIN
    RETURN events @> ('"' || event || '"');
END;
$$ LANGUAGE plpgsql;
