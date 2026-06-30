package com.example.rag.vo;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RespVO 在 Jackson 3 下的序列化兼容测试。
 */
class RespVOJacksonCompatibilityTest {

	/**
	 * 验证成功响应继续省略空错误字段，并保持 record 数据结构。
	 */
	@Test
	public void shouldSerializeSuccessResponseWithRecordData() throws Exception {
		JsonMapper mapper = JsonMapper.builder().build();
		RespVO<ChatVO.AskResponse> response = RespVO.success(
			new ChatVO.AskResponse("c1", "问题", "回答", "knowledge"));

		String json = mapper.writeValueAsString(response);

		assertThat(json).contains("\"success\":true");
		assertThat(json).contains("\"conversationId\":\"c1\"");
		assertThat(json).contains("\"answer\":\"回答\"");
		assertThat(json).doesNotContain("errCode");
		assertThat(json).doesNotContain("exception");
	}

}
