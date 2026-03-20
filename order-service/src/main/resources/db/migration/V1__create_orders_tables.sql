CREATE TABLE t_orders (
                          id BIGINT NOT NULL AUTO_INCREMENT,
                          order_date DATETIME(6) DEFAULT NULL,
                          order_number VARCHAR(255) NOT NULL,
                          status VARCHAR(255) DEFAULT NULL,
                          total_price DECIMAL(38,2) DEFAULT NULL,
                          user_id VARCHAR(255) NOT NULL,
                          PRIMARY KEY (id),
                          UNIQUE KEY UK_t_orders_order_number (order_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE t_orders_line_items (
                                     id BIGINT NOT NULL AUTO_INCREMENT,
                                     color VARCHAR(255) DEFAULT NULL,
                                     price DECIMAL(38,2) DEFAULT NULL,
                                     product_name VARCHAR(255) DEFAULT NULL,
                                     quantity INT DEFAULT NULL,
                                     size VARCHAR(255) DEFAULT NULL,
                                     sku_code VARCHAR(255) DEFAULT NULL,
                                     order_id BIGINT DEFAULT NULL,
                                     PRIMARY KEY (id),
                                     KEY FK_t_orders_line_items_order (order_id),
                                     CONSTRAINT FK_t_orders_line_items_order
                                         FOREIGN KEY (order_id) REFERENCES t_orders(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;