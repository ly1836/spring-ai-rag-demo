package com.example.rag.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一 API 响应包装对象。
 * <p>
 * 所有 Controller 接口（SSE 流式除外）的返回值均包装在此对象中，
 * 前端通过 {@code success} 字段判断请求是否成功，通过 {@code data} 字段获取业务数据。
 * <p>
 * 成功示例：{@code {"success":true, "data":{...}}}
 * <br>
 * 失败示例：{@code {"success":false, "errCode":"BIZ_ERROR", "errMsg":"账户余额不足"}}
 *
 * @param <T> 响应数据实体类型
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RespVO<T> {

	/** 请求处理是否成功 */
	private boolean success;

	/** 错误编码（失败时返回），如 BIZ_ERROR / PARAM_ERROR / SYSTEM_ERROR */
	private String errCode;

	/** 错误消息（失败时返回），面向用户的可读描述 */
	private String errMsg;

	/** 响应内容实体（成功时返回） */
	private T data;

	/** 当前操作的唯一标识串，如单号、流水号等（可选） */
	private String unique;

	/** 原始异常对象，仅内部使用，不会序列化到 JSON 响应中 */
	@JsonIgnore
	private Exception exception;

	protected RespVO() {
	}

	protected RespVO(boolean success, String errCode, String errMsg, T data) {
		this.success = success;
		this.errCode = errCode;
		this.errMsg = errMsg;
		this.data = data;
	}

	protected RespVO(boolean success, String errCode, String errMsg, T data, String unique) {
		this.success = success;
		this.errCode = errCode;
		this.errMsg = errMsg;
		this.data = data;
		this.unique = unique;
	}

	protected RespVO(boolean success, String errCode, String errMsg, T data, Exception exception) {
		this.success = success;
		this.errCode = errCode;
		this.errMsg = errMsg;
		this.data = data;
		this.exception = exception;
	}

	/** 构建成功响应（无数据） */
	public static <T> RespVO<T> success() {
		return new RespVO<>(true, null, null, null);
	}

	/** 构建成功响应（带消息和唯一标识） */
	public static RespVO<String> success(String msg, String unique) {
		return new RespVO<>(true, null, null, msg, unique);
	}

	/** 构建成功响应（带数据） */
	public static <T> RespVO<T> success(T data) {
		return new RespVO<>(true, null, null, data);
	}

	/** 构建失败响应 */
	public static <T> RespVO<T> error(String errCode, String errMsg) {
		return new RespVO<>(false, errCode, errMsg, null);
	}

	/** 构建失败响应（保留异常对象用于日志，不序列化） */
	public static <T> RespVO<T> error(String errCode, String errMsg, Exception exception) {
		return new RespVO<>(false, errCode, errMsg, null, exception);
	}

	public boolean isSuccess() {
		return success;
	}

	public void setSuccess(boolean success) {
		this.success = success;
	}

	public String getErrCode() {
		return errCode;
	}

	public void setErrCode(String errCode) {
		this.errCode = errCode;
	}

	public String getErrMsg() {
		return errMsg;
	}

	public void setErrMsg(String errMsg) {
		this.errMsg = errMsg;
	}

	public T getData() {
		return data;
	}

	public void setData(T data) {
		this.data = data;
	}

	public String getUnique() {
		return unique;
	}

	public void setUnique(String unique) {
		this.unique = unique;
	}

	public Exception getException() {
		return exception;
	}

	public void setException(Exception exception) {
		this.exception = exception;
	}

}
