package com.wys.orderingserver.service.impl;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.CategoryMapper;
import com.wys.orderingserver.mapper.DishMapper;
import com.wys.orderingserver.mapper.StoreMapper;
import com.wys.orderingserver.pojo.Category;
import com.wys.orderingserver.service.CategoryService;
import com.wys.orderingserver.utils.AdminContextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class CategoryServiceimpl implements CategoryService {
    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private StoreMapper storeMapper;

    @Autowired
    private DishMapper dishMapper;
    @Override
    public List<Category> getCategory(Long storeId) {
        Long queryStoreId;
        if (AdminContextUtil.isSuperAdmin()) {
            // 超管没有固定所属门店，必须通过请求参数指定目标门店。
            if (storeId == null || storeId < 1) {
                throw new BizException("超级管理员必须指定有效的门店ID");
            }
            queryStoreId = storeId;
        } else {
            // 门店管理员只能查询本店，忽略前端传入的门店 ID，防止跨店查询。
            queryStoreId = AdminContextUtil.getCurrentAdminStoreId("当前员工未绑定门店");
        }

        // 实际门店确定后只查询一次，返回分类列表，由 Controller 包装 Result。
        return categoryMapper.getCategory(queryStoreId);
    }

    @Override
    @Transactional
    public void add(Category category) {
        // 按 3.2 文档，门店 ID 只能来自 JWT。未绑定门店的超管也不能凭请求体指定门店。
        Long storeId = AdminContextUtil.getCurrentAdminStoreId();

        if (category == null || !StringUtils.hasText(category.getName())) {
            throw new BizException("分类名称不能为空");
        }
        if (category.getStatus() == null) {
            category.setStatus(1);
        } else if (category.getStatus() != 0 && category.getStatus() != 1) {
            throw new BizException("分类状态只能为0或1");
        }
        if (category.getSort() == null) {
            category.setSort(0);
        }

        // 与整店删除使用同一把门店行锁，避免新增分类和删店交错执行。
        if (storeMapper.findByIdForUpdate(storeId) == null) {
            throw new BizException("所属门店不存在");
        }

        // 不信任前端传入的主键、门店归属和时间；这些字段由服务端决定。
        category.setId(null);
        category.setStoreId(storeId);
        category.setName(category.getName().trim());
        LocalDateTime now = LocalDateTime.now();
        category.setCreateTime(now);
        category.setUpdateTime(now);
        categoryMapper.add(category);
    }

    @Override
    @Transactional
    public void updateCategory(Category category) {
        Long storeId = AdminContextUtil.getCurrentAdminStoreId();
        if (category == null || category.getId() == null || category.getId() < 1) {
            throw new BizException("分类ID不合法");
        }
        if (!StringUtils.hasText(category.getName())) {
            throw new BizException("分类名称不能为空");
        }
        if (category.getStatus() != null && category.getStatus() != 0 && category.getStatus() != 1) {
            throw new BizException("分类状态只能为0或1");
        }

        // 与新增分类、整店删除采用相同的锁顺序，确保所属门店存在。
        if (storeMapper.findByIdForUpdate(storeId) == null) {
            throw new BizException("所属门店不存在");
        }

        // 门店归属只从登录信息确定，不能使用请求体伪造的 storeId。
        category.setStoreId(storeId);
        category.setName(category.getName().trim());
        // 修改时不填新增默认值，也不改变排序号；null 交给 SQL 的 coalesce 保留原值。
        category.setUpdateTime(LocalDateTime.now());
        int rows = categoryMapper.updateCategory(category);
        if (rows != 1) {
            throw new BizException("分类不存在或不属于当前门店");
        }
    }

    @Override
    @Transactional
    public void deleteCategory(Long id) {
        if (id == null || id < 1) {
            throw new BizException("分类ID不合法");
        }
        Long storeId = AdminContextUtil.getCurrentAdminStoreId();

        // 与其他写接口按相同顺序锁定门店，再锁定分类。
        if (storeMapper.findByIdForUpdate(storeId) == null) {
            throw new BizException("所属门店不存在");
        }
        if (categoryMapper.findByIdAndStoreIdForUpdate(id, storeId) == null) {
            throw new BizException("分类不存在或不属于当前门店");
        }
        // 下架菜品仍然引用分类，不能只检查上架菜品。
        if (dishMapper.countByCategoryId(id) > 0) {
            throw new BizException("该分类下存在菜品，请先调整菜品分类后再删除");
        }
        try {
            if (categoryMapper.deleteCategory(id, storeId) != 1) {
                throw new BizException("分类不存在或不属于当前门店");
            }
        } catch (DataIntegrityViolationException e) {
            // 配置外键后，并发关联写入由数据库兜底拦截。
            throw new BizException("分类存在关联数据，不能删除", e);
        }
    }
}
