package com.example.rag.billing;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.example.rag.dao.entity.BillingInvoiceEntity;
import com.example.rag.dao.entity.BillingPlanEntity;
import com.example.rag.dao.entity.BillingPriceRuleEntity;
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
		return RespVO.success(billingManagementService.listPlans().stream()
			.map(this::toPlanItem)
			.toList());
	}

	/**
	 * 新增套餐配置。
	 *
	 * @param plan 套餐配置
	 * @return 是否保存成功
	 */
	@PostMapping("/plans")
	public RespVO<Boolean> savePlan(@RequestBody AdminVO.PlanItem plan) {
		return RespVO.success(billingManagementService.savePlan(toPlanEntity(plan)));
	}

	/**
	 * 更新套餐配置。
	 *
	 * @param plan 套餐配置
	 * @return 是否更新成功
	 */
	@PutMapping("/plans")
	public RespVO<Boolean> updatePlan(@RequestBody AdminVO.PlanItem plan) {
		return RespVO.success(billingManagementService.updatePlan(toPlanEntity(plan)));
	}

	/**
	 * 查询模型计价规则列表。
	 *
	 * @return 模型计价规则列表
	 */
	@GetMapping("/price-rules")
	public RespVO<List<AdminVO.PriceRuleItem>> listPriceRules() {
		return RespVO.success(billingManagementService.listPriceRules().stream()
			.map(this::toPriceRuleItem)
			.toList());
	}

	/**
	 * 新增模型计价规则。
	 *
	 * @param rule 模型计价规则
	 * @return 是否保存成功
	 */
	@PostMapping("/price-rules")
	public RespVO<Boolean> savePriceRule(@RequestBody AdminVO.PriceRuleItem rule) {
		return RespVO.success(billingManagementService.savePriceRule(toPriceRuleEntity(rule)));
	}

	/**
	 * 更新模型计价规则。
	 *
	 * @param rule 模型计价规则
	 * @return 是否更新成功
	 */
	@PutMapping("/price-rules")
	public RespVO<Boolean> updatePriceRule(@RequestBody AdminVO.PriceRuleItem rule) {
		return RespVO.success(billingManagementService.updatePriceRule(toPriceRuleEntity(rule)));
	}

	/**
	 * 查询账单列表。
	 *
	 * @return 账单列表
	 */
	@GetMapping("/invoices")
	public RespVO<List<AdminVO.InvoiceItem>> listInvoices() {
		return RespVO.success(billingManagementService.listInvoices().stream()
			.map(this::toInvoiceItem)
			.toList());
	}

	/**
	 * 新增账单。
	 *
	 * @param invoice 账单
	 * @return 是否保存成功
	 */
	@PostMapping("/invoices")
	public RespVO<Boolean> saveInvoice(@RequestBody AdminVO.InvoiceItem invoice) {
		return RespVO.success(billingManagementService.saveInvoice(toInvoiceEntity(invoice)));
	}

	/**
	 * 更新账单。
	 *
	 * @param invoice 账单
	 * @return 是否更新成功
	 */
	@PutMapping("/invoices")
	public RespVO<Boolean> updateInvoice(@RequestBody AdminVO.InvoiceItem invoice) {
		return RespVO.success(billingManagementService.updateInvoice(toInvoiceEntity(invoice)));
	}

	private AdminVO.PlanItem toPlanItem(BillingPlanEntity entity) {
		return new AdminVO.PlanItem(entity.getId(), entity.getPlanCode(), entity.getPlanName(),
			entity.getPlanType(), entity.getMonthlyTokenQuota(), entity.getMonthlyPrice(),
			entity.getOveragePricePer1k(), entity.getMaxConversationsPerDay(),
			entity.getMaxTokensPerRequest(), entity.getMaxUsers(), entity.getFeatures(), entity.getStatus());
	}

	private BillingPlanEntity toPlanEntity(AdminVO.PlanItem item) {
		BillingPlanEntity entity = new BillingPlanEntity();
		entity.setId(item.id());
		entity.setPlanCode(item.planCode());
		entity.setPlanName(item.planName());
		entity.setPlanType(item.planType());
		entity.setMonthlyTokenQuota(item.monthlyTokenQuota());
		entity.setMonthlyPrice(item.monthlyPrice());
		entity.setOveragePricePer1k(item.overagePricePer1k());
		entity.setMaxConversationsPerDay(item.maxConversationsPerDay());
		entity.setMaxTokensPerRequest(item.maxTokensPerRequest());
		entity.setMaxUsers(item.maxUsers());
		entity.setFeatures(item.features());
		entity.setStatus(item.status());
		return entity;
	}

	private AdminVO.PriceRuleItem toPriceRuleItem(BillingPriceRuleEntity entity) {
		return new AdminVO.PriceRuleItem(entity.getId(), entity.getModel(), entity.getInputPricePer1k(),
			entity.getOutputPricePer1k(), toDateString(entity.getEffectiveDate()),
			toDateString(entity.getExpiredDate()), entity.getRemark());
	}

	private BillingPriceRuleEntity toPriceRuleEntity(AdminVO.PriceRuleItem item) {
		BillingPriceRuleEntity entity = new BillingPriceRuleEntity();
		entity.setId(item.id());
		entity.setModel(item.model());
		entity.setInputPricePer1k(item.inputPricePer1k());
		entity.setOutputPricePer1k(item.outputPricePer1k());
		entity.setEffectiveDate(parseDate(item.effectiveDate()));
		entity.setExpiredDate(parseDate(item.expiredDate()));
		entity.setRemark(item.remark());
		return entity;
	}

	private AdminVO.InvoiceItem toInvoiceItem(BillingInvoiceEntity entity) {
		return new AdminVO.InvoiceItem(entity.getId(), entity.getInvoiceNo(), entity.getEntCode(),
			entity.getBillingMonth(), entity.getPlanCode(), entity.getPlanFee(), entity.getTokenUsageFee(),
			entity.getTotalAmount(), entity.getTotalTokens(), entity.getTotalRequests(), entity.getStatus(),
			toDateTimeString(entity.getPaidAt()));
	}

	private BillingInvoiceEntity toInvoiceEntity(AdminVO.InvoiceItem item) {
		BillingInvoiceEntity entity = new BillingInvoiceEntity();
		entity.setId(item.id());
		entity.setInvoiceNo(item.invoiceNo());
		entity.setEntCode(item.entCode());
		entity.setBillingMonth(item.billingMonth());
		entity.setPlanCode(item.planCode());
		entity.setPlanFee(item.planFee());
		entity.setTokenUsageFee(item.tokenUsageFee());
		entity.setTotalAmount(item.totalAmount());
		entity.setTotalTokens(item.totalTokens());
		entity.setTotalRequests(item.totalRequests());
		entity.setStatus(item.status());
		entity.setPaidAt(parseDateTime(item.paidAt()));
		return entity;
	}

	private String toDateString(LocalDate date) {
		return date == null ? null : date.toString();
	}

	private LocalDate parseDate(String value) {
		return value == null || value.isBlank() ? null : LocalDate.parse(value);
	}

	private String toDateTimeString(LocalDateTime dateTime) {
		return dateTime == null ? null : dateTime.toString().replace('T', ' ');
	}

	private LocalDateTime parseDateTime(String value) {
		return value == null || value.isBlank() ? null : LocalDateTime.parse(value.replace(' ', 'T'));
	}

}
