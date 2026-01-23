package com.cryptohft.persistence.repository;

import com.cryptohft.persistence.entity.TradeEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Repository for trade persistence.
 */
@Repository
public interface TradeRepository extends JpaRepository<TradeEntity, String> {
    
    /**
     * Find trades by order ID.
     */
    List<TradeEntity> findByOrderId(String orderId);
    
    /**
     * Find trades by symbol.
     */
    List<TradeEntity> findBySymbol(String symbol);
    
    /**
     * Find trades by account.
     */
    List<TradeEntity> findByAccount(String account);
    
    /**
     * Find trades by symbol and account.
     */
    List<TradeEntity> findBySymbolAndAccount(String symbol, String account);
    
    /**
     * Find trades executed after timestamp.
     */
    List<TradeEntity> findByExecutedAtAfter(Instant timestamp);
    
    /**
     * Find trades by account with pagination.
     */
    Page<TradeEntity> findByAccountOrderByExecutedAtDesc(String account, Pageable pageable);
    
    /**
     * Find trades by symbol with pagination.
     */
    Page<TradeEntity> findBySymbolOrderByExecutedAtDesc(String symbol, Pageable pageable);
    
    /**
     * Find trades within time range.
     */
    List<TradeEntity> findByExecutedAtBetween(Instant start, Instant end);
    
    /**
     * Find trades by account within time range.
     */
    List<TradeEntity> findByAccountAndExecutedAtBetween(String account, Instant start, Instant end);
    
    /**
     * Calculate total volume for a symbol.
     */
    @Query("SELECT SUM(t.quantity) FROM TradeEntity t WHERE t.symbol = :symbol AND t.executedAt >= :since")
    BigDecimal calculateVolume(@Param("symbol") String symbol, @Param("since") Instant since);
    
    /**
     * Calculate total volume for an account.
     */
    @Query("SELECT SUM(t.quantity * t.price) FROM TradeEntity t WHERE t.account = :account AND t.executedAt >= :since")
    BigDecimal calculateNotionalVolume(@Param("account") String account, @Param("since") Instant since);
    
    /**
     * Calculate total commission paid by account.
     */
    @Query("SELECT SUM(t.commission) FROM TradeEntity t WHERE t.account = :account AND t.executedAt >= :since")
    BigDecimal calculateTotalCommission(@Param("account") String account, @Param("since") Instant since);
    
    /**
     * Count trades by account.
     */
    long countByAccount(String account);
    
    /**
     * Count trades by symbol.
     */
    long countBySymbol(String symbol);
    
    /**
     * Count trades by account within time range.
     */
    long countByAccountAndExecutedAtBetween(String account, Instant start, Instant end);
    
    /**
     * Get VWAP (Volume Weighted Average Price) for a symbol.
     */
    @Query("SELECT SUM(t.price * t.quantity) / SUM(t.quantity) FROM TradeEntity t " +
           "WHERE t.symbol = :symbol AND t.executedAt >= :since")
    BigDecimal calculateVwap(@Param("symbol") String symbol, @Param("since") Instant since);
    
    /**
     * Get trade summary by symbol.
     */
    @Query("SELECT t.symbol, COUNT(t), SUM(t.quantity), SUM(t.price * t.quantity) " +
           "FROM TradeEntity t WHERE t.executedAt >= :since GROUP BY t.symbol")
    List<Object[]> getTradeSummaryBySymbol(@Param("since") Instant since);
    
    /**
     * Delete old trades for archival.
     */
    @Query("DELETE FROM TradeEntity t WHERE t.executedAt < :timestamp")
    int deleteOldTrades(@Param("timestamp") Instant timestamp);
}
