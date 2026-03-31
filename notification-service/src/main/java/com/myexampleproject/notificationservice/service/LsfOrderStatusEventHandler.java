package com.myexampleproject.notificationservice.service;

import com.myexampleproject.common.event.OrderStatusEvent;
import com.myorg.lsf.contracts.core.envelope.EventEnvelope;
import com.myorg.lsf.eventing.LsfEventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class LsfOrderStatusEventHandler {

    private final SimpMessagingTemplate messagingTemplate;

    public LsfOrderStatusEventHandler(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @LsfEventHandler(
            value = "ecommerce.order.status.v1",
            payload = OrderStatusEvent.class
    )
    public void handle(EventEnvelope envelope, OrderStatusEvent payload) {
        String eventId = safe(envelope.getEventId());
        String aggregateId = safe(envelope.getAggregateId());
        String eventType = safe(envelope.getEventType());
        String orderNumber = safe(payload.getOrderNumber());
        String status = safe(payload.getStatus());

        NotificationMessage message = new NotificationMessage(
                status,
                "Cập nhật trạng thái đơn hàng: " + status
        );

        messagingTemplate.convertAndSend("/topic/order/" + orderNumber, message);

        log.info(
                "LSF eventing handled order-status-envelope: orderNumber={}, status={}, eventId={}, aggregateId={}, eventType={}",
                orderNumber, status, eventId, aggregateId, eventType
        );
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record NotificationMessage(String status, String message) {}
}
