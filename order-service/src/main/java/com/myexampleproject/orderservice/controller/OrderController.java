package com.myexampleproject.orderservice.controller;

// === IMPORT MỚI CẦN THÊM ===
import com.myexampleproject.orderservice.dto.OrderResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
// === KẾT THÚC IMPORT MỚI ===

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.myexampleproject.orderservice.dto.OrderRequest;
import com.myexampleproject.orderservice.service.OrderService;

import lombok.RequiredArgsConstructor;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> placeOrder(@RequestBody OrderRequest orderRequest, Principal principal) {
        log.info("Placing Order...");
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bạn cần đăng nhập để đặt hàng");
        }
        // Lấy ID thật từ Token
        String userId = principal.getName();
        String orderNumber = orderService.placeOrder(orderRequest, userId);
        // Trả về JSON: { "orderNumber": "uuid-..." }
        return Map.of("orderNumber", orderNumber, "message", "Order Received");
    }

    @GetMapping("/{orderNumber}")
    @ResponseStatus(HttpStatus.OK)
    public OrderResponse getOrderDetails(@PathVariable String orderNumber) {
        log.info("Fetching order details for orderNumber: {}", orderNumber);
        return orderService.getOrderDetails(orderNumber);
    }

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public List<OrderResponse> getAllOrders() {
        return orderService.getAllOrders();
    }
}