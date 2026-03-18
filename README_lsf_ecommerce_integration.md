# Tích hợp LSF Framework vào dự án Ecommerce Microservices

## 1. Mục tiêu tích hợp

Mục tiêu của đợt tích hợp này là chứng minh framework **LSF (Large Scale Framework)** có thể được áp dụng vào một hệ thống microservices có sẵn, không chỉ chạy ở demo nội bộ. Dự án ecommerce được chọn làm **project consumer** để kiểm chứng việc tái sử dụng framework trong luồng nghiệp vụ thật, cụ thể là bài toán **giữ hàng tạm thời và chống oversell**.

Flow được tích hợp là:

- `reserve`: giữ hàng tạm thời khi đơn hàng bắt đầu đi qua inventory check
- `confirm`: xác nhận giữ hàng khi thanh toán thành công
- `release`: giải phóng giữ hàng khi thanh toán thất bại hoặc đơn hàng thất bại do inventory

---

## 2. Những module từ framework đã được áp dụng vào ecommerce

### 2.1 `lsf-quota-streams-starter`

**Mục đích áp dụng:**
- cung cấp core quota logic để giữ tài nguyên có giới hạn
- dùng Redis làm quota state store
- hỗ trợ reserve / confirm / release với request idempotent

**Được áp vào:**
- `inventory-service`

**Vai trò trong ecommerce:**
- thay cách trừ stock trực tiếp trong bước inventory check bằng cơ chế **quota reservation**
- biến flow cũ từ kiểu:
  - trừ stock ngay
  - thất bại thì cộng lại

  sang flow chuẩn hơn:
  - reserve
  - confirm hoặc release

---

### 2.2 `lsf-contracts`

**Mục đích áp dụng:**
- tái sử dụng contract/event chuẩn của framework

**Được áp vào:**
- `inventory-service`
- `order-service`

**Các contract đã dùng trong tích hợp:**
- `ConfirmReservationCommand`
- `ReleaseReservationCommand`

**Vai trò trong ecommerce:**
- `order-service` publish command xác nhận / giải phóng reservation
- `inventory-service` consume command và gọi quota tương ứng

---

### 2.3 `lsf-kafka-starter`

**Mục đích áp dụng:**
- thay cấu hình Kafka producer/consumer thủ công bằng starter dùng lại được
- chuẩn hóa các cấu hình như:
  - bootstrap servers
  - schema registry
  - retry/backoff
  - producer idempotence
  - batch / concurrency
  - DLQ

**Được áp vào:**
- `order-service`
- `payment-service`

**Vai trò trong ecommerce:**
- `order-service` dùng starter cho `KafkaTemplate` và `@KafkaListener`
- `payment-service` bỏ custom consumer config cũ và dùng starter thay thế

> Lưu ý: `inventory-service` hiện vẫn giữ phần Kafka Streams custom (`KafkaStreamsConfig`, `SerdeConfig`, `InventoryTopology`) vì `lsf-kafka-starter` hiện không thay thế Kafka Streams topology. Inventory chỉ tích hợp quota framework; phần streams vẫn là custom của service.

---

## 3. Ecommerce đã thay đổi những gì sau khi tích hợp framework

## 3.1 Thay đổi ở `inventory-service`

### a. Thêm framework dependencies

Trong `pom.xml`, `inventory-service` đã thêm:

- `lsf-quota-streams-starter`
- `lsf-contracts`
- `spring-boot-starter-data-redis`
- `spring-jdbc`

### b. Bật quota framework bằng config

Trong `application.properties` đã thêm các cấu hình quota:

- `lsf.quota.enabled=true`
- `lsf.quota.store=REDIS`
- `lsf.quota.default-hold-seconds=120`
- `lsf.quota.keep-alive-seconds=86400`
- `lsf.quota.allow-release-confirmed=false`

và Redis:

- `spring.data.redis.host=localhost`
- `spring.data.redis.port=6379`

### c. Tạo `InventoryQuotaService`

Service mới này là lớp bọc quota chính trong inventory-service.

Nó chịu trách nhiệm:
- map `quotaKey`
- map `requestId`
- gọi `quotaService.reserve(...)`
- gọi `quotaService.confirm(...)`
- gọi `quotaService.release(...)`

#### Mapping hiện tại

- `quotaKey = shopA:flashsale_sku:<sku>`
- `requestId = <orderNumber>:<sku>`

Điều này cho phép quota hoạt động theo từng SKU trong từng workflow/order.

### d. Thay đổi logic `InventoryTopology`

Đây là thay đổi quan trọng nhất của toàn bộ tích hợp.

**Trước khi tích hợp framework:**
- inventory check đọc stock từ state store
- nếu đủ hàng thì trừ stock ngay trong state store
- nếu thanh toán fail thì order-service cộng stock lại bằng compensation event

