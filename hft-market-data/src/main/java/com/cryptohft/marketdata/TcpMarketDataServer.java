package com.cryptohft.marketdata;

import com.cryptohft.common.domain.MarketData;
import com.cryptohft.common.domain.OrderBook;
import com.cryptohft.common.util.NanoClock;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.util.concurrent.GlobalEventExecutor;
import lombok.extern.slf4j.Slf4j;
import org.agrona.concurrent.UnsafeBuffer;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance TCP server for distributing market data to clients.
 * Uses Netty for non-blocking I/O with optimized buffer management.
 */
@Slf4j
public class TcpMarketDataServer implements AutoCloseable {
    
    private final int port;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final ChannelGroup allChannels;
    
    // Subscription tracking
    private final Map<Channel, Set<String>> channelSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, Set<Channel>> symbolSubscribers = new ConcurrentHashMap<>();
    
    // Metrics
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong bytessSent = new AtomicLong(0);
    
    private Channel serverChannel;
    
    // Pre-allocated buffers for encoding
    private static final int MAX_MESSAGE_SIZE = 4096;
    private final ThreadLocal<UnsafeBuffer> encodeBuffer = ThreadLocal.withInitial(
            () -> new UnsafeBuffer(ByteBuffer.allocateDirect(MAX_MESSAGE_SIZE))
    );
    
    public TcpMarketDataServer(int port) {
        this.port = port;
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();
        this.allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    }
    
