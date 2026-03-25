# Tích hợp LSF Framework vào Ecommerce Backend (cập nhật đến Phase 5.2)

## 1. Mục tiêu tài liệu

Tài liệu này tổng hợp toàn bộ các thay đổi khi dự án ecommerce được tích hợp framework **LSF** theo nhiều giai đoạn phát triển, bao gồm cả phần mở rộng **Outbox Phase 5.2**. Mục tiêu là giúp trả lời rõ các câu hỏi:

- Thành phần nào của framework đã được áp dụng?
- Thành phần đó được áp dụng ở service nào?
- Giai đoạn nào đã thực hiện thay đổi đó?
- Đoạn nào là phần framework được tái sử dụng?
- Đoạn nào là phần mapping/orchestration của project consumer?
- Sau khi tích hợp outbox phase 5.2 thì hệ thống thay đổi thêm những gì?

Tài liệu này dùng tốt nhất khi đi cùng:
- branch tích hợp riêng, ví dụ `feat/integrate-lsf-framework`
- branch cleanup, ví dụ `feat/integrate-lsf-framework-cleanup`
- branch outbox, ví dụ `feat/integrate-lsf-outbox`
- commit history được tách theo từng bước
- file `LSF_INTEGRATION_TRACEABILITY_OUTBOX_PHASE52.md`
- file `LSF_INTEGRATION_BEFORE_AFTER_OUTBOX_PHASE52.md`

---

## 2. Tổng quan các giai đoạn tích hợp

### Giai đoạn 1 — Tích hợp Kafka starter + Quota + Contracts

Đây là giai đoạn đưa các module cốt lõi của framework vào flow đặt hàng thật trong ecommerce.

**Module framework đã dùng**
- `lsf-kafka-starter`
- `lsf-contracts`
- `lsf-quota-streams-starter`

**Service áp dụng**
- `order-service`
- `inventory-service`
- `payment-service`

**Mục tiêu**
- thay cấu hình Kafka thủ công bằng starter dùng lại được
- thay logic giữ hàng kiểu trừ stock trực tiếp bằng quota reservation
- chuẩn hóa lifecycle reservation bằng command dùng chung

**Kết quả chính**

Flow nghiệp vụ được chuyển từ:

```text
check -> deduct stock -> compensate
```

sang:

```text
reserve -> confirm / release
```

### Giai đoạn 1.5 — Cleanup và chuẩn hóa nhánh tích hợp

Đây là giai đoạn dọn code sau khi integration đã chạy pass.

**Mục tiêu**
- loại bỏ hoặc đánh dấu rõ code demo/local-test
- thêm comment ở các điểm framework hóa chính
- chuẩn hóa lại README, traceability, before/after
- giữ ranh giới rõ giữa framework code và consumer code

### Giai đoạn 2 / Phase 5 — Tích hợp Outbox vào `order-service`

Đây là giai đoạn mở rộng framework sang bài toán **reliable event publishing**.

**Module framework đã dùng**
- `lsf-outbox-mysql-starter`

**Service áp dụng**
- `order-service`

**Mục tiêu**
Giải quyết bài toán dual write ở các đoạn:
- update DB order status
- rồi publish Kafka event

Thay vì:

```text
save DB -> kafkaTemplate.send(...)
```

hệ thống chuyển sang:

```text
save DB -> append outbox row -> publisher gửi Kafka
```

### Giai đoạn 2.2 / Phase 5.2 — Mở rộng outbox cho order workflow events

Đây là phần mở rộng sau khi outbox chạy được ở mức cơ bản.

**Mục tiêu**
- chuẩn hóa việc publish status event theo `EventEnvelope`
- tránh xung đột Schema Registry với contract cũ
- sửa consumer để đọc envelope thay vì raw domain event
- làm rõ ranh giới giữa topic legacy và topic framework-based

**Kết quả chính**
- dùng topic mới `order-status-envelope-topic`
- `OrderStatusJoiner` đọc `EventEnvelope` và unwrap `payload`
- migration outbox do **consumer project** quản lý
- starter outbox không còn được coi là nơi sở hữu version Flyway của consumer

---

## 3. Các module framework đã áp dụng và vị trí áp dụng

### 3.1 `lsf-kafka-starter`

**Mục đích**
- chuẩn hóa Kafka producer/consumer setup
- gom cấu hình dùng chung
- giảm config thủ công theo từng service

**Được áp vào**
- `order-service`
- `payment-service`
- listener command trong `inventory-service`

**Vai trò trong ecommerce**
- chuẩn hóa `KafkaTemplate`
- chuẩn hóa `@KafkaListener`
- giảm phụ thuộc vào custom consumer config từng service

