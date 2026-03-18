# LSF Integration Traceability

Tài liệu này dùng để truy vết việc áp dụng framework LSF vào dự án ecommerce, giúp trả lời các câu hỏi như:

- Thành phần nào của framework đã được áp dụng?
- Thành phần đó được áp dụng ở service nào?
- Thay đổi nằm ở file/class/method nào?
- Giai đoạn phát triển nào đã thực hiện thay đổi đó?
- Trước và sau tích hợp khác nhau như thế nào?

---

## 1. Bối cảnh truy vết

Dự án ecommerce ban đầu đã có sẵn luồng order → inventory → payment, nhưng phần tích hợp hạ tầng và xử lý giữ tài nguyên còn mang tính cài đặt riêng lẻ:

- Kafka consumer/producer configuration được cấu hình thủ công ở từng service.
- Inventory check trừ stock trực tiếp trong Kafka Streams state store.
- Payment fail bù kho bằng `InventoryAdjustmentEvent`.
- Chưa có command chuẩn để xác nhận hoặc giải phóng reservation.

Sau khi tích hợp framework LSF, một phần logic hạ tầng và reservation được thay bằng starter/contract của framework.

---

## 2. Bảng truy vết tích hợp

| Giai đoạn | Module framework / Kiểu thay đổi | Service áp dụng | File / Class / Method chính | Trước khi tích hợp | Sau khi tích hợp |
|---|---|---|---|---|---|
| GĐ1 | `lsf-kafka-starter` | `order-service` | `pom.xml`, `OrderService.java` | Kafka config mang tính thủ công, listener phụ thuộc config riêng | Dùng starter để chuẩn hóa Kafka listener/producer |
| GĐ1 | `lsf-kafka-starter` | `payment-service` | `pom.xml`, `PaymentService.java` | Có consumer config riêng cho payment | Chuyển sang listener dùng cấu hình từ starter |
| GĐ1 | `lsf-contracts` | `order-service` + `inventory-service` | `OrderService.publishConfirmCommands`, `OrderService.publishReleaseCommands`, `InventoryReservationCommandListener` | Không có command chuẩn hóa cho confirm/release reservation | Dùng `ConfirmReservationCommand`, `ReleaseReservationCommand` |
| GĐ1 | `lsf-quota-streams-starter` | `inventory-service` | `pom.xml`, `InventoryQuotaService.java` | Không có quota framework để giữ tài nguyên | Dùng `QuotaService` từ framework để reserve/confirm/release |
| GĐ1 | Refactor inventory hold flow | `inventory-service` | `InventoryTopology.buildTopology(...)` | Check inventory pass thì trừ stock trực tiếp trong state store | Check inventory gọi quota reserve, không trừ stock trực tiếp ở bước đầu |
| GĐ1 | Refactor order lifecycle | `order-service` | `handlePaymentSuccess(...)`, `handlePaymentFailure(...)` | Payment success chỉ đổi trạng thái; payment fail phát `InventoryAdjustmentEvent(+qty)` | Payment success phát confirm command; payment fail phát release command |
| GĐ1 | SAGA stabilization | `order-service` | `handleInventoryCheckResult(...)` | Có thể kết luận fail sớm khi một item fail | Đợi đủ kết quả inventory của toàn bộ item rồi mới kết luận |
| GĐ1 | Kafka topics cho reservation lifecycle | `order-service` + `inventory-service` | `KafkaConfig.java`, `KafkaTopicConfig.java` | Chưa có topic riêng cho confirm/release reservation | Thêm `inventory-reservation-confirm-topic`, `inventory-reservation-release-topic` |
| GĐ1 | Inventory-side reservation command handling | `inventory-service` | `InventoryReservationCommandListener.java` | Không có listener xử lý confirm/release reservation | Listener consume command và gọi quota confirm/release |
| GĐ1 | Demo/local test support | `order-service`, `inventory-service`, `payment-service` | `SecurityConfig`, `OrderController`, `PaymentService`, `OrderService` | Auth và payment dùng flow gốc | Tạm mở auth/local test, mock payment result, fallback product cache để demo end-to-end |

