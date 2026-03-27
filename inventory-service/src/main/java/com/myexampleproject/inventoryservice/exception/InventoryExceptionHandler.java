package com.myexampleproject.inventoryservice.exception;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InventoryExceptionHandler {

    @ExceptionHandler(PhysicalStockBelowQuotaUsedException.class)
    public ResponseEntity<?> handlePhysicalStockBelowQuotaUsed(PhysicalStockBelowQuotaUsedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "PHYSICAL_STOCK_BELOW_QUOTA_USED",
                "message", "Không thể giảm tồn kho vật lý xuống dưới số lượng đã được giữ/xác nhận.",
                "skuCode", ex.getSkuCode(),
                "currentPhysicalStock", ex.getCurrentPhysicalStock(),
                "quotaUsed", ex.getQuotaUsed(),
                "requestedNewPhysicalStock", ex.getRequestedNewPhysicalStock(),
                "minimumAllowedPhysicalStock", ex.getMinimumAllowedPhysicalStock()
        ));
    }
}