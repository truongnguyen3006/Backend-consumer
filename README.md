# Ecommerce Backend + LSF Integration

Backend này không được dùng để xây một hệ ecommerce đầy đủ tính năng, mà là **consumer project** để kiểm chứng framework **LSF** trong một hệ microservices thực tế. Trọng tâm của repo là cho thấy khi áp LSF vào flow đặt hàng, hệ thống đã thay đổi như thế nào ở các phần: **reservation lifecycle**, **reliable event publishing**, **observability** và **admin/ops support**.

## LSF đã được áp vào đâu

| Service | Module LSF đã dùng | Vai trò trong hệ thống |
|---|---|---|
| `inventory-service` | `lsf-quota-streams-starter`, `lsf-contracts`, `lsf-kafka-starter`, `lsf-observability-starter` | Chuyển logic giữ hàng từ trừ stock trực tiếp sang `reserve / confirm / release`, expose thêm availability |
| `order-service` | `lsf-kafka-starter`, `lsf-contracts`, `lsf-outbox-mysql-starter`, `lsf-outbox-admin-starter`, `lsf-observability-starter` | Điều phối order flow, ghi status event vào outbox, mở admin endpoint cho outbox |
| `payment-service` | `lsf-kafka-starter`, `lsf-observability-starter` | Chuẩn hóa Kafka integration, giữ vai trò phát kết quả payment để kích hoạt confirm hoặc release |
| `notification-service` | `lsf-eventing-starter` | Nhận status event dạng envelope để đẩy cập nhật realtime |

## Hệ thống đã thay đổi như thế nào sau khi áp LSF

| Trước khi tích hợp | Sau khi tích hợp |
|---|---|
| Kafka config chủ yếu viết thủ công theo từng service | Dùng starter để chuẩn hóa phần Kafka dùng chung |
| Inventory check pass thì trừ stock sớm | Đổi sang quota reservation: `reserve -> confirm / release` |
| Payment fail hoàn tác bằng kiểu cộng stock lại | Payment fail sẽ phát `ReleaseReservationCommand` để release reservation |
| Update DB xong gửi Kafka trực tiếp | Status event được append vào `lsf_outbox`, publisher nền gửi ra Kafka |
| Dùng raw status event trên topic cũ | Chuyển sang `EventEnvelope` và topic riêng `order-status-envelope-topic` |
| UI/admin dễ nhầm giữa tồn vật lý và tồn có thể bán | Tách rõ **physical stock** và **available stock** qua API availability |

## Những phần tích hợp nổi bật

### 1. Quota-based reservation trong `inventory-service`
- Áp `lsf-quota-streams-starter` qua `InventoryQuotaService`
- Map từ dữ liệu nghiệp vụ của ecommerce sang `quotaKey` và `requestId`
- Thay flow giữ hàng từ kiểu trừ stock trực tiếp sang:
  - `reserve`
  - `confirm`
  - `release`
- Có thêm endpoint availability để tách bạch tồn vật lý và tồn khả dụng

### 2. Outbox trong `order-service`
- Áp `lsf-outbox-mysql-starter`
- Thêm bảng `lsf_outbox`
- Khi order đổi trạng thái, service không publish trực tiếp nữa mà ghi `EventEnvelope` vào outbox
- Publisher nền sẽ gửi event ra Kafka sau đó

### 3. Outbox admin và observability
- Áp `lsf-outbox-admin-starter` để mở các endpoint quản trị outbox tại `/admin/outbox`
- Bật Actuator / Prometheus metrics cho các service chính
- Có dashboard để theo dõi quota và outbox flow khi demo hoặc benchmark

### 4. Contract evolution
- Không reuse `order-status-topic` cũ cho outbox
- Tách topic mới `order-status-envelope-topic`
- `OrderStatusJoiner` và notification flow đọc `EventEnvelope` rồi unwrap payload để xử lý tiếp

## API / bằng chứng kỹ thuật đáng chú ý

- `GET /api/inventory/{sku}`: physical stock
- `GET /api/inventory/{sku}/availability`: available stock sau khi trừ quota used
- `/admin/outbox/**`: xem row outbox, filter trạng thái, retry/requeue
- `/actuator/prometheus`: metrics cho quota, outbox và service health

