# LSF Integration Traceability (cập nhật đến Outbox Phase 7)

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


---

## 8. Bổ sung truy vết sau Phase 5.2 — Phase 7 (Outbox Admin / Availability / Observability)

Phần này **được bổ sung thêm** dựa trên nội dung trong `docs_change` để nối tiếp tài liệu truy vết hiện có. Mục tiêu là làm rõ: sau khi đã tích hợp quota, contracts, Kafka starter và outbox/envelope flow, hệ thống còn được mở rộng như thế nào ở lớp vận hành, API semantics và observability.

---

## 9. Bảng truy vết bổ sung cho Phase 7

| Giai đoạn | Module framework / Kiểu thay đổi | Service áp dụng | File / Class / Endpoint / Thành phần chính | Trước khi bổ sung | Sau khi bổ sung |
|---|---|---|---|---|---|
| GĐ3 / Phase 7.1 | `lsf-outbox-admin-starter` | `order-service` | cấu hình starter, admin controller/base path `/admin/outbox` | outbox chủ yếu kiểm qua DB/log | có API admin để list/filter/detail/mark-failed/requeue |
| GĐ3 / Phase 7.1 | Outbox operational evidence | `order-service` | `/admin/outbox`, `/admin/outbox/{id}`, `/admin/outbox/event/{eventId}` | khó chứng minh outbox ở mức vận hành | xem được `NEW / SENT / FAILED / RETRY`, `retryCount`, `lastError`, `nextAttemptAt`, `sentAt` |
| GĐ3 / Phase 7.1 | Retry / recovery operations | `order-service` | `POST /admin/outbox/mark-failed/event/{eventId}`, `POST /admin/outbox/requeue/event/{eventId}` | recovery chủ yếu bằng thao tác thủ công hoặc kiểm tra nền | có thao tác admin để đánh dấu failed và requeue/retry |
| GĐ3 / Phase 7.2 | Quota read/query mở rộng | `inventory-service` + quota framework boundary | `QuotaQueryFacade`, `QuotaSnapshot` | quota chủ yếu được dùng cho write path (reserve/confirm/release) | có read path để truy vấn snapshot và diễn giải availability |
| GĐ3 / Phase 7.2 | Availability API | `inventory-service` | `GET /api/inventory/{sku}/availability` | endpoint cũ dễ bị hiểu như tồn có thể bán | có endpoint riêng cho `availableStock` |
| GĐ3 / Phase 7.2 | Inventory semantics split | `inventory-service` + UI/admin layer | DTO availability, UI fields | stock vật lý và stock khả dụng dễ bị trộn | tách rõ `physicalStock`, `quotaUsed`, `availableStock` |
| GĐ3 / Phase 7.2 | Backend stock guard | `inventory-service` | `POST /api/inventory/adjust` + business guard | admin có thể vô tình giảm stock gây mâu thuẫn với quota used | chặn `newPhysicalStock < quotaUsed`, trả `409 Conflict` |
| GĐ3 / Phase 7.3 | `lsf-observability-starter` | `order-service`, `inventory-service`, `payment-service` | actuator + metrics wiring | bằng chứng chủ yếu dựa vào log/query tay | có metrics chuẩn hóa cho quota/outbox và endpoint quan sát |
| GĐ3 / Phase 7.3 | Actuator / Prometheus exposure | `order-service`, `inventory-service`, `payment-service` | `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus` | thiếu lớp quan sát chuẩn cho service | có health/metrics/prometheus phục vụ scrape và evidence |
| GĐ3 / Phase 7.3 | Quota metrics | `inventory-service` | `lsf.quota.reserve`, `lsf.quota.confirm`, `lsf.quota.release` | reserve/confirm/release nhìn qua log là chính | có counter/tags để chứng minh accepted/rejected/ok |
| GĐ3 / Phase 7.3 | Outbox metrics | `order-service` | `lsf.outbox.append`, `lsf.outbox.sent`, `lsf.outbox.retry`, `lsf.outbox.fail`, `lsf.outbox.pending` | append/send/retry/fail khó đối chiếu tổng thể | có số liệu quan sát cho append/sent/retry/fail/pending |
| GĐ3 / Phase 7.3 | Dashboard / scrape | hạ tầng demo | Prometheus targets, Grafana panels quota/outbox | người xem phải đọc log hoặc query DB | có dashboard tối thiểu để nhìn trực tiếp flow quota/outbox |

---

## 10. Thành phần framework đã được dùng trực tiếp (bổ sung)

### 10.1. `lsf-outbox-admin-starter`
Áp dụng trong `order-service` để mở rộng lớp vận hành cho outbox, bao gồm:
- xem row outbox
- filter theo điều kiện
- xem detail theo `id` hoặc `eventId`
- thao tác `mark-failed`
- thao tác `requeue/retry`

**Ý nghĩa truy vết**
Trước Phase 7, outbox đã được tích hợp ở runtime path. Sau Phase 7, outbox có thêm operation path. Điều này cho thấy framework không chỉ cung cấp cơ chế ghi và publish event, mà còn hỗ trợ khả năng vận hành/kiểm tra khi cần demo hoặc xử lý sự cố.

