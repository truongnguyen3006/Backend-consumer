-- Outbox table (MySQL/InnoDB)
-- Stores the entire EventEnvelope as JSON alongside indexed fields.

CREATE TABLE IF NOT EXISTS lsf_outbox (
    -- định danh & routing
                                          id BIGINT NOT NULL AUTO_INCREMENT,
                                          topic VARCHAR(255) NOT NULL,
    msg_key VARCHAR(255) NULL,

    -- business identity của event
    event_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(200) NOT NULL,
    correlation_id VARCHAR(100) NULL, -- để trace luồng nghiệp vụ xuyên service (request → event → event…)
    aggregate_id VARCHAR(100) NULL,-- id thực thể chính (orderId/bookingId) giúp debug, hoặc dùng làm msg_key.

    envelope_json LONGTEXT NOT NULL, -- Payload thực tế của event,
    -- Chứa toàn bộ EventEnvelope (metadata + payload) dạng JSON string.
    -- trạng thái, retry, audit vận hành
    status VARCHAR(20) NOT NULL DEFAULT 'NEW',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), -- thời điểm ghi outbox (dùng để order/poll).
    sent_at TIMESTAMP(3) NULL, -- lúc publish thành công.
    retry_count INT NOT NULL DEFAULT 0, -- số lần publish fail đã thử.
    last_error TEXT NULL,

    -- Lưu “ai đang giữ quyền xử lý” record (tên instance/pod/hostname, ví dụ publisher-1)
    lease_owner VARCHAR(128) NULL,
    -- Thời điểm “hết hạn lease”. lease_until = now + leaseDuration
    lease_until TIMESTAMP(3) NULL,
    -- Thời điểm được phép thử publish lại
    next_attempt_at TIMESTAMP(3) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_lsf_outbox_event_id (event_id), -- chống ghi trùng
    KEY idx_lsf_outbox_status_created (status, created_at),-- Index này phục vụ query kiểu,
    -- Index này giúp DB lọc nhanh theo (status, lease_until).
    KEY idx_lsf_outbox_lease (status, lease_until),
    -- Lấy record đến hạn retry:
    KEY idx_lsf_outbox_next_attempt (status, next_attempt_at)
    )
    ENGINE=InnoDB;
