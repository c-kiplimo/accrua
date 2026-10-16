-- Accrua Database Schema
-- Version: 1.0.0
-- Description: Initial schema for usage-based billing and metering infrastructure

-- ============================================================================
-- METERS TABLE
-- ============================================================================
CREATE TABLE meters (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    unit VARCHAR(50) NOT NULL,
    aggregation_type VARCHAR(50) NOT NULL,
    pricing_model JSONB NOT NULL,
    metadata JSONB DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    active BOOLEAN NOT NULL DEFAULT true,

    CONSTRAINT meters_unique_name_per_tenant UNIQUE(tenant_id, name)
);

CREATE INDEX idx_meters_tenant ON meters(tenant_id);
CREATE INDEX idx_meters_active ON meters(active);

-- ============================================================================
-- ALLOCATIONS TABLE
-- ============================================================================
CREATE TABLE allocations (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    sponsor_id VARCHAR(255) NOT NULL,
    provider_id VARCHAR(255) NOT NULL,
    meter_id UUID NOT NULL REFERENCES meters(id),
    total_amount NUMERIC(20, 4) NOT NULL,
    used_amount NUMERIC(20, 4) NOT NULL DEFAULT 0,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ,
    last_activity_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    decay_policy VARCHAR(255),
    metadata JSONB DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT allocations_positive_amounts CHECK (total_amount >= 0 AND used_amount >= 0),
    CONSTRAINT allocations_usage_within_total CHECK (used_amount <= total_amount)
);

CREATE INDEX idx_allocations_tenant ON allocations(tenant_id);
CREATE INDEX idx_allocations_sponsor ON allocations(sponsor_id);
CREATE INDEX idx_allocations_provider ON allocations(provider_id);
CREATE INDEX idx_allocations_status ON allocations(status);
CREATE INDEX idx_allocations_expires ON allocations(expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX idx_allocations_decay ON allocations(decay_policy) WHERE decay_policy IS NOT NULL;

-- ============================================================================
-- USAGE EVENTS TABLE (Partitioned by time)
-- ============================================================================
CREATE TABLE usage_events (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    allocation_id UUID NOT NULL REFERENCES allocations(id),
    end_user_id VARCHAR(255) NOT NULL,
    meter_id UUID NOT NULL REFERENCES meters(id),
    amount NUMERIC(20, 4) NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    occurred_at TIMESTAMPTZ NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    metadata JSONB DEFAULT '{}'::jsonb,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    processed_at TIMESTAMPTZ,

    CONSTRAINT usage_events_positive_amount CHECK (amount >= 0)
) PARTITION BY RANGE (timestamp);

-- Create partitions for current year (2026)
CREATE TABLE usage_events_2026_10 PARTITION OF usage_events
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE usage_events_2026_11 PARTITION OF usage_events
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE usage_events_2026_12 PARTITION OF usage_events
    FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
CREATE TABLE usage_events_2027_01 PARTITION OF usage_events
    FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');

-- Indexes on parent table (inherited by partitions)
CREATE INDEX idx_usage_events_tenant ON usage_events(tenant_id, timestamp DESC);
CREATE INDEX idx_usage_events_allocation ON usage_events(allocation_id, timestamp DESC);
CREATE INDEX idx_usage_events_idempotency ON usage_events(idempotency_key);
CREATE INDEX idx_usage_events_end_user ON usage_events(end_user_id, tenant_id, timestamp DESC);
CREATE INDEX idx_usage_events_processed ON usage_events(processed_at) WHERE processed_at IS NULL;

-- ============================================================================
-- LEDGER ENTRIES TABLE (Immutable audit log)
-- ============================================================================
CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    allocation_id UUID NOT NULL REFERENCES allocations(id),
    event_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    amount NUMERIC(20, 4) NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    metadata JSONB DEFAULT '{}'::jsonb,
    sponsor_id VARCHAR(255),
    end_user_id VARCHAR(255),
    provider_id VARCHAR(255)
);

CREATE INDEX idx_ledger_tenant ON ledger_entries(tenant_id, timestamp DESC);
CREATE INDEX idx_ledger_allocation ON ledger_entries(allocation_id, timestamp DESC);
CREATE INDEX idx_ledger_sponsor ON ledger_entries(sponsor_id, timestamp DESC) WHERE sponsor_id IS NOT NULL;
CREATE INDEX idx_ledger_event_type ON ledger_entries(event_type);

