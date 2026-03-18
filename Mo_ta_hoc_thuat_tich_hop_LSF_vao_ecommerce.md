# Mô tả học thuật về việc tích hợp framework LSF vào hệ thống ecommerce microservices

## 1. Bối cảnh
Trong quá trình phát triển framework LSF, nhóm đã xây dựng được các module hỗ trợ xử lý giao tiếp bất đồng bộ qua Kafka, contract dùng chung giữa các service và cơ chế quota để quản lý tài nguyên có giới hạn. Tuy nhiên, nếu chỉ dừng ở mức module độc lập hoặc demo nội bộ, giá trị thực tiễn của framework chưa được thể hiện đầy đủ. Vì vậy, một hệ thống ecommerce microservices đã được chọn làm hệ thống consumer để tiến hành tích hợp framework và đánh giá khả năng tái sử dụng trong bối cảnh nghiệp vụ thực tế.

Hệ thống ecommerce này vốn đã được xây dựng theo kiến trúc microservices và sử dụng mô hình giao tiếp bất đồng bộ giữa các service như order, inventory và payment. Đây là môi trường phù hợp để kiểm chứng khả năng áp dụng của framework LSF, đặc biệt đối với bài toán giữ hàng trong các tình huống có lưu lượng lớn như flash sale hoặc tồn kho giới hạn.

## 2. Mục tiêu tích hợp
Mục tiêu của quá trình tích hợp không phải là bổ sung tính năng mới cho hệ thống ecommerce, mà là thay thế một phần cơ chế xử lý sẵn có bằng các thành phần của framework LSF nhằm chứng minh ba khía cạnh chính:

1. Framework có thể được đóng gói và tái sử dụng bởi một dự án khác.
2. Framework có thể giải quyết một bài toán nghiệp vụ cụ thể trong hệ thống thực tế.
3. Việc tích hợp framework giúp chuẩn hóa và cải thiện cách tổ chức luồng xử lý giữa các service.

Use case được chọn để kiểm chứng là **giữ hàng tạm thời trong quy trình đặt hàng**, vì đây là nơi bài toán quota thể hiện rõ giá trị nhất.

## 3. Các module framework đã được áp dụng

### 3.1. Module `lsf-kafka-starter`
Module này được sử dụng để chuẩn hóa cấu hình Kafka producer và consumer cho các service tham gia vào luồng xử lý bất đồng bộ. Việc áp dụng starter giúp giảm cấu hình thủ công phân tán ở từng service, đồng thời đưa hệ thống tiến gần hơn đến mô hình framework-based integration.

Trong hệ thống ecommerce, module này được áp dụng chủ yếu tại:
- `order-service`
- `payment-service`
- các Kafka listener mới được thêm trong `inventory-service`

### 3.2. Module `lsf-contracts`
Module này cung cấp các contract dùng chung giữa các service, nhờ đó những command liên quan đến quota không cần được khai báo lại riêng lẻ trong từng service. Việc dùng contract chung giúp giảm trùng lặp, tăng tính nhất quán và làm rõ ranh giới giữa framework với project consumer.

Các contract tiêu biểu đã được sử dụng gồm:
- `ConfirmReservationCommand`
- `ReleaseReservationCommand`

### 3.3. Module `lsf-quota-streams-starter`
Đây là module trung tâm trong đợt tích hợp. Module này được áp dụng vào `inventory-service` để chuyển cơ chế giữ hàng từ cách trừ tồn kho trực tiếp sang cơ chế reservation theo quota.

Thông qua module quota, hệ thống sử dụng ba thao tác chính:
- `reserve`: giữ tạm tài nguyên
- `confirm`: xác nhận việc sử dụng tài nguyên sau khi giao dịch thành công
- `release`: giải phóng phần tài nguyên đã giữ khi giao dịch thất bại hoặc bị hủy

## 4. Thay đổi trong hệ thống ecommerce sau khi tích hợp

## 4.1. Thay đổi tại `inventory-service`
Trước khi tích hợp framework, `inventory-service` thực hiện kiểm tra tồn kho và trừ ngay số lượng hàng trong state store khi nhận được yêu cầu kiểm tra inventory. Cách xử lý này tuy đơn giản nhưng dẫn đến mô hình “trừ trước, bù sau”, làm cho logic compensation phụ thuộc nhiều vào event hoàn tác trong các nhánh lỗi.

