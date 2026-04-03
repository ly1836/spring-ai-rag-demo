package com.example.rag.config;

import io.micrometer.context.ContextRegistry;

import jakarta.annotation.PostConstruct;

import org.springframework.context.annotation.Configuration;

import reactor.core.publisher.Hooks;

/**
 * 启用 Reactor 自动上下文传播。
 * <p>
 * 注册 {@link TenantContextAccessor} 后，Reactor 在线程切换（publishOn / subscribeOn /
 * 异步回调等）时会自动将 Subscriber Context 中的租户信息恢复到目标线程的 ThreadLocal。
 * <p>
 * 这样 Spring AI 在 reactor-netty 线程上执行 Tool Calling 时，
 * TenantContext 也能正确获取到 ent_code 和 user_id。
 */
@Configuration
public class ContextPropagationConfig {

	@PostConstruct
	public void init() {
		ContextRegistry.getInstance()
				.registerThreadLocalAccessor(new TenantContextAccessor());
		Hooks.enableAutomaticContextPropagation();
	}

}
