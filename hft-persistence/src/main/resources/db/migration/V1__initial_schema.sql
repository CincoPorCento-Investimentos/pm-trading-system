-- HFT Trading Platform Database Schema
-- Version 1: Initial schema

-- Orders table
CREATE TABLE IF NOT EXISTS orders (
    order_id VARCHAR(64) PRIMARY KEY,
    client_order_id VARCHAR(64),
    symbol VARCHAR(32) NOT NULL,
    side VARCHAR(10) NOT NULL,
    order_type VARCHAR(20) NOT NULL,
    time_in_force VARCHAR(10),
    status VARCHAR(20) NOT NULL,
    price DECIMAL(24, 8),
    quantity DECIMAL(24, 8) NOT NULL,
    filled_quantity DECIMAL(24, 8) DEFAULT 0,
    remaining_quantity DECIMAL(24, 8),
    average_price DECIMAL(24, 8),
    exchange VARCHAR(32),
    account VARCHAR(64),
    sequence_number BIGINT,
    submitted_nanos BIGINT,
    acknowledged_nanos BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE
);

-- Indexes for orders
CREATE INDEX IF NOT EXISTS idx_orders_symbol ON orders(symbol);
CREATE INDEX IF NOT EXISTS idx_orders_account ON orders(account);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);
CREATE INDEX IF NOT EXISTS idx_orders_created ON orders(created_at);
CREATE INDEX IF NOT EXISTS idx_orders_client_order_id ON orders(client_order_id);
CREATE INDEX IF NOT EXISTS idx_orders_symbol_status ON orders(symbol, status);
CREATE INDEX IF NOT EXISTS idx_orders_account_status ON orders(account, status);

-- Trades table
CREATE TABLE IF NOT EXISTS trades (
    trade_id VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL,
    symbol VARCHAR(32) NOT NULL,
    side VARCHAR(10) NOT NULL,
    price DECIMAL(24, 8) NOT NULL,
    quantity DECIMAL(24, 8) NOT NULL,
    commission DECIMAL(24, 8),
    commission_asset VARCHAR(16),
    exchange VARCHAR(32),
    account VARCHAR(64),
    counterparty_order_id VARCHAR(64),
    is_maker BOOLEAN,
    sequence_number BIGINT,
    matched_nanos BIGINT,
    reported_nanos BIGINT,
    executed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for trades
CREATE INDEX IF NOT EXISTS idx_trades_order_id ON trades(order_id);
CREATE INDEX IF NOT EXISTS idx_trades_symbol ON trades(symbol);
CREATE INDEX IF NOT EXISTS idx_trades_account ON trades(account);
CREATE INDEX IF NOT EXISTS idx_trades_executed ON trades(executed_at);
CREATE INDEX IF NOT EXISTS idx_trades_symbol_executed ON trades(symbol, executed_at);
CREATE INDEX IF NOT EXISTS idx_trades_account_executed ON trades(account, executed_at);

-- Positions table
CREATE TABLE IF NOT EXISTS positions (
    id SERIAL PRIMARY KEY,
    symbol VARCHAR(32) NOT NULL,
    account VARCHAR(64) NOT NULL,
    exchange VARCHAR(32),
    quantity DECIMAL(24, 8) NOT NULL DEFAULT 0,
    average_entry_price DECIMAL(24, 8),
    realized_pnl DECIMAL(24, 8) DEFAULT 0,
    unrealized_pnl DECIMAL(24, 8) DEFAULT 0,
    notional_value DECIMAL(24, 8),
    margin_used DECIMAL(24, 8),
    leverage INTEGER DEFAULT 1,
    opened_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(symbol, account)
);

-- Indexes for positions
CREATE INDEX IF NOT EXISTS idx_positions_account ON positions(account);
CREATE INDEX IF NOT EXISTS idx_positions_symbol ON positions(symbol);

-- Market data snapshots (for auditing/replay)
CREATE TABLE IF NOT EXISTS market_data_snapshots (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(32) NOT NULL,
    exchange VARCHAR(32) NOT NULL,
    bid_price DECIMAL(24, 8),
    bid_quantity DECIMAL(24, 8),
    ask_price DECIMAL(24, 8),
    ask_quantity DECIMAL(24, 8),
    last_price DECIMAL(24, 8),
    last_quantity DECIMAL(24, 8),
    volume_24h DECIMAL(24, 8),
    sequence_number BIGINT,
    exchange_timestamp TIMESTAMP WITH TIME ZONE,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Partitioned by time for efficient querying and archival
-- Note: Actual partitioning syntax varies by PostgreSQL version

CREATE INDEX IF NOT EXISTS idx_mds_symbol_received ON market_data_snapshots(symbol, received_at);
CREATE INDEX IF NOT EXISTS idx_mds_received ON market_data_snapshots(received_at);

-- Audit log table
CREATE TABLE IF NOT EXISTS audit_log (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(50) NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id VARCHAR(64),
    account VARCHAR(64),
    old_value JSONB,
    new_value JSONB,
    metadata JSONB,
    ip_address VARCHAR(45),
    user_agent TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_entity ON audit_log(entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_audit_account ON audit_log(account);
CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_log(created_at);

-- Risk limits table
CREATE TABLE IF NOT EXISTS risk_limits (
    id SERIAL PRIMARY KEY,
    account VARCHAR(64) NOT NULL,
    limit_type VARCHAR(50) NOT NULL,
    symbol VARCHAR(32),
    max_value DECIMAL(24, 8) NOT NULL,
    current_value DECIMAL(24, 8) DEFAULT 0,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE,
    UNIQUE(account, limit_type, symbol)
);

CREATE INDEX IF NOT EXISTS idx_risk_limits_account ON risk_limits(account);

-- Create updated_at trigger function
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Apply trigger to tables
CREATE TRIGGER update_orders_updated_at
    BEFORE UPDATE ON orders
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_positions_updated_at
    BEFORE UPDATE ON positions
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_risk_limits_updated_at
    BEFORE UPDATE ON risk_limits
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();
