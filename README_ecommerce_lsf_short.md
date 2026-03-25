# Ecommerce Backend + LSF Framework Integration (cập nhật đến Phase 5.2)

Dự án backend ecommerce này đã được dùng làm **consumer project** để kiểm chứng khả năng tái sử dụng của framework **LSF** trong một hệ thống microservices thực tế.

## Các module LSF đã áp dụng

### Giai đoạn 1
- `lsf-kafka-starter`
- `lsf-contracts`
- `lsf-quota-streams-starter`

### Giai đoạn 2 / Phase 5
- `lsf-outbox-mysql-starter`

### Giai đoạn 2.2 / Phase 5.2
- hoàn thiện contract/event flow cho outbox thông qua `EventEnvelope`
- tách topic envelope riêng cho order status

## Service nào dùng module nào

### `inventory-service`
- dùng `lsf-quota-streams-starter`
- dùng `lsf-contracts`
- quản lý reservation lifecycle:
  - reserve
  - confirm
  - release

### `order-service`
- dùng `lsf-kafka-starter`
- dùng `lsf-contracts`
- dùng `lsf-outbox-mysql-starter`
- điều phối flow:
  - phát confirm/release command
  - append status event vào outbox
  - publish `EventEnvelope` sang topic envelope riêng

### `payment-service`
- dùng `lsf-kafka-starter`
- phát payment success / fail để kích hoạt confirm hoặc release

## Thay đổi chính của hệ thống

### Trước tích hợp
- Kafka config thủ công
- inventory check pass thì trừ stock trực tiếp
- payment fail thì bù kho bằng compensation event
- update DB xong gửi Kafka trực tiếp

### Sau tích hợp
- Kafka integration được chuẩn hóa bằng starter
- stock hold đổi sang quota reservation
- payment success/fail điều khiển confirm/release bằng contract chuẩn
- status event được ghi vào outbox rồi publisher nền gửi ra Kafka
- `OrderStatusJoiner` đọc `EventEnvelope` và unwrap payload
- status envelope dùng topic riêng:
  - `order-status-envelope-topic`

## Vì sao có `order-status-envelope-topic`
`order-status-topic` cũ đã có contract raw `OrderStatusEvent`.
Khi outbox publish `EventEnvelope`, schema thay đổi hoàn toàn. Vì vậy phase 5.2 tách sang topic envelope riêng để:
- tránh conflict với Schema Registry
- giữ contract cũ không bị phá
- làm rõ biên giới giữa legacy topic và framework-based topic

## Các kịch bản đã chứng minh được

1. reserve thành công -> payment success -> confirm
2. reserve bị reject vì vượt quota
3. reserve thành công -> payment fail -> release
4. update status -> row xuất hiện trong `lsf_outbox` -> publisher gửi thành công
5. `OrderStatusJoiner` đọc được `EventEnvelope` và join đúng với payment stream

## Tài liệu đi kèm
- `README_lsf_ecommerce_integration_with_phase52.md`
- `LSF_INTEGRATION_TRACEABILITY_OUTBOX_PHASE52.md`
- `LSF_INTEGRATION_BEFORE_AFTER_OUTBOX_PHASE52.md`
- `Mo_ta_hoc_thuat_tich_hop_LSF_vao_ecommerce_phase52.md`

## Ý nghĩa
Project này chứng minh framework LSF không chỉ dừng ở demo nội bộ, mà đã được áp dụng vào một consumer project thật ở ba lớp:
- Kafka integration
- reservation lifecycle
- reliable event publishing / contract evolution
