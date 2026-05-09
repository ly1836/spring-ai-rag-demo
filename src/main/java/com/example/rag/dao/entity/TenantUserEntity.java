package com.example.rag.dao.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 租户用户表实体。
 */
@Data
@TableName("a_tenant_user")
public class TenantUserEntity {

	/** 主键 ID。*/
	@TableId(type = IdType.AUTO)
	private Long id;

	/** 租户内用。ID。*/
	private String userId;

	/** 租户编码。*/
	private String entCode;

	/** 登录账号。*/
	private String username;

	/** 显示名称。*/
	private String displayName;

	/** 用户角色：admin / user / viewer。*/
	private String role;

	/** 用户状态：active / disabled。*/
	private String status;

	/** 创建时间。*/
	private LocalDateTime createdAt;

	/** 更新时间。*/
	private LocalDateTime updatedAt;

}
