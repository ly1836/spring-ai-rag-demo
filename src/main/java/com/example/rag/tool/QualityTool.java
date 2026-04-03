package com.example.rag.tool;

import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 质检模块 Tool —— LLM 可调用的质量检验业务数据查询工具。
 * <p>
 * 继承 {@link BaseTool}，所有查询自动带上 ent_code 租户隔离条件。
 */
@Component
public class QualityTool extends BaseTool {

	public QualityTool(@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate) {
		super(erpJdbcTemplate);
	}

	/**
	 * 按批次号查询质检结果。
	 *
	 * @param lotNo 批次号
	 * @return 质检结果列表，包含产品名称、检验类型、检验日期、检验员、抽样/合格/不良数量、结果、备注
	 */
	@Tool(description = "根据批次号查询质检结果，包括检验项目、检验结果、不良数量")
	public List<Map<String, Object>> getQualityInspection(
			@ToolParam(description = "批次号") String lotNo) {
		return query(
			"SELECT lot_no, product_name, inspection_type, inspection_date, " +
			"inspector, sample_qty, pass_qty, defect_qty, result, remark " +
			"FROM b_quality_inspection WHERE lot_no = ? LIMIT 20", lotNo);
	}

	/**
	 * 按产品查询历史质检记录列表。
	 *
	 * @param product 产品编码或产品名称，名称支持模糊匹配
	 * @return 质检记录列表，包含批次号、产品编码、产品名称、检验类型、检验日期、结果、不良数量
	 */
	@Tool(description = "查询指定产品的质检记录列表")
	public List<Map<String, Object>> getQualityByProduct(
			@ToolParam(description = "产品编码或产品名称") String product) {
		return query(
			"SELECT lot_no, product_code, product_name, inspection_type, " +
			"inspection_date, result, defect_qty " +
			"FROM b_quality_inspection " +
			"WHERE product_code = ? OR product_name LIKE CONCAT('%',?,'%') " +
			"ORDER BY inspection_date DESC LIMIT 20", product, product);
	}

	/**
	 * 查询某批次的不良明细。
	 *
	 * @param lotNo 批次号
	 * @return 不良明细列表，包含不良代码、不良名称、不良数量、不良等级、处理方式、备注
	 */
	@Tool(description = "查询指定批次的质检不良明细")
	public List<Map<String, Object>> getDefectDetails(
			@ToolParam(description = "批次号") String lotNo) {
		return query(
			"SELECT lot_no, defect_code, defect_name, defect_qty, " +
			"defect_level, handling_method, remark " +
			"FROM b_quality_defect_detail WHERE lot_no = ? LIMIT 50", lotNo);
	}

	/**
	 * 按时间范围统计各产品的质检合格率。
	 *
	 * @param startDate 开始日期，格式 yyyy-MM-dd
	 * @param endDate   结束日期，格式 yyyy-MM-dd
	 * @return 合格率统计列表，包含产品名称、总检验次数、合格次数、合格率（%），按合格率升序
	 */
	/**
	 * 按时间范围查询质检记录列表（支持"最近/今天/本周/本月/本季度/今年"等自然语言时间段）。
	 *
	 * @param startDate 开始日期，格式 yyyy-MM-dd
	 * @param endDate   结束日期，格式 yyyy-MM-dd
	 * @return 质检记录列表，包含批次号、产品、检验类型、日期、检验员、抽样/合格/不良数量、结果
	 */
	@Tool(description = "按时间范围查询质检记录列表，可用于查询最近、今天、本周、本月、本季度、今年等时间段的质检记录")
	public List<Map<String, Object>> getRecentInspections(
			@ToolParam(description = "开始日期，格式 yyyy-MM-dd") String startDate,
			@ToolParam(description = "结束日期，格式 yyyy-MM-dd") String endDate) {
		return query(
			"SELECT lot_no, product_name, inspection_type, inspection_date, " +
			"inspector, sample_qty, pass_qty, defect_qty, result " +
			"FROM b_quality_inspection WHERE inspection_date BETWEEN ? AND ? " +
			"ORDER BY inspection_date DESC LIMIT 50", startDate, endDate);
	}

	@Tool(description = "查询指定时间范围的质检合格率统计")
	public List<Map<String, Object>> getQualityPassRate(
			@ToolParam(description = "开始日期，格式 yyyy-MM-dd") String startDate,
			@ToolParam(description = "结束日期，格式 yyyy-MM-dd") String endDate) {
		return query(
			"SELECT product_name, COUNT(*) AS total_inspections, " +
			"SUM(CASE WHEN result = '合格' THEN 1 ELSE 0 END) AS pass_count, " +
			"ROUND(SUM(CASE WHEN result = '合格' THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 2) AS pass_rate " +
			"FROM b_quality_inspection WHERE inspection_date BETWEEN ? AND ? " +
			"GROUP BY product_name ORDER BY pass_rate ASC", startDate, endDate);
	}

}
