package com.example.rag.config;

import com.example.rag.vo.RespVO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器 —— 所有 Controller 未捕获的异常统一包装为 {@link RespVO} 返回。
 * <p>
 * HTTP 状态码始终 200，业务错误通过 {@code success=false} + {@code errCode} + {@code errMsg} 传递，
 * 前端统一通过 {@code RespVO.success} 字段判断是否成功。
 * <p>
 * 异常分类：
 * <ul>
 *   <li>{@link IllegalStateException} → BIZ_ERROR（业务逻辑异常，如配额超限、账户暂停）</li>
 *   <li>{@link IllegalArgumentException} → PARAM_ERROR（参数校验失败，如金额非法）</li>
 *   <li>其他 {@link Exception} → SYSTEM_ERROR（系统内部错误，隐藏详情防止信息泄露）</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/** 处理业务逻辑异常（配额不足、账户暂停等） */
	@ExceptionHandler(IllegalStateException.class)
	public RespVO<?> handleIllegalState(IllegalStateException e) {
		log.warn("业务异常: {}", e.getMessage());
		return RespVO.error("BIZ_ERROR", e.getMessage(), e);
	}

	/** 处理参数校验异常（金额非法、必填字段为空等） */
	@ExceptionHandler(IllegalArgumentException.class)
	public RespVO<?> handleIllegalArgument(IllegalArgumentException e) {
		log.warn("参数异常: {}", e.getMessage());
		return RespVO.error("PARAM_ERROR", e.getMessage(), e);
	}

	/** 静态资源 404（favicon.ico、Chrome DevTools 探测等），降级为 DEBUG 日志 */
	@ExceptionHandler(NoResourceFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public RespVO<?> handleNoResource(NoResourceFoundException e) {
		log.debug("静态资源未找到: {}", e.getResourcePath());
		return RespVO.error("NOT_FOUND", "资源不存在: " + e.getResourcePath(), e);
	}

	/** 兜底处理所有未预期的异常，返回通用错误信息（不暴露内部细节） */
	@ExceptionHandler(Exception.class)
	public RespVO<?> handleException(Exception e) {
		if (isClientDisconnect(e)) {
			log.debug("客户端已断开连接: {}", e.getMessage());
			return RespVO.error("CLIENT_DISCONNECTED", "客户端已断开连接", e);
		}
		log.error("未处理异常", e);
		return RespVO.error("SYSTEM_ERROR", "系统内部错误，请稍后重试", e);
	}

	/** 判断异常是否由客户端主动断开连接触发（例如流式回答被用户停止）。 */
	private boolean isClientDisconnect(Throwable throwable) {
		Throwable current = throwable;
		while (current != null) {
			String message = current.getMessage();
			if (message != null) {
				String normalized = message.toLowerCase();
				if (normalized.contains("broken pipe")
						|| normalized.contains("connection reset")
						|| normalized.contains("clientabort")
						|| message.contains("你的主机中的软件中止了一个已建立的连接")) {
					return true;
				}
			}
			current = current.getCause();
		}
		return false;
	}

}
