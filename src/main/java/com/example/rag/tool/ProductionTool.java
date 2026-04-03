package com.example.rag.tool;

import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 生产模块 Tool —— LLM 可调用的生产制造业务数据查询工具。
 * <p>
 * 继承 {@link BaseTool}，所有查询自动带上 ent_code 租户隔离条件。
 */
@Component
public class ProductionTool extends BaseTool {

	public ProductionTool(@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate) {
		super(erpJdbcTemplate);
	}

	/**
	 * 按工单号查询工单状态和进度。
	 *
	 * @param workOrderNo 生产工单号
	 * @return 工单状态列表，包含产品名称、计划/完成/报废数量、状态、计划和实际起止日期
	 */
	@Tool(description = "查询生产工单的当前状态和完成进度，包括计划数量、完成数量、报废数量")
	public List<Map<String, Object>> getWorkOrderStatus(
			@ToolParam(description = "生产工单号") String workOrderNo) {
		return query(
			"SELECT wo_no, product_name, planned_qty, completed_qty, scrap_qty, " +
			"status, planned_start_date, planned_end_date, actual_start_date, actual_end_date " +
			"FROM b_work_order WHERE wo_no = ? LIMIT 10", workOrderNo);
	}

	/**
	 * 按产品编码或名称查询相关的所有生产工单。
	 *
	 * @param product 产品编码或产品名称，名称支持模糊匹配
	 * @return 生产工单列表，包含工单号、产品编码、产品名称、计划/完成数量、状态、计划起止日期
	 */
	@Tool(description = "查询指定产品的所有生产工单列表")
	public List<Map<String, Object>> getWorkOrdersByProduct(
			@ToolParam(description = "产品编码或产品名称") String product) {
		return query(
			"SELECT wo_no, product_code, product_name, planned_qty, completed_qty, " +
			"status, planned_start_date, planned_end_date " +
			"FROM b_work_order WHERE product_code = ? OR product_name LIKE CONCAT('%',?,'%') " +
			"ORDER BY planned_start_date DESC LIMIT 20", product, product);
	}

	/**
	 * 查询工单 BOM 领料情况。
	 *
	 * @param workOrderNo 生产工单号
	 * @return 用料领料列表，包含物料编码、物料名称、需求数量、已领数量、退料数量、单位
	 */
	@Tool(description = "查询生产工单的用料领料情况")
	public List<Map<String, Object>> getWorkOrderMaterials(
			@ToolParam(description = "生产工单号") String workOrderNo) {
		return query(
			"SELECT wo_no, material_code, material_name, required_qty, " +
			"issued_qty, returned_qty, unit " +
			"FROM b_work_order_material WHERE wo_no = ? LIMIT 50", workOrderNo);
	}

	/**
	 * 查询工单各工序的报工记录。
	 *
	 * @param workOrderNo 生产工单号
	 * @return 工序报工记录列表，包含工序序号、工序名称、工作中心、计划/完成/报废数量、状态、操作员、报工日期
	 */
	/**
	 * 按时间范围查询生产工单列表（支持"最近/今天/本周/本月/本季度/今年"等自然语言时间段）。
	 *
	 * @param startDate 开始日期，格式 yyyy-MM-dd
	 * @param endDate   结束日期，格式 yyyy-MM-dd
	 * @return 生产工单列表，包含工单号、产品、计划/完成/报废数量、状态、计划起止日期
	 */
	@Tool(description = "按时间范围查询生产工单列表，可用于查询最近、今天、本周、本月、本季度、今年等时间段的工单")
	public List<Map<String, Object>> getRecentWorkOrders(
			@ToolParam(description = "开始日期，格式 yyyy-MM-dd") String startDate,
			@ToolParam(description = "结束日期，格式 yyyy-MM-dd") String endDate) {
		return query(
			"SELECT wo_no, product_name, planned_qty, completed_qty, scrap_qty, " +
			"status, planned_start_date, planned_end_date " +
			"FROM b_work_order WHERE planned_start_date BETWEEN ? AND ? " +
			"ORDER BY planned_start_date DESC LIMIT 50", startDate, endDate);
	}

	@Tool(description = "查询生产工单的工序报工记录")
	public List<Map<String, Object>> getWorkOrderRouting(
			@ToolParam(description = "生产工单号") String workOrderNo) {
		return query(
			"SELECT wo_no, operation_seq, operation_name, work_center, " +
			"planned_qty, completed_qty, scrap_qty, status, operator, report_date " +
			"FROM b_work_order_routing WHERE wo_no = ? ORDER BY operation_seq", workOrderNo);
	}

}
