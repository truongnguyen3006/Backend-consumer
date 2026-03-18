package com.myexampleproject.inventoryservice.service;

import com.myexampleproject.common.dto.OrderLineItemRequest;
import com.myexampleproject.common.event.InventoryCheckResult;
import com.myorg.lsf.quota.api.QuotaDecision;
import com.myorg.lsf.quota.api.QuotaRequest;
import com.myorg.lsf.quota.api.QuotaResult;
import com.myorg.lsf.quota.api.QuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryQuotaService {

    private static final String TENANT = "shopA";
    private static final String RESOURCE_TYPE = "flashsale_sku";

    private final QuotaService quotaService;

    @Value("${lsf.quota.default-hold-seconds:120}")
    private int defaultHoldSeconds;

    public InventoryCheckResult reserve(String orderNumber, OrderLineItemRequest item, int physicalStock) {
        String skuCode = item.getSkuCode();
        String quotaKey = quotaKey(skuCode);
        String requestId = requestId(orderNumber, skuCode);

        QuotaResult result = quotaService.reserve(QuotaRequest.builder()
                .quotaKey(quotaKey)
                .requestId(requestId)
                .amount(item.getQuantity())
                .limit(Math.max(0, physicalStock))
                .hold(Duration.ofSeconds(defaultHoldSeconds))
                .build());

        boolean success = result.decision() == QuotaDecision.ACCEPTED || result.decision() == QuotaDecision.DUPLICATE;
        String reason = success
                ? null
                : String.format("Quota exceeded for %s (need=%d, used=%d, limit=%d, remaining=%d)",
                skuCode, item.getQuantity(), result.used(), result.limit(), result.remaining());

        if (success) {
            log.info("QUOTA RESERVE OK -> order={}, sku={}, qty={}, decision={}, used={}, limit={}, remaining={}",
                    orderNumber, skuCode, item.getQuantity(), result.decision(), result.used(), result.limit(), result.remaining());
        } else {
            log.warn("QUOTA RESERVE REJECTED -> order={}, sku={}, qty={}, used={}, limit={}, remaining={}",
                    orderNumber, skuCode, item.getQuantity(), result.used(), result.limit(), result.remaining());
        }

        return new InventoryCheckResult(orderNumber, item, success, reason);
    }

    public QuotaResult confirm(String workflowId, String resourceId) {
        String quotaKey = quotaKey(resourceId);
        String requestId = requestId(workflowId, resourceId);
        QuotaResult result = quotaService.confirm(quotaKey, requestId);
        log.info("QUOTA CONFIRM -> workflowId={}, resourceId={}, decision={}, used={}, state={}",
                workflowId, resourceId, result.decision(), result.used(), result.state());
        return result;
    }

    public QuotaResult release(String workflowId, String resourceId, String reason) {
        String quotaKey = quotaKey(resourceId);
        String requestId = requestId(workflowId, resourceId);
        QuotaResult result = quotaService.release(quotaKey, requestId);
        log.info("QUOTA RELEASE -> workflowId={}, resourceId={}, decision={}, used={}, reason={}",
                workflowId, resourceId, result.decision(), result.used(), reason);
        return result;
    }

    public String quotaKey(String skuCode) {
        return TENANT + ":" + RESOURCE_TYPE + ":" + skuCode;
    }

    public String requestId(String workflowId, String resourceId) {
        return workflowId + ":" + resourceId;
    }
}