package com.cryptohft.aeron;

import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FragmentHandler;
import lombok.extern.slf4j.Slf4j;
import org.agrona.CloseHelper;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-performance Aeron transport layer for inter-process communication.
 */
@Slf4j
public class AeronTransport implements AutoCloseable {
    
    private final AeronConfig config;
    private final MediaDriver mediaDriver;
    private final Aeron aeron;
    
    private final ConcurrentHashMap<String, Publication> publications = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final IdleStrategy idleStrategy;
    
    // Reusable buffer to avoid allocation
    private final ThreadLocal<UnsafeBuffer> sendBuffer = ThreadLocal.withInitial(
            () -> new UnsafeBuffer(ByteBuffer.allocateDirect(4096))
    );
    
    public AeronTransport(AeronConfig config) {
        this.config = config;
        this.idleStrategy = createIdleStrategy(config.getIdleStrategy());
        
        // Start embedded media driver if configured
        if (config.isEmbeddedMediaDriver()) {
            MediaDriver.Context driverContext = new MediaDriver.Context()
                    .aeronDirectoryName(config.getAeronDirectory())
                    .dirDeleteOnStart(config.isDirDeleteOnStart())
                    .dirDeleteOnShutdown(config.isDirDeleteOnShutdown())
                    .termBufferSparseFile(false)
                    .threadingMode(ThreadingMode.DEDICATED)
                    .conductorIdleStrategy(idleStrategy)
                    .senderIdleStrategy(idleStrategy)
                    .receiverIdleStrategy(idleStrategy);
            
            this.mediaDriver = MediaDriver.launchEmbedded(driverContext);
            log.info("Started embedded Aeron media driver at {}", config.getAeronDirectory());
        } else {
            this.mediaDriver = null;
        }
        
        // Create Aeron client
        Aeron.Context aeronContext = new Aeron.Context()
                .aeronDirectoryName(config.isEmbeddedMediaDriver() 
                        ? mediaDriver.aeronDirectoryName() 
                        : config.getAeronDirectory())
                .idleStrategy(idleStrategy);
        
        this.aeron = Aeron.connect(aeronContext);
        this.running.set(true);
        
        log.info("Aeron transport initialized");
    }
    
    /**
     * Create or get a publication for the given channel and stream.
     */
    public Publication getPublication(String channel, int streamId) {
        String key = channel + ":" + streamId;
        return publications.computeIfAbsent(key, k -> {
            Publication pub = aeron.addPublication(channel, streamId);
            log.info("Created publication: channel={}, streamId={}", channel, streamId);
            return pub;
        });
    }
    
    /**
     * Create or get a subscription for the given channel and stream.
     */
    public Subscription getSubscription(String channel, int streamId) {
        String key = channel + ":" + streamId;
        return subscriptions.computeIfAbsent(key, k -> {
            Subscription sub = aeron.addSubscription(channel, streamId);
            log.info("Created subscription: channel={}, streamId={}", channel, streamId);
            return sub;
        });
    }
    
    /**
     * Send data on a publication with retry logic.
     */
    public boolean send(Publication publication, UnsafeBuffer buffer, int offset, int length) {
        if (!running.get() || publication == null) {
            return false;
        }
        
        long result;
        int retries = 3;
        
        while (retries-- > 0) {
            result = publication.offer(buffer, offset, length);
            
            if (result > 0) {
                return true;
            }
            
            if (result == Publication.NOT_CONNECTED) {
                log.warn("Publication not connected, waiting...");
                idleStrategy.idle();
            } else if (result == Publication.BACK_PRESSURED) {
                log.debug("Back pressure, retrying...");
                idleStrategy.idle();
            } else if (result == Publication.ADMIN_ACTION) {
                log.debug("Admin action, retrying...");
                idleStrategy.idle();
            } else if (result == Publication.CLOSED) {
                log.error("Publication closed");
                return false;
            } else if (result == Publication.MAX_POSITION_EXCEEDED) {
                log.error("Max position exceeded");
                return false;
            }
        }
        
        return false;
    }
    
    /**
     * Poll a subscription for messages.
     */
    public int poll(Subscription subscription, FragmentHandler handler, int fragmentLimit) {
        if (!running.get() || subscription == null) {
            return 0;
        }
        return subscription.poll(handler, fragmentLimit);
    }
    
    /**
     * Get market data publication.
     */
    public Publication getMarketDataPublication() {
        return getPublication(config.getMarketDataChannel(), config.getMarketDataStreamId());
    }
    
    /**
     * Get market data subscription.
     */
    public Subscription getMarketDataSubscription() {
        return getSubscription(config.getMarketDataChannel(), config.getMarketDataStreamId());
    }
    
    /**
     * Get order publication.
     */
    public Publication getOrderPublication() {
        return getPublication(config.getOrderChannel(), config.getOrderStreamId());
    }
    
    /**
     * Get order subscription.
     */
    public Subscription getOrderSubscription() {
        return getSubscription(config.getOrderChannel(), config.getOrderStreamId());
    }
    
    /**
     * Get execution publication.
     */
    public Publication getExecutionPublication() {
        return getPublication(config.getExecutionChannel(), config.getExecutionStreamId());
    }
    
    /**
     * Get execution subscription.
     */
    public Subscription getExecutionSubscription() {
        return getSubscription(config.getExecutionChannel(), config.getExecutionStreamId());
    }
    
    /**
     * Get thread-local send buffer.
     */
    public UnsafeBuffer getSendBuffer() {
        return sendBuffer.get();
    }
    
    /**
     * Check if transport is running.
     */
    public boolean isRunning() {
        return running.get();
    }
    
    /**
     * Get idle strategy.
     */
    public IdleStrategy getIdleStrategy() {
        return idleStrategy;
    }
    
    @Override
    public void close() {
        running.set(false);
        
        publications.values().forEach(CloseHelper::quietClose);
        publications.clear();
        
        subscriptions.values().forEach(CloseHelper::quietClose);
        subscriptions.clear();
        
        CloseHelper.quietClose(aeron);
        CloseHelper.quietClose(mediaDriver);
        
        log.info("Aeron transport closed");
    }
    
    private IdleStrategy createIdleStrategy(AeronConfig.IdleStrategyType type) {
        return switch (type) {
            case SPINNING -> new BusySpinIdleStrategy();
            case YIELDING -> new YieldingIdleStrategy();
            case SLEEPING -> new SleepingMillisIdleStrategy(1);
            case BACK_OFF -> new BackoffIdleStrategy(1, 1, 1, 1000);
        };
    }
}
