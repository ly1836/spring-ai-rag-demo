package com.example.rag.tool;

import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 销售模块 Tool —— LLM 可调用的销售业务数据查询工具。
 * <p>
 * 继承 {@link BaseTool}，所有查询自动带上 ent_code 租户隔离条件。
 * SQL 中的表名和字段名需要根据实际 ERP 表结构修改。
 */
@Component
public class SalesTool extends BaseTool {

	public SalesTool(@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate) {
		super(erpJdbcTemplate);
	}

	/**
	 * 按客户名称模糊查询销售订单列表。
	 *
	 * @param customerName 客户名称，支持模糊匹配
	 * @return 销售订单列表，包含订单号、下单日期、产品、数量、金额、状态
	 */
	@Tool(description = "根据客户名称查询销售订单列表，返回订单号、下单日期、产品、数量、金额、状态")
	public List<Map<String, Object>> getSalesOrders(
			@ToolParam(description = "客户名称，支持模糊匹配") String customerName) {
		return query(
			"SELECT order_no, order_date, customer_name, product_name, qty, total_amount, status " +
			"FROM b_sales_order WHERE customer_name LIKE CONCAT('%',?,'%') " +
			"ORDER BY order_date DESC LIMIT 20", customerName);
	}

	/**
	 * 按订单号查询销售订单主表 + 明细行。
	 *
	 * @param orderNo 销售订单号
	 * @return 订单详情列表，包含主表信息和明细行（产品编码、名称、数量、单价、金额）
	 */
	@Tool(description = "根据订单号查询销售订单详情，包括订单明细行")
	public List<Map<String, Object>> getSalesOrderDetail(
			@ToolParam(description = "销售订单号") String orderNo) {
		return queryWithAlias("o",
			"SELECT o.order_no, o.order_date, o.customer_name, o.status, " +
			"d.product_code, d.product_name, d.qty, d.unit_price, d.amount " +
			"FROM b_sales_order o JOIN b_sales_order_detail d ON o.order_no = d.order_no " +
			"WHERE o.order_no = ? " +
			"LIMIT 50", orderNo);
	}

	/**
	 * 查询指定订单的发货和物流跟踪信息。
	 *
	 * @param orderNo 销售订单号
	 * @return 发货记录列表，包含发货单号、发货日期、承运商、物流单号、已发数量、状态
	 */
	@Tool(description = "查询销售订单的发货状态和物流信息")
	public List<Map<String, Object>> getShipmentStatus(
			@ToolParam(description = "销售订单号") String orderNo) {
		return query(
			"SELECT order_no, b_shipment_no, ship_date, carrier, tracking_no, " +
			"shipped_qty, status " +
			"FROM b_shipment WHERE order_no = ? ORDER BY ship_date DESC", orderNo);
	}

	/**
	 * 查询客户应收账款汇总：总应收、已收、余额。
	 *
	 * @param customerName 客户名称，支持模糊匹配
	 * @return 应收账款汇总列表，包含总应收金额、已收金额、未收余额
	 */
	@Tool(description = "查询客户的应收账款余额，包括已收金额和未收余额")
	public List<Map<String, Object>> getAccountsReceivable(
			@ToolParam(description = "客户名称") String customerName) {
		return query(
			"SELECT customer_name, SUM(receivable_amount) AS total_receivable, " +
			"SUM(received_amount) AS total_received, " +
			"SUM(receivable_amount - received_amount) AS balance " +
			"FROM b_accounts_receivable WHERE customer_name LIKE CONCAT('%',?,'%') " +
			"GROUP BY customer_name", customerName);
	}

	/**
	 * 按时间范围统计销售：订单数、总金额、客户数。
	 *
	 * @param startDate 开始日期，格式 yyyy-MM-dd
	 * @param endDate   结束日期，格式 yyyy-MM-dd
	 * @return 销售汇总统计，包含订单数、总金额、客户数
	 */
	/**
	 * 按时间范围查询销售订单列表（支持"最近/今天/本周/本月/本季度/今年"等自然语言时间段）。
	 *
	 * @param startDate 开始日期，格式 yyyy-MM-dd
	 * @param endDate   结束日期，格式 yyyy-MM-dd
	 * @return 销售订单列表，包含订单号、下单日期、客户、产品、数量、金额、状态
	 */
	@Tool(description = "按时间范围查询销售订单列表，可用于查询最近、今天、本周、本月、本季度、今年等时间段的订单")
	public List<Map<String, Object>> getRecentSalesOrders(
			@ToolParam(description = "开始日期，格式 yyyy-MM-dd") String startDate,
			@ToolParam(description = "结束日期，格式 yyyy-MM-dd") String endDate) {
		return query(
			"SELECT order_no, order_date, customer_name, product_name, qty, total_amount, status " +
			"FROM b_sales_order WHERE order_date BETWEEN ? AND ? " +
			"ORDER BY order_date DESC LIMIT 50", startDate, endDate);
	}

	@Tool(description = "查询指定时间范围的销售汇总统计，包括订单数、总金额")
	public List<Map<String, Object>> getSalesSummary(
			@ToolParam(description = "开始日期，格式 yyyy-MM-dd") String startDate,
			@ToolParam(description = "结束日期，格式 yyyy-MM-dd") String endDate) {
		return query(
			"SELECT COUNT(*) AS order_count, SUM(total_amount) AS total_amount, " +
			"COUNT(DISTINCT customer_name) AS customer_count " +
			"FROM b_sales_order WHERE order_date BETWEEN ? AND ?", startDate, endDate);
	}

}
