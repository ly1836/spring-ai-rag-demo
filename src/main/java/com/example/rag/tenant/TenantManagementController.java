package com.example.rag.tenant;

import java.util.List;

import com.example.rag.dao.entity.TenantEntity;
import com.example.rag.dao.entity.TenantUserEntity;
import com.example.rag.vo.AdminVO;
import com.example.rag.vo.RespVO;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户管理 REST API 控制器。
 */
@RestController
@RequestMapping("/api/admin/tenants")
public class TenantManagementController {

	private final TenantManagementService tenantManagementService;

	public TenantManagementController(TenantManagementService tenantManagementService) {
		this.tenantManagementService = tenantManagementService;
	}

	/**
	 * 查询租户列表。
	 *
	 * @return 租户列表
	 */
	@GetMapping
	public RespVO<List<AdminVO.TenantItem>> listTenants() {
		return RespVO.success(tenantManagementService.listTenants().stream()
			.map(this::toTenantItem)
			.toList());
	}

	/**
	 * 新增租户。
	 *
	 * @param tenant 租户信息
	 * @return 是否保存成功
	 */
	@PostMapping
	public RespVO<Boolean> saveTenant(@RequestBody AdminVO.TenantItem tenant) {
		return RespVO.success(tenantManagementService.saveTenant(toTenantEntity(tenant)));
	}

	/**
	 * 更新租户。
	 *
	 * @param tenant 租户信息
	 * @return 是否更新成功
	 */
	@PutMapping
	public RespVO<Boolean> updateTenant(@RequestBody AdminVO.TenantItem tenant) {
		return RespVO.success(tenantManagementService.updateTenant(toTenantEntity(tenant)));
	}

	/**
	 * 查询当前租户用户列表。
	 *
	 * @return 租户用户列表
	 */
	@GetMapping("/users")
	public RespVO<List<AdminVO.TenantUserItem>> listUsers() {
		return RespVO.success(tenantManagementService.listUsers().stream()
			.map(this::toTenantUserItem)
			.toList());
	}

	/**
	 * 新增租户用户。
	 *
	 * @param user 租户用户信息
	 * @return 是否保存成功
	 */
	@PostMapping("/users")
	public RespVO<Boolean> saveUser(@RequestBody AdminVO.TenantUserItem user) {
		return RespVO.success(tenantManagementService.saveUser(toTenantUserEntity(user)));
	}

	/**
	 * 更新租户用户。
	 *
	 * @param user 租户用户信息
	 * @return 是否更新成功
	 */
	@PutMapping("/users")
	public RespVO<Boolean> updateUser(@RequestBody AdminVO.TenantUserItem user) {
		return RespVO.success(tenantManagementService.updateUser(toTenantUserEntity(user)));
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
