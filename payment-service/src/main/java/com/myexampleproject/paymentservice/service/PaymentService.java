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
    // Listener để nhận sự kiện từ Inventory Service
//    @KafkaListener(
//            topics = "order-validated-topic",
//            groupId = "payment-group",
//            containerFactory = "paymentKafkaListenerContainerFactory"
//    )
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

                // 2. SỬA LỖI: Dùng biến 'event'
                log.info("Received OrderValidatedEvent for Order {}. Processing payment...",
                        event.getOrderNumber());

                // 3. SỬA LỖI: Dùng biến 'event'
                boolean paymentSuccess = processPayment(event);
                if (paymentSuccess) {
                    String paymentId = UUID.randomUUID().toString();

                    // 4. SỬA LỖI: Dùng biến 'event'
                    PaymentProcessedEvent successEvent = new PaymentProcessedEvent(
                            event.getOrderNumber(),
                            paymentId
                    );

                    // 5. SỬA LỖI: Dùng biến 'event'
                    kafkaTemplate.send("payment-processed-topic", event.getOrderNumber(), successEvent);

                    // 6. SỬA LỖI: Dùng biến 'event'
                    log.info("Payment SUCCESS for Order {}. Payment ID: {}",
                            event.getOrderNumber(), paymentId);
                } else {
                    // 7. SỬA LỖI: Dùng biến 'event'
                    PaymentFailedEvent failedEvent = new PaymentFailedEvent(
                            event.getOrderNumber(),
                            "Payment gateway declined."
                    );

                    // 8. SỬA LỖI: Dùng biến 'event'
                    kafkaTemplate.send("payment-failed-topic", event.getOrderNumber(), failedEvent);

                    // 9. SỬA LỖI: Dùng biến 'event'
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

    //test
    private boolean processPayment(OrderValidatedEvent event) {
        int totalQty = event.getItems()
                .stream()
                .mapToInt(item -> item.getQuantity())
                .sum();

        // Test local:
        // đơn có tổng quantity = 2 => fail
        // các đơn khác => success
        return totalQty != 2;
    }
}
