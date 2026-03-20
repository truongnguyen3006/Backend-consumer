package com.myexampleproject.orderservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

//    @Bean
//    public NewTopic orderStatusTopic() {
//        return TopicBuilder.name("order-status-topic")
//                .partitions(1)
//                .replicas(1)
//                .build();
//    }
    //đổi tên topic vì trước đây dùng trực tiếp order-status-topic đã đk với schema registry rồi
    // giờ đổi thành envelope thì sẽ không tương thích schema nên lỗi
    @Bean
    public NewTopic orderStatusEnvelopeTopic() {
        return TopicBuilder.name("order-status-envelope-topic")
                .partitions(1)
                .replicas(1)
                .build();
    }

    //mới
    // Reservation lifecycle topics added for LSF framework integration.
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