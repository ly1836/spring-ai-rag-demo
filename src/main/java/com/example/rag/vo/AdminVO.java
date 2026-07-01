package com.example.rag.vo;

import java.math.BigDecimal;

/**
 * 管理端 VO —— 租户、套餐、计价规则和账单管理接口的请求/响应对象。
 */
public final class AdminVO {

	private AdminVO() {
	}

	/**
	 * 租户管理请求/响应。
	 *
	 * @param id      主键 ID
	 * @param entCode 租户编码
	 * @param entName 企业名称
	 * @param contact 联系人
	 * @param phone   联系电话
	 * @param status  租户状态
	 */
	public record TenantItem(Long id, String entCode, String entName, String contact, String phone, String status) {
	}

	/**
	 * 租户用户管理请求/响应。
	 *
	 * @param id          主键 ID
	 * @param userId      用户 ID
	 * @param entCode     租户编码
	 * @param username    登录账号
	 * @param displayName 显示名称
	 * @param role        用户角色
	 * @param status      用户状态
	 */
	public record TenantUserItem(Long id, String userId, String entCode, String username,
			String displayName, String role, String status) {
	}

	/**
	 * 套餐管理请求/响应。
	 *
	 * @param id                     主键 ID
	 * @param planCode               套餐编码
	 * @param planName               套餐名称
	 * @param planType               套餐类型
	 * @param monthlyTokenQuota      月度 token 配额
	 * @param monthlyPrice           月费
	 * @param overagePricePer1k      超额每千 token 单价
	 * @param maxConversationsPerDay 每日最大会话数
	 * @param maxTokensPerRequest    单次最大 token 数
	 * @param maxUsers               最大用户数
	 * @param features               功能 JSON
	 * @param status                 套餐状态
	 */
	public record PlanItem(Long id, String planCode, String planName, String planType,
			Long monthlyTokenQuota, BigDecimal monthlyPrice, BigDecimal overagePricePer1k,
			Integer maxConversationsPerDay, Integer maxTokensPerRequest, Integer maxUsers,
			String features, String status) {
	}

	/**
	 * 模型计价规则管理请求/响应。
	 *
	 * @param id               主键 ID
	 * @param model            模型名称
	 * @param inputPricePer1k  输入 token 每千单价
	 * @param outputPricePer1k 输出 token 每千单价
	 * @param effectiveDate    生效日期
	 * @param expiredDate      失效日期
	 * @param remark           备注
	 */
	public record PriceRuleItem(Long id, String model, BigDecimal inputPricePer1k,
			BigDecimal outputPricePer1k, String effectiveDate, String expiredDate, String remark) {
	}

	/**
	 * 账单管理请求/响应。
	 *
	 * @param id             主键 ID
	 * @param invoiceNo      账单编号
	 * @param entCode        租户编码
	 * @param billingMonth   账单月份
	 * @param planCode       套餐编码
	 * @param planFee        套餐费用
	 * @param tokenUsageFee  token 用量费用
	 * @param totalAmount    总金额
	 * @param totalTokens    总 token 数
	 * @param totalRequests  总请求数
	 * @param status         账单状态
	 * @param paidAt         支付时间
	 */
	public record InvoiceItem(Long id, String invoiceNo, String entCode, String billingMonth,
			String planCode, BigDecimal planFee, BigDecimal tokenUsageFee, BigDecimal totalAmount,
			Long totalTokens, Integer totalRequests, String status, String paidAt) {
	}

	/**
	 * LLM 动态 Tool 管理请求/响应。
	 *
	 * @param id          主键 ID
	 * @param toolName    Tool 名称
	 * @param toolDesc    Tool 描述
	 * @param inputSchema 入参 JSON Schema
	 * @param sqlTemplate SQL 模板
	 * @param tableAlias  主表别名
	 * @param resultLimit 返回行数上限
	 * @param status      Tool 状态
	 * @param remark      备注
	 * @param createdAt   创建时间
	 * @param updatedAt   更新时间
	 */
	public record ToolItem(Long id, String toolName, String toolDesc, String inputSchema,
			String sqlTemplate, String tableAlias, Integer resultLimit, String status, String remark,
			String createdAt, String updatedAt) {
	}

	/**
	 * LLM Tool 命中流水响应。
	 *
	 * @param id            主键 ID
	 * @param traceId       问答链路 ID
	 * @param conversationId 会话 ID
	 * @param messageId     助手消息 ID
	 * @param entCode       租户编码
	 * @param userId        用户 ID
	 * @param mode          问答模式
	 * @param model         使用模型
	 * @param toolName      Tool 名称
	 * @param toolType      Tool 来源
	 * @param argumentsJson Tool 入参 JSON
	 * @param resultCount   返回结果条数
	 * @param durationMs    调用耗时
	 * @param status        调用状态
	 * @param errorMessage  错误信息
	 * @param createdAt     创建时间
	 */
	public record ToolCallLogItem(Long id, String traceId, String conversationId, String messageId,
			String entCode, String userId, String mode, String model, String toolName, String toolType,
			String argumentsJson, Integer resultCount, Long durationMs, String status, String errorMessage,
			String createdAt) {
	}

	/**
	 * LLM Tool 刷新结果。
	 *
	 * @param version   Tool 快照版本号
	 * @param toolCount 当前 Tool 数量
	 */
	public record ToolRefreshResult(Long version, Integer toolCount) {
	}
}
