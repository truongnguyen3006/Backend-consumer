package com.myexampleproject.orderservice.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name="t_orders", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"orderNumber"})})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Lưu ID của user đã đặt hàng (có thể là keycloakId hoặc ID từ UserService)
    @Column(nullable = false, updatable = false)
    private String userId; // Hoặc Long userId nếu bạn dùng ID của UserService

    @Column(name = "order_number", nullable = false, unique = true)
    private String orderNumber;
    private BigDecimal totalPrice;
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    private List<OrderLineItems> orderLineItemsList = new ArrayList<>(); // ✅ mutable list
    @CreationTimestamp // Tự động gán ngày giờ khi tạo
    private LocalDateTime orderDate;
    private String status;
}
