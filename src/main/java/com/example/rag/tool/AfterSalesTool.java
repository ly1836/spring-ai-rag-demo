package com.example.rag.tool;

import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 售后模块 Tool —— LLM 可调用的售后服务业务数据查询工具。
 * <p>
 * 继承 {@link BaseTool}，所有查询自动带上 ent_code 租户隔离条件。
 */
@Component
public class AfterSalesTool extends BaseTool {

	public AfterSalesTool(@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate) {
		super(erpJdbcTemplate);
	}

	/**
	 * 按客户名称查询售后工单列表。
	 *
	 * @param customerName 客户名称，支持模糊匹配
	 * @return 售后工单列表，包含工单号、客户、产品、问题类型、问题描述、状态、优先级、创建/解决日期、处理人
	 */
	@Tool(description = "查询指定客户的售后工单记录，包括问题类型、处理状态")
	public List<Map<String, Object>> getAfterSalesTickets(
			@ToolParam(description = "客户名称，支持模糊匹配") String customerName) {
		return query(
			"SELECT ticket_no, customer_name, product_name, issue_type, " +
			"issue_description, status, priority, created_date, resolved_date, handler " +
			"FROM b_after_sales_ticket WHERE customer_name LIKE CONCAT('%',?,'%') " +
			"ORDER BY created_date DESC LIMIT 20", customerName);
	}

	/**
	 * 按工单号查询售后详情。
	 *
	 * @param ticketNo 售后工单号
	 * @return 工单详情列表，包含客户、联系人、电话、产品、序列号、问题类型/描述、根因、解决方案、状态、优先级
	 */
	@Tool(description = "根据售后工单号查询工单详情和处理记录")
	public List<Map<String, Object>> getTicketDetail(
			@ToolParam(description = "售后工单号") String ticketNo) {
		return query(
			"SELECT ticket_no, customer_name, contact_person, contact_phone, " +
			"product_name, serial_no, issue_type, issue_description, " +
			"root_cause, solution, status, priority, " +
			"created_date, resolved_date, handler " +
			"FROM b_after_sales_ticket WHERE ticket_no = ? LIMIT 10", ticketNo);
	}

	/**
	 * 按产品查询退换货记录。
	 *
	 * @param product 产品编码或产品名称，名称支持模糊匹配
	 * @return 退换货记录列表，包含退货单号、客户、产品、退换类型、数量、原因、状态、创建日期
	 */
	/**
	 * 按时间范围查询售后工单列表（支持"最近/今天/本周/本月/本季度/今年"等自然语言时间段）。
	 *
	 * @param startDate 开始日期，格式 yyyy-MM-dd
	 * @param endDate   结束日期，格式 yyyy-MM-dd
	 * @return 售后工单列表，包含工单号、客户、产品、问题类型、状态、优先级、创建日期、处理人
	 */
	@Tool(description = "按时间范围查询售后工单列表，可用于查询最近、今天、本周、本月、本季度、今年等时间段的售后工单")
	public List<Map<String, Object>> getRecentTickets(
			@ToolParam(description = "开始日期，格式 yyyy-MM-dd") String startDate,
			@ToolParam(description = "结束日期，格式 yyyy-MM-dd") String endDate) {
		return query(
			"SELECT ticket_no, customer_name, product_name, issue_type, " +
			"status, priority, created_date, handler " +
			"FROM b_after_sales_ticket WHERE created_date BETWEEN ? AND ? " +
			"ORDER BY created_date DESC LIMIT 50", startDate, endDate);
	}

	@Tool(description = "查询指定产品的售后退换货记录")
	public List<Map<String, Object>> getReturnOrders(
			@ToolParam(description = "产品编码或产品名称") String product) {
		return query(
			"SELECT return_no, customer_name, product_name, return_type, " +
			"qty, reason, status, created_date " +
			"FROM b_return_order WHERE product_code = ? OR product_name LIKE CONCAT('%',?,'%') " +
			"ORDER BY created_date DESC LIMIT 20", product, product);
	}

}
