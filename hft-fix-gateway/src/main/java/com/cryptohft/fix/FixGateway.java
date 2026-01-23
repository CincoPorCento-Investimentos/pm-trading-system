package com.cryptohft.fix;

import com.cryptohft.common.domain.Order;
import com.cryptohft.common.domain.Trade;
import com.cryptohft.common.util.IdGenerator;
import com.cryptohft.common.util.NanoClock;
import lombok.extern.slf4j.Slf4j;
import quickfix.*;
import quickfix.field.*;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.Logon;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelReject;
import quickfix.fix44.OrderCancelReplaceRequest;
import quickfix.fix44.OrderCancelRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * FIX protocol gateway for exchange connectivity.
 * Implements FIX 4.4 protocol for order routing and execution reports.
 */
@Slf4j
public class FixGateway implements Application {
    
    private final FixConfig config;
    private final IdGenerator idGenerator;
    
    private SessionID sessionId;
    private final Map<String, Order> pendingOrders = new ConcurrentHashMap<>();
    private final Map<String, String> clOrdIdToOrderId = new ConcurrentHashMap<>();
    
    // Event listeners
    private final List<Consumer<Order>> orderUpdateListeners = new ArrayList<>();
    private final List<Consumer<Trade>> tradeListeners = new ArrayList<>();
    private final List<Consumer<Boolean>> connectionListeners = new ArrayList<>();
    
    private volatile boolean connected = false;
    private SocketInitiator initiator;
    
    public FixGateway(FixConfig config) {
        this.config = config;
        this.idGenerator = IdGenerator.getInstance();
    }
    
    /**
     * Start the FIX gateway.
     */
    public void start() throws ConfigError {
        SessionSettings settings = createSessionSettings();
        MessageStoreFactory storeFactory = new FileStoreFactory(settings);
        LogFactory logFactory = new FileLogFactory(settings);
        quickfix.MessageFactory messageFactory = new DefaultMessageFactory();
        
        initiator = new SocketInitiator(this, storeFactory, settings, logFactory, messageFactory);
        initiator.start();
        
        log.info("FIX gateway started");
    }
    
    /**
     * Stop the FIX gateway.
     */
    public void stop() {
        if (initiator != null) {
            initiator.stop();
        }
        log.info("FIX gateway stopped");
    }
    
    /**
     * Send a new order.
     */
    public void sendOrder(Order order) {
        if (!connected) {
            log.warn("Cannot send order, not connected");
            return;
        }
        
        long startNanos = NanoClock.nanoTime();
        
        try {
            NewOrderSingle newOrder = createNewOrderSingle(order);
            
            // Track pending order
            String clOrdId = newOrder.getClOrdID().getValue();
            pendingOrders.put(clOrdId, order);
            clOrdIdToOrderId.put(clOrdId, order.getOrderId());
            
            Session.sendToTarget(newOrder, sessionId);
            
            long latencyNanos = NanoClock.nanoTime() - startNanos;
            log.debug("Order sent: orderId={}, latency={}ns", order.getOrderId(), latencyNanos);
            
        } catch (Exception e) {
            log.error("Failed to send order: {}", order.getOrderId(), e);
        }
    }
    
    /**
     * Cancel an order.
     */
    public void cancelOrder(String orderId, String clOrdId, String symbol) {
        if (!connected) {
            log.warn("Cannot cancel order, not connected");
            return;
        }
        
        try {
            OrderCancelRequest cancelRequest = new OrderCancelRequest(
                    new OrigClOrdID(clOrdId),
                    new ClOrdID(idGenerator.nextIdString()),
                    new Side(Side.BUY), // Will be corrected based on original order
                    new TransactTime()
            );
            
            cancelRequest.set(new OrderID(orderId));
            cancelRequest.set(new Symbol(symbol));
            
            Session.sendToTarget(cancelRequest, sessionId);
            log.debug("Cancel request sent for order: {}", orderId);
            
        } catch (Exception e) {
            log.error("Failed to send cancel request: {}", orderId, e);
        }
    }
    
