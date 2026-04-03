package com.example.rag.tool;

import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 财务模块 Tool —— LLM 可调用的财务业务数据查询工具。
 * <p>
 * 继承 {@link BaseTool}，所有查询自动带上 ent_code 租户隔离条件。
 */
@Component
public class FinanceTool extends BaseTool {

	public FinanceTool(@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate) {
		super(erpJdbcTemplate);
	}

	/**
	 * 查询客户应收账款明细 + 账龄天数。
	 *
	 * @param customerName 客户名称，支持模糊匹配
	 * @return 应收账款明细列表，包含客户、发票号、发票日期、应收金额、已收金额、余额、账龄天数
	 */
	@Tool(description = "查询指定客户的应收账款明细和账龄")
	public List<Map<String, Object>> getReceivableAging(
			@ToolParam(description = "客户名称，支持模糊匹配") String customerName) {
		return query(
			"SELECT customer_name, invoice_no, invoice_date, receivable_amount, " +
			"received_amount, (receivable_amount - received_amount) AS balance, " +
			"DATEDIFF(CURDATE(), invoice_date) AS aging_days " +
			"FROM b_accounts_receivable " +
			"WHERE customer_name LIKE CONCAT('%',?,'%') AND receivable_amount > received_amount " +
			"ORDER BY aging_days DESC LIMIT 30", customerName);
	}

	/**
	 * 查询供应商应付账款明细 + 账龄天数。
	 *
	 * @param supplierName 供应商名称，支持模糊匹配
	 * @return 应付账款明细列表，包含供应商、发票号、发票日期、应付金额、已付金额、余额、账龄天数
	 */
	@Tool(description = "查询指定供应商的应付账款明细和账龄")
	public List<Map<String, Object>> getPayableAging(
			@ToolParam(description = "供应商名称，支持模糊匹配") String supplierName) {
		return query(
			"SELECT supplier_name, invoice_no, invoice_date, payable_amount, " +
			"paid_amount, (payable_amount - paid_amount) AS balance, " +
			"DATEDIFF(CURDATE(), invoice_date) AS aging_days " +
			"FROM b_accounts_payable " +
			"WHERE supplier_name LIKE CONCAT('%',?,'%') AND payable_amount > paid_amount " +
			"ORDER BY aging_days DESC LIMIT 30", supplierName);
	}

	/**
	 * 按年月查询收入/支出/净利润汇总。
	 *
	 * @param yearMonth 年月，格式 yyyy-MM，如 2026-03
	 * @return 月度财务汇总，包含总收入、总支出、净利润
	 */
	@Tool(description = "查询指定月份的收入支出汇总")
	public List<Map<String, Object>> getMonthlySummary(
			@ToolParam(description = "年月，格式 yyyy-MM，如 2026-03") String yearMonth) {
		return query(
			"SELECT " +
			"SUM(CASE WHEN type = '收入' THEN amount ELSE 0 END) AS total_income, " +
			"SUM(CASE WHEN type = '支出' THEN amount ELSE 0 END) AS total_expense, " +
			"SUM(CASE WHEN type = '收入' THEN amount ELSE -amount END) AS net_profit " +
			"FROM b_finance_ledger WHERE DATE_FORMAT(ledger_date, '%Y-%m') = ? " +
			"LIMIT 1", yearMonth);
	}

	/**
	 * 按时间范围查询收款流水记录。
	 *
	 * @param startDate 开始日期，格式 yyyy-MM-dd
	 * @param endDate   结束日期，格式 yyyy-MM-dd
	 * @return 收款记录列表，包含收款单号、客户、收款日期、金额、收款方式、关联单号、备注
	 */
	/**
	 * 按时间范围查询财务账簿明细（支持"最近/今天/本周/本月/本季度/今年"等自然语言时间段）。
	 *
	 * @param startDate 开始日期，格式 yyyy-MM-dd
	 * @param endDate   结束日期，格式 yyyy-MM-dd
	 * @return 账簿明细列表，包含日期、类型、科目、金额、描述、关联单号
	 */
	@Tool(description = "按时间范围查询财务收支明细记录，可用于查询最近、今天、本周、本月、本季度、今年等时间段的收支流水")
	public List<Map<String, Object>> getRecentLedgerEntries(
			@ToolParam(description = "开始日期，格式 yyyy-MM-dd") String startDate,
			@ToolParam(description = "结束日期，格式 yyyy-MM-dd") String endDate) {
		return query(
			"SELECT ledger_date, type, category, amount, description, reference_no " +
			"FROM b_finance_ledger WHERE ledger_date BETWEEN ? AND ? " +
			"ORDER BY ledger_date DESC LIMIT 50", startDate, endDate);
	}

	@Tool(description = "查询指定时间范围内的收款记录")
	public List<Map<String, Object>> getPaymentRecords(
			@ToolParam(description = "开始日期，格式 yyyy-MM-dd") String startDate,
			@ToolParam(description = "结束日期，格式 yyyy-MM-dd") String endDate) {
		return query(
			"SELECT payment_no, customer_name, payment_date, amount, " +
			"payment_method, reference_no, remark " +
			"FROM b_payment_record WHERE payment_date BETWEEN ? AND ? " +
			"ORDER BY payment_date DESC LIMIT 30", startDate, endDate);
	}

}
