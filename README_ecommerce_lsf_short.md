# LSF Framework Integration in Ecommerce Backend

## Mục tiêu
Repo ecommerce này đã được tích hợp một phần framework **LSF** để chứng minh khả năng tái sử dụng framework trên một hệ thống microservices thực tế, thay vì chỉ dừng ở demo nội bộ của framework.

## Các module LSF đã áp dụng

### 1. `lsf-kafka-starter`
Được dùng để chuẩn hóa cấu hình Kafka producer/consumer cho các service sử dụng event-driven messaging.

**Áp dụng tại:**
- `order-service`
- `payment-service`
- listener command trong `inventory-service`

### 2. `lsf-contracts`
Được dùng để chia sẻ contract chung giữa các service, đặc biệt là các command liên quan đến quota.

**Contract đang dùng:**
- `ConfirmReservationCommand`
- `ReleaseReservationCommand`

### 3. `lsf-quota-streams-starter`
Được dùng trong `inventory-service` để quản lý logic giữ tài nguyên có giới hạn theo mô hình quota.

**Chức năng đang dùng:**
- `reserve(...)`
- `confirm(...)`
- `release(...)`

## Flow đã được thay đổi
Trước khi tích hợp framework, flow tồn kho hoạt động theo kiểu:
- inventory check thành công -> trừ stock ngay
- payment fail -> cộng stock lại bằng compensation event

Sau khi tích hợp LSF, flow được chuyển sang:
- order được tạo
- inventory reserve bằng quota
- payment success -> confirm reservation
- payment fail / inventory fail -> release reservation

Mô hình mới:

```text
reserve -> confirm / release
```

## Thay đổi chính trong ecommerce

### `inventory-service`
- Thay logic trừ stock trực tiếp trong `InventoryTopology`
- Dùng `InventoryQuotaService` để gọi quota framework
- Thêm listener nhận:
  - `inventory-reservation-confirm-topic`
  - `inventory-reservation-release-topic`
- Mapping chuẩn:
  - `quotaKey = shopA:flashsale_sku:<sku>`
  - `requestId = <orderNumber>:<sku>`

### `order-service`
- Khi payment success: publish `ConfirmReservationCommand`
- Khi payment fail hoặc inventory fail: publish `ReleaseReservationCommand`
- Sửa saga inventory để đợi đủ kết quả của toàn bộ item trước khi kết luận order fail/success
- Thay compensation restock cũ bằng quota release

### `payment-service`
- Dùng Kafka starter để nhận `order-validated-topic`
- Giữ vai trò kích hoạt nhánh `confirm` hoặc `release` thông qua event payment success/fail

## Kịch bản test đã xác nhận thành công

### 1. Reserve thành công rồi confirm
- order được nhận
- inventory reserve thành công
- payment success
- reservation được confirm
- order kết thúc ở trạng thái `COMPLETED`

### 2. Reserve bị từ chối vì vượt quota
- inventory reserve bị reject
- order kết thúc ở trạng thái `FAILED`

### 3. Reserve thành công rồi payment fail rồi release
- inventory reserve thành công
- payment fail
- reservation được release
- order kết thúc ở trạng thái `PAYMENT_FAILED`

## Ý nghĩa của phần tích hợp
Việc tích hợp này chứng minh rằng framework LSF không chỉ là bộ module demo, mà có thể được áp dụng vào một hệ thống microservices có use case thực tế để xử lý:
- giữ tài nguyên có giới hạn
- orchestration bằng event
- flow compensation theo hướng reserve/confirm/release
- chuẩn hóa contract và cấu hình Kafka giữa nhiều service

## Ghi chú
Bản tích hợp hiện tại phục vụ mục tiêu chứng minh framework trên một hệ thống ecommerce thực tế. Một số phần local-test hoặc fallback có thể đã được thêm trong quá trình kiểm thử và nên được rà soát lại trước khi dùng cho production.
