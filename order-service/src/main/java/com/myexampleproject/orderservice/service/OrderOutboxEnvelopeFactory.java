package com.myexampleproject.orderservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myorg.lsf.contracts.core.envelope.EventEnvelope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderOutboxEnvelopeFactory {

    private final ObjectMapper objectMapper;

    public EventEnvelope wrap(String eventType, String aggregateId, String correlationId, Object payload) {
        return EventEnvelope.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(eventType)
                .version(1)
                .aggregateId(aggregateId)
                .correlationId(correlationId)
                .occurredAtMs(System.currentTimeMillis())
                .producer("order-service")
                .payload(objectMapper.valueToTree(payload))
                .build();
    }
}
