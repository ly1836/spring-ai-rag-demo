package com.example.rag.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.rag.dao.entity.KnowledgeBaseEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 租户知识库 Mapper。
 */
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBaseEntity> {

	/**
	 * 锁定当前租户指定知识库记录。
	 *
	 * @param entCode        租户编码
	 * @param knowledgeBaseId 知识库 ID
	 * @return 最新知识库记录，不存在或已删除时为空
	 */
	@Select("SELECT * FROM a_knowledge_base "
		+ "WHERE ent_code = #{entCode} AND knowledge_base_id = #{knowledgeBaseId} "
		+ "AND status <> 'deleted' FOR UPDATE")
	KnowledgeBaseEntity selectForUpdate(@Param("entCode") String entCode,
			@Param("knowledgeBaseId") String knowledgeBaseId);
}
