[English](README_EN.md) | 中文

# ERP 智能助手 — Spring AI RAG Demo

基于 Spring AI 构建的制造业 ERP 智能助手，集成了 **Tool Calling**（实时查询业务数据）和 **RAG**（检索产品手册知识）两大 AI 能力，支持多模型切换、流式对话、会话记忆、多租户隔离和计费管理。

![示例截图](doc/img.png)

## 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| 语言 | Java | 17+ |
| 框架 | Spring Boot | 3.5.12 |
| AI 框架 | Spring AI | 1.1.4 |
| 对话模型 | DeepSeek / 通义千问 / Google Gemini | 多模型可切换 |
| 嵌入模型 | all-MiniLM-L6-v2（ONNX 本地推理） | 384 维向量 |
| 向量数据库 | PostgreSQL + PgVector | HNSW 索引 |
| 业务数据库 | MySQL | ERP 业务数据 |
| 前端 | 原生 HTML/CSS/JS + marked.js + highlight.js | 单页应用 |
| 流式传输 | SSE（Server-Sent Events） | WebFlux Reactor |

### 核心特性

- **Spring AI Tool Calling** — LLM 自动调用 8 大 ERP 模块的 `@Tool` 方法查询 MySQL 实时数据
- **Spring AI RAG** — `QuestionAnswerAdvisor` 从 PgVector 检索产品手册片段注入 prompt 上下文
- **多模型切换** — 前端下拉框选择模型，后端通过 `ModelRegistry` 路由到对应 provider 的 `ChatModel`
- **会话记忆** — `MessageChatMemoryAdvisor` + `JdbcChatMemoryRepository` 基于数据库的上下文记忆
- **多租户隔离** — 所有数据查询和向量检索自动按 `ent_code` 隔离
- **流式 Markdown 渲染** — SSE 逐 token 推送 + 前端实时 marked.js 渲染（表格、加粗、列表等）
- **计费管理** — 按 token 用量计费，支持套餐配额、充值、交易流水

## 中间件依赖

### 1. PostgreSQL + PgVector（向量数据库）

```bash
docker run -d \
  --name pgvector \
  -p 5432:5432 \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=rag_demo \
  pgvector/pgvector:pg16
```

PgVector 表和索引由 Spring AI 启动时自动创建（`initialize-schema: true`）。

### 2. MySQL（ERP 业务数据 + 对话记录 + 计费）

```bash
docker run -d \
  --name mysql-erp \
  -p 13306:3306 \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=erp \
  -e MYSQL_CHARSET=utf8mb4 \
  mysql:8.0
```

需自行导入 ERP 业务表（`b_sales_order`、`b_purchase_order` 等）和平台表（`a_chat_conversation`、`a_chat_message`、`a_billing_account` 等）的建表 SQL。

## 快速启动

### 1. 启动中间件

```bash
docker start pgvector
docker start mysql-erp
```

### 2. 配置环境变量

```bash
# 必需：至少配置一个模型的 API Key
export DEEPSEEK_API_KEY=sk-your-deepseek-key

# 可选：通义千问
export DASHSCOPE_API_KEY=sk-your-dashscope-key

# 可选：Google Gemini
export GOOGLE_GENAI_API_KEY=your-google-genai-key
```

API Key 获取地址：
- DeepSeek: https://platform.deepseek.com/api_keys
- 通义千问: https://bailian.console.aliyun.com/cn-beijing?tab=model#/api-key
- Gemini: https://aistudio.google.com/app/apikey

### 3. 启动应用

```bash
mvn clean spring-boot:run
```

访问 http://localhost:8080 即可使用。

## 功能列表

### AI 对话

| 功能 | 说明 |
|------|------|
| 智能模式 | Tool Calling + RAG 同时启用，LLM 自动判断查数据还是查文档 |
| 数据查询模式 | 仅 Tool Calling，适合明确的业务数据查询 |
| 知识问答模式 | 仅 RAG，适合产品手册类知识问答 |
| 流式输出 | SSE 逐 token 推送，实时 Markdown 渲染 |
| 会话记忆 | 基于数据库的上下文记忆（最近 20 条消息窗口） |
| 多模型切换 | 前端下拉框实时切换 DeepSeek / 通义千问 / Gemini |
| 预置示例问题 | AI 根据 Tool 描述自动生成，页面加载时展示 |
| 消息复制 | 所有消息气泡支持一键复制 |

### ERP Tool Calling（8 大模块，39 个工具方法）

