# Mô tả học thuật về việc tích hợp framework LSF vào hệ thống ecommerce microservices (cập nhật đến Phase 5.2)

## 1. Bối cảnh
Trong quá trình phát triển framework LSF, nhóm đã xây dựng được các module hỗ trợ giao tiếp bất đồng bộ qua Kafka, contract dùng chung giữa các service, cơ chế quota để quản lý tài nguyên có giới hạn, và tiếp tục mở rộng sang cơ chế outbox cho reliable event publishing. Tuy nhiên, nếu chỉ dừng ở mức module độc lập hoặc demo nội bộ, giá trị thực tiễn của framework chưa được thể hiện đầy đủ. Vì vậy, một hệ thống ecommerce microservices đã được chọn làm hệ thống consumer để tiến hành tích hợp framework và đánh giá khả năng tái sử dụng trong bối cảnh nghiệp vụ thực tế.

Hệ thống ecommerce này vốn đã được xây dựng theo kiến trúc microservices và sử dụng mô hình giao tiếp bất đồng bộ giữa các service như order, inventory và payment. Đây là môi trường phù hợp để kiểm chứng khả năng áp dụng của framework LSF, đặc biệt đối với ba lớp bài toán điển hình: (1) giữ hàng tạm thời trong tình huống tồn kho giới hạn, (2) phát hành event tin cậy sau khi cập nhật trạng thái nghiệp vụ trong cơ sở dữ liệu, và (3) quản lý sự tiến hóa của contract event khi chuyển từ raw domain event sang `EventEnvelope`.

## 2. Mục tiêu tích hợp
Mục tiêu của quá trình tích hợp không phải là bổ sung tính năng mới cho hệ thống ecommerce, mà là thay thế một phần cơ chế xử lý sẵn có bằng các thành phần của framework LSF nhằm chứng minh năm khía cạnh chính:

1. Framework có thể được đóng gói và tái sử dụng bởi một dự án khác.
2. Framework có thể giải quyết một bài toán nghiệp vụ cụ thể trong hệ thống thực tế.
3. Việc tích hợp framework giúp chuẩn hóa và cải thiện cách tổ chức luồng xử lý giữa các service.
4. Framework có thể mở rộng từ bài toán reservation sang bài toán reliable event publishing.
5. Framework có thể được áp dụng trong một chiến lược contract evolution thực tế mà không phá vỡ topic legacy hiện có.

Hai use case ban đầu được chọn để kiểm chứng gồm:
- **giữ hàng tạm thời trong quy trình đặt hàng**
- **phát hành status event theo mô hình outbox**

Ở Phase 5.2, quá trình kiểm chứng được mở rộng thêm sang:
- **quản lý event contract bằng topic envelope riêng**

## 3. Các module framework đã được áp dụng

### 3.1. Module `lsf-kafka-starter`
Module này được sử dụng để chuẩn hóa cấu hình Kafka producer và consumer cho các service tham gia vào luồng xử lý bất đồng bộ. Việc áp dụng starter giúp giảm cấu hình thủ công phân tán ở từng service, đồng thời đưa hệ thống tiến gần hơn đến mô hình framework-based integration.

Trong hệ thống ecommerce, module này được áp dụng chủ yếu tại:
- `order-service`
- `payment-service`
- các Kafka listener được bổ sung trong `inventory-service`

### 3.2. Module `lsf-contracts`
Module này cung cấp các contract dùng chung giữa các service, nhờ đó những command liên quan đến quota và envelope sự kiện không cần được khai báo lại riêng lẻ trong từng service.

Các contract tiêu biểu đã được sử dụng gồm:
- `ConfirmReservationCommand`
- `ReleaseReservationCommand`
- `EventEnvelope`

### 3.3. Module `lsf-quota-streams-starter`
Đây là module trung tâm trong đợt tích hợp đầu tiên. Module này được áp dụng vào `inventory-service` để chuyển cơ chế giữ hàng từ cách trừ tồn kho trực tiếp sang cơ chế reservation theo quota.

Thông qua module quota, hệ thống sử dụng ba thao tác chính:
- `reserve`
- `confirm`
- `release`

