# LSF Integration Before vs After (cập nhật đến Outbox Phase 5.2)

Tài liệu này mô tả ngắn gọn trạng thái **trước khi tích hợp framework LSF**, **sau khi tích hợp Kafka/Quota/Contracts**, **sau khi mở rộng thêm Outbox**, và **sau khi hoàn tất Phase 5.2** vào dự án ecommerce.

---

## 1. Tổng quan

### Trước khi tích hợp
Hệ thống ecommerce đã có kiến trúc microservices theo hướng event-driven, gồm các service chính như:
- `order-service`
- `inventory-service`
- `payment-service`

Tuy nhiên, nhiều phần quan trọng vẫn là cài đặt riêng:
- Kafka config thủ công theo từng service
- inventory hold bằng cách trừ stock trực tiếp
- payment fail hoàn tác bằng cách cộng lại stock
- direct send Kafka sau khi update DB
- chưa có outbox cho status event
- chưa có chiến lược tách contract topic cũ và topic mới

### Sau Giai đoạn 1
Dự án ecommerce đã sử dụng framework LSF để thay thế một phần cài đặt thủ công:
- Kafka integration được chuẩn hóa bằng `lsf-kafka-starter`
- inventory hold được chuyển sang quota reservation
- reservation lifecycle được chuẩn hóa bằng `lsf-contracts`
- order lifecycle được refactor theo mô hình `reserve -> confirm -> release`

### Sau Giai đoạn 2 / Phase 5
Dự án tiếp tục dùng framework để thay thế một phần direct event publishing:
- status event của order được ghi vào bảng outbox
- outbox publisher gửi event ra Kafka
- status event được bọc trong `EventEnvelope`

### Sau Giai đoạn 2.2 / Phase 5.2
Dự án tiếp tục hoàn thiện outbox integration bằng cách:
- tách topic envelope riêng cho status event
- sửa consumer để unwrap `EventEnvelope`
- chốt boundary giữa migration của consumer và runtime của starter
- giải quyết rõ bài toán contract evolution

---

## 2. Before vs After theo service

### 2.1 Inventory Service

**Trước**
- Nhận `InventoryCheckRequest`
- Đọc stock hiện tại từ Kafka Streams state store
- Nếu đủ hàng thì trừ stock trực tiếp trong state store
- Trả `InventoryCheckResult(success=true)`

**Sau Giai đoạn 1**
- Nhận `InventoryCheckRequest`
- Đọc stock hiện tại từ state store để lấy `limit` vật lý
- Gọi `QuotaService.reserve(...)` thông qua `InventoryQuotaService`
- Không trừ stock trực tiếp ở bước reserve
- Trả `InventoryCheckResult` dựa trên quota decision
- Nghe thêm reservation command để:
  - `confirm`
  - `release`

**Sau Giai đoạn 2.2**
- Không thay đổi business ownership
- Vẫn là nơi sở hữu tài nguyên
- Vẫn xử lý reservation lifecycle thông qua quota framework
- Không phải service chính chịu trách nhiệm outbox ở phase này

**Ý nghĩa**
Inventory Service đã chuyển từ “trừ stock sớm” sang “điều khiển reservation lifecycle bằng quota framework”.

### 2.2 Order Service

**Trước**
- Nhận order request, phát `order-placed-topic`
- Khi payment success: đổi trạng thái order
- Khi payment fail: phát `InventoryAdjustmentEvent(+quantity)` để bù kho
- SAGA inventory có thể fail sớm khi một item fail
- Sau khi update DB, status event thường được gửi Kafka trực tiếp
- Dùng `order-status-topic` như topic status duy nhất

**Sau Giai đoạn 1**
- Khi payment success:
  - đổi trạng thái order
  - phát `ConfirmReservationCommand`
- Khi payment fail:
  - đổi trạng thái order
  - phát `ReleaseReservationCommand`
- Khi inventory fail:
  - đổi trạng thái order sang `FAILED`
  - phát `ReleaseReservationCommand`
- SAGA inventory được sửa để đợi đủ kết quả của toàn bộ item trước khi chốt trạng thái

**Sau Giai đoạn 2 / Phase 5**
- Khi đổi status order:
  - không direct send status event ngay
  - append `EventEnvelope` vào `lsf_outbox`
- Outbox publisher sẽ publish status event ra Kafka nền

**Sau Giai đoạn 2.2 / Phase 5.2**
- Không publish `EventEnvelope` vào `order-status-topic` cũ nữa
- Dùng topic mới:
  - `order-status-envelope-topic`
- Làm rõ rằng `order-status-topic` là contract legacy, còn topic mới là contract framework-based
- Migration bảng outbox thuộc về consumer project, không phải starter

**Ý nghĩa**
Order Service đã chuyển qua ba lớp chuẩn hóa:
1. business/resource lifecycle: `reserve -> confirm -> release`
2. event lifecycle: `append -> publish -> consume envelope`
3. contract lifecycle: `legacy topic -> envelope topic`

### 2.3 Payment Service

**Trước**
- Nghe `order-validated-topic`
- Tự dùng consumer config riêng
- Phát `payment-processed-topic` hoặc `payment-failed-topic`

**Sau Giai đoạn 1**
- Vẫn giữ business responsibility cũ
- Kafka listener được chuẩn hóa theo hướng dùng `lsf-kafka-starter`
- Tham gia vào flow mới:
  - success -> confirm reservation
  - fail -> release reservation

