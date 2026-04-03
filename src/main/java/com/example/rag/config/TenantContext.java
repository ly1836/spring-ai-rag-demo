package com.example.rag.config;

/**
 * 租户上下文持有器（基于 ThreadLocal）。
 * <p>
 * 存储当前请求的租户标识 ent_code，供整个请求链路使用：
 * - Tool 类查询 ERP 数据库时，自动拼接 ent_code 条件
 * - RAG 检索产品手册时，自动按 ent_code 过滤
 * - 文档导入时，自动标记 ent_code 元数据
 * <p>
 * 生命周期由 {@link TenantFilter} 管理：请求进入时 set，请求结束时 clear。
 */
public final class TenantContext {

	private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();
	private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

	private TenantContext() {
	}

	public static void setEntCode(String entCode) {
		CURRENT_TENANT.set(entCode);
	}

	public static String getEntCode() {
		return CURRENT_TENANT.get();
	}

	public static String requireEntCode() {
		String entCode = CURRENT_TENANT.get();
		if (entCode == null || entCode.isBlank()) {
			throw new IllegalStateException("租户标识 ent_code 未设置，请检查请求 Header");
		}
		return entCode;
	}

	public static void setUserId(String userId) {
		CURRENT_USER.set(userId);
	}

	public static String getUserId() {
		return CURRENT_USER.get();
	}

	/** 获取用户ID，未设置时返回 "anonymous" */
	public static String getUserIdOrDefault() {
		String userId = CURRENT_USER.get();
		return (userId != null && !userId.isBlank()) ? userId : "anonymous";
	}

	public static void clear() {
		CURRENT_TENANT.remove();
		CURRENT_USER.remove();
	}

}
