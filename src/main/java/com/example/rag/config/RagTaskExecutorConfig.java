package com.example.rag.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * RAG 检索异步执行器配置。
 */
@Configuration
public class RagTaskExecutorConfig {

	/**
	 * 创建应用级共享 RAG 执行器，并由 Spring 统一管理启动和关闭。
	 *
	 * @return 可传播租户上下文的 RAG 执行器
	 */
	@Bean(name = "ragTaskExecutor")
	public ThreadPoolTaskExecutor ragTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("rag-advisor-");
		executor.setCorePoolSize(4);
		executor.setMaxPoolSize(16);
		executor.setQueueCapacity(100);
		executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
		// 队列满时由当前请求线程执行，避免直接丢失本轮检索任务。
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(30);
		return executor;
	}

}
