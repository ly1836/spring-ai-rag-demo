package com.example.rag.dao.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.rag.dao.entity.LlmToolEntity;
import org.apache.ibatis.annotations.Select;

/**
 * LLM 动态 Tool 定义 Mapper。
 */
public interface LlmToolMapper extends BaseMapper<LlmToolEntity> {

	/**
	 * 查询所有启用的动态 Tool。
	 *
	 * @return 启用的动态 Tool 列表
	 */
	@Select("""
		SELECT id, tool_name AS toolName, tool_desc AS toolDesc, input_schema AS inputSchema,
		sql_template AS sqlTemplate, table_alias AS tableAlias, result_limit AS resultLimit,
		status, remark, created_at AS createdAt, updated_at AS updatedAt
		FROM a_llm_tool
		WHERE status = 'active'
		ORDER BY tool_name ASC
		""")
	List<LlmToolEntity> selectActiveTools();
}
