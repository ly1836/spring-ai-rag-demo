package com.example.rag.billing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.example.rag.dao.entity.BillingTransactionEntity;
import com.example.rag.dao.mapper.BillingAccountMapper;
import com.example.rag.dao.mapper.BillingPlanMapper;
import com.example.rag.dao.mapper.BillingPriceRuleMapper;
import com.example.rag.dao.mapper.BillingTransactionMapper;
import com.example.rag.dao.mapper.TokenUsageDailyMapper;
import com.example.rag.dao.mapper.TokenUsageMonthlyMapper;
import com.example.rag.config.TenantContext;
import com.example.rag.vo.BillingVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计费服务测试。
 */
class BillingServiceTest {

	private BillingAccountMapper accountMapper;
	private BillingPlanMapper planMapper;
	private BillingPriceRuleMapper priceRuleMapper;
	private BillingTransactionMapper transactionMapper;
	private TokenUsageDailyMapper dailyMapper;
	private TokenUsageMonthlyMapper monthlyMapper;
	private BillingService service;

	@BeforeEach
	void setUp() {
		accountMapper = mock(BillingAccountMapper.class);
		planMapper = mock(BillingPlanMapper.class);
		priceRuleMapper = mock(BillingPriceRuleMapper.class);
		transactionMapper = mock(BillingTransactionMapper.class);
		dailyMapper = mock(TokenUsageDailyMapper.class);
		monthlyMapper = mock(TokenUsageMonthlyMapper.class);
		service = new BillingService(accountMapper, planMapper, priceRuleMapper, transactionMapper,
			dailyMapper, monthlyMapper);
		TenantContext.setEntCode("ENT001");
		TenantContext.setUserId("U002");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
	}

	/**
	 * 验证账户查询保持响应结构。
	 */
	@Test
	void shouldReturnAccountResponse() {
		when(accountMapper.selectCurrentAccountWithPlan()).thenReturn(Map.ofEntries(
			Map.entry("ent_code", "ENT001"),
			Map.entry("plan_code", "PRO"),
			Map.entry("plan_name", "专业版"),
			Map.entry("plan_type", "pro"),
			Map.entry("balance", new BigDecimal("100.00")),
			Map.entry("total_recharged", new BigDecimal("200.00")),
			Map.entry("total_consumed", new BigDecimal("50.00")),
			Map.entry("used_tokens_this_month", 1000L),
			Map.entry("monthly_token_quota", 10000L),
			Map.entry("monthly_price", new BigDecimal("99.00")),
			Map.entry("billing_cycle_start", "2026-05-01"),
			Map.entry("status", "active")));

		BillingVO.AccountResponse account = service.getAccount();

		assertThat(account.entCode()).isEqualTo("ENT001");
		assertThat(account.planCode()).isEqualTo("PRO");
		assertThat(account.usedTokensThisMonth()).isEqualTo(1000L);
	}

	/**
	 * 验证无账户时配额检查保持向前兼容。
	 */
	@Test
	void shouldSkipQuotaWhenAccountMissing() {
		when(accountMapper.selectCurrentQuotaInfo()).thenReturn(Map.of());

		service.checkQuota();

		verify(accountMapper).selectCurrentQuotaInfo();
	}

	/**
	 * 验证账户暂停时拒绝请求。
	 */
	@Test
	void shouldRejectSuspendedAccount() {
		when(accountMapper.selectCurrentQuotaInfo()).thenReturn(Map.of(
			"status", "suspended",
			"plan_type", "pro",
			"monthly_token_quota", 0L,
			"used_tokens_this_month", 0L,
			"balance", BigDecimal.TEN));

		assertThatThrownBy(() -> service.checkQuota())
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("账户已被暂停，请充值后重试");
	}

	/**
	 * 验证充值会更新账户并追加交易流水。
	 */
	@Test
	void shouldRechargeAndInsertTransaction() {
		when(accountMapper.selectCurrentBalance()).thenReturn(new BigDecimal("120.00"));

		BillingVO.RechargeResponse response = service.recharge(new BigDecimal("20.00"), "tester");

		assertThat(response.balanceAfter()).isEqualByComparingTo("120.00");
		verify(accountMapper).recharge(new BigDecimal("20.00"));
		verify(transactionMapper).insert(any(BillingTransactionEntity.class));
	}

	/**
	 * 验证扣费会计算费用、扣减账户、写流水并更新每日用量。
	 */
	@Test
	void shouldDeductAndUpdateUsage() {
		when(priceRuleMapper.selectActiveRule("deepseek-chat")).thenReturn(Map.of(
			"input_price_per_1k", new BigDecimal("0.001000"),
			"output_price_per_1k", new BigDecimal("0.002000")));
		when(accountMapper.selectCurrentBalance()).thenReturn(new BigDecimal("99.99"));

		service.deductForTokenUsage(300, 100, 200, "deepseek-chat", "c1");

		verify(accountMapper).deduct(any(BigDecimal.class), eq(300));
		verify(transactionMapper).insert(any(BillingTransactionEntity.class));
		verify(dailyMapper).upsertDailyUsage(eq("ENT001"), eq("U002"), eq("deepseek-chat"),
			eq(100), eq(200), eq(300), any(BigDecimal.class));
	}

	/**
	 * 验证查询类接口委托 Mapper 并保持列表返回。
	 */
	@Test
	void shouldDelegateQueryMethodsToMappers() {
		when(planMapper.selectActivePlans()).thenReturn(List.of());
		when(transactionMapper.selectTransactions(20, 0)).thenReturn(List.of());
		when(dailyMapper.selectDailyUsage("2026-05-01", "2026-05-08")).thenReturn(List.of());
		when(monthlyMapper.selectMonthlyUsage()).thenReturn(List.of());

		assertThat(service.getPlans()).isEmpty();
		assertThat(service.getTransactions(0, 20)).isEmpty();
		assertThat(service.getDailyUsage("2026-05-01", "2026-05-08")).isEmpty();
		assertThat(service.getMonthlyUsage()).isEmpty();
	}
}
