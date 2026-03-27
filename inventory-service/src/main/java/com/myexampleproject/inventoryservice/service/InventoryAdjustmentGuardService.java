package com.myexampleproject.inventoryservice.service;

import com.myexampleproject.inventoryservice.exception.PhysicalStockBelowQuotaUsedException;
import com.myorg.lsf.quota.api.QuotaQueryFacade;
import com.myorg.lsf.quota.api.QuotaSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InventoryAdjustmentGuardService {

    private final InventoryQuotaService inventoryQuotaService;
    private final QuotaQueryFacade quotaQueryFacade;

    public void validateAdjustment(String skuCode, int currentPhysicalStock, int adjustmentQuantity) {
        int requestedNewPhysicalStock = currentPhysicalStock + adjustmentQuantity;

        String quotaKey = inventoryQuotaService.quotaKey(skuCode);
        QuotaSnapshot snapshot = quotaQueryFacade.getSnapshot(quotaKey);
        int quotaUsed = snapshot.used();

        if (requestedNewPhysicalStock < quotaUsed) {
            throw new PhysicalStockBelowQuotaUsedException(
                    skuCode,
                    currentPhysicalStock,
                    quotaUsed,
                    requestedNewPhysicalStock
            );
        }
    }
}