### 3.4. Module `lsf-outbox-mysql-starter`
Đây là module được áp dụng ở giai đoạn tiếp theo nhằm xử lý bài toán dual write giữa cơ sở dữ liệu và message broker. Thay vì gửi status event trực tiếp sau khi cập nhật DB, hệ thống ghi event vào bảng outbox trong cùng transaction, sau đó publisher nền của framework đảm nhiệm việc phát hành event ra Kafka.

Module này được áp dụng tại:
- `order-service`

## 4. Thay đổi trong hệ thống ecommerce sau khi tích hợp

### 4.1. Thay đổi tại `inventory-service`
Trước khi tích hợp framework, `inventory-service` thực hiện kiểm tra tồn kho và trừ ngay số lượng hàng trong state store khi nhận được yêu cầu kiểm tra inventory. Cách xử lý này dẫn tới mô hình “trừ trước, bù sau”, khiến logic compensation phụ thuộc nhiều vào event hoàn tác ở các nhánh lỗi.

Sau khi tích hợp framework LSF, `inventory-service` được thay đổi như sau:
- logic trừ trực tiếp tồn kho trong bước inventory check được loại bỏ,
- một lớp `InventoryQuotaService` được bổ sung để bao bọc việc gọi quota framework,
- việc giữ hàng được chuyển sang thao tác `reserve`,
- service được bổ sung listener để nhận `confirm` hoặc `release` command.

Việc này biến `inventory-service` thành nơi sở hữu tài nguyên và chịu trách nhiệm quản lý trạng thái reservation thông qua framework thay vì thao tác trực tiếp trên stock ở bước đầu tiên.

### 4.2. Thay đổi tại `order-service`
`order-service` giữ vai trò điều phối quy trình đặt hàng. Sau khi tích hợp framework, service này không trực tiếp hoàn tác stock theo cách cũ nữa mà chuyển sang phát command dựa trên contract của framework.

Các thay đổi chính của phase reservation gồm:
- khi thanh toán thành công, `order-service` phát `ConfirmReservationCommand`,
- khi thanh toán thất bại hoặc inventory fail, `order-service` phát `ReleaseReservationCommand`,
- saga kiểm tra inventory được chỉnh sửa để chỉ kết luận đơn hàng thất bại sau khi đã nhận đủ kết quả cho toàn bộ item trong đơn hàng.

Ở phase outbox, `order-service` tiếp tục được thay đổi theo hướng:
- bổ sung bảng outbox trong cơ sở dữ liệu,
- ghi status event vào outbox thay vì gửi trực tiếp,
- bọc status event trong `EventEnvelope`.

Ở Phase 5.2, `order-service` được mở rộng thêm theo hướng:
- sử dụng topic riêng `order-status-envelope-topic` thay cho việc tái dùng `order-status-topic`,
- tách contract envelope khỏi contract status legacy,
- chốt rằng migration version của bảng outbox thuộc quyền quản lý của consumer project chứ không phải starter.

### 4.3. Thay đổi tại `payment-service`
`payment-service` được điều chỉnh để hoạt động thống nhất hơn với lớp hạ tầng Kafka từ framework. Trong luồng xử lý mới, service này giữ vai trò phát sinh kết quả thanh toán, từ đó kích hoạt nhánh xác nhận hoặc giải phóng reservation ở `order-service`.

### 4.4. Thay đổi tại `OrderStatusJoiner`
Trước phase outbox, `OrderStatusJoiner` đọc trực tiếp `OrderStatusEvent` từ topic status và join với payment event. Sau khi tích hợp outbox, joiner được sửa để đọc `EventEnvelope`, trích xuất phần `payload` và chuyển đổi trở lại thành `OrderStatusEvent` trước khi thực hiện join.

Ở Phase 5.2, joiner được điều chỉnh thêm để:
- đọc từ `order-status-envelope-topic`,
- không còn phụ thuộc vào việc tái dùng topic status cũ cho contract mới.

Đây là điểm thể hiện rõ ràng nhất việc contract kỹ thuật của event đã được framework hóa thêm một lớp nhưng vẫn đảm bảo không phá hủy topic legacy.

## 5. Chuyển đổi mô hình nghiệp vụ
Trước khi tích hợp framework, hệ thống hoạt động theo mô hình:
- inventory check thành công
- trừ stock ngay
- nếu thanh toán thất bại thì phát event cộng tồn kho lại
- status event được gửi trực tiếp sau khi update DB

Sau khi tích hợp framework, hệ thống chuyển sang hai mô hình chuẩn hóa:

