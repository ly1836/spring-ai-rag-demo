package com.example.rag.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 多模型配置属性，对应 application.yml 中的 app.models 列表。
 * <p>
 * 每个 ModelItem 描述一个可用模型：
 * <ul>
 *   <li>id        — 唯一标识，前端传参时使用</li>
 *   <li>label     — 展示名称，显示在前端下拉框中</li>
 *   <li>provider  — 服务商标识，对应 Spring AI ChatModel bean 的 qualifier</li>
 *   <li>modelName — 实际传给 API 的模型名称，通过 ChatOptions 运行时覆盖</li>
 *   <li>isDefault — 是否为默认选中模型</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "app")
public class ModelProperties {

	private List<ModelItem> models = List.of();

	public List<ModelItem> getModels() {
		return models;
	}

	public void setModels(List<ModelItem> models) {
		this.models = models;
	}

	/** 获取默认模型，找不到时返回列表第一个 */
	public ModelItem getDefault() {
		return models.stream()
				.filter(ModelItem::isDefault)
				.findFirst()
				.orElse(models.isEmpty() ? null : models.get(0));
	}

	/** 按 id 查找模型，找不到时返回默认模型 */
	public ModelItem findById(String id) {
		if (id == null || id.isBlank()) return getDefault();
		return models.stream()
				.filter(m -> m.getId().equals(id))
				.findFirst()
				.orElse(getDefault());
	}

	public static class ModelItem {

		/** 模型唯一标识，前端传参和下拉框 value 使用，如 deepseek-chat */
		private String id;

		/** 前端下拉框展示名称，如 DeepSeek Chat（通用） */
		private String label;

		/** 服务商标识，对应 Spring AI 自动配置的 ChatModel bean qualifier，如 deepseek / openai */
		private String provider;

		/** 实际传给服务商 API 的模型名称，通过 ChatOptions 在请求级覆盖，如 deepseek-chat */
		private String modelName;

		/** 是否为默认选中模型，前端初始化时自动选中，yaml 中至多一个为 true */
		private boolean isDefault;

		public String getId() { return id; }
		public void setId(String id) { this.id = id; }

		public String getLabel() { return label; }
		public void setLabel(String label) { this.label = label; }

		public String getProvider() { return provider; }
		public void setProvider(String provider) { this.provider = provider; }

		public String getModelName() { return modelName; }
		public void setModelName(String modelName) { this.modelName = modelName; }

		public boolean isDefault() { return isDefault; }
		public void setDefault(boolean aDefault) { isDefault = aDefault; }
	}

}