    /**
     * Start the TCP server.
     */
    public void start() throws InterruptedException {
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_SNDBUF, 1024 * 1024)
                .childOption(ChannelOption.SO_RCVBUF, 1024 * 1024)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                // Frame decoder: 4-byte length prefix
                                .addLast(new LengthFieldBasedFrameDecoder(MAX_MESSAGE_SIZE, 0, 4, 0, 4))
                                .addLast(new LengthFieldPrepender(4))
                                .addLast(new MarketDataHandler());
                    }
                });
        
        serverChannel = bootstrap.bind(port).sync().channel();
        log.info("TCP market data server started on port {}", port);
    }
    
    /**
     * Broadcast market data tick to all subscribers.
     */
    public void broadcastTick(MarketData tick) {
        Set<Channel> subscribers = symbolSubscribers.get(tick.getSymbol());
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        
        ByteBuf buffer = encodeTick(tick);
        if (buffer == null) return;
        
        for (Channel channel : subscribers) {
            if (channel.isActive()) {
                channel.writeAndFlush(buffer.retainedDuplicate());
                messagesSent.incrementAndGet();
                bytessSent.addAndGet(buffer.readableBytes());
            }
        }
        
        buffer.release();
    }
    
    /**
     * Broadcast order book update to all subscribers.
     */
    public void broadcastOrderBook(OrderBook book) {
        Set<Channel> subscribers = symbolSubscribers.get(book.getSymbol());
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        
        ByteBuf buffer = encodeOrderBook(book);
        if (buffer == null) return;
        
        for (Channel channel : subscribers) {
            if (channel.isActive()) {
                channel.writeAndFlush(buffer.retainedDuplicate());
                messagesSent.incrementAndGet();
                bytessSent.addAndGet(buffer.readableBytes());
            }
        }
        
        buffer.release();
    }
    
    /**
     * Get number of connected clients.
     */
    public int getClientCount() {
        return allChannels.size();
    }
    
    /**
     * Get total messages sent.
     */
    public long getMessagesSent() {
        return messagesSent.get();
    }
    
    /**
     * Encode market data tick to binary format.
     * Format: [type:1][symbol_len:2][symbol:var][bid:8][ask:8][last:8][timestamp:8]
     */
    private ByteBuf encodeTick(MarketData tick) {
        ByteBuf buffer = Unpooled.buffer(256);
        
        try {
            // Message type: 1 = tick
            buffer.writeByte(1);
            
            // Symbol
            byte[] symbolBytes = tick.getSymbol().getBytes(StandardCharsets.UTF_8);
            buffer.writeShort(symbolBytes.length);
            buffer.writeBytes(symbolBytes);
            
            // Prices (as longs with 8 decimal places)
            buffer.writeLong(priceToLong(tick.getBidPrice()));
            buffer.writeLong(priceToLong(tick.getAskPrice()));
            buffer.writeLong(priceToLong(tick.getLastPrice()));
            
            // Quantities
            buffer.writeLong(priceToLong(tick.getBidQuantity()));
            buffer.writeLong(priceToLong(tick.getAskQuantity()));
            buffer.writeLong(priceToLong(tick.getLastQuantity()));
            
            // Timestamp (epoch nanos)
            buffer.writeLong(tick.getReceivedNanos());
            
            return buffer;
        } catch (Exception e) {
            buffer.release();
            log.error("Error encoding tick", e);
            return null;
        }
    }
    
    /**
     * Encode order book to binary format.
     * Format: [type:1][symbol_len:2][symbol:var][bid_count:2][bids:var][ask_count:2][asks:var][seq:8]
     */
    private ByteBuf encodeOrderBook(OrderBook book) {
        ByteBuf buffer = Unpooled.buffer(4096);
        
        try {
            // Message type: 2 = order book
            buffer.writeByte(2);
            
            // Symbol
            byte[] symbolBytes = book.getSymbol().getBytes(StandardCharsets.UTF_8);
            buffer.writeShort(symbolBytes.length);
            buffer.writeBytes(symbolBytes);
            
            // Top 10 bids
            var bids = book.getTopBids(10);
            buffer.writeShort(bids.size());
            for (var level : bids) {
                buffer.writeLong(priceToLong(level.getPrice()));
                buffer.writeLong(priceToLong(level.getTotalQuantity()));
            }
            
            // Top 10 asks
            var asks = book.getTopAsks(10);
            buffer.writeShort(asks.size());
            for (var level : asks) {
                buffer.writeLong(priceToLong(level.getPrice()));
                buffer.writeLong(priceToLong(level.getTotalQuantity()));
            }
            
            // Sequence number
            buffer.writeLong(book.getSequenceNumber());
            
            return buffer;
        } catch (Exception e) {
            buffer.release();
            log.error("Error encoding order book", e);
            return null;
        }
    }
    
    private long priceToLong(BigDecimal price) {
        if (price == null) return 0;
        return price.multiply(BigDecimal.valueOf(100_000_000L)).longValue();
    }
    
    @Override
    public void close() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        allChannels.close();
        workerGroup.shutdownGracefully();
        bossGroup.shutdownGracefully();
        log.info("TCP market data server stopped");
    }
    
    /**
     * Handler for client connections and messages.
     */
    private class MarketDataHandler extends ChannelInboundHandlerAdapter {
        
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            allChannels.add(ctx.channel());
            channelSubscriptions.put(ctx.channel(), ConcurrentHashMap.newKeySet());
            log.info("Client connected: {}", ctx.channel().remoteAddress());
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            // Remove all subscriptions for this channel
            Set<String> subscriptions = channelSubscriptions.remove(ctx.channel());
            if (subscriptions != null) {
                for (String symbol : subscriptions) {
                    Set<Channel> subscribers = symbolSubscribers.get(symbol);
                    if (subscribers != null) {
                        subscribers.remove(ctx.channel());
                    }
                }
            }
            log.info("Client disconnected: {}", ctx.channel().remoteAddress());
        }
        
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buffer = (ByteBuf) msg;
            
            try {
                // Read message type
                byte msgType = buffer.readByte();
                
                switch (msgType) {
                    case 1 -> handleSubscribe(ctx.channel(), buffer);
                    case 2 -> handleUnsubscribe(ctx.channel(), buffer);
                    case 9 -> handleHeartbeat(ctx.channel());
                }
            } finally {
                buffer.release();
            }
        }
        
        private void handleSubscribe(Channel channel, ByteBuf buffer) {
            int symbolLen = buffer.readShort();
            byte[] symbolBytes = new byte[symbolLen];
            buffer.readBytes(symbolBytes);
            String symbol = new String(symbolBytes, StandardCharsets.UTF_8);
            
            // Add subscription
            channelSubscriptions.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet()).add(symbol);
            symbolSubscribers.computeIfAbsent(symbol, k -> ConcurrentHashMap.newKeySet()).add(channel);
            
            log.debug("Client {} subscribed to {}", channel.remoteAddress(), symbol);
        }
        
        private void handleUnsubscribe(Channel channel, ByteBuf buffer) {
            int symbolLen = buffer.readShort();
            byte[] symbolBytes = new byte[symbolLen];
            buffer.readBytes(symbolBytes);
            String symbol = new String(symbolBytes, StandardCharsets.UTF_8);
            
            // Remove subscription
            Set<String> subs = channelSubscriptions.get(channel);
            if (subs != null) {
                subs.remove(symbol);
            }
            
            Set<Channel> subscribers = symbolSubscribers.get(symbol);
            if (subscribers != null) {
                subscribers.remove(channel);
            }
            
            log.debug("Client {} unsubscribed from {}", channel.remoteAddress(), symbol);
        }
        
        private void handleHeartbeat(Channel channel) {
            // Send heartbeat response
            ByteBuf response = Unpooled.buffer(9);
            response.writeByte(9);
            response.writeLong(NanoClock.epochNanos());
            channel.writeAndFlush(response);
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Channel error: {}", ctx.channel().remoteAddress(), cause);
            ctx.close();
        }
    }
}
