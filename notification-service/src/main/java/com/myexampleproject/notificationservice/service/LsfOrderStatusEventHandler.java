package com.myexampleproject.notificationservice.service;

import com.myexampleproject.common.event.OrderStatusEvent;
import com.myorg.lsf.contracts.core.envelope.EventEnvelope;
import com.myorg.lsf.eventing.LsfEventHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class LsfOrderStatusEventHandler {

    private final SimpMessagingTemplate messagingTemplate;

    @LsfEventHandler(value = "ecommerce.order.status.v1", payload = OrderStatusEvent.class)
    public void onOrderStatus(EventEnvelope envelope, OrderStatusEvent event) {
        String normalizedStatus = normalizeStatus(event.getStatus());
        String message = switch (normalizedStatus) {
            case "PENDING" -> "Đơn hàng đã được tiếp nhận!";
            case "VALIDATED" -> "Reservation thành công, đang chờ payment result.";
            case "COMPLETED" -> "Thanh toán thành công! Đơn hàng hoàn tất.";
            case "PAYMENT_FAILED" -> "Thanh toán lỗi, reservation đã được release.";
            case "FAILED" -> "Đơn hàng thất bại trong quá trình xử lý.";
            default -> "Trạng thái đơn hàng đã được cập nhật.";
        };

        log.info("LSF eventing handled order-status-envelope: orderNumber={}, status={}, eventId={}, aggregateId={}",
                event.getOrderNumber(), normalizedStatus, envelope.getEventId(), envelope.getAggregateId());

        messagingTemplate.convertAndSend(
                "/topic/order/" + event.getOrderNumber(),
                Map.of(
                        "status", normalizedStatus,
                        "message", message,
                        "eventId", safe(envelope.getEventId()),
                        "source", "lsf-eventing-starter"
                )
        );
    }

    private String normalizeStatus(String status) {
        return status == null ? "PENDING" : status.trim().toUpperCase();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
