package com.example.rag.dao.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 租户表实体。
 */
@Data
@TableName("a_tenant")
public class TenantEntity {

	/** 主键 ID。*/
	@TableId(type = IdType.AUTO)
	private Long id;

	/** 租户编码。*/
	private String entCode;

	/** 企业名称。*/
	private String entName;

	/** 联系人。*/
	private String contact;

	/** 联系电话。*/
	private String phone;

	/** 租户状态：active / suspended / closed。*/
	private String status;

	/** 创建时间。*/
	private LocalDateTime createdAt;

	/** 更新时间。*/
	private LocalDateTime updatedAt;

}