---

## 3. Thành phần framework đã được dùng trực tiếp

### 3.1 `lsf-kafka-starter`
Áp dụng để chuẩn hóa cấu hình Kafka cho các service dùng `@KafkaListener` và `KafkaTemplate`, cụ thể ở:

- `order-service`
- `payment-service`
- phần reservation command listener trong `inventory-service`

### 3.2 `lsf-contracts`
Áp dụng để dùng command chuẩn cho lifecycle reservation:

- `ConfirmReservationCommand`
- `ReleaseReservationCommand`

Các command này được dùng để tách rõ:

- **Order Service**: phát command theo business lifecycle
- **Inventory Service**: là nơi sở hữu tài nguyên và xử lý confirm/release thật sự

### 3.3 `lsf-quota-streams-starter`
Áp dụng trong `inventory-service` để đưa quota framework vào luồng inventory hold thực tế.

Thành phần sử dụng trực tiếp:

- `QuotaService`
- `QuotaRequest`
- `QuotaResult`
- `QuotaDecision`

Thông qua lớp adapter của consumer project:

- `InventoryQuotaService`

---

## 4. Ranh giới giữa framework và consumer project

### 4.1 Phần thuộc framework
- Cơ chế quota API và implementation phía starter.
- Kafka starter để chuẩn hóa Kafka consumer/producer setup.
- Contract command chuẩn hóa reservation lifecycle.

### 4.2 Phần thuộc consumer project (ecommerce)
- Business mapping từ order sang `workflowId`, `resourceId`, `quotaKey`, `requestId`.
- Kafka Streams topology của inventory.
- Saga/orchestration logic của order.
- Cấu trúc domain model order, inventory, payment.

### 4.3 Ý nghĩa
Điểm này rất quan trọng khi trình bày: framework **không thay toàn bộ hệ thống ecommerce**, mà thay thế các phần hạ tầng và cơ chế dùng chung, còn domain flow vẫn nằm ở consumer project.

---

## 5. Những thay đổi mang tính demo/local test

Để phục vụ tích hợp và demo end-to-end, một số thay đổi tạm thời đã được thêm vào consumer project:

- `OrderController` dùng `test-user` khi tạo order.
- `SecurityConfig` ở `order-service` và `inventory-service` mở `permitAll()` cho local test.
- `PaymentService` dùng rule mock để cố định case success/fail.
- `OrderService` có fallback product cache khi Redis chưa có dữ liệu sản phẩm.
- `InventoryReservationCommandListener` có normalize payload để xử lý dữ liệu Kafka ở nhiều dạng trong quá trình tích hợp.

Các mục này nên được coi là **demo-support code**, không phải phần lõi của framework.

---

## 6. Cách dùng tài liệu này khi bảo vệ / giải thích

Khi được hỏi:

### “Framework đã được áp vào đâu?”
Trả lời bằng Bảng truy vết ở Mục 2.

### “Đoạn nào là code của framework, đoạn nào là code của project?”
Trả lời bằng Mục 4.

### “Giai đoạn nào đã làm những gì?”
Trả lời bằng cột **Giai đoạn** trong bảng truy vết, kết hợp với Git branch/commit history.

### “Có giữ code cũ không?”
Trả lời:
- Các phần hạ tầng mà framework đã cover thì đã được thay bằng starter/contract/quota flow mới.
- Những phần còn giữ là boundary hiện tại của framework, ví dụ Kafka Streams topology và business orchestration.

---

## 7. Gợi ý sử dụng cùng Git history

Để tài liệu này phát huy tối đa giá trị, nên dùng cùng:

- branch tích hợp riêng, ví dụ `feat/integrate-lsf-framework`
- commit tách theo bước
- tag theo mốc tích hợp nếu cần

Nhờ vậy bạn có thể chứng minh được cả:

- **mặt kiến trúc**: framework áp vào đâu
- **mặt tiến trình phát triển**: thay đổi được thực hiện ở giai đoạn nào

