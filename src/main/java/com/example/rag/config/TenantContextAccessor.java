package com.example.rag.config;

import io.micrometer.context.ThreadLocalAccessor;

/**
 * 将 TenantContext 的 ThreadLocal 注册到 Micrometer ContextPropagation 体系。
 * <p>
 * 配合 {@code Hooks.enableAutomaticContextPropagation()} 使用后，
 * Reactor 在线程切换时会自动从 Subscriber Context 中恢复 TenantContext，
 * 解决 Tool Calling / doFinally 等在非 Servlet 线程执行时 ThreadLocal 丢失的问题。
 */
public class TenantContextAccessor implements ThreadLocalAccessor<TenantContextAccessor.TenantInfo> {

	public static final String KEY = "tenant.context";

	public record TenantInfo(String entCode, String userId) {
	}

	@Override
	public Object key() {
		return KEY;
	}

	@Override
	public TenantInfo getValue() {
		String entCode = TenantContext.getEntCode();
		String userId = TenantContext.getUserId();
		if (entCode == null && userId == null) {
			return null;
		}
		return new TenantInfo(entCode, userId);
	}

	@Override
	public void setValue(TenantInfo value) {
        TenantContext.setEntCode(value.entCode());
        TenantContext.setUserId(value.userId());
    }

	@Override
	public void setValue() {
		TenantContext.clear();
	}

	@Override
	public void restore(TenantInfo previousValue) {
        setValue(previousValue);
    }

	@Override
	public void restore() {
		TenantContext.clear();
	}

}
