# LSF Integration Before vs After (cập nhật đến Outbox Phase 7)

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


---

## 7. Bổ sung cập nhật sau Phase 5.2 — Phase 7 (Outbox Admin / Availability / Observability)

Phần này **được bổ sung thêm** dựa trên các thay đổi tích hợp trong `docs_change`, nhằm nối tiếp tài liệu hiện có mà **không thay đổi nội dung các mục trước**. Nếu các phase trước tập trung vào **framework hóa flow reservation** và **framework hóa reliable event publishing**, thì Phase 7 tập trung vào việc làm cho hệ thống:
- **vận hành được**
- **quan sát được**
- **giải thích/demo được**

Nói cách khác, Phase 7 không thay business flow cốt lõi của ecommerce, mà mở rộng phần “sau tích hợp” theo ba hướng:
1. outbox không chỉ publish được mà còn **admin/operate** được
2. inventory không chỉ có “stock” mà còn có khái niệm **available stock** rõ ràng
3. quota/outbox không chỉ chạy trong log mà còn **đo đếm và hiển thị** được qua metrics/dashboard

### 7.1. Sau Phase 7 nhìn tổng thể hệ thống thay đổi như thế nào?

Nếu ở các phase trước hệ thống chuyển từ:

```text
direct deduction -> reserve / confirm / release
```

và:

```text
direct send -> append to outbox -> publish envelope
```

thì sau Phase 7 hệ thống đi thêm một bước nữa:

```text
framework integration -> operational visibility -> demo evidence
```

Điều này có nghĩa là các cơ chế đã tích hợp không còn chỉ tồn tại ở mức code/runtime, mà đã có thêm:
- API vận hành
- API giải thích dữ liệu khả dụng
- metrics/logs
- dashboard tối thiểu
- checklist và evidence phục vụ demo/bảo vệ

---

## 8. Before vs After bổ sung theo từng nhánh của Phase 7

### 8.1. Outbox Admin

**Trước Phase 7**
- `lsf_outbox` chủ yếu được nhìn như cơ chế kỹ thuật nền
- muốn kiểm tra row outbox thường phải:
  - đọc log
  - query DB trực tiếp
  - suy luận thủ công trạng thái publish
- khó chứng minh rõ với người xem rằng outbox không chỉ “có code” mà còn “vận hành được”

**Sau Phase 7**
- `order-service` tích hợp thêm `lsf-outbox-admin-starter`
- có base path admin để thao tác trực tiếp với outbox:

```http
/admin/outbox
```

- có thể:
  - list row
  - filter theo `status`, `topic`, `from`, `to`
  - xem detail theo `id`
  - xem theo `eventId`
  - `mark-failed`
  - `requeue/retry`
- có thể nhìn rõ các trạng thái như:
  - `NEW`
  - `SENT`
  - `FAILED`
  - `RETRY`
- có thể xem thêm:
  - `retryCount`
  - `lastError`
  - `nextAttemptAt`
  - `sentAt`

**Ý nghĩa**
Outbox được nâng từ “cơ chế publish tin cậy trong nền” lên thành “thành phần có thể kiểm tra, vận hành và giải thích được khi demo”.

### 8.2. Availability / Inventory semantics

**Trước Phase 7**
- sau khi tích hợp quota, hệ thống đã có khái niệm reservation lifecycle
- tuy nhiên ở tầng API/UI vẫn dễ bị hiểu nhầm giữa:
  - stock vật lý
  - stock còn bán được
- endpoint cũ `GET /api/inventory/{sku}` dễ bị dùng như thể đó là “số lượng khả dụng cho khách mua”

**Sau Phase 7**
- bổ sung endpoint mới:

```http
GET /api/inventory/{sku}/availability
```

- giữ nguyên endpoint cũ:

```http
GET /api/inventory/{sku}
```

- ngữ nghĩa được tách rõ:
  - endpoint cũ = **physical stock**
  - endpoint mới = **available stock**
- công thức làm rõ:

```text
availableStock = max(physicalStock - quotaUsed, 0)
```

- response availability có thể hiển thị rõ:
  - `physicalStock`
  - `quotaUsed`
  - `reservedCount`
  - `confirmedCount`
  - `availableStock`
  - `quotaKey`
- customer-facing UI chuyển sang dùng `availableStock`
- admin UI hiển thị tách bạch tồn vật lý và tồn khả dụng
- bổ sung guard backend:
  - không cho giảm `physicalStock` xuống dưới `quotaUsed`
  - trả `409 Conflict` với thông báo rõ ràng

**Ý nghĩa**
Sau Phase 7, inventory không chỉ dừng ở mức “đã dùng quota framework” mà còn được diễn giải rõ ràng thành hai lớp dữ liệu phục vụ hai mục đích khác nhau:
- quản trị / internal
- bán hàng / customer-facing

### 8.3. Observability

**Trước Phase 7**
- bằng chứng tích hợp chủ yếu đến từ:
  - code
  - log runtime
  - DB/query tay
- khó cho hội đồng hoặc người review nhìn nhanh sự khác biệt của quota/outbox flow

**Sau Phase 7**
- bật `Actuator` cho:
  - `order-service`
  - `inventory-service`
  - `payment-service`
- mở các endpoint:

```http
GET /actuator/health
GET /actuator/metrics
GET /actuator/prometheus
```

- tích hợp `lsf-observability-starter`
- chuẩn hóa metrics/logs cho hai nhóm chính:

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

