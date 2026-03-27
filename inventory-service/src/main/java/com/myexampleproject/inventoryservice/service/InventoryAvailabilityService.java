package com.myexampleproject.inventoryservice.service;

import com.myexampleproject.inventoryservice.dto.InventoryAvailabilityResponse;
import com.myorg.lsf.quota.api.QuotaQueryFacade;
import com.myorg.lsf.quota.api.QuotaSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InventoryAvailabilityService {

    private final InventoryService inventoryService;
    private final InventoryQuotaService inventoryQuotaService;
    private final QuotaQueryFacade quotaQueryFacade;

    public InventoryAvailabilityResponse getAvailability(String skuCode) {
        int physicalStock = inventoryService.getQuantity(skuCode);

        String quotaKey = inventoryQuotaService.quotaKey(skuCode);
        QuotaSnapshot snapshot = quotaQueryFacade.getSnapshot(quotaKey);

        int quotaUsed = snapshot.used();
        int availableStock = Math.max(physicalStock - quotaUsed, 0);

        return InventoryAvailabilityResponse.builder()
                .skuCode(skuCode)
                .physicalStock(physicalStock)
                .quotaUsed(quotaUsed)
                .reservedCount(snapshot.reservedCount())
                .confirmedCount(snapshot.confirmedCount())
                .availableStock(availableStock)
                .quotaKey(quotaKey)
                .refreshedAtEpochMs(snapshot.refreshedAtEpochMs())
                .build();
    }
}