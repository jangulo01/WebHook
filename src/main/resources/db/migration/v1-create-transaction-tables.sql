-- Flyway migration script for transaction tables
-- Version: 1
-- Description: Creates the initial tables for transaction management

-- Create an enum type for transaction status
CREATE TYPE transaction_status AS ENUM (
    'PENDING',
    'PROCESSING',
    'COMPLETED',
    'FAILED',
    'TIMEOUT',
    'INCONSISTENT',
    'PERMANENTLY_FAILED'
);

-- Create the transactions table
CREATE TABLE transactions (
    id UUID PRIMARY KEY,
    origin_system VARCHAR(50) NOT NULL,
    status transaction_status NOT NULL,
    payload JSONB,
    response JSONB,
    error_details JSONB,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMP,
    completion_at TIMESTAMP,
    webhook_url VARCHAR(255),
    webhook_security_token VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    is_reconciled BOOLEAN NOT NULL DEFAULT FALSE,
    notes TEXT,
    version BIGINT NOT NULL DEFAULT 0
);

-- Create index for quick access by origin system
CREATE INDEX idx_transactions_origin_system ON transactions(origin_system);

-- Create index for status queries
CREATE INDEX idx_transactions_status ON transactions(status);

-- Create index for finding transactions by creation date
CREATE INDEX idx_transactions_created_at ON transactions(created_at);

-- Create index for finding transactions by update date
CREATE INDEX idx_transactions_updated_at ON transactions(updated_at);

-- Create index for finding transactions with webhooks enabled
CREATE INDEX idx_transactions_webhook ON transactions(webhook_url) 
WHERE webhook_url IS NOT NULL AND webhook_url != '';

-- Create the transaction_history table
CREATE TABLE transaction_history (
    id BIGSERIAL PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES transactions(id),
    previous_status transaction_status,
    new_status transaction_status NOT NULL,
    changed_at TIMESTAMP NOT NULL,
    reason VARCHAR(255),
    changed_by VARCHAR(50) NOT NULL,
    context TEXT,
    attempt_number INTEGER,
    is_automatic BOOLEAN NOT NULL DEFAULT TRUE
);

-- Create index for quick access to a transaction's history
CREATE INDEX idx_transaction_history_transaction_id ON transaction_history(transaction_id);

-- Create index for finding history entries by change date
CREATE INDEX idx_transaction_history_changed_at ON transaction_history(changed_at);

-- Create index for finding history entries by status change
CREATE INDEX idx_transaction_history_status_change ON transaction_history(previous_status, new_status);

-- Create a view for transaction summary
CREATE VIEW transaction_summary AS
SELECT 
    t.id,
    t.origin_system,
    t.status,
    t.created_at,
    t.updated_at,
    t.attempt_count,
    (t.webhook_url IS NOT NULL AND t.webhook_url != '') AS has_webhook,
    t.is_reconciled,
    COUNT(th.id) AS history_count
FROM 
    transactions t
LEFT JOIN 
    transaction_history th ON t.id = th.transaction_id
GROUP BY 
    t.id, t.origin_system, t.status, t.created_at, t.updated_at, 
    t.attempt_count, has_webhook, t.is_reconciled;

-- Create a function to automatically add history entries on status change
CREATE OR REPLACE FUNCTION fn_transaction_status_history()
RETURNS TRIGGER AS $$
BEGIN
    IF (OLD.status IS DISTINCT FROM NEW.status) THEN
        INSERT INTO transaction_history (
            transaction_id,
            previous_status,
            new_status,
            changed_at,
            reason,
            changed_by,
            is_automatic
        ) VALUES (
            NEW.id,
            OLD.status,
            NEW.status,
            CURRENT_TIMESTAMP,
            'Automatic status change trigger',
            'SYSTEM_TRIGGER',
            TRUE
        );
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Create trigger for automatic history tracking
CREATE TRIGGER trg_transaction_status_history
AFTER UPDATE OF status ON transactions
FOR EACH ROW
EXECUTE FUNCTION fn_transaction_status_history();

-- Create a statistics table for aggregated metrics
CREATE TABLE transaction_statistics (
    id BIGSERIAL PRIMARY KEY,
    date DATE NOT NULL,
    origin_system VARCHAR(50) NOT NULL,
    status transaction_status NOT NULL,
    count INTEGER NOT NULL DEFAULT 0,
    avg_processing_time_ms BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create unique constraint for date, origin system and status
CREATE UNIQUE INDEX idx_transaction_statistics_unique 
ON transaction_statistics(date, origin_system, status);
