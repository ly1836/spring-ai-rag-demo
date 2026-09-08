package com.example.rag.dao.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.rag.dao.entity.KnowledgeDocumentEntity;
import com.example.rag.knowledge.dto.KnowledgeDocumentCount;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 知识文档版本 Mapper。
 */
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentEntity> {

	/**
	 * 锁定指定稳定文档的全部版本。
	 *
	 * @param entCode        租户编码
	 * @param knowledgeBaseId 知识库 ID
	 * @param documentId     稳定文档 ID
	 * @return 按版本号升序排列的版本记录
	 */
	@Select("SELECT * FROM a_knowledge_document "
		+ "WHERE ent_code = #{entCode} AND knowledge_base_id = #{knowledgeBaseId} "
		+ "AND document_id = #{documentId} ORDER BY version ASC FOR UPDATE")
	List<KnowledgeDocumentEntity> selectVersionsForUpdate(@Param("entCode") String entCode,
			@Param("knowledgeBaseId") String knowledgeBaseId,
			@Param("documentId") String documentId);

	/**
	 * 按知识库聚合当前租户未删除的稳定文档数量。
	 *
	 * @param entCode 租户编码
	 * @return 各知识库的稳定文档数量
	 */
	@Select("SELECT knowledge_base_id AS knowledgeBaseId, "
		+ "COUNT(DISTINCT document_id) AS documentCount FROM a_knowledge_document "
		+ "WHERE ent_code = #{entCode} AND status <> 'deleted' GROUP BY knowledge_base_id")
	List<KnowledgeDocumentCount> selectDocumentCounts(@Param("entCode") String entCode);
}
