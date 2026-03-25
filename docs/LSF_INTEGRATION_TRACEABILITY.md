# LSF Integration Traceability (cập nhật đến Outbox Phase 5.2)

Tài liệu này dùng để truy vết việc áp dụng framework LSF vào dự án ecommerce, bao gồm cả phase tích hợp outbox và phần mở rộng phase 5.2. Nó giúp trả lời các câu hỏi như:

- Thành phần nào của framework đã được áp dụng?
- Thành phần đó được áp dụng ở service nào?
- Thay đổi nằm ở file/class/method nào?
- Giai đoạn phát triển nào đã thực hiện thay đổi đó?
- Đoạn nào là phần framework, đoạn nào là phần consumer project?
- Sau khi thêm outbox phase 5.2 thì contract/event flow đã thay đổi ra sao?

---

## 1. Bối cảnh truy vết

Dự án ecommerce ban đầu đã có luồng order → inventory → payment và đã dùng Kafka để giao tiếp bất đồng bộ. Tuy nhiên, các phần hạ tầng và reservation còn mang tính cài đặt riêng:

- Kafka config mang tính thủ công theo từng service
- inventory hold bằng cách trừ stock trực tiếp
- payment fail bù kho bằng compensation event
- chưa có command chuẩn cho reservation lifecycle
- publish event sau cập nhật DB còn theo kiểu direct send
- chưa có outbox cho status event
- chưa có chiến lược tách biệt contract topic cũ và topic mới

Sau các phase tích hợp, một phần logic hạ tầng, reservation và event publishing đã được thay bằng module của framework LSF.

---

## 2. Bảng truy vết tích hợp

| Giai đoạn | Module framework / Kiểu thay đổi | Service áp dụng | File / Class / Method chính | Trước khi tích hợp | Sau khi tích hợp |
|---|---|---|---|---|---|
| GĐ1 | `lsf-kafka-starter` | `order-service` | `pom.xml`, `OrderService.java`, `application.properties` | Kafka config mang tính thủ công, listener phụ thuộc config riêng | Dùng starter để chuẩn hóa Kafka listener/producer |
| GĐ1 | `lsf-kafka-starter` | `payment-service` | `pom.xml`, `PaymentService.java`, `application.properties` | Có consumer config riêng cho payment | Chuyển sang listener dùng cấu hình từ starter |
| GĐ1 | `lsf-contracts` | `order-service` + `inventory-service` | `OrderService.publishConfirmCommands`, `OrderService.publishReleaseCommands`, `InventoryReservationCommandListener` | Không có command chuẩn hóa cho confirm/release reservation | Dùng `ConfirmReservationCommand`, `ReleaseReservationCommand` |
| GĐ1 | `lsf-quota-streams-starter` | `inventory-service` | `pom.xml`, `InventoryQuotaService.java` | Không có quota framework để giữ tài nguyên | Dùng `QuotaService` từ framework để reserve/confirm/release |
| GĐ1 | Refactor inventory hold flow | `inventory-service` | `InventoryTopology.buildTopology(...)` | Check inventory pass thì trừ stock trực tiếp trong state store | Check inventory gọi quota reserve, không trừ stock trực tiếp ở bước đầu |
| GĐ1 | Refactor order lifecycle | `order-service` | `handlePaymentSuccess(...)`, `handlePaymentFailure(...)`, `handleOrderFailure(...)` | Payment success chỉ đổi trạng thái; payment fail phát `InventoryAdjustmentEvent(+qty)` | Payment success phát confirm command; payment fail phát release command |
| GĐ1 | SAGA stabilization | `order-service` | `handleInventoryCheckResult(...)` | Có thể kết luận fail sớm khi một item fail | Đợi đủ kết quả inventory của toàn bộ item rồi mới kết luận |
| GĐ1 | Kafka topics cho reservation lifecycle | `order-service` + `inventory-service` | `KafkaConfig.java`, `KafkaTopicConfig.java` | Chưa có topic riêng cho confirm/release reservation | Thêm `inventory-reservation-confirm-topic`, `inventory-reservation-release-topic` |
| GĐ1 | Inventory-side reservation command handling | `inventory-service` | `InventoryReservationCommandListener.java` | Không có listener xử lý confirm/release reservation | Listener consume command và gọi quota confirm/release |
| GĐ1.5 | Cleanup / traceability / docs | toàn hệ thống | README, traceability, before/after, code comments | Khó trả lời ranh giới giữa framework và consumer | Có tài liệu truy vết và comment tại các điểm framework hóa chính |
| GĐ2 / Phase 5 | `lsf-outbox-mysql-starter` | `order-service` | `pom.xml`, `application.properties`, migration outbox | Update DB xong gửi Kafka trực tiếp | Append EventEnvelope vào outbox để publisher gửi nền |
| GĐ2 / Phase 5 | Outbox schema | `order-service` | migration `Vx__create_lsf_outbox.sql` | Không có bảng outbox | Có bảng `lsf_outbox` phục vụ reliable publishing |
| GĐ2 / Phase 5 | Event envelope factory / mapping | `order-service` | helper/factory tạo `EventEnvelope` | Status event publish trực tiếp dạng raw DTO | Status event được bọc trong `EventEnvelope` trước khi append outbox |
| GĐ2 / Phase 5 | Outbox-based status publishing | `order-service` | `OrderService` các chỗ đổi status order | `kafkaTemplate.send("order-status-topic", ...)` | `outboxWriter.append(..., envelopeTopic, ...)` |
| GĐ2.2 / Phase 5.2 | Contract/topic evolution | `order-service` | `KafkaConfig.java`, topic mapping | Dùng chung `order-status-topic` cho raw status event và muốn tái dùng cho outbox | Tách sang `order-status-envelope-topic` để tránh conflict schema registry |
| GĐ2.2 / Phase 5.2 | Envelope consumer adaptation | `order-service` | `OrderStatusJoiner.java` | Đọc `OrderStatusEvent` trực tiếp từ topic cũ | Đọc `EventEnvelope`, unwrap `payload` thành `OrderStatusEvent` rồi join |
| GĐ2.2 / Phase 5.2 | Ownership of migration versioning | `order-service` + framework boundary | migration outbox, starter packaging | Dễ hiểu nhầm starter sở hữu luôn version migration của consumer | Chốt rõ migration version do consumer project quản lý, starter chỉ cung cấp runtime/outbox mechanism |
| GĐ2.2 / Phase 5.2 | Event contract boundary | `order-service` | status topic strategy | contract cũ và contract mới có nguy cơ trộn trên cùng topic | topic legacy và topic envelope được tách riêng |

