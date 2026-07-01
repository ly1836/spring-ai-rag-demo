package com.example.rag.tool.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.rag.config.TenantContext;
import com.example.rag.dao.mapper.ToolCallLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tool 调用流水服务测试。
 */
public class ToolCallLogServiceTest {

	/**
	 * 清理测试写入的租户上下文。
	 */
	@AfterEach
	public void tearDown() {
		TenantContext.clear();
	}

	/**
	 * 验证按 traceId 和当前租户回填助手消息 ID。
	 */
	@Test
	public void shouldAttachMessageIdWithCurrentTenant() {
		ToolCallLogMapper mapper = mock(ToolCallLogMapper.class);
		ToolCallLogService service = new ToolCallLogService(mapper);
		TenantContext.setEntCode("ENT001");
		when(mapper.updateMessageIdByTraceId("trace-1", "assistant-msg", "ENT001")).thenReturn(2);

		int updated = service.attachMessageId("trace-1", "assistant-msg");

		assertThat(updated).isEqualTo(2);
		verify(mapper).updateMessageIdByTraceId("trace-1", "assistant-msg", "ENT001");
	}

}
