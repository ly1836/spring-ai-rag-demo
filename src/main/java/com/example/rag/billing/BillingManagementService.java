package com.example.rag.billing;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.rag.dao.entity.BillingInvoiceEntity;
import com.example.rag.dao.entity.BillingPlanEntity;
import com.example.rag.dao.entity.BillingPriceRuleEntity;
import com.example.rag.dao.mapper.BillingInvoiceMapper;
import com.example.rag.dao.mapper.BillingPlanMapper;
import com.example.rag.dao.mapper.BillingPriceRuleMapper;

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
	public List<BillingPlanEntity> listPlans() {
		return planMapper.selectList(new LambdaQueryWrapper<BillingPlanEntity>()
			.orderByAsc(BillingPlanEntity::getMonthlyPrice));
	}

	/**
	 * 保存套餐配置。
	 *
	 * @param plan 套餐实体
	 * @return 是否保存成功
	 */
	public boolean savePlan(BillingPlanEntity plan) {
		return planMapper.insert(plan) > 0;
	}

	/**
	 * 更新套餐配置。
	 *
	 * @param plan 套餐实体
	 * @return 是否更新成功
	 */
	public boolean updatePlan(BillingPlanEntity plan) {
		if (plan.getId() == null) {
			throw new IllegalArgumentException("套餐 ID 不能为空");
		}
		return planMapper.updateById(plan) > 0;
	}

	/**
	 * 查询模型计价规则列表。
	 *
	 * @return 计价规则列表
	 */
	public List<BillingPriceRuleEntity> listPriceRules() {
		return priceRuleMapper.selectList(new LambdaQueryWrapper<BillingPriceRuleEntity>()
			.orderByDesc(BillingPriceRuleEntity::getEffectiveDate));
	}

	/**
	 * 保存模型计价规则。
	 *
	 * @param rule 计价规则实体
	 * @return 是否保存成功
	 */
	public boolean savePriceRule(BillingPriceRuleEntity rule) {
		return priceRuleMapper.insert(rule) > 0;
	}

	/**
	 * 更新模型计价规则。
	 *
	 * @param rule 计价规则实体
	 * @return 是否更新成功
	 */
	public boolean updatePriceRule(BillingPriceRuleEntity rule) {
		if (rule.getId() == null) {
			throw new IllegalArgumentException("计价规则 ID 不能为空");
		}
		return priceRuleMapper.updateById(rule) > 0;
	}

	/**
	 * 查询账单列表。
	 *
	 * @return 账单列表
	 */
	public List<BillingInvoiceEntity> listInvoices() {
		return invoiceMapper.selectList(new LambdaQueryWrapper<BillingInvoiceEntity>()
			.orderByDesc(BillingInvoiceEntity::getCreatedAt));
	}

	/**
	 * 保存账单。
	 *
	 * @param invoice 账单实体
	 * @return 是否保存成功
	 */
	public boolean saveInvoice(BillingInvoiceEntity invoice) {
		return invoiceMapper.insert(invoice) > 0;
	}

	/**
	 * 更新账单。
	 *
	 * @param invoice 账单实体
	 * @return 是否更新成功
	 */
	public boolean updateInvoice(BillingInvoiceEntity invoice) {
		if (invoice.getId() == null) {
			throw new IllegalArgumentException("账单 ID 不能为空");
		}
		return invoiceMapper.updateById(invoice) > 0;
	}

}
