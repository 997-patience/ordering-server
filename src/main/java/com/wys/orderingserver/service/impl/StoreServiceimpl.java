package com.wys.orderingserver.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.StoreMapper;
import com.wys.orderingserver.pojo.PageBean;
import com.wys.orderingserver.pojo.Store;
import com.wys.orderingserver.service.StoreService;
import com.wys.orderingserver.utils.AdminContextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/** 门店业务实现：参数检查、权限判断、分页结果组装。 */
@Service
public class StoreServiceimpl implements StoreService {
    @Autowired
    private  StoreMapper storeMapper;

    @Override
    public PageBean<Store> page(Integer pageNum, Integer pageSize, String name, Integer status) {
        if (pageNum == null || pageNum < 1) {
            throw new BizException("pageNum必须大于等于1");
        }
        if (pageSize == null || pageSize < 1) {
            throw new BizException("pageSize必须大于等于1");
        }
        if (status != null && status != 0 && status != 1) {
            throw new BizException("门店状态只能为0或1");
        }

        // 超管查询全部门店；门店管理员由公共方法检查绑定关系并限定本店。
        Long storeId = AdminContextUtil.isSuperAdmin()
                ? null : AdminContextUtil.getCurrentAdminStoreId("当前员工未绑定门店");

        String queryName = StringUtils.hasText(name) ? name.trim() : null;

        // 所有检查放在 startPage 前；下一条 MyBatis 查询才是要分页的查询。
        // PageHelper 自动执行总数查询，并为列表 SQL 添加 LIMIT。
        // try-with-resources 会清理分页上下文，即使查询异常也不会影响后续请求。
        try (Page<Store> page = PageHelper.startPage(pageNum, pageSize)) {
            storeMapper.list(queryName, status, storeId);
            return new PageBean<>(page.getTotal(), page.getResult());
        }
    }

    @Override
    public void add(Store store) {
        AdminContextUtil.requireSuperAdmin("只有超级管理员可以创建新的门店");
        if (store.getStatus()==null) {
            store.setStatus(1);
        }
        if (store.getStatus()!=0&&store.getStatus()!=1) {
            throw new BizException("门店状态只有0和1");
        }
        LocalDateTime now = LocalDateTime.now();
        store.setCreateTime(now);
        store.setUpdateTime(now);
        storeMapper.add(store);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        if (id == null || id < 1) {
            throw new BizException("门店ID不合法");
        }
        AdminContextUtil.requireSuperAdmin("只有超管可以删除门店");
        Store store = storeMapper.findByIdForUpdate(id);
        if (store == null) {
            throw new BizException("门店不存在");
        }

        // 先删子记录，再删父记录；任何一步失败，整个数据库事务回滚。
        storeMapper.deleteOrderDetailsByStoreId(id);
        storeMapper.deleteOrdersByStoreId(id);
        storeMapper.deleteCartsByStoreId(id);
        storeMapper.deleteDishesByStoreId(id);
        storeMapper.deleteCategoriesByStoreId(id);
        storeMapper.deleteEmployeesByStoreId(id);
        if (storeMapper.delete(id) != 1) {
            throw new BizException("门店删除失败，请刷新后重试");
        }
    }
}