**Sau khi tích hợp framework:**
- inventory check đọc `physicalStock` từ state store
- gọi `InventoryQuotaService.reserve(...)`
- trả `InventoryCheckResult` dựa trên quota decision
- không còn trừ stock trực tiếp trong bước reserve nữa

Kết quả:
- stock vật lý và quota state được tách rõ
- reservation được quản lý bởi framework quota

### e. Thêm listener cho confirm/release command

`InventoryReservationCommandListener` là listener mới để nhận:

- `inventory-reservation-confirm-topic`
- `inventory-reservation-release-topic`

và gọi lần lượt:
- `inventoryQuotaService.confirm(...)`
- `inventoryQuotaService.release(...)`

Listener này đã được làm robust để xử lý payload ở nhiều format khác nhau khi deserialize từ Kafka.

### f. Thêm 2 topic mới

`KafkaTopicConfig` đã được mở rộng thêm:
- `inventory-reservation-confirm-topic`
- `inventory-reservation-release-topic`

---

## 3.2 Thay đổi ở `order-service`

### a. Thêm framework dependencies

Trong `pom.xml`, `order-service` đã thêm:

- `lsf-contracts`
- `lsf-kafka-starter`

### b. Chuyển cấu hình Kafka sang chuẩn framework

Các cấu hình Kafka cũ theo kiểu `spring.kafka.*` đã được comment/bỏ dần, thay bằng:

- `lsf.kafka.bootstrap-servers`
- `lsf.kafka.schema-registry-url`
- `lsf.kafka.consumer.*`
- `lsf.kafka.producer.*`
- `lsf.kafka.dlq.*`
- `lsf.kafka.observability.observation-enabled`

### c. Thêm 2 topic command mới

`KafkaConfig` đã được mở rộng thêm:
- `inventory-reservation-confirm-topic`
- `inventory-reservation-release-topic`

### d. Sửa flow SAGA inventory theo hướng an toàn hơn

Đây là thay đổi logic quan trọng ở `handleInventoryCheckResult(...)`.

**Trước đây:**
- fail ngay khi một SKU fail
- có nguy cơ order nhiều SKU bị fail sớm nhưng reservation của SKU khác đến muộn gây leak/khó bù

**Sau khi sửa:**
- Redis saga state lưu:
  - `totalItems`
  - `receivedItems`
  - `failed`
  - `failureReason`
- chỉ khi đã nhận đủ toàn bộ kết quả inventory cho order thì mới kết luận:
  - `FAILED`, hoặc
  - `VALIDATED`

Kết quả:
- tránh fail sớm khi order có nhiều SKU
- release an toàn hơn

### e. Sửa `handleOrderFailure(...)`

**Trước đây:**
- chỉ cập nhật order status `FAILED`

**Sau khi tích hợp framework:**
- cập nhật status `FAILED`
- publish `ReleaseReservationCommand` cho toàn bộ items

### f. Sửa `handlePaymentSuccess(...)`

**Trước đây:**
- chỉ cập nhật status `COMPLETED`

**Sau khi tích hợp framework:**
- cập nhật status `COMPLETED`
- publish `ConfirmReservationCommand` cho từng item trong order

### g. Sửa `handlePaymentFailure(...)`

**Trước đây:**
- phát `InventoryAdjustmentEvent(+quantity)` để cộng kho lại

**Sau khi tích hợp framework:**
- cập nhật status `PAYMENT_FAILED`
- publish `ReleaseReservationCommand`

Điều này là bằng chứng rõ ràng nhất cho việc flow đã chuyển từ:
- stock compensation

sang:
- quota release

### h. Thêm helper methods cho command publishing

`OrderService` đã thêm:
- `publishConfirmCommands(Order order)`
- `publishReleaseCommands(Order order, String reason)`

Hai helper này dùng `lsf-contracts` để phát command framework sang inventory-service.

### i. Thêm endpoint GET order details

Để phục vụ demo và kiểm tra kết quả flow async, `OrderController` đã bổ sung:
- `GET /api/order/{orderNumber}`
- `GET /api/order`

### j. Thêm fallback product cache cho local integration test

Trong `handleOrderPlacement(...)`, nếu product cache chưa có thì service đang tạm dùng dữ liệu fallback để tiếp tục flow.

Đây là thay đổi phục vụ tích hợp/demo, giúp framework được test end-to-end mà không phụ thuộc hoàn toàn vào product cache seed từ service khác.

> Đây là phần **test-oriented**. Khi đưa về production, nên thay bằng cơ chế seed/cache chính thức từ product-service.

### k. Tạm nới logic auth để test local

`OrderController` hiện đang dùng `userId = "test-user"` ở `POST /api/order` để đơn giản hóa việc test framework.