---

## 3. Thành phần framework đã được dùng trực tiếp

### 3.1 `lsf-kafka-starter`
Áp dụng để chuẩn hóa Kafka setup cho:
- `order-service`
- `payment-service`
- các listener reservation command ở inventory-side

### 3.2 `lsf-contracts`
Áp dụng để dùng contract chuẩn cho reservation lifecycle:
- `ConfirmReservationCommand`
- `ReleaseReservationCommand`

Ở phase outbox, contract chung còn được mở rộng sang:
- `EventEnvelope`

### 3.3 `lsf-quota-streams-starter`
Áp dụng trong `inventory-service` cho:
- `QuotaService`
- `QuotaRequest`
- `QuotaResult`
- `QuotaDecision`

Thông qua lớp adapter consumer:
- `InventoryQuotaService`

### 3.4 `lsf-outbox-mysql-starter`
Áp dụng trong `order-service` cho:
- `OutboxWriter`
- outbox publisher runtime
- cơ chế lease/retry/polling
- reliable publish từ DB outbox ra Kafka

---

## 4. Ranh giới giữa framework và consumer project

### 4.1 Phần thuộc framework
- Kafka starter
- Quota API và implementation
- Reservation command contracts
- `EventEnvelope`
- Outbox writer/publisher runtime
- cơ chế retry/lease/batch gửi outbox

### 4.2 Phần thuộc consumer project
- mapping từ order domain sang `workflowId`, `resourceId`, `quotaKey`, `requestId`
- Kafka Streams topology của `inventory-service`
- orchestration trong `OrderService`
- `OrderStatusJoiner`
- migration version cụ thể của `order-service`
- quyết định dùng topic mới `order-status-envelope-topic`
- cách unwrap payload sang `OrderStatusEvent`
- quyết định biên giới giữa topic legacy và topic envelope

### 4.3 Ý nghĩa
Framework không thay toàn bộ domain của ecommerce. Nó thay thế các phần có tính tái sử dụng cao ở tầng:
- Kafka integration
- reservation lifecycle
- outbox/reliable event publishing

Còn business flow, domain model và orchestration vẫn thuộc consumer project.

---

## 5. Những thay đổi mang tính demo/local test

Tùy nhánh cleanup/demo, có thể còn hoặc đã loại bỏ:
- `test-user`
- `permitAll()` tạm
- mock payment rule
- fallback product cache
- normalize payload ở listener inventory

Các mục này nên được coi là:
- code hỗ trợ local demo/integration
- không phải lõi framework

---

## 6. Cách dùng tài liệu này khi bảo vệ / giải thích

### “Framework đã được áp vào đâu?”
Trả lời bằng Bảng truy vết ở Mục 2.

### “Đoạn nào là code của framework, đoạn nào là code của project?”
Trả lời bằng Mục 4.

### “Giai đoạn nào đã làm những gì?”
Trả lời bằng cột **Giai đoạn** trong bảng và đối chiếu với commit history/branch riêng:
- `feat/integrate-lsf-framework`
- `feat/integrate-lsf-framework-cleanup`
- `feat/integrate-lsf-outbox`

### “Phase 5.2 thay đổi gì so với phase outbox ban đầu?”
Trả lời:
- phase outbox ban đầu thêm outbox writer và outbox table
- phase 5.2 xử lý contract evolution
- tách topic envelope riêng
- sửa consumer để unwrap `EventEnvelope`
- chốt rằng migration version thuộc về consumer project

---

## 7. Gợi ý dùng cùng Git history

Để tài liệu này phát huy tối đa giá trị, nên dùng cùng:
- branch tích hợp quota/kafka
- branch cleanup
- branch outbox
- commit tách theo bước
- compare giữa branch gốc và branch tích hợp

Nhờ vậy bạn có thể chứng minh được cả:
- mặt kiến trúc: framework áp ở đâu
- mặt tiến trình phát triển: thay đổi được thực hiện ở giai đoạn nào
