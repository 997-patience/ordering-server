DELETE FROM t_orders;
DELETE FROM t_user;

UPDATE t_store SET name='一号店', status=1 WHERE id=1;
UPDATE t_store SET name='二号店', status=1 WHERE id=2;
UPDATE t_store SET name='已打烊门店', status=0 WHERE id=3;

INSERT INTO t_user(id,phone,password,nickname) VALUES
    (1,'13800000001','e10adc3949ba59abbe56e057f20f883e','用户一'),
    (2,'13900000002','e10adc3949ba59abbe56e057f20f883e','用户二'),
    (3,'13700000003','e10adc3949ba59abbe56e057f20f883e','暂无订单的用户');

-- 故意打乱插入顺序；同秒订单用主键倒序，大主键也不能超过较新的下单时间。
INSERT INTO t_orders(id,order_no,user_id,store_id,amount,status,pay_status,pay_time,remark,
                     pickup_type,pickup_time,cancel_reason,finish_time,create_time,update_time) VALUES
    (1102,'USER1-1102',1,2,42.00,2,1,'2026-10-21 10:01:00','不返回的备注',
     1,NULL,NULL,NULL,'2026-10-21 10:00:00','2026-10-21 10:01:00'),
    (2201,'USER2-2201',2,1,88.00,1,0,NULL,NULL,
     1,NULL,NULL,NULL,'2026-10-24 10:00:00','2026-10-24 10:00:00'),
    (1105,'USER1-1105',1,1,12.30,1,0,NULL,'不要香菜',
     2,'2026-10-23 11:30:00',NULL,NULL,'2026-10-23 10:00:00','2026-10-23 10:00:00'),
    (3000000000,'USER1-BIG-ID',1,1,2.00,1,0,NULL,NULL,
     1,NULL,NULL,NULL,'2026-10-18 08:00:00','2026-10-18 08:00:00'),
    (1104,'USER1-1104',1,3,18.00,4,0,NULL,NULL,
     1,NULL,'用户取消',NULL,'2026-10-22 10:00:00','2026-10-22 10:02:00'),
    (2202,'USER2-2202',2,2,28.00,4,0,NULL,NULL,
     1,NULL,'用户取消',NULL,'2026-10-25 10:00:00','2026-10-25 10:02:00'),
    (1101,'USER1-1101',1,1,38.00,1,0,NULL,NULL,
     1,NULL,NULL,NULL,'2026-10-20 10:00:00','2026-10-20 10:00:00'),
    (1103,'USER1-1103',1,1,76.00,3,1,'2026-10-21 10:01:00',NULL,
     1,NULL,NULL,'2026-10-21 11:00:00','2026-10-21 10:00:00','2026-10-21 11:00:00');