> Lưu ý: `inventory-service` vẫn giữ phần Kafka Streams topology riêng (`KafkaStreamsConfig`, `SerdeConfig`, `InventoryTopology`) vì `lsf-kafka-starter` hiện không thay thế Kafka Streams topology.

### 3.2 `lsf-contracts`

**Mục đích**
- chia sẻ command/event dùng chung giữa các service

**Contract đang dùng**
- `ConfirmReservationCommand`
- `ReleaseReservationCommand`
- `EventEnvelope` (ở phase outbox)

**Vai trò trong ecommerce**
- `order-service` phát command xác nhận / giải phóng reservation
- `inventory-service` consume command để confirm/release quota
- `order-service` dùng `EventEnvelope` để ghi outbox event

### 3.3 `lsf-quota-streams-starter`

**Mục đích**
- cung cấp core quota logic cho bài toán giữ tài nguyên có giới hạn

**Được áp vào**
- `inventory-service`

**Chức năng đang dùng**
- `reserve(...)`
- `confirm(...)`
- `release(...)`

**Vai trò trong ecommerce**
- thay cách trừ stock trực tiếp bằng reservation lifecycle
- dùng Redis làm quota state
- tách rõ stock vật lý với stock khả dụng theo quota

### 3.4 `lsf-outbox-mysql-starter`

**Mục đích**
- giải quyết việc publish event tin cậy hơn trong các đoạn DB + Kafka
- tách việc ghi event khỏi việc gửi event
- cho phép publisher nền xử lý retry/lease/batch publishing

**Được áp vào**
- `order-service`

**Vai trò trong ecommerce**
- thay direct send của status event bằng cơ chế outbox append
- chuẩn hóa status event theo `EventEnvelope`
- tạo nền cho reliable event publishing và observability sau này

---

## 4. Ecommerce đã thay đổi những gì sau khi tích hợp framework

### 4.1 Thay đổi ở `inventory-service`

**Giai đoạn 1**
- thêm dependency:
  - `lsf-quota-streams-starter`
  - `lsf-contracts`
  - `spring-boot-starter-data-redis`
  - `spring-jdbc`
- thêm config quota:
  - `lsf.quota.enabled=true`
  - `lsf.quota.store=REDIS`
  - `lsf.quota.default-hold-seconds=120`
- tạo `InventoryQuotaService`
- sửa `InventoryTopology`:
  - trước đây pass thì trừ stock trực tiếp
  - sau đó gọi `reserve(...)` thông qua framework quota
- thêm `InventoryReservationCommandListener`
- thêm topics:
  - `inventory-reservation-confirm-topic`
  - `inventory-reservation-release-topic`

**Mapping hiện tại**
- `quotaKey = shopA:flashsale_sku:<sku>`
- `requestId = <orderNumber>:<sku>`

**Ý nghĩa**
`inventory-service` từ chỗ thao tác trực tiếp trên stock ở bước đầu đã chuyển thành service sở hữu tài nguyên và điều khiển reservation lifecycle bằng framework.

### 4.2 Thay đổi ở `order-service`

**Giai đoạn 1**
- thêm dependency:
  - `lsf-kafka-starter`
  - `lsf-contracts`
- thêm topics:
  - `inventory-reservation-confirm-topic`
  - `inventory-reservation-release-topic`
- sửa saga inventory để đợi đủ kết quả của toàn bộ item trước khi kết luận
- khi payment success:
  - đổi status
  - phát `ConfirmReservationCommand`
- khi payment fail hoặc inventory fail:
  - đổi status
  - phát `ReleaseReservationCommand`
- thay compensation kiểu `InventoryAdjustmentEvent(+qty)` bằng quota release

**Giai đoạn 2 / Phase 5**
- thêm dependency:
  - `lsf-outbox-mysql-starter`
- thêm bảng `lsf_outbox` qua migration riêng của consumer project
- thêm helper/factory để tạo `EventEnvelope`
- thay direct publish của status event bằng `OutboxWriter.append(...)`

**Giai đoạn 2.2 / Phase 5.2**
- không publish `EventEnvelope` vào `order-status-topic` cũ nữa
- chuyển sang topic mới:
  - `order-status-envelope-topic`
- sửa `OrderStatusJoiner` để consume envelope topic
- unwrap `payload` thành `OrderStatusEvent`
- chuẩn hóa quyết định: **migration outbox do consumer project giữ**
- xử lý rõ boundary:
  - framework cung cấp runtime outbox
  - consumer project quyết định version Flyway, topic và mapping event