Sau khi tích hợp framework LSF, `inventory-service` được thay đổi như sau:
- Logic trừ trực tiếp tồn kho trong bước inventory check được loại bỏ.
- Một lớp `InventoryQuotaService` được bổ sung để bao bọc việc gọi quota framework.
- Việc giữ hàng được chuyển sang thao tác `reserve` của quota framework.
- Service được bổ sung listener để nhận các command xác nhận hoặc giải phóng reservation.

Việc này biến `inventory-service` thành nơi sở hữu tài nguyên và chịu trách nhiệm quản lý trạng thái reservation thông qua framework thay vì thao tác trực tiếp trên stock ở bước đầu tiên.

## 4.2. Thay đổi tại `order-service`
`order-service` giữ vai trò điều phối quy trình đặt hàng. Sau khi tích hợp framework, service này không trực tiếp hoàn tác stock theo cách cũ nữa mà chuyển sang phát command dựa trên contract của framework.

Các thay đổi chính gồm:
- Khi thanh toán thành công, `order-service` phát `ConfirmReservationCommand`.
- Khi thanh toán thất bại hoặc inventory fail, `order-service` phát `ReleaseReservationCommand`.
- Saga kiểm tra inventory được chỉnh sửa để chỉ kết luận đơn hàng thất bại sau khi đã nhận đủ kết quả cho toàn bộ item trong đơn hàng.

Sự thay đổi này giúp giảm nguy cơ rò rỉ reservation trong trường hợp đơn hàng có nhiều item và kết quả trả về không đồng thời.

## 4.3. Thay đổi tại `payment-service`
`payment-service` được điều chỉnh để hoạt động thống nhất hơn với lớp hạ tầng Kafka từ framework. Trong luồng xử lý mới, service này giữ vai trò phát sinh kết quả thanh toán, từ đó kích hoạt nhánh xác nhận hoặc giải phóng reservation ở `order-service`.

## 5. Chuyển đổi mô hình nghiệp vụ
Trước khi tích hợp framework, hệ thống hoạt động theo mô hình:

- inventory check thành công
- trừ stock ngay
- nếu thanh toán thất bại thì phát event cộng tồn kho lại

Sau khi tích hợp framework, hệ thống chuyển sang mô hình:

- inventory reserve thành công
- nếu thanh toán thành công thì confirm
- nếu thanh toán thất bại hoặc quy trình bị hủy thì release

Mô hình sau phù hợp hơn với các bài toán flash sale, giữ tài nguyên ngắn hạn và giảm phụ thuộc vào cơ chế compensation kiểu “cộng tồn kho lại” khi có lỗi ở downstream service.

## 6. Kết quả kiểm thử
Quá trình kiểm thử đã xác nhận hệ thống hoạt động đúng với ba kịch bản chính:

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

Ba kịch bản trên cho thấy framework không chỉ được tích hợp về mặt kỹ thuật mà còn thực sự tham gia vào luồng nghiệp vụ chính của hệ thống.

## 7. Ý nghĩa học thuật và thực tiễn
Việc tích hợp framework LSF vào ecommerce microservices mang lại một số ý nghĩa quan trọng.

Về học thuật, đây là minh chứng cho hướng tiếp cận xây dựng framework theo mô hình tái sử dụng, trong đó các module hạ tầng và nghiệp vụ dùng chung có thể được đóng gói và tích hợp vào một hệ thống khác mà không cần sao chép thủ công toàn bộ logic.

Về thực tiễn, quá trình tích hợp cho thấy framework có khả năng:
- chuẩn hóa giao tiếp giữa các service thông qua contract chung,
- đóng gói cấu hình Kafka thành starter,
- áp dụng quota như một cơ chế quản lý tài nguyên thực tế,
- hỗ trợ tái cấu trúc quy trình giữ hàng theo mô hình reserve–confirm–release.

## 8. Kết luận
Kết quả tích hợp cho thấy framework LSF đã được áp dụng thành công vào một hệ thống ecommerce microservices hiện hữu. Việc tích hợp không dừng ở mức cấu hình hoặc demo đơn lẻ, mà đã thay đổi trực tiếp cách hệ thống xử lý nghiệp vụ giữ hàng. Điều này chứng minh framework có khả năng tái sử dụng trên dự án khác, đồng thời mở ra khả năng tiếp tục mở rộng sang các module khác như outbox, quản trị event hoặc observability trong các giai đoạn tiếp theo.
