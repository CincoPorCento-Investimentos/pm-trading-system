# Crypto HFT Trading Platform

A high-frequency trading system for cryptocurrency built with Java 17, Spring Boot, and advanced low-latency technologies.

## Architecture Overview

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                        HFT Trading Platform                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐              │
│  │  REST API    │    │  WebSocket   │    │  TCP Server  │              │
│  │  (Spring)    │    │  Handler     │    │  (Netty)     │              │
│  └──────┬───────┘    └──────┬───────┘    └──────┬───────┘              │
│         │                   │                   │                       │
│         └───────────────────┼───────────────────┘                       │
│                             │                                           │
│                   ┌─────────▼─────────┐                                 │
│                   │   Order Service   │                                 │
│                   └─────────┬─────────┘                                 │
│                             │                                           │
│         ┌───────────────────┼───────────────────┐                       │
│         │                   │                   │                       │
│  ┌──────▼──────┐    ┌──────▼──────┐    ┌──────▼──────┐                │
│  │    Risk     │    │  Matching   │    │    FIX      │                │
│  │   Manager   │    │   Engine    │    │   Gateway   │                │
│  └─────────────┘    └──────┬──────┘    └──────┬──────┘                │
│                            │                   │                       │
│                   ┌────────▼───────────────────▼───────┐               │
│                   │         Aeron IPC                   │               │
│                   │    (Low-latency messaging)          │               │
│                   └────────────────┬────────────────────┘               │
│                                    │                                    │
│                   ┌────────────────▼────────────────┐                  │
│                   │      Market Data Service        │                  │
│                   │   (WebSocket/TCP Connectors)    │                  │
│                   └─────────────────────────────────┘                  │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┼───────────────┐
              │               │               │
       ┌──────▼──────┐ ┌─────▼─────┐ ┌──────▼──────┐
       │ PostgreSQL  │ │   Redis   │ │ Prometheus  │
       │  (Orders)   │ │  (Cache)  │ │  (Metrics)  │
       └─────────────┘ └───────────┘ └─────────────┘
```

## Key Technologies

- **Java 17** - Latest LTS with modern language features
- **Spring Boot 3.2** - Application framework
- **Aeron** - Ultra-low latency messaging
- **LMAX Disruptor** - High-performance inter-thread messaging
- **SBE (Simple Binary Encoding)** - Zero-copy serialization
- **Netty** - Asynchronous TCP networking
- **QuickFIX/J** - FIX protocol implementation
- **PostgreSQL** - Order and trade persistence
- **Docker** - Containerization

## Project Structure

```text
crypto-hft-platform/
├── hft-common/       # Shared utilities and domain models
├── hft-sbe/          # SBE message schemas and codecs
├── hft-aeron/        # Aeron messaging infrastructure
├── hft-engine/       # Core trading engine with order matching
├── hft-market-data/  # Market data connectors (WebSocket/TCP)
├── hft-fix-gateway/  # FIX protocol gateway
├── hft-api/          # REST API and WebSocket server
├── hft-persistence/  # PostgreSQL repositories
└── hft-app/          # Main Spring Boot application
```

## Features

### Core Trading

- **Order Matching Engine** - Price-time priority matching with LMAX Disruptor
- **Risk Management** - Pre-trade risk checks, position limits, daily loss limits
- **Position Tracking** - Real-time position and P&L calculation

### Connectivity

- **REST API** - Order submission, cancellation, and queries
- **WebSocket** - Real-time order updates and market data streaming
- **FIX Protocol** - FIX 4.4 gateway for exchange connectivity
- **TCP Server** - Low-latency binary protocol for market data

### Market Data

- **WebSocket Clients** - Binance, Coinbase connectors
- **Order Book Management** - Real-time order book with lock-free reads
- **Tick Data Processing** - Sub-millisecond market data handling

### Performance

- **Aeron IPC** - Sub-microsecond inter-process communication
- **SBE Encoding** - Zero-copy binary serialization
- **Off-heap Memory** - Direct buffers for reduced GC pressure
- **Lock-free Data Structures** - Concurrent collections with minimal contention

## Quick Start

### Prerequisites

- Docker & Docker Compose
- Java 17 (for local development)
- Maven 3.8+

### Running with Docker

```bash
# Build and start all services
docker-compose up -d

# View logs
docker-compose logs -f hft-app

# Stop all services
docker-compose down
```

### Local Development

```bash
# Build the project
mvn clean package -DskipTests

# Start PostgreSQL (using Docker)
docker-compose up -d postgres redis

# Run the application
java -jar hft-app/target/hft-app-1.0.0-SNAPSHOT.jar
```

### API Endpoints

#### Orders

```bash
# Submit order
POST /api/v1/orders
{
  "symbol": "BTCUSDT",
  "side": "BUY",
  "orderType": "LIMIT",
  "price": 42000.00,
  "quantity": 0.1,
  "timeInForce": "GTC"
}

# Cancel order
DELETE /api/v1/orders/{orderId}

# Get order
GET /api/v1/orders/{orderId}

# Get open orders
GET /api/v1/orders/open?symbol=BTCUSDT

# Cancel all orders
DELETE /api/v1/orders/cancel-all?symbol=BTCUSDT
```

#### WebSocket

```javascript
// Connect to WebSocket
ws://localhost:8080/ws/trading

// Subscribe to market data
{"type": "subscribe", "channel": "marketdata", "symbol": "BTCUSDT"}

// Subscribe to order updates
{"type": "subscribe", "channel": "orders", "account": "DEFAULT"}

// Subscribe to trades
{"type": "subscribe", "channel": "trades", "account": "DEFAULT"}
```

## Configuration

Key configuration options in `application.yml`:

```yaml
hft:
  node:
    id: 1  # Unique node ID for distributed deployment
    
  aeron:
    directory: /dev/shm/aeron-hft
    idle-strategy: YIELDING  # SPINNING for lowest latency
    
  market-data:
    exchange: BINANCE
    symbols: BTCUSDT,ETHUSDT,BNBUSDT
    tcp-port: 9500
    
  risk:
    max-order-size: 1000
    max-position-size: 10000
    max-daily-loss: 100000
    max-orders-per-second: 100
```

## Performance Tuning

### JVM Options

```bash
-server
-XX:+UseG1GC
-XX:MaxGCPauseMillis=10
-XX:+UseStringDeduplication
-XX:+AlwaysPreTouch
-XX:+DisableExplicitGC
-Xms2g
-Xmx4g
```

### Linux Kernel Tuning

```bash
# Increase network buffer sizes
sysctl -w net.core.rmem_max=16777216
sysctl -w net.core.wmem_max=16777216

# Enable huge pages
sysctl -w vm.nr_hugepages=1024

# Disable CPU frequency scaling
cpupower frequency-set -g performance
```

## Monitoring

- **Prometheus Metrics**: <http://localhost:9090>
- **Grafana Dashboards**: <http://localhost:3000> (admin/admin123)
- **Actuator Health**: <http://localhost:8080/actuator/health>
- **Metrics Endpoint**: <http://localhost:8080/actuator/prometheus>

## Testing

```bash
# Run unit tests
mvn test

# Run integration tests
mvn verify -P integration-tests

# Load testing with wrk
wrk -t12 -c400 -d30s http://localhost:8080/api/v1/orders
```

## License

MIT License

## Contributing

1. Fork the repository
2. Create a feature branch
3. Commit changes
4. Push to the branch
5. Create a Pull Request
