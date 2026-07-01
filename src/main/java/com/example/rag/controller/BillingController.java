package com.example.rag.controller;

import com.example.rag.billing.BillingService;
import com.example.rag.vo.BillingVO;
import com.example.rag.vo.RespVO;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计费管理 REST API 控制器。
 * <p>
 * 提供以下接口：
 * <ul>
 *   <li>GET  /api/billing/account       — 查询计费账户详情（含套餐信息）</li>
 *   <li>GET  /api/billing/plans         — 查询可用套餐列表</li>
 *   <li>GET  /api/billing/transactions  — 查询交易流水（分页）</li>
 *   <li>POST /api/billing/recharge      — 账户充值（JSON body）</li>
 *   <li>GET  /api/billing/usage/daily   — 查询每日 token 用量</li>
 *   <li>GET  /api/billing/usage/monthly — 查询月度 token 用量</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/billing")
public class BillingController {

	private final BillingService billingService;

	public BillingController(BillingService billingService) {
		this.billingService = billingService;
	}

	/** 查询当前租户的计费账户信息（含套餐详情） */
	@GetMapping("/account")
	public RespVO<BillingVO.AccountResponse> getAccount() {
		return RespVO.success(billingService.getAccount());
	}

	/** 查询所有可用的计费套餐 */
	@GetMapping("/plans")
	public RespVO<BillingVO.PlansResponse> getPlans() {
		return RespVO.success(new BillingVO.PlansResponse(billingService.getPlans()));
	}

	/** 查询交易流水（支持分页） */
	@GetMapping("/transactions")
	public RespVO<BillingVO.TransactionsResponse> getTransactions(BillingVO.TransactionQueryRequest request) {
		return RespVO.success(new BillingVO.TransactionsResponse(
			request.page(), request.size(), billingService.getTransactions(request.page(), request.size())));
	}

	/** 账户充值（JSON body: amount + operator） */
	@PostMapping("/recharge")
	public RespVO<BillingVO.RechargeResponse> recharge(@RequestBody BillingVO.RechargeRequest request) {
		if (request.amount() == null || request.amount().compareTo(java.math.BigDecimal.ZERO) <= 0) {
			throw new IllegalArgumentException("充值金额必须大于0");
		}
		return RespVO.success(billingService.recharge(request.amount(), request.operator()));
	}

	/** 查询指定日期范围的每日 token 用量 */
	@GetMapping("/usage/daily")
	public RespVO<BillingVO.DailyUsageResponse> getDailyUsage(BillingVO.DailyUsageQueryRequest request) {
		return RespVO.success(new BillingVO.DailyUsageResponse(
			request.startDate(), request.endDate(),
			billingService.getDailyUsage(request.startDate(), request.endDate())));
	}

	/** 查询月度 token 用量（最近 12 个月） */
	@GetMapping("/usage/monthly")
	public RespVO<BillingVO.MonthlyUsageResponse> getMonthlyUsage() {
		return RespVO.success(new BillingVO.MonthlyUsageResponse(billingService.getMonthlyUsage()));
	}

}
