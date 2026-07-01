package com.example.rag.dao.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * LLM 动态 Tool 定义表实体。
 */
@Data
@TableName("a_llm_tool")
public class LlmToolEntity {

	/** 主键 ID。*/
	@TableId(type = IdType.AUTO)
	private Long id;

	/** Tool 名称，暴露给 LLM 使用。*/
	private String toolName;

	/** Tool 描述，作为模型选择工具的依据。*/
	private String toolDesc;

	/** Tool 入参 JSON Schema。*/
	private String inputSchema;

	/** 只读 SQL 模板，使用 :name 命名参数。*/
	private String sqlTemplate;

	/** 主表别名，用于拼接租户条件。*/
	private String tableAlias;

	/** 最大返回行数。*/
	private Integer resultLimit;

	/** 状态：active / inactive。*/
	private String status;

	/** 备注。*/
	private String remark;

	/** 创建时间。*/
	private LocalDateTime createdAt;

	/** 更新时间。*/
	private LocalDateTime updatedAt;

}