    /**
     * Replace/modify an order.
     */
    public void replaceOrder(String orderId, String origClOrdId, String symbol,
                             BigDecimal newPrice, BigDecimal newQuantity, Order.Side side) {
        if (!connected) {
            log.warn("Cannot replace order, not connected");
            return;
        }
        
        try {
            OrderCancelReplaceRequest replaceRequest = new OrderCancelReplaceRequest(
                    new OrigClOrdID(origClOrdId),
                    new ClOrdID(idGenerator.nextIdString()),
                    convertSide(side),
                    new TransactTime(),
                    new OrdType(OrdType.LIMIT)
            );
            
            replaceRequest.set(new OrderID(orderId));
            replaceRequest.set(new Symbol(symbol));
            replaceRequest.set(new Price(newPrice.doubleValue()));
            replaceRequest.set(new OrderQty(newQuantity.doubleValue()));
            
            Session.sendToTarget(replaceRequest, sessionId);
            log.debug("Replace request sent for order: {}", orderId);
            
        } catch (Exception e) {
            log.error("Failed to send replace request: {}", orderId, e);
        }
    }
    
    /**
     * Add order update listener.
     */
    public void addOrderUpdateListener(Consumer<Order> listener) {
        orderUpdateListeners.add(listener);
    }
    
    /**
     * Add trade listener.
     */
    public void addTradeListener(Consumer<Trade> listener) {
        tradeListeners.add(listener);
    }
    
    /**
     * Add connection listener.
     */
    public void addConnectionListener(Consumer<Boolean> listener) {
        connectionListeners.add(listener);
    }
    
    /**
     * Check if connected.
     */
    public boolean isConnected() {
        return connected;
    }
    
    // Application interface methods
    
    @Override
    public void onCreate(SessionID sessionId) {
        this.sessionId = sessionId;
        log.info("FIX session created: {}", sessionId);
    }
    
    @Override
    public void onLogon(SessionID sessionId) {
        connected = true;
        log.info("FIX session logged on: {}", sessionId);
        notifyConnectionListeners(true);
    }
    
    @Override
    public void onLogout(SessionID sessionId) {
        connected = false;
        log.info("FIX session logged out: {}", sessionId);
        notifyConnectionListeners(false);
    }
    
    @Override
    public void toAdmin(quickfix.Message message, SessionID sessionId) {
        // Add authentication to logon message if required
        if (message instanceof Logon) {
            if (config.getUsername() != null) {
                ((Logon) message).set(new Username(config.getUsername()));
            }
            if (config.getPassword() != null) {
                ((Logon) message).set(new Password(config.getPassword()));
            }
        }
    }
    
    @Override
    public void fromAdmin(quickfix.Message message, SessionID sessionId) 
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {
        // Handle admin messages (heartbeat, test request, etc.)
    }
    
    @Override
    public void toApp(quickfix.Message message, SessionID sessionId) throws DoNotSend {
        // Outgoing application messages
    }
    
    @Override
    public void fromApp(quickfix.Message message, SessionID sessionId) 
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        long receivedNanos = NanoClock.nanoTime();
        
