INSERT INTO t_orders(id, order_no, user_id, store_id, amount, status, pay_status, pay_time, remark,
                     pickup_type, pickup_time, cancel_reason, finish_time, create_time, update_time)
SELECT 3000000000, 'ORDER-BIG', user_id, store_id, amount, status, pay_status, pay_time, remark,
       pickup_type, pickup_time, cancel_reason, finish_time, create_time, update_time
FROM t_orders WHERE id = 102;