**Vì sao dùng topic mới**
Trước khi tích hợp outbox, `order-status-topic` đã có contract cũ là `OrderStatusEvent`.
Khi dùng outbox, payload gửi đi là `EventEnvelope`, tức là schema thay đổi hoàn toàn. Để tránh xung đột Schema Registry và giữ rõ ràng contract cũ/mới, phase 5.2 sử dụng topic mới cho envelope thay vì tái dùng `order-status-topic`.

**Ý nghĩa**
`order-service` trở thành nơi:
- giữ business orchestration
- ánh xạ domain event sang framework contract
- ghi outbox thay vì gửi Kafka trực tiếp cho status event
- quản lý contract evolution giữa topic cũ và topic envelope mới

### 4.3 Thay đổi ở `payment-service`

**Giai đoạn 1**
- thêm dependency:
  - `lsf-kafka-starter`
- bỏ custom consumer config cũ
- listener dùng cấu hình từ starter
- vẫn giữ vai trò phát:
  - `payment-processed-topic`
  - `payment-failed-topic`

**Ý nghĩa**
`payment-service` không đổi business role, nhưng được chuẩn hóa hơn ở tầng Kafka integration.

### 4.4 Thay đổi ở `OrderStatusJoiner`

**Trước outbox**
Joiner đọc:
- `payment-processed-topic`
- `order-status-topic` chứa raw `OrderStatusEvent`

**Sau phase 5**
Joiner được sửa để:
- đọc `order-status-envelope-topic`
- deserialize `EventEnvelope`
- unwrap `payload` thành `OrderStatusEvent`
- rồi join với payment stream như cũ

**Ý nghĩa**
Đây là bằng chứng rõ nhất cho việc phase outbox không chỉ thêm bảng outbox, mà còn thay đổi contract xử lý status event trong hệ thống.

---

## 5. Những thành phần đã viết được dùng ở giai đoạn nào

### Giai đoạn 1 — Kafka + Quota + Contracts

**`inventory-service`**
- `InventoryQuotaService`
- `InventoryReservationCommandListener`
- sửa `InventoryTopology`
- thêm topic confirm/release

**`order-service`**
- sửa `OrderService`:
  - `handleInventoryCheckResult(...)`
  - `handlePaymentSuccess(...)`
  - `handlePaymentFailure(...)`
  - `handleOrderFailure(...)`
  - `publishConfirmCommands(...)`
  - `publishReleaseCommands(...)`
- thêm topic config cho reservation lifecycle

**`payment-service`**
- bỏ custom Kafka consumer config
- listener dùng starter

### Giai đoạn 1.5 — Cleanup / tài liệu hóa

Các thành phần/document được thêm hoặc cập nhật:
- `README_ecommerce_lsf_short.md`
- `README_lsf_ecommerce_integration.md`
- `LSF_INTEGRATION_TRACEABILITY.md`
- `LSF_INTEGRATION_BEFORE_AFTER.md`
- comment ngắn ở các điểm framework hóa chính

### Giai đoạn 2 / Phase 5 — Outbox cơ bản

**`order-service`**
- migration tạo bảng `lsf_outbox`
- helper/factory tạo `EventEnvelope`
- inject `OutboxWriter`
- thay direct status publish bằng `outbox append`

### Giai đoạn 2.2 / Phase 5.2 — Outbox contract evolution

**`order-service`**
- topic mới `order-status-envelope-topic`
- đổi helper append sang envelope topic
- thống nhất `EventEnvelope` cho status publishing
- chốt việc migration outbox thuộc consumer project

**`OrderStatusJoiner`**
- đổi serde / consume type từ `OrderStatusEvent` sang `EventEnvelope`
- unwrap payload trước khi join
- không còn phụ thuộc vào raw status event trên topic cũ trong flow mới

---

## 6. Đoạn nào là phần framework, đoạn nào là phần consumer project

### 6.1 Phần thuộc framework
- Kafka starter để chuẩn hóa Kafka config
- quota API và implementation ở starter quota
- reservation contract:
  - `ConfirmReservationCommand`
  - `ReleaseReservationCommand`
- outbox runtime:
  - `OutboxWriter`
  - outbox publisher
  - outbox processing model
- `EventEnvelope`

### 6.2 Phần thuộc consumer project
- business mapping từ order -> `workflowId`, `resourceId`
- cách xây `quotaKey`, `requestId`
- `InventoryTopology`
- `OrderService` orchestration
- `OrderStatusJoiner`
- migration version cụ thể trong `order-service`
- chọn topic nào dùng contract cũ, topic nào dùng envelope mới
- cách unwrap payload và duy trì tương thích với payment flow