> Đây cũng là phần **test-oriented**, không phải thay đổi nên giữ nguyên cho production.

---

## 3.3 Thay đổi ở `payment-service`

### a. Thêm framework dependency

Trong `pom.xml`, `payment-service` đã thêm:

- `lsf-kafka-starter`

### b. Chuyển cấu hình Kafka sang chuẩn framework

`application.properties` đã dùng:
- `lsf.kafka.bootstrap-servers`
- `lsf.kafka.schema-registry-url`
- `lsf.kafka.consumer.*`
- `lsf.kafka.producer.*`
- `lsf.kafka.dlq.*`

### c. Bỏ custom consumer config cũ

`PaymentService` đã chuyển từ listener dùng `paymentKafkaListenerContainerFactory` sang dùng listener mặc định từ framework starter.

### d. Thêm rule payment giả lập để test release

`processPayment(...)` hiện dùng rule đơn giản:
- tổng quantity = 2 -> fail
- các case khác -> success

Điều này phục vụ test deterministic cho case:
- reserve thành công
- payment fail
- release

> Đây là thay đổi phục vụ demo/test, không phải business rule production.

---

## 4. Flow mới sau khi tích hợp framework

### 4.1 Flow success

1. Client gọi `POST /api/order`
2. `order-service` phát `OrderPlacedEvent`
3. order được persist và saga state được tạo trong Redis
4. `order-service` phát `InventoryCheckRequest` cho từng SKU
5. `inventory-service` gọi `quota reserve`
6. nếu tất cả item pass -> `order-validated-topic`
7. `payment-service` xử lý payment thành công
8. `order-service` nhận `PaymentProcessedEvent`
9. `order-service` publish `ConfirmReservationCommand`
10. `inventory-service` confirm quota
11. order status = `COMPLETED`

### 4.2 Flow reject vì hết quota

1. `order-service` phát inventory check
2. `inventory-service` reserve bị reject vì vượt limit
3. `order-service` chỉ kết luận fail khi đã nhận đủ kết quả inventory của toàn bộ items
4. `order-service` cập nhật status `FAILED`
5. `order-service` phát `ReleaseReservationCommand` nếu có reservation cần giải phóng

### 4.3 Flow payment fail rồi release

1. reserve thành công ở inventory-service
2. `payment-service` trả `PaymentFailedEvent`
3. `order-service` cập nhật status `PAYMENT_FAILED`
4. `order-service` publish `ReleaseReservationCommand`
5. `inventory-service` release reservation

---

## 5. Những gì framework đã chứng minh được qua project consumer này

Sau khi tích hợp vào ecommerce, framework đã chứng minh được các điểm sau:

1. **Quota framework không chỉ chạy ở demo nội bộ** mà áp được vào use case giữ hàng thật trong microservices.
2. **Kafka starter có thể thay cấu hình Kafka thủ công** ở các service event-driven như order-service và payment-service.
3. **Contracts của framework có thể dùng trực tiếp giữa các service** mà không cần tự định nghĩa lại command/event riêng.
4. Framework hỗ trợ một kiến trúc rõ ràng hơn cho bài toán oversell:
   - reserve
   - confirm
   - release
5. Project consumer không cần copy toàn bộ logic framework; chỉ cần thêm dependency, config, và nối vào flow nghiệp vụ hiện có.

---

## 6. Hướng dẫn tích hợp cho project consumer khác

## 6.1 Cài framework vào local Maven repo

Tại thư mục `lsf-parent`:

```bash
mvn clean install -DskipTests
```

Sau đó các artifact sẽ có trong local Maven repository (`~/.m2/repository` hoặc `C:\Users\<user>\.m2\repository`).

---

## 6.2 Dependency gợi ý cho project consumer

### a. Nếu project cần quota giữ tài nguyên có giới hạn

```xml
<dependency>
    <groupId>com.myorg.lsf</groupId>
    <artifactId>lsf-quota-streams-starter</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>

<dependency>
    <groupId>com.myorg.lsf</groupId>
    <artifactId>lsf-contracts</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>

<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-jdbc</artifactId>
</dependency>
```

### b. Nếu project cần chuẩn hóa Kafka producer/consumer

```xml
<dependency>
    <groupId>com.myorg.lsf</groupId>
    <artifactId>lsf-kafka-starter</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

---

## 6.3 Cấu hình Kafka theo chuẩn framework

Ví dụ `application.properties`:

```properties
lsf.kafka.bootstrap-servers=localhost:9092
lsf.kafka.schema-registry-url=http://localhost:8081

lsf.kafka.consumer.group-id=my-group
lsf.kafka.consumer.auto-offset-reset=earliest
lsf.kafka.consumer.batch=true
lsf.kafka.consumer.concurrency=4
lsf.kafka.consumer.max-poll-records=1000

