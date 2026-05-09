package com.example.rag.config;

import javax.sql.DataSource;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * ERP MySQL 专用 MyBatis-Plus 配置。
 * <p>
 * MyBatis-Plus 只绑定 ERP 数据源，避免影响 PgVector 主数据源和 Spring AI 自动配置。
 */
@Configuration
@EnableConfigurationProperties(TenantProperties.class)
@MapperScan(
	basePackages = "com.example.rag.dao.mapper",
	sqlSessionTemplateRef = "erpSqlSessionTemplate"
)
public class ErpMybatisPlusConfig {

	/**
	 * ERP 专用 MyBatis-Plus 拦截器，统一处理租户隔离。
	 *
	 * @param tenantProperties 租户隔离配置
	 * @return MyBatis-Plus 拦截器链
	 */
	@Bean
	public MybatisPlusInterceptor erpMybatisPlusInterceptor(TenantProperties tenantProperties) {
		MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
		interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
			@Override
			public Expression getTenantId() {
				return new StringValue(TenantContext.requireEntCode());
			}

			@Override
			public String getTenantIdColumn() {
				return tenantProperties.getColumn();
			}

			@Override
			public boolean ignoreTable(String tableName) {
				return tenantProperties.isIgnoredTable(tableName);
			}
		}));
		return interceptor;
	}

	/**
	 * ERP 专用 SqlSessionFactory。
	 *
	 * @param erpDataSource ERP MySQL 数据源
	 * @param interceptor   MyBatis-Plus 拦截器链
	 * @return 绑定 ERP 数据源的 SqlSessionFactory
	 * @throws Exception SqlSessionFactory 初始化异常
	 */
	@Bean
	public SqlSessionFactory erpSqlSessionFactory(
			@Qualifier("erpDataSource") DataSource erpDataSource,
			MybatisPlusInterceptor interceptor) throws Exception {
		MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
		factoryBean.setDataSource(erpDataSource);
		factoryBean.setPlugins(interceptor);
		return factoryBean.getObject();
	}

	/**
	 * ERP 专用 SqlSessionTemplate。
	 *
	 * @param erpSqlSessionFactory ERP SqlSessionFactory
	 * @return ERP SqlSessionTemplate
	 */
	@Bean
	public SqlSessionTemplate erpSqlSessionTemplate(
			@Qualifier("erpSqlSessionFactory") SqlSessionFactory erpSqlSessionFactory) {
		return new SqlSessionTemplate(erpSqlSessionFactory);
	}

	/**
	 * ERP 专用事务管理器。
	 *
	 * @param erpDataSource ERP MySQL 数据源
	 * @return ERP 事务管理器
	 */
	@Bean
	public PlatformTransactionManager erpTransactionManager(
			@Qualifier("erpDataSource") DataSource erpDataSource) {
		return new DataSourceTransactionManager(erpDataSource);
	}

	/**
	 * 公共字段自动填充处理器。
	 * <p>
	 * 当前保留 MyBatis-Plus 扩展点，业务写入仍显式设置租户、时间等关键字段，避免和数据库默认值冲突。
	 *
	 * @return MetaObjectHandler 实例
	 */
	@Bean
	public MetaObjectHandler erpMetaObjectHandler() {
		return new MetaObjectHandler() {
			@Override
			public void insertFill(MetaObject metaObject) {
				strictInsertFill(metaObject, "entCode", String.class, TenantContext.getEntCode());
			}

			@Override
			public void updateFill(MetaObject metaObject) {
				// 更新时间由 MySQL 的 ON UPDATE CURRENT_TIMESTAMP 或自定义 SQL 维护。
			}
		};
	}

}