-- ============================================================================
-- BALANCE SNAPSHOTS TABLE (Materialized view)
-- ============================================================================
CREATE TABLE balance_snapshots (
    id UUID PRIMARY KEY,
    allocation_id UUID NOT NULL REFERENCES allocations(id),
    balance NUMERIC(20, 4) NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT balance_snapshots_unique_allocation_time UNIQUE(allocation_id, timestamp)
);

CREATE INDEX idx_balance_snapshots_allocation ON balance_snapshots(allocation_id, timestamp DESC);

-- ============================================================================
-- INVOICES TABLE
-- ============================================================================
CREATE TABLE invoices (
    id UUID PRIMARY KEY,
    sponsor_id VARCHAR(255) NOT NULL,
    billing_date DATE NOT NULL,
    due_date DATE NOT NULL,
    total_amount NUMERIC(20, 4) NOT NULL,
    status VARCHAR(50) NOT NULL,
    line_items JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sent_at TIMESTAMPTZ,
    paid_at TIMESTAMPTZ,
    payment_reference VARCHAR(255),
    metadata JSONB DEFAULT '{}'::jsonb,

    CONSTRAINT invoices_valid_dates CHECK (due_date >= billing_date),
    CONSTRAINT invoices_positive_amount CHECK (total_amount >= 0)
);

CREATE INDEX idx_invoices_sponsor ON invoices(sponsor_id, billing_date DESC);
CREATE INDEX idx_invoices_status ON invoices(status);
CREATE INDEX idx_invoices_due ON invoices(due_date) WHERE status IN ('PENDING', 'SENT');
CREATE INDEX idx_invoices_billing_date ON invoices(billing_date DESC);

-- ============================================================================
-- FUNCTIONS AND TRIGGERS
-- ============================================================================

-- Update updated_at timestamp automatically
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_meters_updated_at BEFORE UPDATE ON meters
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- VIEWS FOR ANALYTICS
-- ============================================================================

-- View for active allocations summary
CREATE VIEW v_active_allocations AS
SELECT
    tenant_id,
    sponsor_id,
    provider_id,
    COUNT(*) as allocation_count,
    SUM(total_amount) as total_allocated,
    SUM(used_amount) as total_used,
    SUM(total_amount - used_amount) as total_remaining,
    AVG((used_amount / NULLIF(total_amount, 0)) * 100) as avg_utilization_pct
FROM allocations
WHERE status = 'ACTIVE'
GROUP BY tenant_id, sponsor_id, provider_id;

-- View for daily usage summary
CREATE VIEW v_daily_usage AS
SELECT
    DATE(occurred_at) as usage_date,
    tenant_id,
    allocation_id,
    COUNT(*) as event_count,
    SUM(amount) as total_amount,
    AVG(amount) as avg_amount
FROM usage_events
GROUP BY DATE(occurred_at), tenant_id, allocation_id;

-- ============================================================================
-- COMMENTS (Documentation)
-- ============================================================================

COMMENT ON TABLE meters IS 'Defines what is being measured and how it is priced';
COMMENT ON TABLE allocations IS 'Resource pools allocated to sponsors';
COMMENT ON TABLE usage_events IS 'Immutable consumption events (event sourced)';
COMMENT ON TABLE ledger_entries IS 'Immutable financial audit log';
COMMENT ON TABLE balance_snapshots IS 'Materialized view for fast balance queries';
COMMENT ON TABLE invoices IS 'Settlement documents for billing';

COMMENT ON COLUMN allocations.total_amount IS 'Total amount allocated (immutable after creation)';
COMMENT ON COLUMN allocations.used_amount IS 'Amount consumed so far';
COMMENT ON COLUMN allocations.decay_policy IS 'Time-based decay policy name (e.g., linear_1pct_daily)';
COMMENT ON COLUMN allocations.version IS 'Optimistic locking version';

COMMENT ON COLUMN usage_events.timestamp IS 'When event was received/processed';
COMMENT ON COLUMN usage_events.occurred_at IS 'When consumption actually occurred';
COMMENT ON COLUMN usage_events.idempotency_key IS 'Deduplication key (client-provided or auto-generated)';

COMMENT ON COLUMN ledger_entries.amount IS 'Amount (positive for credits, negative for debits)';