### 6.3 Ý nghĩa
Framework không thay toàn bộ domain của ecommerce.
Framework cung cấp các cơ chế dùng chung ở tầng hạ tầng và lifecycle, còn consumer project chịu trách nhiệm nối các cơ chế đó vào luồng nghiệp vụ cụ thể.

---

## 7. Flow sau khi có thêm Outbox Phase 5.2

### 7.1 Flow reserve / confirm / release
1. Client gửi order
2. `order-service` tạo workflow
3. `inventory-service` reserve bằng quota
4. nếu payment success -> `ConfirmReservationCommand`
5. nếu payment fail / inventory fail -> `ReleaseReservationCommand`

### 7.2 Flow status publishing sau outbox
1. `order-service` cập nhật status trong DB
2. trong cùng transaction, append `EventEnvelope` vào `lsf_outbox`
3. outbox publisher đọc row chưa gửi
4. publish sang `order-status-envelope-topic`
5. `OrderStatusJoiner` consume envelope, unwrap payload, join với payment stream

### 7.3 Flow contract evolution sau phase 5.2
- `order-status-topic` được xem là topic legacy cho raw status event
- `order-status-envelope-topic` là topic mới cho contract framework-based
- consumer mới đọc envelope topic, không ép phá contract cũ

**Ý nghĩa**
Đây là bước mở rộng từ “framework giải quyết quota” sang “framework giải quyết reliable event publishing và quản lý evolution của event contract”.

---

## 8. Kịch bản test/đánh giá nên dùng sau phase 5.2

### Kịch bản 1 — Reserve thành công rồi confirm
- order được nhận
- quota reserve thành công
- payment success
- reservation confirm
- order hoàn thành
- status event được ghi vào outbox và publish ra topic envelope

### Kịch bản 2 — Reserve bị reject
- inventory reserve reject
- order fail
- status fail được ghi qua outbox

### Kịch bản 3 — Payment fail rồi release
- reserve thành công
- payment fail
- release reservation
- status `PAYMENT_FAILED` được ghi qua outbox

### Kịch bản 4 — Kiểm tra dual write
- update status trong DB
- kiểm tra có row trong `lsf_outbox`
- publisher gửi thành công
- row được mark sent / retry theo cấu hình

### Kịch bản 5 — Kiểm tra envelope consumer
- `OrderStatusJoiner` đọc `order-status-envelope-topic`
- unwrap `payload`
- join đúng với `payment-processed-topic`

---

## 9. Những thay đổi mang tính demo/local test

Tùy branch cleanup/demo, có thể còn hoặc đã được loại bỏ:
- `test-user`
- `permitAll()` tạm
- mock payment rule
- fallback product cache
- compatibility payload handling trong listener

Nên phân biệt rõ:
- đây là code phục vụ local demo/integration
- không phải phần lõi của framework

---

## 10. Gợi ý commit message theo từng giai đoạn

### Giai đoạn 1
```text
feat: integrate lsf-kafka-starter into order and payment services
feat: integrate lsf quota and contracts into inventory service
refactor: replace direct inventory deduction with quota reservation flow
feat: add reservation confirm and release commands for order lifecycle
fix: make inventory saga wait for all item results before completing order
```

### Giai đoạn 1.5
```text
docs: add traceability, before-after notes, and framework integration comments
refactor: clean up integration code and remove temporary noise
```

### Giai đoạn 2 / Phase 5
```text
feat: add lsf outbox mysql starter and outbox schema to order service
feat: publish order status changes via lsf outbox envelope
```

### Giai đoạn 2.2 / Phase 5.2
```text
feat: expand lsf outbox integration for order workflow events
refactor: consume order status event envelope in status joiner
docs: update outbox phase 5.2 integration documentation
```

---

## 11. Kết luận

Sau khi bổ sung phase outbox 5.2, dự án ecommerce không chỉ chứng minh được framework LSF ở bài toán quota/reservation, mà còn mở rộng sang bài toán **reliable event publishing và contract evolution**. Điều này làm rõ hơn giá trị tái sử dụng của framework ở ba lớp:

1. **resource lifecycle**
   - reserve
   - confirm
   - release

2. **event lifecycle**
   - append vào outbox
   - publish nền
   - unwrap envelope ở consumer

3. **event contract management**
   - giữ contract cũ ở topic legacy
   - tách contract mới sang topic envelope riêng

Nhờ đó, branch tích hợp của ecommerce trở thành bằng chứng rõ ràng cho việc framework LSF đã được áp dụng vào một project consumer thực tế theo nhiều giai đoạn phát triển, chứ không chỉ dừng ở demo nội bộ.
