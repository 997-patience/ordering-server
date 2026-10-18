CREATE TABLE t_employee (
    id BIGINT PRIMARY KEY,
    username VARCHAR(50),
    store_id BIGINT,
    role TINYINT,
    status TINYINT
);

INSERT INTO t_employee VALUES
    (1, 'admin', NULL, 1, 1),
    (2, 'store1', 1, 2, 1),
    (3, 'store2', 2, 2, 1);

CREATE TABLE t_dish (
    id BIGINT PRIMARY KEY,
    store_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    image VARCHAR(255),
    description VARCHAR(500),
    status TINYINT NOT NULL,
    sort INT NOT NULL,
    create_time TIMESTAMP,
    update_time TIMESTAMP
);

INSERT INTO t_dish VALUES
    (10, 1, 11, '宫保鸡丁', 38.00, '/upload/chicken.jpg', '微辣', 1, 2,
     '2026-10-11 09:10:05', '2026-10-11 09:10:05'),
    (11, 1, 11, '宫保鸡丁小份', 20.00, '/upload/small-chicken.jpg', '小份', 0, 1,
     '2026-10-11 09:10:05', '2026-10-11 09:10:05'),
    (12, 1, 12, '酸辣土豆丝', 16.00, '/upload/potato.jpg', '素菜', 1, 1,
     '2026-10-11 09:10:05', '2026-10-11 09:10:05'),
    (13, 1, 3000000000, '米饭', 2.00, NULL, NULL, 1, 3,
     '2026-10-11 09:10:05', '2026-10-11 09:10:05'),
    (20, 2, 21, '宫保鸡丁', 45.00, '/upload/other-store.jpg', '其他门店', 1, 0,
     '2026-10-11 09:10:05', '2026-10-11 09:10:05');
