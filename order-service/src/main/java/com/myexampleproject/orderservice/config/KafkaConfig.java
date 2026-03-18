package com.myexampleproject.orderservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic orderStatusTopic() {
        return TopicBuilder.name("order-status-topic")
                .partitions(1)
                .replicas(1)
                .build();
    }

    //mới
    @Bean
    public NewTopic inventoryReservationConfirmTopic() {
        return TopicBuilder.name("inventory-reservation-confirm-topic")
                .partitions(10)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic inventoryReservationReleaseTopic() {
        return TopicBuilder.name("inventory-reservation-release-topic")
                .partitions(10)
                .replicas(1)
                .build();
    }
}