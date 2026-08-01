package com.example.rag.init;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import static java.util.Map.entry;

/**
 * ERP MySQL 数据库初始化器。
 * <p>
 * 初始化内容来自 {@code classpath:db/init/business-data.sql} 与 {@code classpath:db/init/conversation-billing-schema.sql}，
 * 运行时读取 classpath 下的同名脚本，DDL 直接幂等执行，DML 按业务键判断存在后再插入。
 */
@Component
public class ErpDatabaseInitializer implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(ErpDatabaseInitializer.class);

	private static final String CONVERSATION_BILLING_SCRIPT = "classpath:db/init/conversation-billing-schema.sql";

	private static final String BUSINESS_DATA_SCRIPT = "classpath:db/init/business-data.sql";

	private static final Pattern INSERT_PATTERN = Pattern.compile(
		"(?is)^INSERT\\s+INTO\\s+`?([a-zA-Z0-9_]+)`?\\s*\\((.*?)\\)\\s*VALUES\\s*(.*)$");

	private static final Map<String, List<String>> SEED_KEYS = Map.ofEntries(
		entry("a_tenant", List.of("ent_code")),
		entry("a_tenant_user", List.of("ent_code", "user_id")),
		entry("a_chat_conversation", List.of("conversation_id")),
		entry("a_chat_message", List.of("message_id")),
		entry("a_token_usage_daily", List.of("ent_code", "user_id", "usage_date", "model")),
		entry("a_token_usage_monthly", List.of("ent_code", "usage_month", "model")),
		entry("a_billing_plan", List.of("plan_code")),
		entry("a_billing_price_rule", List.of("model", "effective_date")),
		entry("a_billing_account", List.of("ent_code")),
		entry("a_billing_transaction", List.of("transaction_no")),
		entry("a_billing_invoice", List.of("invoice_no")),
		entry("a_llm_tool", List.of("tool_name")),
		entry("b_sales_order", List.of("order_no")),
		entry("b_sales_order_detail", List.of("order_no", "product_code", "product_name")),
		entry("b_shipment", List.of("shipment_no")),
		entry("b_accounts_receivable", List.of("invoice_no")),
		entry("b_purchase_order", List.of("po_no")),
		entry("b_purchase_order_detail", List.of("po_no", "product_code", "product_name")),
		entry("b_purchase_receive", List.of("receive_no")),
		entry("b_accounts_payable", List.of("invoice_no")),
		entry("b_work_order", List.of("wo_no")),
		entry("b_work_order_material", List.of("wo_no", "material_code", "material_name")),
		entry("b_work_order_routing", List.of("wo_no", "operation_seq")),
		entry("b_inventory", List.of("product_code", "warehouse", "lot_no")),
		entry("b_stock_movement", List.of("move_no")),
		entry("b_quality_inspection", List.of("lot_no", "inspection_type")),
		entry("b_quality_defect_detail", List.of("lot_no", "defect_code", "defect_name")),
		entry("b_after_sales_ticket", List.of("ticket_no")),
		entry("b_return_order", List.of("return_no")),
		entry("b_finance_ledger", List.of("type", "amount", "ledger_date", "remark")),
		entry("b_payment_record", List.of("payment_no")),
		entry("b_outsourcing_order", List.of("oo_no")),
		entry("b_outsourcing_material_flow", List.of("oo_no", "flow_type", "product_name", "flow_date"))
	);

	private final JdbcTemplate erpJdbcTemplate;

	private final ResourceLoader resourceLoader;

	/**
	 * 创建 ERP MySQL 初始化器。
	 *
	 * @param erpJdbcTemplate ERP MySQL 专用 JdbcTemplate
	 * @param resourceLoader  classpath 脚本加载器
	 */
	public ErpDatabaseInitializer(
			@Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate,
			ResourceLoader resourceLoader) {
		this.erpJdbcTemplate = erpJdbcTemplate;
		this.resourceLoader = resourceLoader;
	}

	/**
	 * Spring Boot 启动后执行 ERP MySQL 初始化。
	 *
	 * @param args 应用启动参数
	 */
	@Override
	public void run(ApplicationArguments args) {
		initialize();
	}

	/**
	 * 执行 ERP MySQL 表结构与演示数据初始化。
	 */
	public void initialize() {
		log.info("开始初始化 ERP MySQL 表结构和演示数据");
		executeScript(CONVERSATION_BILLING_SCRIPT);
		ensureChatMessageChartSpecColumn();
		executeScript(BUSINESS_DATA_SCRIPT);
		log.info("ERP MySQL 表结构和演示数据初始化完成");
	}

	/**
	 * 读取并执行指定 classpath SQL 脚本。
	 *
	 * @param location SQL 脚本资源路径
	 */
	private void executeScript(String location) {
		String script = readScript(location);
		String normalizedScript = stripLineComments(script);
		for (String statement : splitSqlStatements(normalizedScript)) {
			executeStatement(statement);
		}
	}

	/**
	 * 从 classpath 中读取 UTF-8 SQL 脚本内容。
	 *
	 * @param location SQL 脚本资源路径
	 * @return 脚本文本
	 */
	private String readScript(String location) {
		Resource resource = resourceLoader.getResource(location);
		if (!resource.exists()) {
			throw new IllegalStateException("初始化 SQL 脚本不存在: " + location);
		}
		try (InputStream inputStream = resource.getInputStream()) {
			return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException("读取初始化 SQL 脚本失败: " + location, ex);
		}
	}

	/**
	 * 执行单条 SQL 语句，DML 会先做幂等判断。
	 *
	 * @param statement SQL 语句
	 */
	private void executeStatement(String statement) {
		String trimmed = statement.trim();
		if (trimmed.isEmpty()) {
			return;
		}
		if (isInsertStatement(trimmed)) {
			executeSeedInsert(trimmed);
			return;
		}
		erpJdbcTemplate.execute(trimmed);
	}

	/**
	 * 判断 SQL 是否为种子数据插入语句。
	 *
	 * @param statement SQL 语句
	 * @return true 表示 INSERT 语句
	 */
	private boolean isInsertStatement(String statement) {
		return statement.regionMatches(true, 0, "INSERT INTO", 0, "INSERT INTO".length());
	}

	/**
	 * 执行带幂等保护的种子数据插入。
	 *
	 * @param statement INSERT 语句
	 */
	private void executeSeedInsert(String statement) {
		InsertStatement insertStatement = parseInsertStatement(statement);
		List<String> keyColumns = SEED_KEYS.get(insertStatement.tableName);
		if (keyColumns == null) {
			throw new IllegalStateException("缺少种子数据幂等键配置: " + insertStatement.tableName);
		}
		for (String rowSql : splitValueRows(insertStatement.valuesSql)) {
			if (!seedExists(insertStatement, rowSql, keyColumns)) {
				erpJdbcTemplate.execute(buildSingleInsertSql(insertStatement, rowSql));
			}
		}
	}

	/**
	 * 解析 INSERT 语句中的表名、字段和 VALUES 片段。
	 *
	 * @param statement INSERT 语句
	 * @return 解析后的 INSERT 信息
	 */
	private InsertStatement parseInsertStatement(String statement) {
		Matcher matcher = INSERT_PATTERN.matcher(statement);
		if (!matcher.matches()) {
			throw new IllegalStateException("无法解析初始化 INSERT 语句: " + statement);
		}
		String tableName = matcher.group(1).toLowerCase(Locale.ROOT);
		List<String> columns = splitColumns(matcher.group(2));
		return new InsertStatement(tableName, columns, matcher.group(3).trim());
	}

	/**
	 * 查询指定种子数据行是否已经存在。
	 *
	 * @param insertStatement INSERT 语句信息
	 * @param rowSql          单行 VALUES 内容
	 * @param keyColumns      幂等判断字段
	 * @return true 表示该行已经存在
	 */
	private boolean seedExists(InsertStatement insertStatement, String rowSql, List<String> keyColumns) {
		Map<String, String> rowValues = mapRowValues(insertStatement.columns, rowSql);
		List<Object> args = new ArrayList<>();
		StringBuilder sql = new StringBuilder("SELECT COUNT(1) FROM ");
		sql.append(insertStatement.tableName).append(" WHERE ");
		for (int i = 0; i < keyColumns.size(); i++) {
			if (i > 0) {
				sql.append(" AND ");
			}
			String column = keyColumns.get(i);
			sql.append(column).append(" = ?");
			args.add(toSqlParameter(rowValues.get(column)));
		}
		Integer count = erpJdbcTemplate.queryForObject(sql.toString(), Integer.class, args.toArray());
		return count != null && count > 0;
	}

	/**
	 * 构建单行 INSERT 语句，避免重复执行整批 VALUES。
	 *
	 * @param insertStatement INSERT 语句信息
	 * @param rowSql          单行 VALUES 内容
	 * @return 单行 INSERT SQL
	 */
	private String buildSingleInsertSql(InsertStatement insertStatement, String rowSql) {
		return "INSERT INTO " + insertStatement.tableName + " (" + String.join(", ", insertStatement.columns)
			+ ") VALUES (" + rowSql + ")";
	}

	/**
	 * 将字段名和单行 VALUES 内容映射为列值。
	 *
	 * @param columns 字段名列表
	 * @param rowSql  单行 VALUES 内容
	 * @return 字段到原始 SQL 值的映射
	 */
	private Map<String, String> mapRowValues(List<String> columns, String rowSql) {
		List<String> values = splitValues(rowSql);
		if (columns.size() != values.size()) {
			throw new IllegalStateException("初始化 INSERT 字段和值数量不一致: " + rowSql);
		}
		Map<String, String> rowValues = new LinkedHashMap<>();
		for (int i = 0; i < columns.size(); i++) {
			rowValues.put(columns.get(i), values.get(i));
		}
		return rowValues;
	}

	/**
	 * 将 SQL 原始字面量转换为 JdbcTemplate 查询参数。
	 *
	 * @param rawValue SQL 原始字面量
	 * @return 查询参数值
	 */
	private Object toSqlParameter(String rawValue) {
		String value = rawValue.trim();
		if ("NULL".equalsIgnoreCase(value)) {
			return null;
		}
		if (value.startsWith("'") && value.endsWith("'")) {
			return value.substring(1, value.length() - 1).replace("''", "'");
		}
		if (value.matches("-?\\d+")) {
			return Long.valueOf(value);
		}
		if (value.matches("-?\\d+\\.\\d+")) {
			return new BigDecimal(value);
		}
		return value;
	}

	/**
	 * 拆分 INSERT 语句中的字段列表。
	 *
	 * @param columnsSql 字段 SQL 片段
	 * @return 小写字段名列表
	 */
	private List<String> splitColumns(String columnsSql) {
		List<String> columns = new ArrayList<>();
		for (String column : columnsSql.split(",")) {
			columns.add(column.trim().replace("`", "").toLowerCase(Locale.ROOT));
		}
		return columns;
	}

	/**
	 * 拆分 VALUES 后的多行数据。
	 *
	 * @param valuesSql VALUES SQL 片段
	 * @return 单行 VALUES 内容列表，不含外层括号
	 */
	private List<String> splitValueRows(String valuesSql) {
		List<String> rows = new ArrayList<>();
		boolean inQuote = false;
		int depth = 0;
		int rowStart = -1;
		for (int i = 0; i < valuesSql.length(); i++) {
			char ch = valuesSql.charAt(i);
			if (ch == '\'' && !isEscapedQuote(valuesSql, i)) {
				inQuote = !inQuote;
			}
			if (inQuote) {
				continue;
			}
			if (ch == '(') {
				if (depth == 0) {
					rowStart = i + 1;
				}
				depth++;
			}
			else if (ch == ')') {
				depth--;
				if (depth == 0 && rowStart >= 0) {
					rows.add(valuesSql.substring(rowStart, i).trim());
				}
			}
		}
		return rows;
	}

	/**
	 * 拆分单行 VALUES 内容中的值列表。
	 *
	 * @param rowSql 单行 VALUES 内容
	 * @return 原始 SQL 值列表
	 */
	private List<String> splitValues(String rowSql) {
		List<String> values = new ArrayList<>();
		boolean inQuote = false;
		int start = 0;
		for (int i = 0; i < rowSql.length(); i++) {
			char ch = rowSql.charAt(i);
			if (ch == '\'' && !isEscapedQuote(rowSql, i)) {
				inQuote = !inQuote;
			}
			if (!inQuote && ch == ',') {
				values.add(rowSql.substring(start, i).trim());
				start = i + 1;
			}
		}
		values.add(rowSql.substring(start).trim());
		return values;
	}

	/**
	 * 去除 SQL 脚本中位于字符串外部的单行注释。
	 *
	 * @param script SQL 脚本文本
	 * @return 去除注释后的文本
	 */
	private String stripLineComments(String script) {
		StringBuilder result = new StringBuilder(script.length());
		boolean inQuote = false;
		for (int i = 0; i < script.length(); i++) {
			char ch = script.charAt(i);
			if (ch == '\'' && !isEscapedQuote(script, i)) {
				inQuote = !inQuote;
			}
			if (!inQuote && ch == '-' && i + 1 < script.length() && script.charAt(i + 1) == '-') {
				while (i < script.length() && script.charAt(i) != '\n') {
					i++;
				}
				if (i < script.length()) {
					result.append(script.charAt(i));
				}
				continue;
			}
			result.append(ch);
		}
		return result.toString();
	}

	/**
	 * 按分号拆分 SQL 脚本，避免切分字符串字面量中的内容。
	 *
	 * @param script SQL 脚本文本
	 * @return SQL 语句列表
	 */
	private List<String> splitSqlStatements(String script) {
		List<String> statements = new ArrayList<>();
		boolean inQuote = false;
		int start = 0;
		for (int i = 0; i < script.length(); i++) {
			char ch = script.charAt(i);
			if (ch == '\'' && !isEscapedQuote(script, i)) {
				inQuote = !inQuote;
			}
			if (!inQuote && ch == ';') {
				statements.add(script.substring(start, i).trim());
				start = i + 1;
			}
		}
		String tail = script.substring(start).trim();
		if (!tail.isEmpty()) {
			statements.add(tail);
		}
		return statements;
	}

	/**
	 * 判断当前位置的单引号是否为 SQL 双单引号转义。
	 *
	 * @param text  SQL 文本
	 * @param index 当前字符位置
	 * @return true 表示该单引号是转义内容
	 */
	private boolean isEscapedQuote(String text, int index) {
		return index + 1 < text.length() && text.charAt(index + 1) == '\''
			|| index > 0 && text.charAt(index - 1) == '\'';
	}

	/**
	 * 保存 INSERT 语句解析结果。
	 */
	private static final class InsertStatement {

		private final String tableName;

		private final List<String> columns;

		private final String valuesSql;

		/**
		 * 创建 INSERT 语句解析结果。
		 *
		 * @param tableName 表名
		 * @param columns   字段列表
		 * @param valuesSql VALUES SQL 片段
		 */
		private InsertStatement(String tableName, List<String> columns, String valuesSql) {
			this.tableName = tableName;
			this.columns = columns;
			this.valuesSql = valuesSql;
		}

	}

	/**
	 * 确保已有对话消息表包含图表字段。
	 */
	private void ensureChatMessageChartSpecColumn() {
		Integer count = erpJdbcTemplate.queryForObject(
			"SELECT COUNT(1) FROM information_schema.COLUMNS "
				+ "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
			Integer.class, "a_chat_message", "chart_spec");
		if (count == null || count == 0) {
			log.info("检测到对话消息表缺少图表字段，开始执行幂等升级");
			erpJdbcTemplate.execute("ALTER TABLE a_chat_message ADD COLUMN chart_spec TEXT NULL "
				+ "COMMENT '助手图表数据（ChartSpec JSON，最大60KiB）' AFTER tool_calls_count");
		}
	}

}
