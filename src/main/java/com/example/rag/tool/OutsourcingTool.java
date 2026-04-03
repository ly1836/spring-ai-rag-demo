package com.example.rag.tool;

import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 委外模块 Tool —— LLM 可调用的委外加工业务数据查询工具。
 * <p>
 * 继承 {@link BaseTool}，所有查询自动带上 ent_code 租户隔离条件。
 */
@Component
public class OutsourcingTool extends BaseTool {

	public OutsourcingTool(@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate) {
		super(erpJdbcTemplate);
	}

	/**
	 * 按供应商名称查询委外加工订单列表。
	 *
	 * @param supplierName 供应商名称，支持模糊匹配
	 * @return 委外订单列表，包含委外单号、供应商、产品、数量、单价、金额、状态、交期
	 */
	@Tool(description = "查询委外加工订单列表，返回委外单号、供应商、产品、数量、状态")
	public List<Map<String, Object>> getOutsourcingOrders(
			@ToolParam(description = "供应商名称，支持模糊匹配") String supplierName) {
		return query(
			"SELECT oo_no, supplier_name, product_name, qty, unit_price, " +
			"total_amount, status, delivery_date " +
			"FROM b_outsourcing_order WHERE supplier_name LIKE CONCAT('%',?,'%') " +
			"ORDER BY delivery_date DESC LIMIT 20", supplierName);
	}

	/**
	 * 按委外单号查询详情和加工进度。
	 *
	 * @param ooNo 委外订单号
	 * @return 委外订单详情列表，包含供应商、产品、工序、完成数量、状态、交期、实际回货日期
	 */
	@Tool(description = "根据委外单号查询委外订单详情和进度")
	public List<Map<String, Object>> getOutsourcingOrderDetail(
			@ToolParam(description = "委外订单号") String ooNo) {
		return query(
			"SELECT oo_no, supplier_name, product_name, process_name, " +
			"qty, completed_qty, status, delivery_date, actual_return_date " +
			"FROM b_outsourcing_order WHERE oo_no = ? LIMIT 20", ooNo);
	}

	/**
	 * 查询委外单的来料和退料记录。
	 *
	 * @param ooNo 委外订单号
	 * @return 来料退料记录列表，包含流转类型、产品名称、数量、日期、备注
	 */
	/**
	 * 按时间范围查询委外加工订单列表（支持"最近/今天/本周/本月/本季度/今年"等自然语言时间段）。
	 *
	 * @param startDate 开始日期，格式 yyyy-MM-dd
	 * @param endDate   结束日期，格式 yyyy-MM-dd
	 * @return 委外订单列表，包含委外单号、供应商、产品、数量、金额、状态、交期
	 */
	@Tool(description = "按时间范围查询委外加工订单列表，可用于查询最近、今天、本周、本月、本季度、今年等时间段的委外单")
	public List<Map<String, Object>> getRecentOutsourcingOrders(
			@ToolParam(description = "开始日期，格式 yyyy-MM-dd") String startDate,
			@ToolParam(description = "结束日期，格式 yyyy-MM-dd") String endDate) {
		return query(
			"SELECT oo_no, supplier_name, product_name, qty, total_amount, status, delivery_date " +
			"FROM b_outsourcing_order WHERE delivery_date BETWEEN ? AND ? " +
			"ORDER BY delivery_date DESC LIMIT 50", startDate, endDate);
	}

	@Tool(description = "查询委外加工的来料和退料记录")
	public List<Map<String, Object>> getOutsourcingMaterialFlow(
			@ToolParam(description = "委外订单号") String ooNo) {
		return query(
			"SELECT oo_no, flow_type, product_name, qty, flow_date, remark " +
			"FROM b_outsourcing_material_flow WHERE oo_no = ? " +
			"ORDER BY flow_date DESC", ooNo);
	}

}
