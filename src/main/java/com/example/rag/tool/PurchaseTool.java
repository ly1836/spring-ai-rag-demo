package com.example.rag.tool;

import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 采购模块 Tool —— LLM 可调用的采购业务数据查询工具。
 * <p>
 * 继承 {@link BaseTool}，所有查询自动带上 ent_code 租户隔离条件。
 */
@Component
public class PurchaseTool extends BaseTool {

	public PurchaseTool(@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate) {
		super(erpJdbcTemplate);
	}

	/**
	 * 按供应商名称模糊查询采购订单列表。
	 *
	 * @param supplierName 供应商名称，支持模糊匹配
	 * @return 采购订单列表，包含采购单号、日期、供应商、金额、状态
	 */
	@Tool(description = "查询指定供应商的采购订单列表，返回采购单号、日期、金额、状态")
	public List<Map<String, Object>> getPurchaseOrders(
			@ToolParam(description = "供应商名称，支持模糊匹配") String supplierName) {
		return query(
			"SELECT po_no, po_date, supplier_name, total_amount, status " +
			"FROM b_purchase_order WHERE supplier_name LIKE CONCAT('%',?,'%') " +
			"ORDER BY po_date DESC LIMIT 20", supplierName);
	}

	/**
	 * 按采购单号查询主表 + 物料明细。
	 *
	 * @param poNo 采购订单号
	 * @return 采购订单详情列表，包含主表信息和物料明细（产品编码、名称、数量、单价、金额）
	 */
	@Tool(description = "根据采购单号查询采购订单详情，包括物料明细")
	public List<Map<String, Object>> getPurchaseOrderDetail(
			@ToolParam(description = "采购订单号") String poNo) {
		return queryWithAlias("o",
			"SELECT o.po_no, o.po_date, o.supplier_name, o.status, " +
			"d.product_code, d.product_name, d.qty, d.unit_price, d.amount " +
			"FROM b_purchase_order o JOIN b_purchase_order_detail d ON o.po_no = d.po_no " +
			"WHERE o.po_no = ? " +
			"LIMIT 50", poNo);
	}

	/**
	 * 查询采购订单的到货收货记录。
	 *
	 * @param poNo 采购订单号
	 * @return 收货记录列表，包含收货单号、收货日期、产品名称、订购数量、已收数量、状态
	 */
	@Tool(description = "查询采购订单的到货和收货状态")
	public List<Map<String, Object>> getPurchaseReceiveStatus(
			@ToolParam(description = "采购订单号") String poNo) {
		return query(
			"SELECT po_no, receive_no, receive_date, product_name, " +
			"ordered_qty, received_qty, status " +
			"FROM b_purchase_receive WHERE po_no = ? ORDER BY receive_date DESC", poNo);
	}

	/**
	 * 查询供应商应付账款汇总。
	 *
	 * @param supplierName 供应商名称，支持模糊匹配
	 * @return 应付账款汇总列表，包含总应付金额、已付金额、未付余额
	 */
	/**
	 * 按时间范围查询采购订单列表（支持"最近/今天/本周/本月/本季度/今年"等自然语言时间段）。
	 *
	 * @param startDate 开始日期，格式 yyyy-MM-dd
	 * @param endDate   结束日期，格式 yyyy-MM-dd
	 * @return 采购订单列表，包含采购单号、日期、供应商、金额、状态
	 */
	@Tool(description = "按时间范围查询采购订单列表，可用于查询最近、今天、本周、本月、本季度、今年等时间段的采购单")
	public List<Map<String, Object>> getRecentPurchaseOrders(
			@ToolParam(description = "开始日期，格式 yyyy-MM-dd") String startDate,
			@ToolParam(description = "结束日期，格式 yyyy-MM-dd") String endDate) {
		return query(
			"SELECT po_no, po_date, supplier_name, total_amount, status " +
			"FROM b_purchase_order WHERE po_date BETWEEN ? AND ? " +
			"ORDER BY po_date DESC LIMIT 50", startDate, endDate);
	}

	@Tool(description = "查询供应商的应付账款余额")
	public List<Map<String, Object>> getAccountsPayable(
			@ToolParam(description = "供应商名称") String supplierName) {
		return query(
			"SELECT supplier_name, SUM(payable_amount) AS total_payable, " +
			"SUM(paid_amount) AS total_paid, " +
			"SUM(payable_amount - paid_amount) AS balance " +
			"FROM b_accounts_payable WHERE supplier_name LIKE CONCAT('%',?,'%') " +
			"GROUP BY supplier_name", supplierName);
	}

}
