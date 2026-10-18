CREATE TABLE t_orders (
    id BIGINT PRIMARY KEY,
    order_no VARCHAR(32) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL REFERENCES t_store(id),
    amount DECIMAL(10, 2) NOT NULL,
    status TINYINT NOT NULL,
    pay_status TINYINT NOT NULL DEFAULT 0,
    pay_time TIMESTAMP,
    remark VARCHAR(255),
    pickup_type TINYINT NOT NULL DEFAULT 1,
    pickup_time TIMESTAMP,
    cancel_reason VARCHAR(255),
    finish_time TIMESTAMP,
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NOT NULL
);

INSERT INTO t_employee VALUES
    (3, 'bound-admin', 1, 1, 1),
    (4, 'unbound-staff', NULL, 2, 1),
    (5, 'store2', 2, 2, 1);

INSERT INTO t_orders(id, order_no, user_id, store_id, amount, status, pay_status, pay_time, remark,
                     pickup_type, pickup_time, cancel_reason, finish_time, create_time, update_time) VALUES
    (101, 'ORDER-101', 11, 1, 38.00, 1, 0, NULL, NULL, 1, NULL, NULL, NULL,
     '2026-10-10 23:59:59', '2026-10-10 23:59:59'),
    (102, 'ORDER-102', 12, 1, 42.00, 2, 1, '2026-10-11 00:00:05', '少辣', 1, NULL, NULL, NULL,
     '2026-10-11 00:00:00', '2026-10-11 00:00:05'),
    (103, 'ORDER-103', 13, 1, 76.00, 3, 1, '2026-10-11 12:00:05', '不要香菜', 2,
     '2026-10-11 12:30:00', NULL, '2026-10-12 13:00:00', '2026-10-11 12:00:00', '2026-10-12 13:00:00'),
    (104, 'ORDER-104', 14, 1, 50.00, 4, 0, NULL, NULL, 1, NULL, '用户取消', NULL,
     '2026-10-11 23:59:59', '2026-10-11 23:59:59'),
    (105, 'ORDER-105', 15, 1, 2.00, 1, 0, NULL, NULL, 1, NULL, NULL, NULL,
     '2026-10-12 00:00:00', '2026-10-12 00:00:00'),
    (106, 'ORDER-106', 16, 1, 16.00, 1, 0, NULL, NULL, 1, NULL, NULL, NULL,
     '2026-10-11 12:00:00', '2026-10-11 12:00:00'),
    (201, 'ORDER-201', 21, 2, 88.00, 1, 1, '2026-10-11 10:00:05', '其他店订单', 1, NULL, NULL, NULL,
     '2026-10-11 10:00:00', '2026-10-11 10:00:05');
