package com.example.rag.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 多 ChatModel Bean 共存时的优先级配置。
 * <p>
 * 当同时引入 deepseek / openai / google-genai 等多个 starter 时，
 * Spring AI 会注册多个 ChatModel bean，导致 ChatClientAutoConfiguration 无法自动装配。
 * 此配置将 DeepSeek 标记为 @Primary，作为默认 ChatModel。
 */
@Configuration
public class ChatModelConfig {

	@Bean
	@Primary
	public ChatModel primaryChatModel(@Qualifier("deepSeekChatModel") ChatModel deepSeekChatModel) {
		return deepSeekChatModel;
	}

}