- dựng thêm Prometheus scrape
- dựng Grafana dashboard tối thiểu để nhìn thấy:
  - reserve accepted / rejected
  - confirm / release
  - outbox sent / retry / fail
  - pending gauge

**Ý nghĩa**
Observability giúp biến integration từ thứ “đọc code mới thấy” thành thứ “chạy demo là thấy”. Đây là phần nối tiếp tự nhiên của các phase trước: khi framework đã áp được vào flow thật, bước tiếp theo là phải quan sát được trạng thái vận hành của nó.

---

## 9. Before vs After bổ sung theo service và vai trò

### 9.1. Order Service

**Trước Phase 7**
- đã có outbox append cho status event
- đã có envelope topic riêng
- nhưng thiếu khả năng admin/operate outbox một cách trực tiếp

**Sau Phase 7**
- tích hợp thêm `lsf-outbox-admin-starter`
- expose API vận hành outbox
- trở thành nơi không chỉ điều phối workflow và append event, mà còn là nơi cung cấp bằng chứng vận hành cho reliable publishing

### 9.2. Inventory Service

**Trước Phase 7**
- đã reserve/confirm/release thông qua quota framework
- business ownership của tài nguyên đã rõ hơn
- nhưng lớp API/UI chưa tách đủ rõ “physical” và “available”

**Sau Phase 7**
- có thêm endpoint availability
- có thêm khả năng truy vấn snapshot quota qua `QuotaQueryFacade` / `QuotaSnapshot`
- trở thành nơi vừa sở hữu tài nguyên, vừa cung cấp được cách giải thích rõ ràng cho dữ liệu khả dụng

### 9.3. Payment Service

**Trước Phase 7**
- tham gia flow reservation lifecycle gián tiếp qua payment success / fail
- bằng chứng chủ yếu dựa trên status cuối và log

**Sau Phase 7**
- không đổi business role
- nhưng tác động của payment success / fail được quan sát rõ hơn thông qua:
  - availability sau reserve/release
  - quota metrics confirm/release
  - outbox metrics/status ở order-side

### 9.4. Admin / Demo / Evidence layer

**Trước Phase 7**
- phần demo kỹ thuật còn phụ thuộc nhiều vào:
  - log terminal
  - query DB trực tiếp
  - giải thích miệng

**Sau Phase 7**
- có thể demo theo chuỗi bằng chứng rõ ràng hơn:
  - waiting page / trạng thái order
  - availability theo SKU
  - `/admin/outbox`
  - Grafana dashboard
  - DB/log chỉ dùng làm bằng chứng phụ

---

## 10. Các kịch bản test/evidence được mở rộng sau Phase 7

Ngoài các kịch bản đã nêu ở các mục trước, Phase 7 cho phép chứng minh tốt hơn các case sau:

### 10.1. Happy path đầy đủ
```text
reserve thành công -> payment success -> confirm -> status append vào outbox -> outbox publish thành công
```

Bằng chứng có thể mở:
- waiting page success
- availability theo SKU
- `/admin/outbox`
- Grafana panel quota / outbox

### 10.2. Reserve reject
```text
reserve bị reject -> order fail sớm -> không giữ tài nguyên sai cách
```

Bằng chứng có thể mở:
- trạng thái order lỗi
- availability theo SKU
- panel quota reserve rejected

### 10.3. Payment fail -> release
```text
reserve thành công -> payment fail -> release reservation -> available stock tăng lại
```

Bằng chứng có thể mở:
- waiting page nhánh lỗi
- availability sau fail
- log `QUOTA RELEASE`
- panel release

### 10.4. Outbox failure / recovery
```text
append outbox -> mark failed / requeue -> retry thành công hoặc trạng thái thay đổi đúng
```

Bằng chứng có thể mở:
- `/admin/outbox`
- detail theo `eventId`
- outbox metrics retry/fail/pending

---

## 11. Ý nghĩa học thuật và thực tiễn của phần mở rộng Phase 7

Nếu các phase trước trả lời câu hỏi:
- framework có áp được vào consumer project thật hay không?
- framework có thay được các concern dùng chung hay không?

thì Phase 7 trả lời thêm các câu hỏi:
- sau khi tích hợp, hệ thống có **vận hành và quan sát** được không?
- consumer project có **giải thích rõ semantics dữ liệu** hay không?
- có đủ bằng chứng để **demo/bảo vệ** mà không phụ thuộc hoàn toàn vào log hay không?

Như vậy, phần mở rộng này không thay đổi luận điểm gốc của tài liệu, mà làm cho luận điểm đó đầy đủ hơn ở ba lớp:
1. **integration** — framework đã được áp dụng
2. **operation** — frameworkized flow đã vận hành được
3. **evidence** — frameworkized flow đã quan sát và chứng minh được

---

## 12. Kết luận bổ sung

Sau khi nối tiếp từ Phase 5.2 sang Phase 7, hệ thống ecommerce không chỉ dừng ở mức:
- dùng starter cho Kafka
- dùng quota cho reservation lifecycle
- dùng outbox cho reliable event publishing
- tách topic envelope để xử lý contract evolution

mà còn tiến thêm sang mức:
- có outbox admin để vận hành
- có availability semantics để tránh hiểu nhầm tồn kho
- có observability để nhìn thấy quota/outbox flow
- có dashboard và evidence để trình bày rõ giá trị của framework

Đây là phần hoàn thiện tự nhiên của quá trình tích hợp: từ **thay đổi code và flow**, sang **thay đổi cách hệ thống được vận hành, quan sát và giải thích**.
