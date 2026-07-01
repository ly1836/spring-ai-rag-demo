package com.example.rag.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.rag.dao.entity.ToolCallLogEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * LLM Tool 命中流水 Mapper。
 */
public interface ToolCallLogMapper extends BaseMapper<ToolCallLogEntity> {

	/**
	 * 按问答链路和租户回填助手消息 ID。
	 *
	 * @param traceId   问答链路 ID
	 * @param messageId 助手消息 ID
	 * @param entCode   租户编码
	 * @return 更新行数
	 */
	@Update("""
		UPDATE a_tool_call_log
		SET message_id = #{messageId}
		WHERE trace_id = #{traceId}
		AND ent_code = #{entCode}
		""")
	int updateMessageIdByTraceId(@Param("traceId") String traceId, @Param("messageId") String messageId,
			@Param("entCode") String entCode);
}
