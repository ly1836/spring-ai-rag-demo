package com.example.rag.billing;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.rag.dao.entity.BillingInvoiceEntity;
import com.example.rag.dao.entity.BillingPlanEntity;
import com.example.rag.dao.entity.BillingPriceRuleEntity;
import com.example.rag.dao.mapper.BillingInvoiceMapper;
import com.example.rag.dao.mapper.BillingPlanMapper;
import com.example.rag.dao.mapper.BillingPriceRuleMapper;
import com.example.rag.vo.AdminVO;

import org.springframework.stereotype.Service;

/**
 * 计费配置管理服务。
 * <p>
 * 提供套餐、模型计价规则和账单的受控 CRUD 能力；交易流水仍只允许通过业务流程追加。
 */
@Service
public class BillingManagementService {

	private final BillingPlanMapper planMapper;
	private final BillingPriceRuleMapper priceRuleMapper;
	private final BillingInvoiceMapper invoiceMapper;

	public BillingManagementService(BillingPlanMapper planMapper, BillingPriceRuleMapper priceRuleMapper,
			BillingInvoiceMapper invoiceMapper) {
		this.planMapper = planMapper;
		this.priceRuleMapper = priceRuleMapper;
		this.invoiceMapper = invoiceMapper;
	}

	/**
	 * 查询套餐配置列表。
	 *
	 * @return 套餐列表
	 */
	public List<AdminVO.PlanItem> listPlans() {
		return planMapper.selectList(new LambdaQueryWrapper<BillingPlanEntity>()
			.orderByAsc(BillingPlanEntity::getMonthlyPrice)).stream()
			.map(this::toPlanItem)
			.toList();
	}

	/**
	 * 保存套餐配置。
	 *
	 * @param plan 套餐 VO
	 * @return 是否保存成功
	 */
	public boolean savePlan(AdminVO.PlanItem plan) {
		return planMapper.insert(toPlanEntity(plan)) > 0;
	}

	/**
	 * 更新套餐配置。
	 *
	 * @param plan 套餐 VO
	 * @return 是否更新成功
	 */
	public boolean updatePlan(AdminVO.PlanItem plan) {
		if (plan.id() == null) {
			throw new IllegalArgumentException("套餐 ID 不能为空");
		}
		return planMapper.updateById(toPlanEntity(plan)) > 0;
	}

	/**
	 * 查询模型计价规则列表。
	 *
	 * @return 计价规则列表
	 */
	public List<AdminVO.PriceRuleItem> listPriceRules() {
		return priceRuleMapper.selectList(new LambdaQueryWrapper<BillingPriceRuleEntity>()
			.orderByDesc(BillingPriceRuleEntity::getEffectiveDate)).stream()
			.map(this::toPriceRuleItem)
			.toList();
	}

	/**
	 * 保存模型计价规则。
	 *
	 * @param rule 计价规则 VO
	 * @return 是否保存成功
	 */
	public boolean savePriceRule(AdminVO.PriceRuleItem rule) {
		return priceRuleMapper.insert(toPriceRuleEntity(rule)) > 0;
	}

	/**
	 * 更新模型计价规则。
	 *
	 * @param rule 计价规则 VO
	 * @return 是否更新成功
	 */
	public boolean updatePriceRule(AdminVO.PriceRuleItem rule) {
		if (rule.id() == null) {
			throw new IllegalArgumentException("计价规则 ID 不能为空");
		}
		return priceRuleMapper.updateById(toPriceRuleEntity(rule)) > 0;
	}

	/**
	 * 查询账单列表。
	 *
	 * @return 账单列表
	 */
	public List<AdminVO.InvoiceItem> listInvoices() {
		return invoiceMapper.selectList(new LambdaQueryWrapper<BillingInvoiceEntity>()
			.orderByDesc(BillingInvoiceEntity::getCreatedAt)).stream()
			.map(this::toInvoiceItem)
			.toList();
	}

	/**
	 * 保存账单。
	 *
	 * @param invoice 账单 VO
	 * @return 是否保存成功
	 */
	public boolean saveInvoice(AdminVO.InvoiceItem invoice) {
		return invoiceMapper.insert(toInvoiceEntity(invoice)) > 0;
	}

	/**
	 * 更新账单。
	 *
	 * @param invoice 账单 VO
	 * @return 是否更新成功
	 */
	public boolean updateInvoice(AdminVO.InvoiceItem invoice) {
		if (invoice.id() == null) {
			throw new IllegalArgumentException("账单 ID 不能为空");
		}
		return invoiceMapper.updateById(toInvoiceEntity(invoice)) > 0;
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
