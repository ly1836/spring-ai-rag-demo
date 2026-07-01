package com.example.rag.controller;

import java.util.List;

import com.example.rag.billing.BillingManagementService;
import com.example.rag.vo.AdminVO;
import com.example.rag.vo.RespVO;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计费配置管理 REST API 控制器。
 */
@RestController
@RequestMapping("/api/admin/billing")
public class BillingManagementController {

	private final BillingManagementService billingManagementService;

	public BillingManagementController(BillingManagementService billingManagementService) {
		this.billingManagementService = billingManagementService;
	}

	/**
	 * 查询套餐配置列表。
	 *
	 * @return 套餐配置列表
	 */
	@GetMapping("/plans")
	public RespVO<List<AdminVO.PlanItem>> listPlans() {
		return RespVO.success(billingManagementService.listPlans());
	}

	/**
	 * 新增套餐配置。
	 *
	 * @param plan 套餐配置
	 * @return 是否保存成功
	 */
	@PostMapping("/plans")
	public RespVO<Boolean> savePlan(@RequestBody AdminVO.PlanItem plan) {
		return RespVO.success(billingManagementService.savePlan(plan));
	}

	/**
	 * 更新套餐配置。
	 *
	 * @param plan 套餐配置
	 * @return 是否更新成功
	 */
	@PutMapping("/plans")
	public RespVO<Boolean> updatePlan(@RequestBody AdminVO.PlanItem plan) {
		return RespVO.success(billingManagementService.updatePlan(plan));
	}

	/**
	 * 查询模型计价规则列表。
	 *
	 * @return 模型计价规则列表
	 */
	@GetMapping("/price-rules")
	public RespVO<List<AdminVO.PriceRuleItem>> listPriceRules() {
		return RespVO.success(billingManagementService.listPriceRules());
	}

	/**
	 * 新增模型计价规则。
	 *
	 * @param rule 模型计价规则
	 * @return 是否保存成功
	 */
	@PostMapping("/price-rules")
	public RespVO<Boolean> savePriceRule(@RequestBody AdminVO.PriceRuleItem rule) {
		return RespVO.success(billingManagementService.savePriceRule(rule));
	}

	/**
	 * 更新模型计价规则。
	 *
	 * @param rule 模型计价规则
	 * @return 是否更新成功
	 */
	@PutMapping("/price-rules")
	public RespVO<Boolean> updatePriceRule(@RequestBody AdminVO.PriceRuleItem rule) {
		return RespVO.success(billingManagementService.updatePriceRule(rule));
	}

	/**
	 * 查询账单列表。
	 *
	 * @return 账单列表
	 */
	@GetMapping("/invoices")
	public RespVO<List<AdminVO.InvoiceItem>> listInvoices() {
		return RespVO.success(billingManagementService.listInvoices());
	}

	/**
	 * 新增账单。
	 *
	 * @param invoice 账单
	 * @return 是否保存成功
	 */
	@PostMapping("/invoices")
	public RespVO<Boolean> saveInvoice(@RequestBody AdminVO.InvoiceItem invoice) {
		return RespVO.success(billingManagementService.saveInvoice(invoice));
	}

	/**
	 * 更新账单。
	 *
	 * @param invoice 账单
	 * @return 是否更新成功
	 */
	@PutMapping("/invoices")
	public RespVO<Boolean> updateInvoice(@RequestBody AdminVO.InvoiceItem invoice) {
		return RespVO.success(billingManagementService.updateInvoice(invoice));
	}

}
