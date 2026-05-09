package com.example.rag.tenant;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.rag.dao.entity.TenantEntity;
import com.example.rag.dao.entity.TenantUserEntity;
import com.example.rag.dao.mapper.TenantMapper;
import com.example.rag.dao.mapper.TenantUserMapper;
import com.example.rag.vo.AdminVO;

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
	public List<AdminVO.TenantItem> listTenants() {
		return tenantMapper.selectList(new LambdaQueryWrapper<TenantEntity>()
			.orderByDesc(TenantEntity::getCreatedAt)).stream()
			.map(this::toTenantItem)
			.toList();
	}

	/**
	 * 保存租户。
	 *
	 * @param tenant 租户 VO
	 * @return 是否保存成功
	 */
	public boolean saveTenant(AdminVO.TenantItem tenant) {
		return tenantMapper.insert(toTenantEntity(tenant)) > 0;
	}

	/**
	 * 更新租户。
	 *
	 * @param tenant 租户 VO
	 * @return 是否更新成功
	 */
	public boolean updateTenant(AdminVO.TenantItem tenant) {
		if (tenant.id() == null) {
			throw new IllegalArgumentException("租户 ID 不能为空");
		}
		return tenantMapper.updateById(toTenantEntity(tenant)) > 0;
	}

	/**
	 * 查询租户用户列表。
	 *
	 * @return 租户用户列表
	 */
	public List<AdminVO.TenantUserItem> listUsers() {
		return tenantUserMapper.selectList(new LambdaQueryWrapper<TenantUserEntity>()
			.orderByDesc(TenantUserEntity::getCreatedAt)).stream()
			.map(this::toTenantUserItem)
			.toList();
	}

	/**
	 * 保存租户用户。
	 *
	 * @param user 租户用户 VO
	 * @return 是否保存成功
	 */
	public boolean saveUser(AdminVO.TenantUserItem user) {
		return tenantUserMapper.insert(toTenantUserEntity(user)) > 0;
	}

	/**
	 * 更新租户用户。
	 *
	 * @param user 租户用户 VO
	 * @return 是否更新成功
	 */
	public boolean updateUser(AdminVO.TenantUserItem user) {
		if (user.id() == null) {
			throw new IllegalArgumentException("用户 ID 不能为空");
		}
		return tenantUserMapper.updateById(toTenantUserEntity(user)) > 0;
	}

	private AdminVO.TenantItem toTenantItem(TenantEntity entity) {
		return new AdminVO.TenantItem(entity.getId(), entity.getEntCode(), entity.getEntName(),
			entity.getContact(), entity.getPhone(), entity.getStatus());
	}

	private TenantEntity toTenantEntity(AdminVO.TenantItem item) {
		TenantEntity entity = new TenantEntity();
		entity.setId(item.id());
		entity.setEntCode(item.entCode());
		entity.setEntName(item.entName());
		entity.setContact(item.contact());
		entity.setPhone(item.phone());
		entity.setStatus(item.status());
		return entity;
	}

	private AdminVO.TenantUserItem toTenantUserItem(TenantUserEntity entity) {
		return new AdminVO.TenantUserItem(entity.getId(), entity.getUserId(), entity.getEntCode(),
			entity.getUsername(), entity.getDisplayName(), entity.getRole(), entity.getStatus());
	}

	private TenantUserEntity toTenantUserEntity(AdminVO.TenantUserItem item) {
		TenantUserEntity entity = new TenantUserEntity();
		entity.setId(item.id());
		entity.setUserId(item.userId());
		entity.setEntCode(item.entCode());
		entity.setUsername(item.username());
		entity.setDisplayName(item.displayName());
		entity.setRole(item.role());
		entity.setStatus(item.status());
		return entity;
	}

}
