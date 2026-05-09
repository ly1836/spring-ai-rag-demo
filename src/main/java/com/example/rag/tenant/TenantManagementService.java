package com.example.rag.tenant;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.rag.dao.entity.TenantEntity;
import com.example.rag.dao.entity.TenantUserEntity;
import com.example.rag.dao.mapper.TenantMapper;
import com.example.rag.dao.mapper.TenantUserMapper;

import org.springframework.stereotype.Service;

/**
 * 租户管理服务。
 * <p>
 * 提供租户和租户用户的结构化 CRUD 能力，供后续管理端接口复用。
 */
@Service
public class TenantManagementService {

	private final TenantMapper tenantMapper;
	private final TenantUserMapper tenantUserMapper;

	public TenantManagementService(TenantMapper tenantMapper, TenantUserMapper tenantUserMapper) {
		this.tenantMapper = tenantMapper;
		this.tenantUserMapper = tenantUserMapper;
	}

	/**
	 * 查询租户列表。
	 *
	 * @return 租户列表
	 */
	public List<TenantEntity> listTenants() {
		return tenantMapper.selectList(new LambdaQueryWrapper<TenantEntity>()
			.orderByDesc(TenantEntity::getCreatedAt));
	}

	/**
	 * 保存租户。
	 *
	 * @param tenant 租户实体
	 * @return 是否保存成功
	 */
	public boolean saveTenant(TenantEntity tenant) {
		return tenantMapper.insert(tenant) > 0;
	}

	/**
	 * 更新租户。
	 *
	 * @param tenant 租户实体
	 * @return 是否更新成功
	 */
	public boolean updateTenant(TenantEntity tenant) {
		if (tenant.getId() == null) {
			throw new IllegalArgumentException("租户 ID 不能为空");
		}
		return tenantMapper.updateById(tenant) > 0;
	}

	/**
	 * 查询租户用户列表。
	 *
	 * @return 租户用户列表
	 */
	public List<TenantUserEntity> listUsers() {
		return tenantUserMapper.selectList(new LambdaQueryWrapper<TenantUserEntity>()
			.orderByDesc(TenantUserEntity::getCreatedAt));
	}

	/**
	 * 保存租户用户。
	 *
	 * @param user 租户用户实体
	 * @return 是否保存成功
	 */
	public boolean saveUser(TenantUserEntity user) {
		return tenantUserMapper.insert(user) > 0;
	}

	/**
	 * 更新租户用户。
	 *
	 * @param user 租户用户实体
	 * @return 是否更新成功
	 */
	public boolean updateUser(TenantUserEntity user) {
		if (user.getId() == null) {
			throw new IllegalArgumentException("用户 ID 不能为空");
		}
		return tenantUserMapper.updateById(user) > 0;
	}

}
