package com.myexampleproject.orderservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myexampleproject.common.event.*;
import com.myorg.lsf.contracts.core.envelope.EventEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import io.confluent.kafka.streams.serdes.json.KafkaJsonSchemaSerde;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;

import java.util.Map;

@Configuration
@EnableKafkaStreams
@Slf4j
@RequiredArgsConstructor
public class OrderStatusJoiner {
    private final ObjectMapper objectMapper;

    @Value("${lsf.kafka.schema-registry-url}")
    private String schemaRegistryUrl;

    private <T> Serde<T> jsonSerde(Class<T> clazz) {
        Serde<T> serde = new KafkaJsonSchemaSerde<>(clazz);
        serde.configure(Map.of(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl), false);
        return serde;
    }

    @Bean
    public KStream<String, PaymentProcessedEvent> joinPaymentAndOrderStatus(StreamsBuilder builder) throws InterruptedException {
//        Serde<OrderStatusEvent> statusSerde = jsonSerde(OrderStatusEvent.class);
        Serde<PaymentProcessedEvent> paymentSerde = jsonSerde(PaymentProcessedEvent.class);
        Serde<EventEnvelope> envelopeSerde = jsonSerde(EventEnvelope.class);

        // Tạo KTable từ order-status-topic (key = orderNumber)
//        KTable<String, OrderStatusEvent> orderStatusTable = builder.table(
//                "order-status-topic",
//                Consumed.with(Serdes.String(), statusSerde)
//        );
        // order-status-topic is now published via LSF outbox, so records arrive as EventEnvelope.
        KTable<String, EventEnvelope> rawStatusTable = builder.table(
                "order-status-envelope-topic",
                Consumed.with(Serdes.String(), envelopeSerde)
        );

        // unwrap payload từ EventEnvelope -> OrderStatusEvent
        // Unwrap the domain payload from EventEnvelope before joining with payment events.
        KTable<String, OrderStatusEvent> orderStatusTable = rawStatusTable.mapValues(envelope -> {
            if (envelope == null || envelope.getPayload() == null) {
                return null;
            }

            try {
                return objectMapper.convertValue(envelope.getPayload(), OrderStatusEvent.class);
            } catch (Exception e) {
                log.error("Failed to unwrap OrderStatusEvent from EventEnvelope. envelope={}", envelope, e);
                return null;
            }
        });

        // Tạo KStream từ payment-processed-topic
        KStream<String, PaymentProcessedEvent> paymentStream = builder.stream(
                "payment-processed-topic",
                Consumed.with(Serdes.String(), paymentSerde)
        );

        //  JOIN giữa payment và status
        KStream<String, PaymentProcessedEvent> validPayments = paymentStream.join(
                orderStatusTable,
                (payment, status) -> {
                    if (status == null) {
                        log.warn("BỎ QUA PaymentProcessedEvent: Order {} chưa có trong order-status-topic", payment.getOrderNumber());
                        return null;
                    }
                    String currentStatus = status.getStatus();
                    if (!"PENDING".equals(currentStatus) && !"VALIDATED".equals(currentStatus)) {
                        log.warn("BỎ QUA PaymentProcessedEvent: Order {} không ở trạng thái PENDING (status={})",
                                payment.getOrderNumber(), status.getStatus());
                        return null;
                    }
                    log.info("VALID PaymentProcessedEvent cho Order {}", payment.getOrderNumber());
                    return payment;
                },
                Joined.with(Serdes.String(), paymentSerde, null)
        ).filter((key, value) -> value != null); // Bỏ các record bị loại

        // Gửi các event hợp lệ sang topic mới
        validPayments.to("payment-validated-topic", Produced.with(Serdes.String(), paymentSerde));

        return validPayments;
    }
}