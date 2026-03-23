package com.myexampleproject.inventoryservice.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myexampleproject.inventoryservice.service.InventoryQuotaService;
import com.myorg.lsf.contracts.core.envelope.EventEnvelope;
import com.myorg.lsf.contracts.quota.ConfirmReservationCommand;
import com.myorg.lsf.contracts.quota.ReleaseReservationCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.logging.Logger;

@Slf4j
@Component
@RequiredArgsConstructor
// Consumer-side handler for reservation lifecycle commands defined in lsf-contracts.
// Order service publishes confirm/release commands; inventory service owns the resource state.
public class InventoryReservationCommandListener {
    private final InventoryQuotaService inventoryQuotaService;
    private final ObjectMapper objectMapper;

//    @KafkaListener(topics = {
//            "inventory-reservation-confirm-envelope-topic",
//            "inventory-reservation-release-envelope-topic"
//    })
//    public void handleReservationCommand(ConsumerRecord<String, Object> record) {
//        try {
//            Object payload = record.value();
//            String topic = record.topic();
//            EventEnvelope envelope = toEnvelope(payload);
//
//            log.info("Reservation command payload class={}",
//                    payload == null ? "null" : payload.getClass().getName());
//            // Map framework reservation commands to quota operations on the inventory side.
//            switch (topic) {
//                case "inventory-reservation-confirm-envelope-topic" -> {
////                    ConfirmReservationCommand command = toConfirmCommand(payload);
////                    inventoryQuotaService.confirm(command.getWorkflowId(), command.getResourceId());
//                    ConfirmReservationCommand cmd = objectMapper.treeToValue(
//                            envelope.getPayload(),
//                            ConfirmReservationCommand.class
//                    );
//                    inventoryQuotaService.confirm(cmd.getWorkflowId(), cmd.getResourceId());
//                }
//                case "inventory-reservation-release-envelope-topic" -> {
////                    ReleaseReservationCommand command = toReleaseCommand(payload);
////                    inventoryQuotaService.release(
////                            command.getWorkflowId(),
////                            command.getResourceId(),
////                            command.getReason()
////                    );
//                    ReleaseReservationCommand cmd = objectMapper.treeToValue(
//                            envelope.getPayload(),
//                            ReleaseReservationCommand.class
//                    );
//
//                    inventoryQuotaService.release(
//                            cmd.getWorkflowId(),
//                            cmd.getResourceId(),
//                            cmd.getReason()
//                    );
//                }
//                default -> log.warn("Unhandled topic={}", topic);
//            }
//        } catch (Exception e) {
//            log.error("Failed to process reservation command. key={}, topic={}, payload={}",
//                    record.key(), record.topic(), record.value(), e);
//        }
//    }

    @KafkaListener(topics = {
            "inventory-reservation-confirm-envelope-topic",
            "inventory-reservation-release-envelope-topic"
    })
    public void handleReservationCommand(ConsumerRecord<String, Object> record) {
        try {
            EventEnvelope envelope = (EventEnvelope) record.value();

            switch (record.topic()) {
                case "inventory-reservation-confirm-envelope-topic" -> {
                    ConfirmReservationCommand cmd = objectMapper.convertValue(
                            envelope.getPayload(),
                            ConfirmReservationCommand.class
                    );
                    inventoryQuotaService.confirm(cmd.getWorkflowId(), cmd.getResourceId());
                }
                case "inventory-reservation-release-envelope-topic" -> {
                    ReleaseReservationCommand cmd = objectMapper.convertValue(
                            envelope.getPayload(),
                            ReleaseReservationCommand.class
                    );
                    inventoryQuotaService.release(
                            cmd.getWorkflowId(),
                            cmd.getResourceId(),
                            cmd.getReason()
                    );
                }
            }
        } catch (Exception e) {
            log.error("Failed to process reservation command", e);
        }
    }

//    private ConfirmReservationCommand toConfirmCommand(Object payload) throws Exception {
//        Object normalized = normalizePayload(payload);
//        return objectMapper.convertValue(normalized, ConfirmReservationCommand.class);
//    }
//
//    private ReleaseReservationCommand toReleaseCommand(Object payload) throws Exception {
//        Object normalized = normalizePayload(payload);
//        return objectMapper.convertValue(normalized, ReleaseReservationCommand.class);
//    }
//    // Integration helper for local/demo environments where payloads may arrive in different serialized forms.
//    private Object normalizePayload(Object payload) throws Exception {
//        if (payload == null) {
//            return null;
//        }
//
//        if (payload instanceof byte[] bytes) {
//            return decodePossiblyConfluentFramedBytes(bytes);
//        }
//
//        if (payload instanceof String s) {
//            String cleaned = stripLeadingGarbageBeforeJson(s);
//            if (!cleaned.equals(s)) {
//                return objectMapper.readTree(cleaned);
//            }
//            return payload;
//        }
//
//        return payload;
//    }
//
//    private Object decodePossiblyConfluentFramedBytes(byte[] bytes) throws Exception {
//        if (bytes.length > 5 && bytes[0] == 0) {
//            byte[] jsonBytes = Arrays.copyOfRange(bytes, 5, bytes.length);
//            return objectMapper.readTree(jsonBytes);
//        }
//        return objectMapper.readTree(bytes);
//    }
//
//    private String stripLeadingGarbageBeforeJson(String s) {
//        int objIdx = s.indexOf('{');
//        int arrIdx = s.indexOf('[');
//
//        int idx;
//        if (objIdx == -1) idx = arrIdx;
//        else if (arrIdx == -1) idx = objIdx;
//        else idx = Math.min(objIdx, arrIdx);
//
//        if (idx > 0) {
//            return s.substring(idx);
//        }
//        return s;
//    }

//    private EventEnvelope toEnvelope(Object value) throws Exception {
//        if (value instanceof String json) {
//            return objectMapper.readValue(json, EventEnvelope.class);
//        }
//        if (value instanceof byte[] bytes) {
//            return objectMapper.readValue(bytes, EventEnvelope.class);
//        }
//        return objectMapper.convertValue(value, EventEnvelope.class);
//    }
}