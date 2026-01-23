package com.cryptohft.api.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * Batch cancel order request DTO.
 */
@Data
public class CancelOrderRequest {
    
    @NotEmpty(message = "Order IDs list cannot be empty")
    private List<String> orderIds;
    
    private String symbol;
}