lsf.kafka.consumer.retry.attempts=3
lsf.kafka.consumer.retry.backoff=200ms

lsf.kafka.dlq.enabled=true
lsf.kafka.dlq.suffix=.DLQ

lsf.kafka.producer.acks=all
lsf.kafka.producer.idempotence=true
lsf.kafka.producer.retries=10
lsf.kafka.producer.max-in-flight=5
lsf.kafka.producer.compression=snappy
lsf.kafka.producer.linger-ms=5
lsf.kafka.producer.batch-size=65536
```

---

## 6.4 Cấu hình quota theo chuẩn framework

```properties
lsf.quota.enabled=true
lsf.quota.store=REDIS
lsf.quota.default-hold-seconds=120
lsf.quota.keep-alive-seconds=86400
lsf.quota.allow-release-confirmed=false

spring.data.redis.host=localhost
spring.data.redis.port=6379
```

---

## 6.5 Cách nối quota vào use case thật

### a. Ở service sở hữu tài nguyên

Tạo lớp service bọc quota, ví dụ `InventoryQuotaService`, để:
- build `quotaKey`
- build `requestId`
- gọi `reserve / confirm / release`

### b. Ở bước bắt đầu giữ tài nguyên

Thay logic trừ tài nguyên trực tiếp bằng `quota reserve`.

### c. Ở bước thành công

Publish hoặc gọi `confirm`.

### d. Ở bước thất bại / timeout / cancel

Publish hoặc gọi `release`.

---

## 6.6 Mapping được dùng trong ecommerce hiện tại

- `quotaKey = shopA:flashsale_sku:<sku>`
- `requestId = <orderNumber>:<sku>`

Project consumer khác có thể đổi mapping này theo:
- tenant
- domain object
- resource type
- business key

---

## 6.7 Test cases tối thiểu cho project consumer

Khi tích hợp framework vào một project khác, nên test ít nhất 3 case:

1. `reserve thành công -> confirm`
2. `reserve reject vì vượt limit`
3. `reserve thành công -> business fail -> release`

Nếu 3 case này chạy đúng, nghĩa là tích hợp quota cơ bản đã thành công.

---

## 7. Các thay đổi đang mang tính demo/test

Các mục dưới đây phục vụ việc chứng minh framework trong môi trường local, nên cần đánh dấu rõ:

- `OrderController` dùng `test-user` thay vì principal/JWT thật
- `OrderService` có fallback product cache khi cache chưa có
- `PaymentService` dùng rule giả lập `totalQty == 2 -> fail`
- `eureka.client.enabled=false` ở một số service để chạy local đơn giản hơn

Các thay đổi này nên được:
- giữ trong nhánh demo/integration
- hoặc revert khi đưa về môi trường production gần thật

---

## 8. Các commit message gợi ý theo từng bước

### Bước 1 - áp Kafka starter vào order-service và payment-service

```text
feat: integrate lsf-kafka-starter into order and payment services
```

Hoặc chi tiết hơn:

```text
feat: replace manual Kafka consumer/producer config with lsf-kafka-starter
```

### Bước 2 - áp quota starter + contracts vào inventory-service

```text
feat: integrate lsf quota and contracts into inventory service
```

### Bước 3 - đổi flow inventory từ stock deduction sang quota reserve

```text
refactor: replace direct inventory deduction with quota reservation flow
```

### Bước 4 - thêm confirm/release command giữa order-service và inventory-service

```text
feat: add reservation confirm and release commands for order lifecycle
```

### Bước 5 - sửa order saga để đợi đủ kết quả inventory trước khi kết luận

```text
fix: make inventory saga wait for all item results before completing order
```

### Bước 6 - đổi compensation payment failure sang quota release

```text
refactor: replace inventory restock compensation with quota release
```

### Bước 7 - thêm endpoint và hỗ trợ local demo/test

```text
chore: add local test helpers and order query endpoints for framework demo
```

### Nếu muốn gộp thành 1 commit tổng cho nhánh demo

```text
feat: integrate lsf framework into ecommerce reserve-confirm-release flow
```

---

## 9. Kết luận

Qua tích hợp này, dự án ecommerce đã trở thành một **project consumer thực tế** cho framework LSF. Giá trị chính không nằm ở việc thêm demo mới trong framework, mà ở chỗ framework đã được dùng để giải quyết một bài toán nghiệp vụ cụ thể trong hệ thống microservices thật:

- chống oversell
- giữ hàng tạm thời
- xác nhận giữ hàng khi thành công
- giải phóng giữ hàng khi thất bại

Đây là bằng chứng rõ ràng nhất cho khả năng **tái sử dụng framework** trong các dự án khác.