| 模块 | 工具数 | 支持的查询 |
|------|--------|-----------|
| 销售 | 6 | 订单列表/详情/发货/应收/汇总 + 按时间查询 |
| 采购 | 5 | 订单列表/详情/收货/应付 + 按时间查询 |
| 生产 | 5 | 工单状态/列表/用料/工序 + 按时间查询 |
| 质检 | 5 | 质检结果/列表/不良明细/合格率 + 按时间查询 |
| 仓库 | 5 | 库存/仓库明细/出入库/预警 + 按时间查询 |
| 财务 | 5 | 应收账龄/应付账龄/月度汇总/收款/收支明细 |
| 售后 | 4 | 工单列表/详情/退换货 + 按时间查询 |
| 委外 | 4 | 订单列表/详情/来料退料 + 按时间查询 |

所有工具方法支持自然语言时间表达（"最近一周"、"本月"、"今年"等），LLM 自动转换为日期范围。

### 文档管理

- 一键加载 `classpath:docs/` 预置文档
- 上传 PDF / Word / Excel / TXT（Apache Tika 解析）
- 文档搜索（向量相似度检索，调试用）

### 计费管理

- 账户余额 / 套餐配额
- 交易流水（充值/扣费/赠送）
- 每日 / 月度 token 用量统计
- 在线充值

### 对话历史

- 会话列表（分页，按时间倒序）
- 消息详情（含 token 用量、耗时等元数据）
- 会话删除（软删除）

## 多模型配置

项目支持同时接入多个模型服务商，前端可自由切换。配置分三步：

### 第一步：添加 Maven 依赖

每个服务商对应一个 Spring AI starter：

```xml
<!-- DeepSeek（已内置） -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-deepseek</artifactId>
</dependency>

<!-- 通义千问（复用 OpenAI 兼容协议） -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>

<!-- Google Gemini -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-google-genai</artifactId>
</dependency>
```

### 第二步：配置 application.yml

```yaml
app:
  models:
    - id: deepseek-chat         # 唯一标识，前端传参用
      label: DeepSeek Chat（通用） # 下拉框显示名
      provider: deepseek         # 服务商标识
      model-name: deepseek-chat  # 实际传给 API 的模型名
      default: true              # 默认选中
    - id: qwen-max
      label: 通义千问 Max
      provider: openai           # 通义千问走 OpenAI 兼容协议
      model-name: qwen-max

spring:
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
    openai:
      api-key: ${DASHSCOPE_API_KEY}
      base-url: https://dashscope.aliyuncs.com/compatible-mode  # 不要加 /v1
    google:
      genai:
        api-key: ${GOOGLE_GENAI_API_KEY}
```

### 第三步：注册 provider（仅新服务商需要）

如果新增的服务商不在已有映射中，需在 `ModelRegistry.PROVIDER_BEAN_NAMES` 中添加：

```java
private static final Map<String, String> PROVIDER_BEAN_NAMES = Map.ofEntries(
    Map.entry("deepseek",     "deepSeekChatModel"),
    Map.entry("openai",       "openAiChatModel"),
    Map.entry("google-genai", "googleGenAiChatModel")
    // 新增: Map.entry("anthropic", "anthropicChatModel")
);
```

Bean 名称来自各 Spring AI starter 的 AutoConfiguration 类。

## 项目结构

```
com.example.rag
├── RagDemoApplication              # 启动类
├── chat/                           # AI 对话核心
│   ├── ChatController              # HTTP 接口（问答/文档/模型列表/hints）
│   ├── ErpAssistantService         # LLM 问答 + Tool Calling + RAG + 多模型路由
│   ├── DocumentLoaderService       # 文档导入向量库
│   └── ModelRegistry               # 多模型注册中心（provider → ChatClient 缓存）
├── conversation/                   # 对话历史
│   ├── ConversationController      # 历史记录 API
│   ├── ChatHistoryService          # 会话/消息 CRUD
│   └── JdbcChatMemoryRepository    # Spring AI ChatMemory 的 JDBC 实现
├── billing/                        # 计费
│   ├── BillingController
│   └── BillingService
├── tool/                           # ERP Tool Calling（8 个模块）
│   ├── BaseTool                    # 租户隔离基类（自动注入 ent_code）
│   ├── SalesTool / PurchaseTool / ProductionTool / QualityTool
│   ├── WarehouseTool / FinanceTool / AfterSalesTool / OutsourcingTool
├── config/                         # 配置
│   ├── ModelProperties             # 多模型 YAML 配置绑定
│   ├── ChatModelConfig             # @Primary ChatModel 声明
│   ├── DataSourceConfig            # 双数据源（PgVector + MySQL）
│   ├── TenantFilter / TenantContext / TenantContextAccessor
│   ├── ContextPropagationConfig    # Reactor 上下文传播
│   └── GlobalExceptionHandler
└── vo/                             # 请求/响应对象
    ├── ChatVO / ConversationVO / BillingVO / RespVO
```