        if (message instanceof ExecutionReport) {
            handleExecutionReport((ExecutionReport) message, receivedNanos);
        } else if (message instanceof OrderCancelReject) {
            handleOrderCancelReject((OrderCancelReject) message);
        }
    }
    
    /**
     * Handle execution report from exchange.
     */
    private void handleExecutionReport(ExecutionReport report, long receivedNanos) throws FieldNotFound {
        String clOrdId = report.getClOrdID().getValue();
        String orderId = report.isSetOrderID() ? report.getOrderID().getValue() : clOrdIdToOrderId.get(clOrdId);
        
        ExecType execType = report.getExecType();
        OrdStatus ordStatus = report.getOrdStatus();
        
        // Update order ID mapping
        if (orderId != null && !clOrdIdToOrderId.containsKey(clOrdId)) {
            clOrdIdToOrderId.put(clOrdId, orderId);
        }
        
        // Get original order
        Order originalOrder = pendingOrders.get(clOrdId);
        
        Order.OrderStatus status = convertOrdStatus(ordStatus);
        
        Order updatedOrder = Order.builder()
                .orderId(orderId)
                .clientOrderId(clOrdId)
                .symbol(report.getSymbol().getValue())
                .side(convertSide(report.getSide()))
                .orderType(originalOrder != null ? originalOrder.getOrderType() : Order.OrderType.LIMIT)
                .status(status)
                .price(report.isSetPrice() ? BigDecimal.valueOf(report.getPrice().getValue()) : null)
                .quantity(report.isSetOrderQty() ? BigDecimal.valueOf(report.getOrderQty().getValue()) : null)
                .filledQuantity(report.isSetCumQty() ? BigDecimal.valueOf(report.getCumQty().getValue()) : BigDecimal.ZERO)
                .remainingQuantity(report.isSetLeavesQty() ? BigDecimal.valueOf(report.getLeavesQty().getValue()) : null)
                .averagePrice(report.isSetAvgPx() ? BigDecimal.valueOf(report.getAvgPx().getValue()) : null)
                .acknowledgedNanos(receivedNanos)
                .build();
        
        // Notify order update listeners
        notifyOrderUpdateListeners(updatedOrder);
        
        // Handle trade (fill)
        if (execType.getValue() == ExecType.TRADE || execType.getValue() == ExecType.PARTIAL_FILL) {
            Trade trade = Trade.builder()
                    .tradeId(report.getExecID().getValue())
                    .orderId(orderId)
                    .symbol(report.getSymbol().getValue())
                    .side(convertSide(report.getSide()))
                    .price(BigDecimal.valueOf(report.getLastPx().getValue()))
                    .quantity(BigDecimal.valueOf(report.getLastQty().getValue()))
                    .executedAt(Instant.now())
                    .reportedNanos(receivedNanos)
                    .build();
            
            notifyTradeListeners(trade);
        }
        
        // Remove from pending if terminal state
        if (status.ordinal() >= Order.OrderStatus.FILLED.ordinal()) {
            pendingOrders.remove(clOrdId);
        }
        
        log.debug("Execution report: orderId={}, status={}, execType={}",
                orderId, status, execType.getValue());
    }
    
    /**
     * Handle order cancel reject.
     */
    private void handleOrderCancelReject(OrderCancelReject reject) throws FieldNotFound {
        String clOrdId = reject.getClOrdID().getValue();
        String orderId = reject.isSetOrderID() ? reject.getOrderID().getValue() : null;
        
        log.warn("Order cancel rejected: orderId={}, clOrdId={}, reason={}",
                orderId, clOrdId, 
                reject.isSetText() ? reject.getText().getValue() : "unknown");
    }
    
    /**
     * Create FIX NewOrderSingle message from Order.
     */
    private NewOrderSingle createNewOrderSingle(Order order) {
        NewOrderSingle newOrder = new NewOrderSingle(
                new ClOrdID(order.getClientOrderId()),
                convertSide(order.getSide()),
                new TransactTime(),
                convertOrderType(order.getOrderType())
        );
        
        newOrder.set(new Symbol(order.getSymbol()));
        newOrder.set(new OrderQty(order.getQuantity().doubleValue()));
        
        if (order.getOrderType() == Order.OrderType.LIMIT) {
            newOrder.set(new Price(order.getPrice().doubleValue()));
        }
        
        newOrder.set(convertTimeInForce(order.getTimeInForce()));
        
        if (order.getAccount() != null) {
            newOrder.set(new Account(order.getAccount()));
        }
        
        return newOrder;
    }
    
    private SessionSettings createSessionSettings() throws ConfigError {
        SessionSettings settings = new SessionSettings();
        
        // Default settings
        settings.setString("ConnectionType", "initiator");
        settings.setString("StartTime", "00:00:00");
        settings.setString("EndTime", "00:00:00");
        settings.setString("HeartBtInt", String.valueOf(config.getHeartbeatInterval()));
        settings.setString("ReconnectInterval", String.valueOf(config.getReconnectInterval()));
        settings.setString("FileStorePath", config.getFileStorePath());
        settings.setString("FileLogPath", config.getFileLogPath());
        
        // Session-specific settings
        SessionID sessionID = new SessionID(
                config.getBeginString(),
                config.getSenderCompId(),
                config.getTargetCompId()
        );
        
        settings.setString(sessionID, "SocketConnectHost", config.getHost());
        settings.setString(sessionID, "SocketConnectPort", String.valueOf(config.getPort()));
        
        return settings;
    }
    
    private Side convertSide(Order.Side side) {
        return side == Order.Side.BUY ? new Side(Side.BUY) : new Side(Side.SELL);
    }
    
    private Order.Side convertSide(Side side) throws FieldNotFound {
        return side.getValue() == Side.BUY ? Order.Side.BUY : Order.Side.SELL;
    }
    
    private OrdType convertOrderType(Order.OrderType type) {
        return switch (type) {
            case MARKET -> new OrdType(OrdType.MARKET);
            case LIMIT -> new OrdType(OrdType.LIMIT);
            case STOP -> new OrdType(OrdType.STOP_STOP_LOSS);
            case STOP_LIMIT -> new OrdType(OrdType.STOP_LIMIT);
            default -> new OrdType(OrdType.LIMIT);
        };
    }
    
    private TimeInForce convertTimeInForce(Order.TimeInForce tif) {
        return switch (tif) {
            case GTC -> new TimeInForce(TimeInForce.GOOD_TILL_CANCEL);
            case IOC -> new TimeInForce(TimeInForce.IMMEDIATE_OR_CANCEL);
            case FOK -> new TimeInForce(TimeInForce.FILL_OR_KILL);
            case DAY -> new TimeInForce(TimeInForce.DAY);
            default -> new TimeInForce(TimeInForce.GOOD_TILL_CANCEL);
        };
    }
    
    private Order.OrderStatus convertOrdStatus(OrdStatus status) throws FieldNotFound {
        return switch (status.getValue()) {
            case OrdStatus.NEW -> Order.OrderStatus.NEW;
            case OrdStatus.PARTIALLY_FILLED -> Order.OrderStatus.PARTIALLY_FILLED;
            case OrdStatus.FILLED -> Order.OrderStatus.FILLED;
            case OrdStatus.CANCELED -> Order.OrderStatus.CANCELLED;
            case OrdStatus.REJECTED -> Order.OrderStatus.REJECTED;
            case OrdStatus.EXPIRED -> Order.OrderStatus.EXPIRED;
            case OrdStatus.PENDING_NEW -> Order.OrderStatus.PENDING_NEW;
            case OrdStatus.PENDING_CANCEL -> Order.OrderStatus.PENDING_CANCEL;
            default -> Order.OrderStatus.NEW;
        };
    }
    
    private void notifyOrderUpdateListeners(Order order) {
        for (Consumer<Order> listener : orderUpdateListeners) {
            try {
                listener.accept(order);
            } catch (Exception e) {
                log.error("Error in order update listener", e);
            }
        }
    }
    
    private void notifyTradeListeners(Trade trade) {
        for (Consumer<Trade> listener : tradeListeners) {
            try {
                listener.accept(trade);
            } catch (Exception e) {
                log.error("Error in trade listener", e);
            }
        }
    }
    
    private void notifyConnectionListeners(boolean connected) {
        for (Consumer<Boolean> listener : connectionListeners) {
            try {
                listener.accept(connected);
            } catch (Exception e) {
                log.error("Error in connection listener", e);
            }
        }
    }
}
