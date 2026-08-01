package com.example.rag.vo;

import java.util.List;
import java.util.Map;

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
		ChartVO.ChartSpec chart = new ChartVO.ChartSpec(
			ChartVO.SCHEMA_VERSION, "chart-1", ChartVO.ChartType.BAR, "销售额", null,
			new ChartVO.Dataset(
				List.of(new ChartVO.Dimension("amount", "销售额", "number", "元")),
				List.of(Map.of("amount", 100))),
			Map.of("value", List.of("amount")),
			new ChartVO.ChartOptions(null, null, null, null, null,
				null, null, "元", null, null),
			new ChartVO.ChartSource(List.of("query_sales")));
		RespVO<ChatVO.AskResponse> response = RespVO.success(
			new ChatVO.AskResponse("c1", "问题", "回答", "data", chart));

		String json = mapper.writeValueAsString(response);

		assertThat(json).contains("\"success\":true");
		assertThat(json).contains("\"conversationId\":\"c1\"");
		assertThat(json).contains("\"answer\":\"回答\"");
		assertThat(json).contains("\"type\":\"bar\"");
		assertThat(json).doesNotContain("errCode");
		assertThat(json).doesNotContain("exception");
	}

}