### 10.2. Quota read/query extension
Ngoài write path của quota framework (`reserve`, `confirm`, `release`), Phase 7 bổ sung thêm read/query path thông qua:
- `QuotaQueryFacade`
- `QuotaSnapshot`

**Ý nghĩa truy vết**
Đây là bước mở rộng từ “quota như cơ chế giữ tài nguyên” sang “quota như nguồn dữ liệu để giải thích khả năng bán được của tồn kho”.

### 10.3. `lsf-observability-starter`
Áp dụng để chuẩn hóa lớp đo đếm/quan sát cho các service chính. Các nhóm metric quan trọng sau khi bổ sung gồm:

**Quota**
- `lsf.quota.reserve`
- `lsf.quota.confirm`
- `lsf.quota.release`

**Outbox**
- `lsf.outbox.append`
- `lsf.outbox.sent`
- `lsf.outbox.retry`
- `lsf.outbox.fail`
- `lsf.outbox.pending`

Ngoài ra có nhóm `lsf.event.*` đã được preregister, nhưng hiện chưa tăng số đếm runtime vì flow hiện tại chưa đi qua `LsfDispatcher` / `lsf-eventing` thực tế.

**Ý nghĩa truy vết**
Metrics này là bằng chứng rằng observability đã trở thành một phần của integration outcome, chứ không chỉ là tài liệu mô tả bên ngoài.

---

## 11. Ranh giới framework và consumer project sau Phase 7 (bổ sung)

### 11.1. Phần nghiêng về framework nhiều hơn
- `lsf-outbox-admin-starter`
- `lsf-observability-starter`
- quota query/read API nền tảng (`QuotaQueryFacade`, `QuotaSnapshot`)
- naming và metric lifecycle chuẩn cho quota/outbox

### 11.2. Phần vẫn thuộc consumer project
- quyết định expose `/admin/outbox` ở đâu và dùng thế nào trong demo
- thiết kế endpoint `GET /api/inventory/{sku}/availability`
- DTO availability cụ thể và mapping field cho UI
- rule nghiệp vụ chặn `newPhysicalStock < quotaUsed`
- cách trình bày admin UI giữa physical stock và available stock
- cấu hình Prometheus scrape/Grafana dashboard cụ thể cho môi trường demo
- checklist/regression/demo script/evidence organization

### 11.3. Ý nghĩa
Sau Phase 7, ranh giới vẫn nhất quán với các phase trước:
- framework cung cấp concern tái sử dụng cao
- consumer project quyết định cách áp concern đó vào semantics nghiệp vụ, API và demo flow cụ thể

Framework không thay business ownership của inventory hay order. Nó cung cấp nền cho:
- reservation lifecycle
- reliable publishing
- outbox operations
- observability

Còn consumer project vẫn chịu trách nhiệm cho:
- endpoint semantics
- UI/admin behavior
- demo/evidence orchestration

---

## 12. Truy vết theo câu hỏi thường gặp khi bảo vệ (bổ sung)

### “Sau phase outbox thì nhóm còn làm gì nữa?”
Trả lời:
- thêm lớp vận hành cho outbox (`lsf-outbox-admin-starter`)
- thêm lớp semantics cho inventory availability
- thêm lớp observability qua actuator/metrics/prometheus/grafana

### “Điểm nào cho thấy framework không chỉ có ở code mà còn hoạt động được?”
Trả lời bằng ba loại evidence:
1. API vận hành: `/admin/outbox/**`
2. API semantics: `/api/inventory/{sku}/availability`
3. metrics/dashboard: quota và outbox panels

### “Phase 7 thuộc framework hay thuộc project tích hợp?”
Trả lời:
- một phần là framework capability mở rộng (`outbox-admin`, `observability`, quota query)
- một phần là integration outcome ở consumer project (endpoint, guard, dashboard, evidence)

### “Vì sao availability lại quan trọng trong tài liệu truy vết?”
Vì sau khi dùng quota reservation, nếu vẫn chỉ hiển thị stock vật lý thì rất khó giải thích lợi ích thật sự của framework. Availability là nơi kết quả của reservation lifecycle được phản ánh thành dữ liệu nghiệp vụ mà người dùng và hội đồng nhìn thấy được.

---

## 13. Gợi ý dùng cùng API/evidence sau Phase 7

Để tài liệu truy vết này phát huy tối đa giá trị ở giai đoạn cuối, nên dùng kèm:
- `phase7-summary.md`
- `api-overview.md`
- `regression-checklist.md`
- `demo-script.md`
- evidence screenshots cho:
  - outbox admin
  - availability
  - stock guard
  - quota metrics
  - outbox metrics
  - dashboard

Nhờ vậy có thể chứng minh được trọn vẹn cả ba lớp:
1. **kiến trúc** — framework áp vào đâu
2. **tiến trình** — thay đổi được thực hiện qua phase nào
3. **vận hành/evidence** — sau khi áp, hệ thống quan sát và giải thích được ra sao