### 5.1. Reservation lifecycle
- inventory reserve thành công
- nếu thanh toán thành công thì confirm
- nếu thanh toán thất bại hoặc quy trình bị hủy thì release

### 5.2. Event lifecycle
- service cập nhật DB
- append event vào outbox
- publisher nền gửi envelope ra Kafka
- consumer unwrap envelope để xử lý domain payload

### 5.3. Contract evolution lifecycle (Phase 5.2)
- topic status cũ được giữ như contract legacy
- topic envelope mới được tạo cho contract mới
- consumer mới đọc topic envelope thay vì ép thay đổi contract của topic cũ

Mô hình sau phù hợp hơn với các bài toán flash sale, giữ tài nguyên ngắn hạn, dual write trong microservices, và quản lý sự tiến hóa của event contract trong hệ thống đang vận hành.

## 6. Kết quả kiểm thử
Quá trình kiểm thử đã xác nhận hệ thống hoạt động đúng với các kịch bản chính:

### 6.1. Kịch bản 1: giữ hàng thành công và xác nhận thành công
- Đơn hàng được gửi lên hệ thống.
- Inventory reserve thành công.
- Payment trả về thành công.
- Reservation được confirm.
- Đơn hàng kết thúc ở trạng thái `COMPLETED`.

### 6.2. Kịch bản 2: giữ hàng thất bại do vượt hạn mức
- Yêu cầu reserve vượt quá quota cho phép.
- Inventory trả về kết quả thất bại.
- Đơn hàng bị đánh dấu `FAILED`.

### 6.3. Kịch bản 3: giữ hàng thành công nhưng thanh toán thất bại
- Inventory reserve thành công.
- Payment thất bại.
- Reservation được release.
- Đơn hàng kết thúc ở trạng thái `PAYMENT_FAILED`.

### 6.4. Kịch bản 4: status event được ghi và phát hành qua outbox
- `order-service` cập nhật trạng thái đơn hàng.
- Event được ghi vào bảng `lsf_outbox` trong cùng transaction.
- Publisher nền phát event ra Kafka.
- `OrderStatusJoiner` xử lý thành công `EventEnvelope` và trích xuất `OrderStatusEvent`.

### 6.5. Kịch bản 5: topic envelope riêng không phá contract cũ
- `order-status-envelope-topic` tiếp nhận `EventEnvelope`
- `OrderStatusJoiner` unwrap payload đúng
- contract cũ của `order-status-topic` không bị ép thay đổi trên cùng subject

## 7. Ý nghĩa học thuật và thực tiễn
Việc tích hợp framework LSF vào ecommerce microservices mang lại một số ý nghĩa quan trọng.

Về học thuật, đây là minh chứng cho hướng tiếp cận xây dựng framework theo mô hình tái sử dụng, trong đó các module hạ tầng và cơ chế dùng chung có thể được đóng gói và tích hợp vào một hệ thống khác mà không cần sao chép thủ công toàn bộ logic.

Về thực tiễn, quá trình tích hợp cho thấy framework có khả năng:
- chuẩn hóa giao tiếp giữa các service thông qua contract chung,
- đóng gói cấu hình Kafka thành starter,
- áp dụng quota như một cơ chế quản lý tài nguyên thực tế,
- hỗ trợ tái cấu trúc quy trình giữ hàng theo mô hình reserve–confirm–release,
- mở rộng tiếp sang bài toán reliable event publishing bằng outbox,
- và quan trọng hơn, xử lý được vấn đề contract evolution thông qua chiến lược topic envelope riêng.

## 8. Kết luận
Kết quả tích hợp cho thấy framework LSF đã được áp dụng thành công vào một hệ thống ecommerce microservices hiện hữu qua nhiều giai đoạn phát triển. Việc tích hợp không dừng ở mức cấu hình hoặc demo đơn lẻ, mà đã thay đổi trực tiếp:
- cách hệ thống xử lý giữ hàng,
- cách hệ thống quản lý lifecycle reservation,
- cách hệ thống phát hành status event theo mô hình outbox,
- và cách hệ thống tổ chức contract event mới mà không phá contract cũ.

Điều này chứng minh framework có khả năng tái sử dụng trên dự án khác, đồng thời mở ra khả năng tiếp tục mở rộng sang các module như outbox admin, observability và benchmark trong các giai đoạn tiếp theo.
