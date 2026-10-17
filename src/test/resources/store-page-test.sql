CREATE TABLE t_store (
    id BIGINT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    address VARCHAR(255),
    phone VARCHAR(20),
    open_time TIME,
    close_time TIME,
    notice VARCHAR(255),
    status TINYINT NOT NULL,
    create_time TIMESTAMP,
    update_time TIMESTAMP
);

INSERT INTO t_store VALUES
    (1, '望京SOHO店', '望京SOHO', '010-88886666', '09:00:00', '21:30:00', '测试公告', 1,
     '2026-10-11 09:10:05', '2026-10-11 09:10:05'),
    (2, '中关村店', '中关村', '010-66668888', '10:00:00', '22:00:00', '测试公告', 1,
     '2026-10-11 09:10:05', '2026-10-11 09:10:05'),
    (3, '望京夜宵店', '望京', '010-88889999', '18:00:00', '23:30:00', '测试公告', 0,
     '2026-10-11 09:10:05', '2026-10-11 09:10:05');

CREATE TABLE t_employee (
    id BIGINT PRIMARY KEY,
    username VARCHAR(50),
    store_id BIGINT,
    role TINYINT,
    status TINYINT
);

INSERT INTO t_employee VALUES
    (1, 'admin', NULL, 1, 1),
    (2, 'store1', 1, 2, 1);