## Ý nghĩa của repo này

Repo này chủ yếu chứng minh LSF đã được áp vào một consumer project thật ở 4 lớp:

1. **Kafka integration**
2. **Reservation lifecycle**
3. **Reliable event publishing bằng outbox**
4. **Observability / admin operations**

Nói ngắn gọn, thay đổi quan trọng nhất của hệ thống sau khi áp framework là:

```text
trừ stock trực tiếp -> reserve / confirm / release
```

và:

```text
update DB rồi gửi Kafka trực tiếp -> append to outbox -> publisher gửi nền
```

## Kiểm thử tải với JMeter

Repo có 2 kịch bản JMeter để kiểm thử luồng đặt hàng đồng thời:

- `oversell-single-sku.jmx`: nhiều request cùng đặt mua một SKU
- `multi-sku-concurrent-order.jmx`: nhiều request đồng thời đặt mua nhiều SKU khác nhau

Hai file CSV đi kèm:

- `data_oversell.csv`: chứa một `skuCode` dùng chung cho toàn bộ request
- `data_multi.csv`: chứa danh sách nhiều `skuCode` để phân tán tải trên nhiều biến thể sản phẩm

### Cách setup kịch bản

1. Mở file `.jmx` bằng JMeter.
2. Sửa lại địa chỉ host và port của các HTTP Request theo môi trường đang chạy.
3. Sửa `CSV Data Set Config` để trỏ tới file CSV trong máy của bạn.
4. Thay `Authorization: Bearer ...` bằng access token mới.
5. Kiểm tra lại dữ liệu test như user, SKU, tồn kho và trạng thái dịch vụ trước khi chạy.

### Lưu ý khi benchmark

Khi chạy JMeter, nên gửi request trực tiếp tới IP của môi trường chạy backend thay vì `localhost`, để hạn chế ảnh hưởng từ lớp proxy/NAT của Docker Desktop.

Ví dụ:
- dùng IP của WSL2 nếu backend chạy trong WSL2
- hoặc dùng IP LAN / DNS nội bộ của máy chạy backend
- tránh trộn `localhost` và IP khác nhau trong cùng một file test

### Tìm IP WSL2 để chạy JMeter

Nếu backend hoặc hạ tầng đang chạy trong WSL2, có thể lấy IP của WSL2 bằng lệnh:

```bash
ip -4 addr show eth0
```

### Chuẩn bị access token cho JMeter

#### Lấy token từ Postman

1. Đăng nhập bằng tài khoản test qua API hoặc collection Postman của project.
2. Sau khi đăng nhập thành công, copy `access_token` từ response.
3. Mở file `.jmx` và thay giá trị trong header:

```text
Authorization: Bearer <access_token>
```

## Kết quả kiểm thử tải

### Kịch bản 1: Oversell trên một SKU

#### JMeter Test Plan
<img src="screenshots/oversell_lsf_testplan.png" alt="Admin" width="1512">

#### JMeter Summary Report
<img src="screenshots/oversell_lsf_result.png" alt="Admin" width="1505">

#### Grafana dashboard Oversell Outbox
<img src="screenshots/grafana_outbox.png" alt="Admin" width="1633">

#### Grafana dashboard Oversell Quota
<img src="screenshots/grafana_quota.png" alt="Admin" width="1633">

#### Kết quả tồn kho sau test
<img src="screenshots/oversell_lsf.png" alt="Admin" width="1540">

### Kịch bản 2: Tải đồng thời trên nhiều SKU

#### JMeter Test Plan
<img src="screenshots/multi_1000.png" alt="Admin" width="1513">

#### JMeter Summary Report
<img src="screenshots/multi_1000_lsf.png" alt="Admin" width="1511">

## Hạn chế hiện tại

- Đây vẫn là consumer project để chứng minh framework, không phải một backend ecommerce hoàn chỉnh theo hướng production
- Một số phần domain và orchestration vẫn thuộc về project consumer, framework không thay toàn bộ business flow
- Benchmark tải lớn vẫn phụ thuộc khá nhiều vào môi trường local
