package com.cryptohft.persistence.repository;

import com.cryptohft.common.domain.Order;
import com.cryptohft.persistence.entity.OrderEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for order persistence.
 */
@Repository
public interface OrderRepository extends JpaRepository<OrderEntity, String> {
    
    /**
     * Find order by client order ID.
     */
    Optional<OrderEntity> findByClientOrderId(String clientOrderId);
    
    /**
     * Find orders by symbol.
     */
    List<OrderEntity> findBySymbol(String symbol);
    
    /**
     * Find orders by account.
     */
    List<OrderEntity> findByAccount(String account);
    
    /**
     * Find orders by symbol and account.
     */
    List<OrderEntity> findBySymbolAndAccount(String symbol, String account);
    
    /**
     * Find orders by status.
     */
    List<OrderEntity> findByStatus(Order.OrderStatus status);
    
    /**
     * Find open orders (NEW or PARTIALLY_FILLED).
     */
    @Query("SELECT o FROM OrderEntity o WHERE o.status IN ('NEW', 'PARTIALLY_FILLED')")
    List<OrderEntity> findOpenOrders();
    
    /**
     * Find open orders by symbol.
     */
    @Query("SELECT o FROM OrderEntity o WHERE o.symbol = :symbol AND o.status IN ('NEW', 'PARTIALLY_FILLED')")
    List<OrderEntity> findOpenOrdersBySymbol(@Param("symbol") String symbol);
    
    /**
     * Find open orders by account.
     */
    @Query("SELECT o FROM OrderEntity o WHERE o.account = :account AND o.status IN ('NEW', 'PARTIALLY_FILLED')")
    List<OrderEntity> findOpenOrdersByAccount(@Param("account") String account);
    
    /**
     * Find orders by account with pagination.
     */
    Page<OrderEntity> findByAccountOrderByCreatedAtDesc(String account, Pageable pageable);
    
    /**
     * Find orders by symbol with pagination.
     */
    Page<OrderEntity> findBySymbolOrderByCreatedAtDesc(String symbol, Pageable pageable);
    
    /**
     * Find orders created after a timestamp.
     */
    List<OrderEntity> findByCreatedAtAfter(Instant timestamp);
    
    /**
     * Find orders by status and created after timestamp.
     */
    List<OrderEntity> findByStatusAndCreatedAtAfter(Order.OrderStatus status, Instant timestamp);
    
    /**
     * Count orders by status.
     */
    long countByStatus(Order.OrderStatus status);
    
    /**
     * Count orders by account and status.
     */
    long countByAccountAndStatus(String account, Order.OrderStatus status);
    
    /**
     * Update order status.
     */
    @Modifying
    @Query("UPDATE OrderEntity o SET o.status = :status, o.updatedAt = CURRENT_TIMESTAMP WHERE o.orderId = :orderId")
    int updateStatus(@Param("orderId") String orderId, @Param("status") Order.OrderStatus status);
    
    /**
     * Update order fill.
     */
    @Modifying
    @Query("UPDATE OrderEntity o SET o.status = :status, o.filledQuantity = :filledQty, " +
           "o.remainingQuantity = :remainingQty, o.averagePrice = :avgPrice, o.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE o.orderId = :orderId")
    int updateFill(@Param("orderId") String orderId,
                   @Param("status") Order.OrderStatus status,
                   @Param("filledQty") java.math.BigDecimal filledQty,
                   @Param("remainingQty") java.math.BigDecimal remainingQty,
                   @Param("avgPrice") java.math.BigDecimal avgPrice);
    
    /**
     * Delete orders older than timestamp.
     */
    @Modifying
    @Query("DELETE FROM OrderEntity o WHERE o.createdAt < :timestamp AND o.status NOT IN ('NEW', 'PARTIALLY_FILLED')")
    int deleteOldOrders(@Param("timestamp") Instant timestamp);
}
