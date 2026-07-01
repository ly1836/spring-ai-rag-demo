package com.example.rag.tool.dynamic;

/**
 * 动态 SQL 租户条件注入结果。
 *
 * @param sql                  注入租户条件后的 SQL
 * @param tenantParameterIndex 租户参数在 JDBC 参数列表中的下标
 */
public record TenantSqlInjection(String sql, int tenantParameterIndex) {
}
