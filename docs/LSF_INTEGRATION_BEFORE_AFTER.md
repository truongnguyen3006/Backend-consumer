# LSF Integration Before vs After

Tài liệu này mô tả ngắn gọn trạng thái **trước khi tích hợp framework LSF** và **sau khi tích hợp** vào dự án ecommerce.

---

## 1. Tổng quan

### Trước khi tích hợp
Hệ thống ecommerce đã có kiến trúc microservices theo hướng event-driven, gồm các service chính như:

- `order-service`
- `inventory-service`
- `payment-service`

Tuy nhiên, một số phần quan trọng vẫn là cài đặt riêng của dự án:

- Kafka config mang tính thủ công theo từng service.
- Inventory hold được thực hiện bằng cách trừ stock trực tiếp.
- Payment fail hoàn tác bằng cách cộng lại stock.
- Chưa có reservation lifecycle chuẩn hóa bằng command.

### Sau khi tích hợp
Dự án ecommerce đã sử dụng framework LSF để thay thế một phần cài đặt thủ công:

- Kafka integration được chuẩn hóa bằng `lsf-kafka-starter`.
- Inventory hold được chuyển sang quota reservation bằng `lsf-quota-streams-starter`.
- Reservation lifecycle được chuẩn hóa bằng `lsf-contracts`.
- Order lifecycle được refactor theo mô hình `reserve → confirm → release`.

---

## 2. Before vs After theo service

## 2.1 Inventory Service

### Trước
- Nhận `InventoryCheckRequest`.
- Đọc stock hiện tại từ Kafka Streams state store.
- Nếu đủ hàng thì **trừ stock trực tiếp** trong state store.
- Trả `InventoryCheckResult(success=true)`.

### Sau
- Nhận `InventoryCheckRequest`.
- Đọc stock hiện tại từ Kafka Streams state store để lấy `limit` vật lý.
- Gọi `QuotaService.reserve(...)` thông qua `InventoryQuotaService`.
- Không trừ stock trực tiếp ở bước reserve.
- Trả `InventoryCheckResult` dựa trên quota decision.
- Nghe thêm reservation command để:
  - `confirm`
  - `release`

### Ý nghĩa
Inventory Service từ chỗ “vừa kiểm tra vừa trừ stock ngay” đã chuyển thành “service sở hữu tài nguyên và điều khiển reservation lifecycle bằng quota framework”.

---

## 2.2 Order Service

### Trước
- Nhận order request, phát `order-placed-topic`.
- Khi payment success: đổi trạng thái order.
- Khi payment fail: phát `InventoryAdjustmentEvent(+quantity)` để bù kho.
- SAGA inventory có thể fail sớm khi một item fail.

### Sau
- Vẫn nhận order request và phát `order-placed-topic`.
- Khi payment success:
  - đổi trạng thái order
  - phát `ConfirmReservationCommand`
- Khi payment fail:
  - đổi trạng thái order
  - phát `ReleaseReservationCommand`
- Khi inventory fail:
  - đổi trạng thái order sang `FAILED`
  - phát `ReleaseReservationCommand`
- SAGA inventory được sửa để đợi đủ kết quả của toàn bộ item trước khi chốt trạng thái.

### Ý nghĩa
Order Service từ chỗ dùng compensation kiểu cộng lại kho đã chuyển sang orchestration reservation lifecycle bằng command chuẩn hóa của framework.

---

## 2.3 Payment Service

### Trước
- Nghe `order-validated-topic`.
- Tự dùng consumer config riêng.
- Phát `payment-processed-topic` hoặc `payment-failed-topic`.

### Sau
- Vẫn giữ business responsibility cũ.
- Kafka listener được chuẩn hóa theo hướng dùng `lsf-kafka-starter`.
- Tham gia vào flow mới:
  - success → confirm reservation
  - fail → release reservation

### Ý nghĩa
Payment Service không thay đổi business role, nhưng trở thành mắt xích chuẩn trong reservation lifecycle mới.

---

## 3. Before vs After theo kỹ thuật hạ tầng

## 3.1 Kafka integration

### Trước
- Mỗi service có xu hướng tự cấu hình Kafka listener/consumer riêng.
- Dễ sinh chênh lệch config giữa các service.

### Sau
- Chuẩn hóa bằng `lsf-kafka-starter`.
- Giảm cấu hình thủ công lặp lại.
- Tăng tính tái sử dụng khi áp framework vào project khác.

---

## 3.2 Reservation contract

### Trước
- Không có command chuẩn cho confirm/release reservation.
- Compensation gắn chặt với domain event của project.

### Sau
- Dùng command chuẩn từ `lsf-contracts`:
  - `ConfirmReservationCommand`
  - `ReleaseReservationCommand`

### Ý nghĩa
Làm rõ ranh giới giữa orchestration và resource ownership.

---

## 3.3 Quota/Resource hold

### Trước
- Resource hold được mô phỏng bằng cách trừ stock sớm.
- Khó giải thích như một cơ chế quota framework độc lập.

### Sau
- Resource hold được xử lý bằng quota framework.
- Có các thao tác rõ ràng:
  - reserve
  - confirm
  - release
- Có thể chứng minh bài toán chống oversell/overbooking rõ ràng hơn.

---

## 4. Kịch bản test trước và sau

### Trước
Có thể test luồng order/inventory/payment thông thường, nhưng khó tách riêng cơ chế giữ tài nguyên như một framework tái sử dụng.

### Sau
Có thể test rõ 3 case:

1. `reserve thành công → payment success → confirm`
2. `reserve bị reject vì vượt quota`
3. `reserve thành công → payment fail → release → reserve lại được`

### Ý nghĩa
Đây là bằng chứng mạnh cho việc framework đã được dùng trong hệ thống thật.

---

## 5. Những phần vẫn còn thuộc boundary của consumer project

Sau khi tích hợp, vẫn còn một số phần chưa được framework thay thế hoàn toàn:

- Kafka Streams topology của `inventory-service`
- Business orchestration trong `OrderService`
- Product cache / order model / repository của ecommerce
- Local test support như mock payment, fallback cache, mở auth

Điều này không làm giảm giá trị framework; ngược lại, nó giúp làm rõ rằng framework đang cung cấp **khả năng tái sử dụng ở tầng hạ tầng và cơ chế dùng chung**, chứ không thay toàn bộ business domain.

---

## 6. Kết luận

Sau khi tích hợp LSF, ecommerce backend đã chuyển từ cách xử lý tồn kho và event hạ tầng mang tính dự án riêng lẻ sang mô hình dùng framework ở những điểm có khả năng tái sử dụng cao, cụ thể là:

- Kafka integration
- reservation contract
- quota-based resource hold

Sự thay đổi quan trọng nhất là flow inventory/order/payment đã được chuẩn hóa thành mô hình:

`reserve → confirm → release`

Đây là cơ sở kỹ thuật tốt để mở rộng tiếp sang các phần như outbox, benchmark và observability trong các giai đoạn sau.

