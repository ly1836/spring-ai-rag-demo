package com.example.rag.config;

import javax.sql.DataSource;

import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 双数据源配置。
 * <p>
 * 本项目同时连接两个数据库：
 * - PgVector（PostgreSQL）：存储产品手册的向量嵌入，供 RAG 检索使用
 * - ERP（MySQL）：存储业务数据（订单、库存、工单等），供 Tool Calling 查询使用
 * <p>
 * PgVector 数据源设为 @Primary，因为 Spring AI 的 VectorStore 自动配置需要它作为默认数据源。
 */
@Configuration
public class DataSourceConfig {

	/**
	 * PgVector 数据源属性，绑定 application.yml 中 spring.datasource.pgvector 配置。
	 */
	@Bean
	@ConfigurationProperties("spring.datasource.pgvector")
	public DataSourceProperties pgvectorDataSourceProperties() {
		return new DataSourceProperties();
	}

	/**
	 * 主数据源（PgVector），Spring AI 的 VectorStore 自动配置会使用这个数据源。
	 */
	@Primary
	@Bean
	public DataSource dataSource() {
		return pgvectorDataSourceProperties().initializeDataSourceBuilder().build();
	}

	/**
	 * ERP 数据源属性，绑定 application.yml 中 spring.datasource.erp 配置。
	 */
	@Bean
	@ConfigurationProperties("spring.datasource.erp")
	public DataSourceProperties erpDataSourceProperties() {
		return new DataSourceProperties();
	}

	/**
	 * ERP 数据源（MySQL），连接 ERP 业务数据库。
	 */
	@Bean
	public DataSource erpDataSource() {
		return erpDataSourceProperties().initializeDataSourceBuilder().build();
	}

	/**
	 * 主 JdbcTemplate（PgVector），供 Spring AI VectorStore 自动配置使用。
	 * 必须显式定义并标记 @Primary，否则 Spring Boot 的 JdbcTemplateAutoConfiguration
	 * 会因检测到 erpJdbcTemplate 已存在而跳过自动创建，导致 PgVectorStore 错误地使用 MySQL 连接。
	 */
	@Primary
	@Bean
	public JdbcTemplate jdbcTemplate() {
		return new JdbcTemplate(dataSource());
	}

	/**
	 * ERP 专用 JdbcTemplate，所有 Tool 类通过注入此 Bean 来查询 ERP 数据库。
	 */
	@Bean
	public JdbcTemplate erpJdbcTemplate() {
		return new JdbcTemplate(erpDataSource());
	}

}
