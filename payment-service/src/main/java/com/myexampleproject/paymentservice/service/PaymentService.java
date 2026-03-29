package com.myexampleproject.paymentservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myexampleproject.common.event.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    // Kafka listener now relies on lsf-kafka-starter instead of a service-specific consumer configuration.
    @KafkaListener(
            topics = "order-validated-topic",
            groupId = "payment-group"
    )
    public void handleOrderValidation(List<ConsumerRecord<String, Object>> records) {
        log.info("Received batch of {} validated events", records.size());

        for (ConsumerRecord<String, Object> record : records) {
            try {
                // 1. Convert từ record.value() sang object
                OrderValidatedEvent event = objectMapper.convertValue(record.value(), OrderValidatedEvent.class);
                log.info("Received OrderValidatedEvent for Order {}. Processing payment...",
                        event.getOrderNumber());
                boolean paymentSuccess = processPayment(event);
                if (paymentSuccess) {
                    String paymentId = UUID.randomUUID().toString();
                    PaymentProcessedEvent successEvent = new PaymentProcessedEvent(
                            event.getOrderNumber(),
                            paymentId
                    );
                    kafkaTemplate.send("payment-processed-topic", event.getOrderNumber(), successEvent);
                    log.info("Payment SUCCESS for Order {}. Payment ID: {}",
                            event.getOrderNumber(), paymentId);
                } else {
                    PaymentFailedEvent failedEvent = new PaymentFailedEvent(
                            event.getOrderNumber(),
                            "Payment gateway declined."
                    );
                    kafkaTemplate.send("payment-failed-topic", event.getOrderNumber(), failedEvent);
                    log.warn("Payment FAILED for Order {}. Reason: {}",
                            event.getOrderNumber(), failedEvent.getReason());
                }
            } catch (Exception e) {
                log.error("Lỗi xử lý payment cho key {}: {}", record.key(), e.getMessage(), e);
            }
        }
    }

//    private boolean processPayment(OrderValidatedEvent event) {
//        log.info("Simulating payment processing for Order {}...", event.getOrderNumber());
//        // Thêm logic phức tạp hơn nếu muốn (ví dụ: random thành công/thất bại)
//        return true; // Luôn trả về thành công cho đơn giản
//    }

    private boolean processPayment(OrderValidatedEvent event) {
        return simulatePaymentDecision(event);
    }

    private boolean simulatePaymentDecision(OrderValidatedEvent event) {
//        int totalQty = event.getItems().stream().mapToInt(item -> item.getQuantity()).sum();
//        return totalQty != 2;
        return false;
    }
}
