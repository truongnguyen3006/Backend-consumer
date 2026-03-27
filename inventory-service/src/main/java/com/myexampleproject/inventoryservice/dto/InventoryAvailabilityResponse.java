package com.myexampleproject.inventoryservice.dto;

import lombok.Builder;

@Builder
public record InventoryAvailabilityResponse(
        String skuCode,
        int physicalStock,
        int quotaUsed,
        int reservedCount,
        int confirmedCount,
        int availableStock,
        String quotaKey,
        long refreshedAtEpochMs
) {
}