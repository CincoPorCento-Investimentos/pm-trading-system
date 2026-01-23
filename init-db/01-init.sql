-- Initialize the HFT Trading Database
-- This script runs when PostgreSQL container starts for the first time

-- Create additional users if needed
-- CREATE USER hft_readonly WITH PASSWORD 'readonly_password';
-- GRANT CONNECT ON DATABASE hft_trading TO hft_readonly;
-- GRANT USAGE ON SCHEMA public TO hft_readonly;
-- GRANT SELECT ON ALL TABLES IN SCHEMA public TO hft_readonly;

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Set optimal PostgreSQL parameters for trading workload
-- Note: These should also be set in postgresql.conf for persistence
ALTER SYSTEM SET shared_buffers = '512MB';
ALTER SYSTEM SET effective_cache_size = '1536MB';
ALTER SYSTEM SET maintenance_work_mem = '128MB';
ALTER SYSTEM SET checkpoint_completion_target = 0.9;
ALTER SYSTEM SET wal_buffers = '16MB';
ALTER SYSTEM SET default_statistics_target = 100;
ALTER SYSTEM SET random_page_cost = 1.1;
ALTER SYSTEM SET effective_io_concurrency = 200;
ALTER SYSTEM SET work_mem = '16MB';
ALTER SYSTEM SET min_wal_size = '1GB';
ALTER SYSTEM SET max_wal_size = '4GB';

-- Log slow queries
ALTER SYSTEM SET log_min_duration_statement = 100;  -- Log queries taking more than 100ms

SELECT pg_reload_conf();

-- Grant permissions
GRANT ALL PRIVILEGES ON DATABASE hft_trading TO hft_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO hft_user;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO hft_user;
