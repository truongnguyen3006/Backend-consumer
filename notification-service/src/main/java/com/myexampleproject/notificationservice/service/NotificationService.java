package com.myexampleproject.notificationservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myexampleproject.common.event.OrderFailedEvent;
import com.myexampleproject.common.event.OrderPlacedEvent;
import com.myexampleproject.common.event.PaymentFailedEvent;
import com.myexampleproject.common.event.PaymentProcessedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "order-placed-topic",
            groupId = "notification-group",
            containerFactory = "orderPlacedKafkaListenerContainerFactory"
    )
    public void handleOrderPlaced(@Payload OrderPlacedEvent event) {
        log.info("Order placed: {}", event.getOrderNumber());
        messagingTemplate.convertAndSend(
                "/topic/order/" + event.getOrderNumber(),
                Map.of("status", "PENDING", "message", "Đơn hàng đã được tiếp nhận!")
        );
    }

    @KafkaListener(
            topics = "payment-processed-topic",
            groupId = "notification-group",
            containerFactory = "paymentProcessedKafkaListenerContainerFactory"
    )
    public void handlePaymentSuccess(@Payload PaymentProcessedEvent event) {
        log.info("Payment success for order {} (paymentId={})",
                event.getOrderNumber(), event.getPaymentId());
        messagingTemplate.convertAndSend(
                "/topic/order/" + event.getOrderNumber(),
                Map.of("status", "COMPLETED", "message", "Thanh toán thành công! Đơn hàng hoàn tất.")
        );
    }

    @KafkaListener(
            topics = "order-failed-topic",
            groupId = "notification-group",
            containerFactory = "orderFailedRawKafkaListenerContainerFactory"
    )
    public void handleOrderFailed(ConsumerRecord<String, String> record) {
        try {
            String json = cleanJson(record.value());
            OrderFailedEvent event = objectMapper.readValue(json, OrderFailedEvent.class);

            log.warn("Notification: Inventory Failed for Order {}", event.getOrderNumber());
            messagingTemplate.convertAndSend(
                    "/topic/order/" + event.getOrderNumber(),
                    Map.of("status", "FAILED", "message", "Hết hàng: " + event.getReason())
            );
        } catch (Exception e) {
            log.error("Lỗi parse OrderFailedEvent: {}", e.getMessage());
        }
    }

    @KafkaListener(
            topics = "payment-failed-topic",
            groupId = "notification-group",
            containerFactory = "paymentFailedRawKafkaListenerContainerFactory"
    )
    public void handlePaymentFailed(ConsumerRecord<String, String> record) {
        try {
            String json = cleanJson(record.value());
            PaymentFailedEvent event = objectMapper.readValue(json, PaymentFailedEvent.class);

            log.warn("Notification: Payment Failed for Order {}", event.getOrderNumber());
            messagingTemplate.convertAndSend(
                    "/topic/order/" + event.getOrderNumber(),
                    Map.of("status", "PAYMENT_FAILED", "message", "Thanh toán lỗi: " + event.getReason())
            );
        } catch (Exception e) {
            log.error("Lỗi parse PaymentFailedEvent: {}", e.getMessage());
        }
    }

    private String cleanJson(String raw) {
        if (raw == null) return "";
        int jsonStart = raw.indexOf('{');
        return jsonStart >= 0 ? raw.substring(jsonStart) : raw;
    }
}
