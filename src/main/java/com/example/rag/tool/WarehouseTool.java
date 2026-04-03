package com.example.rag.tool;

import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 仓库模块 Tool —— LLM 可调用的库存仓储业务数据查询工具。
 * <p>
 * 继承 {@link BaseTool}，所有查询自动带上 ent_code 租户隔离条件。
 */
@Component
public class WarehouseTool extends BaseTool {

	public WarehouseTool(@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate) {
		super(erpJdbcTemplate);
	}

	/**
	 * 按产品编码或名称查询各仓库的库存。
	 *
	 * @param product 产品编码或产品名称，名称支持模糊匹配
	 * @return 库存列表，包含产品编码、产品名称、仓库、可用库存、预留库存、在途库存、单位
	 */
	@Tool(description = "查询指定产品的当前库存数量，包括各仓库的可用库存和在途库存")
	public List<Map<String, Object>> getInventory(
			@ToolParam(description = "产品编码或产品名称") String product) {
		return query(
			"SELECT product_code, product_name, warehouse, available_qty, " +
			"reserved_qty, in_transit_qty, unit " +
			"FROM b_inventory WHERE product_code = ? OR product_name LIKE CONCAT('%',?,'%') " +
			"LIMIT 20", product, product);
	}

	/**
	 * 查询某个仓库中所有产品的库存明细。
	 *
	 * @param warehouse 仓库名称或仓库编码，支持模糊匹配
	 * @return 库存明细列表，包含产品编码、产品名称、批次号、可用库存、预留库存、单位、库位
	 */
	@Tool(description = "查询指定仓库的所有库存明细")
	public List<Map<String, Object>> getWarehouseStock(
			@ToolParam(description = "仓库名称或仓库编码") String warehouse) {
		return query(
			"SELECT product_code, product_name, lot_no, available_qty, " +
			"reserved_qty, unit, location " +
			"FROM b_inventory WHERE warehouse = ? OR warehouse LIKE CONCAT('%',?,'%') " +
			"ORDER BY product_code LIMIT 50", warehouse, warehouse);
	}

	/**
	 * 查询产品的出入库流水记录。
	 *
	 * @param product 产品编码或产品名称，名称支持模糊匹配
	 * @return 出入库记录列表，包含单号、类型、产品、数量、源/目标仓库、日期、关联单号、备注
	 */
	@Tool(description = "查询指定产品的出入库记录")
	public List<Map<String, Object>> getStockMovements(
			@ToolParam(description = "产品编码或产品名称") String product) {
		return query(
			"SELECT move_no, move_type, product_code, product_name, qty, " +
			"from_warehouse, to_warehouse, move_date, reference_no, remark " +
			"FROM b_stock_movement WHERE product_code = ? OR product_name LIKE CONCAT('%',?,'%') " +
			"ORDER BY move_date DESC LIMIT 20", product, product);
	}

	/**
	 * 库存预警：可用库存低于安全库存的产品。
	 *
	 * @return 库存预警列表，包含产品编码、产品名称、仓库、可用库存、安全库存、单位、缺口数量，按缺口降序
	 */
	/**
	 * 按时间范围查询出入库记录（支持"最近/今天/本周/本月/本季度/今年"等自然语言时间段）。
	 *
	 * @param startDate 开始日期，格式 yyyy-MM-dd
	 * @param endDate   结束日期，格式 yyyy-MM-dd
	 * @return 出入库记录列表，包含单号、类型、产品、数量、源/目标仓库、日期、关联单号
	 */
	@Tool(description = "按时间范围查询出入库记录，可用于查询最近、今天、本周、本月、本季度、今年等时间段的库存变动")
	public List<Map<String, Object>> getRecentStockMovements(
			@ToolParam(description = "开始日期，格式 yyyy-MM-dd") String startDate,
			@ToolParam(description = "结束日期，格式 yyyy-MM-dd") String endDate) {
		return query(
			"SELECT move_no, move_type, product_name, qty, " +
			"from_warehouse, to_warehouse, move_date, reference_no " +
			"FROM b_stock_movement WHERE move_date BETWEEN ? AND ? " +
			"ORDER BY move_date DESC LIMIT 50", startDate, endDate);
	}

	@Tool(description = "查询库存预警：低于安全库存的产品列表")
	public List<Map<String, Object>> getLowStockAlerts() {
		return query(
			"SELECT product_code, product_name, warehouse, available_qty, " +
			"safety_stock, unit, (safety_stock - available_qty) AS shortage " +
			"FROM b_inventory WHERE available_qty < safety_stock " +
			"ORDER BY shortage DESC LIMIT 30");
	}

}