**Sau Giai đoạn 2.2**
- Không phải service chính tích hợp outbox ở phase này
- Vẫn tương tác gián tiếp với flow mới thông qua status/order lifecycle đã thay đổi

**Ý nghĩa**
Payment Service không đổi business role, nhưng trở thành mắt xích chuẩn trong reservation lifecycle mới.

### 2.4 OrderStatusJoiner

**Trước**
- Đọc `order-status-topic`
- Deserialize trực tiếp `OrderStatusEvent`
- Join với `payment-processed-topic`

**Sau Giai đoạn 2 / Phase 5**
- Bắt đầu phụ thuộc vào status event được bọc trong outbox

**Sau Giai đoạn 2.2 / Phase 5.2**
- Đọc `order-status-envelope-topic`
- Deserialize `EventEnvelope`
- Unwrap `payload` thành `OrderStatusEvent`
- Sau đó join với payment stream

**Ý nghĩa**
Joiner trở thành bằng chứng rõ nhất rằng outbox integration đã thay đổi contract kỹ thuật của status event và consumer side đã được điều chỉnh cho phù hợp.

---

## 3. Before vs After theo kỹ thuật hạ tầng

### 3.1 Kafka integration

**Trước**
- Mỗi service tự cấu hình Kafka listener/consumer/producer
- Dễ sinh chênh lệch config giữa các service

**Sau Giai đoạn 1**
- Chuẩn hóa bằng `lsf-kafka-starter`
- Giảm cấu hình thủ công lặp lại
- Tăng tính tái sử dụng khi áp framework vào project khác

### 3.2 Reservation contract

**Trước**
- Không có command chuẩn cho confirm/release reservation
- compensation gắn chặt với domain event của project

**Sau Giai đoạn 1**
- Dùng command chuẩn từ `lsf-contracts`:
  - `ConfirmReservationCommand`
  - `ReleaseReservationCommand`

**Ý nghĩa**
Làm rõ ranh giới giữa orchestration và resource ownership.

### 3.3 Quota / Resource hold

**Trước**
- Resource hold được mô phỏng bằng cách trừ stock sớm
- Khó giải thích như một cơ chế quota framework độc lập

**Sau Giai đoạn 1**
- Resource hold được xử lý bằng quota framework
- Có các thao tác rõ ràng:
  - reserve
  - confirm
  - release

**Ý nghĩa**
Tạo nền rõ ràng cho bài toán chống oversell/overbooking.

### 3.4 Event publishing

**Trước**
- Service cập nhật DB xong gửi Kafka trực tiếp
- Có rủi ro dual write giữa DB và message broker

**Sau Giai đoạn 2 / Phase 5**
- Service append event vào outbox trong cùng transaction DB
- Publisher nền gửi event ra Kafka
- Event được bọc bằng `EventEnvelope`

**Sau Giai đoạn 2.2 / Phase 5.2**
- Event envelope không dùng lại topic cũ
- Consumer unwrap payload trên topic envelope mới
- contract cũ và contract mới được tách riêng
- migration version của outbox được giao cho consumer project

**Ý nghĩa**
Hệ thống được chuẩn hóa thêm hai lớp rất quan trọng:
- reliable event publishing
- contract evolution management

---

## 4. Kịch bản test trước và sau

### Trước
Có thể test luồng order/inventory/payment thông thường, nhưng khó tách riêng:
- cơ chế giữ tài nguyên
- cơ chế publish event tin cậy
- hành vi contract khi schema thay đổi

### Sau Giai đoạn 1
Có thể test rõ 3 case:
1. `reserve thành công -> payment success -> confirm`
2. `reserve bị reject vì vượt quota`
3. `reserve thành công -> payment fail -> release -> reserve lại được`

### Sau Giai đoạn 2 / Phase 5
Có thể test thêm:
4. status event được ghi vào outbox và publish nền

### Sau Giai đoạn 2.2 / Phase 5.2
Có thể test thêm:
5. `OrderStatusJoiner` xử lý đúng `EventEnvelope`
6. topic envelope riêng không phá contract của `order-status-topic` cũ

---

## 5. Những phần vẫn còn thuộc boundary của consumer project

Sau khi tích hợp, vẫn còn một số phần chưa được framework thay thế hoàn toàn:
- Kafka Streams topology của `inventory-service`
- business orchestration trong `OrderService`
- domain model order / inventory / payment
- `OrderStatusJoiner`
- migration version cụ thể của consumer project
- local test support nếu còn giữ lại
- quyết định topic naming và event evolution strategy

Điều này không làm giảm giá trị framework; ngược lại, nó làm rõ rằng framework cung cấp khả năng tái sử dụng ở:
- tầng Kafka integration
- tầng reservation lifecycle
- tầng outbox/reliable event publishing

---

## 6. Kết luận

Sau khi tích hợp LSF, ecommerce backend đã chuyển từ cách xử lý event hạ tầng và tồn kho mang tính dự án riêng lẻ sang mô hình dùng framework ở những điểm có khả năng tái sử dụng cao, cụ thể là:
- Kafka integration
- reservation contract
- quota-based resource hold
- outbox-based event publishing
- contract evolution qua topic envelope riêng

Sự thay đổi quan trọng nhất qua các phase là:

```text
direct deduction -> reserve / confirm / release
```

và tiếp theo là:

```text
direct send -> append to outbox -> publish envelope
```

và ở phase 5.2 là:

```text
reuse old topic -> introduce dedicated envelope topic
```

Đây là cơ sở kỹ thuật tốt để tiếp tục mở rộng sang:
- outbox admin
- observability
- benchmark / resilience evaluation
