package com.myexampleproject.inventoryservice.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myexampleproject.inventoryservice.service.InventoryQuotaService;
import com.myorg.lsf.contracts.quota.ConfirmReservationCommand;
import com.myorg.lsf.contracts.quota.ReleaseReservationCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryReservationCommandListener {

    private final InventoryQuotaService inventoryQuotaService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = {
            "inventory-reservation-confirm-topic",
            "inventory-reservation-release-topic"
    })
    public void handleReservationCommand(ConsumerRecord<String, Object> record) {
        try {
            Object payload = record.value();
            String topic = record.topic();

            log.info("Reservation command payload class={}",
                    payload == null ? "null" : payload.getClass().getName());

            switch (topic) {
                case "inventory-reservation-confirm-topic" -> {
                    ConfirmReservationCommand command = toConfirmCommand(payload);
                    inventoryQuotaService.confirm(command.getWorkflowId(), command.getResourceId());
                }
                case "inventory-reservation-release-topic" -> {
                    ReleaseReservationCommand command = toReleaseCommand(payload);
                    inventoryQuotaService.release(
                            command.getWorkflowId(),
                            command.getResourceId(),
                            command.getReason()
                    );
                }
                default -> log.warn("Unhandled topic={}", topic);
            }
        } catch (Exception e) {
            log.error("Failed to process reservation command. key={}, topic={}, payload={}",
                    record.key(), record.topic(), record.value(), e);
        }
    }

    private ConfirmReservationCommand toConfirmCommand(Object payload) throws Exception {
        Object normalized = normalizePayload(payload);
        return objectMapper.convertValue(normalized, ConfirmReservationCommand.class);
    }

    private ReleaseReservationCommand toReleaseCommand(Object payload) throws Exception {
        Object normalized = normalizePayload(payload);
        return objectMapper.convertValue(normalized, ReleaseReservationCommand.class);
    }

    private Object normalizePayload(Object payload) throws Exception {
        if (payload == null) {
            return null;
        }

        if (payload instanceof byte[] bytes) {
            return decodePossiblyConfluentFramedBytes(bytes);
        }

        if (payload instanceof String s) {
            String cleaned = stripLeadingGarbageBeforeJson(s);
            if (!cleaned.equals(s)) {
                return objectMapper.readTree(cleaned);
            }
            return payload;
        }

        return payload;
    }

    private Object decodePossiblyConfluentFramedBytes(byte[] bytes) throws Exception {
        if (bytes.length > 5 && bytes[0] == 0) {
            byte[] jsonBytes = Arrays.copyOfRange(bytes, 5, bytes.length);
            return objectMapper.readTree(jsonBytes);
        }
        return objectMapper.readTree(bytes);
    }

    private String stripLeadingGarbageBeforeJson(String s) {
        int objIdx = s.indexOf('{');
        int arrIdx = s.indexOf('[');

        int idx;
        if (objIdx == -1) idx = arrIdx;
        else if (arrIdx == -1) idx = objIdx;
        else idx = Math.min(objIdx, arrIdx);

        if (idx > 0) {
            return s.substring(idx);
        }
        return s;
    }
}