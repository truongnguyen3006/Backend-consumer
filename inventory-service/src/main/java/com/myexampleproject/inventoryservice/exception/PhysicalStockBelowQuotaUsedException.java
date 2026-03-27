package com.myexampleproject.inventoryservice.exception;

import lombok.Getter;

@Getter
public class PhysicalStockBelowQuotaUsedException extends RuntimeException {

    private final String skuCode;
    private final int currentPhysicalStock;
    private final int quotaUsed;
    private final int requestedNewPhysicalStock;

    public PhysicalStockBelowQuotaUsedException(
            String skuCode,
            int currentPhysicalStock,
            int quotaUsed,
            int requestedNewPhysicalStock
    ) {
        super("Cannot reduce physical stock below quota used");
        this.skuCode = skuCode;
        this.currentPhysicalStock = currentPhysicalStock;
        this.quotaUsed = quotaUsed;
        this.requestedNewPhysicalStock = requestedNewPhysicalStock;
    }

    public int getMinimumAllowedPhysicalStock() {
        return quotaUsed;
    }
}