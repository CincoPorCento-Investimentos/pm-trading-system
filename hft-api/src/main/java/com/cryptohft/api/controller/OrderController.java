package com.cryptohft.api.controller;

import com.cryptohft.api.dto.OrderRequest;
import com.cryptohft.api.dto.OrderResponse;
import com.cryptohft.api.dto.CancelOrderRequest;
import com.cryptohft.api.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API controller for order management.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {
    
    private final OrderService orderService;
    
    /**
     * Submit a new order.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> submitOrder(@Valid @RequestBody OrderRequest request) {
        log.info("Received order request: symbol={}, side={}, qty={}",
                request.getSymbol(), request.getSide(), request.getQuantity());
        
        OrderResponse response = orderService.submitOrder(request);
        return ResponseEntity.ok(response);
    }
    
    /**
     * Cancel an order.
     */
    @DeleteMapping("/{orderId}")
    public ResponseEntity<OrderResponse> cancelOrder(
            @PathVariable String orderId,
            @RequestParam(required = false) String symbol) {
        log.info("Received cancel request: orderId={}", orderId);
        
        OrderResponse response = orderService.cancelOrder(orderId, symbol);
        return ResponseEntity.ok(response);
    }
    
    /**
     * Cancel multiple orders.
     */
    @PostMapping("/cancel-batch")
    public ResponseEntity<List<OrderResponse>> cancelOrders(@Valid @RequestBody CancelOrderRequest request) {
        log.info("Received batch cancel request: count={}", request.getOrderIds().size());
        
        List<OrderResponse> responses = orderService.cancelOrders(request.getOrderIds());
        return ResponseEntity.ok(responses);
    }
    
    /**
     * Get order by ID.
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable String orderId) {
        OrderResponse response = orderService.getOrder(orderId);
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get all orders for an account.
     */
    @GetMapping
    public ResponseEntity<List<OrderResponse>> getOrders(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        
        List<OrderResponse> orders = orderService.getOrders(symbol, status, limit);
        return ResponseEntity.ok(orders);
    }
    
    /**
     * Get open orders.
     */
    @GetMapping("/open")
    public ResponseEntity<List<OrderResponse>> getOpenOrders(
            @RequestParam(required = false) String symbol) {
        
        List<OrderResponse> orders = orderService.getOpenOrders(symbol);
        return ResponseEntity.ok(orders);
    }
    
    /**
     * Cancel all open orders.
     */
    @DeleteMapping("/cancel-all")
    public ResponseEntity<List<OrderResponse>> cancelAllOrders(
            @RequestParam(required = false) String symbol) {
        
        log.info("Received cancel all request: symbol={}", symbol);
        List<OrderResponse> responses = orderService.cancelAllOrders(symbol);
        return ResponseEntity.ok(responses);
    }
}
