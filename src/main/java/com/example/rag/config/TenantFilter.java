package com.example.rag.config;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 租户过滤器 — 从 HTTP Header 中提取网关设置的 ent_code，写入 TenantContext。
 * <p>
 * 网关应在转发请求时设置 Header: X-Ent-Code: {租户标识}
 * <p>
 * 过滤器执行顺序设为最高优先级（@Order(1)），确保在所有业务逻辑之前完成租户识别。
 * 请求结束后自动清除 ThreadLocal，防止线程池复用导致的租户数据泄漏。
 */
@Component
@Order(1)
public class TenantFilter implements Filter {

	private static final Logger log = LoggerFactory.getLogger(TenantFilter.class);

	private static final String HEADER_ENT_CODE = "X-Ent-Code";
	private static final String HEADER_USER_ID = "X-User-Id";

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		HttpServletRequest httpRequest = (HttpServletRequest) request;

		// 只拦截 /api/** 路径，静态资源和其他路径直接放行
		String uri = httpRequest.getRequestURI();
		if (!uri.startsWith("/api/")) {
			chain.doFilter(request, response);
			return;
		}

		String entCode = httpRequest.getHeader(HEADER_ENT_CODE);

		if (entCode == null || entCode.isBlank()) {
			log.warn("请求缺少租户标识 Header: {}, URI: {}", HEADER_ENT_CODE, httpRequest.getRequestURI());
			HttpServletResponse httpResponse = (HttpServletResponse) response;
			httpResponse.setStatus(400);
			httpResponse.setContentType("application/json;charset=UTF-8");
			httpResponse.getWriter().write("{\"error\":\"缺少租户标识，请在 Header 中设置 X-Ent-Code\"}");
			return;
		}

		try {
			TenantContext.setEntCode(entCode);
			String userId = httpRequest.getHeader(HEADER_USER_ID);
			if (userId != null && !userId.isBlank()) {
				TenantContext.setUserId(userId);
			}
			log.debug("租户上下文已设置: ent_code={}, user_id={}", entCode, TenantContext.getUserIdOrDefault());
			chain.doFilter(request, response);
		}
		finally {
			// 无论请求成功还是异常，都必须清除 ThreadLocal
			TenantContext.clear();
		}
	}

}
