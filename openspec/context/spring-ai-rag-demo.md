# Spring AI RAG Demo — ERP 智能助手

## 项目定位
基于 Spring AI 2.0.0 的 ERP 智能问答 Demo，融合两大 AI 能力：
- **Tool Calling**：LLM 自动调用 `@Tool` 方法查询 ERP MySQL 业务数据（订单、库存、工单、财务等）
- **RAG**：从 PgVector 向量库检索产品手册片段，作为知识上下文注入 Prompt

支持多 LLM Provider（DeepSeek / 通义千问 / Gemini）切换、SSE 流式输出、多租户隔离、按 token 计费。

## 技术栈
- 启动类：`com.example.rag.RagDemoApplication`
- 根包：`com.example.rag`
- Java 17、Spring Boot 4.0.7、Spring AI 2.0.0（BOM 统一管理）
- 构建：Maven 单模块（无子 module）
- 数据库：
  - PostgreSQL + pgvector（向量库 `rag_demo`，`@Primary`，承载文档嵌入与 RAG 检索）
  - MySQL（ERP 业务库 `erp`，承载销售/采购/库存/财务/计费等业务表）
- 嵌入模型：本地 ONNX Runtime 加载 `all-MiniLM-L6-v2`（384 维），无需远程 API
- LLM Provider：
  - DeepSeek（`@Primary`，默认 `deepseek-chat`）
  - OpenAI 兼容协议（指向 DashScope，承载通义千问系列）
  - Google Gemini（GenAI API Key 模式）

## 架构风格
- 单模块 Spring Boot 应用，**无模块前缀**概念
- 经典三层为主：Controller → Service → MyBatis-Plus / JdbcTemplate（无 JPA）
- 业务域按职责划分子包，复杂业务可拆分为多个 Service / Helper 协作
- AI 编排集中在 `chat/ErpAssistantService`，通过 `ChatClient` + `Advisor`（`MessageChatMemoryAdvisor`、`QuestionAnswerAdvisor`）组合能力

## 包结构
- `chat/` — 智能助手入口
  - `ChatController`：`/api/ask`、`/api/ask/stream`、`/api/upload`、`/api/load`、`/api/search`、`/api/models`、`/api/hints`
  - `ErpAssistantService`：三种问答模式（auto / data / knowledge）的模型调用编排
  - `chat/client`：多模型 ChatClient 路由、Tool 装配与缓存
  - `chat/lifecycle`：问答消息、计费、Tool 记录与流式收口
  - `chat/dto`：chat 域内部不可变 DTO / record
  - `chat/chart/*`：按 model、capture、compile、protocol、tool 分包的图表能力
  - `DocumentLoaderService`：基于 Tika 的受控文本提取、实际 ONNX WordPiece token 二次切分、同来源覆盖和分批向量入库
  - `ModelRegistry`：多 ChatModel Bean 按 `modelId` 路由
- `billing/` — 计费域：账户、套餐、配额校验、token 扣费、充值、用量聚合
- `conversation/` — 对话历史：会话列表、消息列表、`JdbcChatMemoryRepository`（PgVector 同库，承载 ChatMemory 持久化）
- `tool/` — 8 大 ERP 业务域工具集（销售 / 采购 / 委外 / 生产 / 质检 / 仓库 / 售后 / 财务），统一继承 `BaseTool`
- `config/` — 双数据源、租户过滤器、ThreadLocal 上下文、ChatModel `@Primary` 配置、模型属性绑定、全局异常处理
- `vo/` — 请求/响应 VO（`RespVO<T>` 顶层包装；`ChatVO` / `BillingVO` / `ConversationVO` 内嵌业务 record）
- `src/main/resources/static/` — 前端资源（前后端一体部署，无独立前端工程，详见「前端」章节）

## 多租户与上下文
- 网关在请求 Header 中注入 `X-Ent-Code`（必填）、`X-User-Id`（可选）
- `TenantFilter`（`@Order(1)`）拦截 `/api/**`，将 `ent_code` 写入 `TenantContext` ThreadLocal，请求结束统一 `clear()`
- `BaseTool.query()` / `queryWithAlias()` 在 SQL 中自动追加 ` AND ent_code = ?`，并将当前租户作为最后一个参数传入
- RAG 检索时通过 `FilterExpressionBuilder` 按 `ent_code` 过滤向量元数据
- 文档导入时 `DocumentLoaderService` 自动写入 `ent_code` 元数据
- 异步/响应式（reactor）链路必须借助 `TenantContextAccessor` 透传 ThreadLocal

## 计费
- 每次 LLM 调用前 `BillingService.checkQuota()`：检查账户状态（suspended / arrears）、月度 token 配额（免费套餐）、余额（付费套餐）
- 调用后 `BillingService.deductTokens()`：按 `(promptTokens × 输入单价 + completionTokens × 输出单价) / 1000` 计算并扣费
- 单价从 `a_billing_price_rule` 表按模型名 + 生效日期查询
- 仅查询/搜索类接口（如 `/api/search`、`/api/models`）不计费

## 编码约束
- **依赖注入**：构造器注入 + `private final`，禁止 `@Autowired` 字段注入
- **统一响应**：Controller 返回 `RespVO<T>`（流式接口除外），失败场景抛业务异常，由 `GlobalExceptionHandler` 兜底
- **业务异常**：
  - `IllegalStateException` → `BIZ_ERROR`（配额不足、账户暂停、状态非法等）
  - `IllegalArgumentException` → `PARAM_ERROR`（参数校验失败）
  - 兜底 `Exception` → `SYSTEM_ERROR`（隐藏内部细节）
- **事务**：`@Transactional` 放在 Service 实现类的写入方法上；本地事务内禁止调用第三方 LLM
- **SQL**：所有动态值通过 `?` 占位 + 参数列表，禁止字符串拼接
- **数据源选择**：
  - VectorStore / ChatMemory 默认走 `@Primary` PgVector
  - ERP 业务查询统一注入 `@Qualifier("erpJdbcTemplate") JdbcTemplate`，并经 `BaseTool` 隔离 `ent_code`
- **Controller 薄**：仅参数绑定 + 调用 Service + 返回 `RespVO`，禁止业务逻辑、禁止直接执行 SQL
- **VO 命名**：业务域内部 record 嵌套在 `XxxVO` 容器类（如 `ChatVO.AskRequest`、`BillingVO.RechargeResponse`）
- **方法注释**：新增 Java 方法/类必须写中文注释，覆盖含义、参数、返回值、错误码

## AI 能力专项约束
- **`@Tool` 方法**：`description` 必须详细描述触发场景与返回字段；参数用 `@ToolParam` 标注语义；返回值优先用 `List<Map<String,Object>>` 直接序列化
- **多模型路由**：禁止在业务代码注入特定 provider 的 ChatModel，统一通过 `ModelRegistry.getChatModel(modelId)` 获取
- **System Prompt**：auto/data 模式使用 ERP 业务提示词，knowledge 模式使用独立知识库提示词；两者均保持 Markdown 格式和最终答案边界约定
- **流式接口**：`/api/ask/stream` 直接返回带 `delta`、`chart`、`done`、`error` 事件的类型化 SSE，**不**包装为 `RespVO`
- **RAG 检索**：auto 模式默认相似度阈值为 `0.5`，knowledge 模式使用 `topK = 8`、相似度阈值 `0.25`；所有模式必须按 `ent_code` 过滤
- **文档导入**：HTTP 允许 500MB 单文件/550MB 请求；提取文本最多 500 万字符、最终分片最多 2 万个、单批向量写入最多 100 条，同租户同 `source` 重新导入时覆盖旧数据

## 前端
项目为**前后端一体部署**，前端资源由 Spring Boot 直接托管在 `src/main/resources/static/` 下，**无**独立前端工程、**无** npm / webpack / vite 等构建工具。

### 资源结构
- `index.html` — 单页应用入口，三 Tab 布局：
  - **AI 对话**：左侧文档管理 + 文档搜索（向量库），右侧聊天面板（模式切换 + 模型选择 + SSE 流式输出）
  - **历史记录**：会话列表 + 消息详情，助手消息支持 Markdown 回放
  - **计费管理**：账户概览卡片 + 子 Tab（套餐 / 交易流水 / 每日用量 / 月度用量 / 充值）
- `app.js` — 全部交互逻辑，按 `// ===` 注释分块：通用工具 → Tab 导航 → 文档管理 → 文档搜索 → 聊天 → 历史记录 → 计费 → 初始化
- `style.css` — 全局样式，主题变量集中在 `:root`（`--primary` / `--bg` / `--text` / `--success` / `--error` 等）
- `vendor/` — 第三方库本地化（`marked.min.js` / `highlight.min.js` / `highlight-github.min.css`），**禁止**直连 CDN

### 静态资源放行
- `TenantFilter`（`@Order(1)`）只拦截 `/api/**`；`/`、`/index.html`、`/app.js`、`/style.css`、`/vendor/**` 默认放行，**无需**额外配置 `WebMvcConfigurer`
- `GlobalExceptionHandler` 把静态资源的 `NoResourceFoundException` 降级为 DEBUG 日志，避免 favicon / DevTools 探测刷屏

### API 调用规约
- **统一封装**：所有非流式 API 必须走 `apiCall(url, options)` / `apiPost(url, body)`，它会：
  1. 自动附加 `X-Ent-Code`（来自 `getEntCode()`）和 `X-User-Id`（来自 `getUserId()`）
  2. 解包 `RespVO`，`success=false` 时抛 `Error(errMsg)`，`success=true` 时返回 `data` 字段
- **SSE 流式**：`/api/ask/stream` 不走 `apiCall`，需手动 `fetch` + `getReader()` 流式读取，按 `\n\n` 切分标准 `event:` / JSON `data:` 事件并分别处理文本、图表、完成和错误状态
- **会话 ID**：首次提问时由前端 `crypto.randomUUID()` 生成 `currentConversationId`，同会话期间复用并通过 `conversationId` query 参数传给后端；切换会话调 `newConversation()` 重置

### 渲染与安全
- **Markdown**：助手消息 `bubble.innerHTML = renderMarkdown(text)`，由 `marked.parse()` 渲染；流式结束后调 `highlightCodeBlocks(bubble)` 触发 `hljs.highlightElement()` 做代码块高亮
- **XSS 防护**：任何动态值（用户输入、后端返回字段）拼入 `innerHTML` 前**必须**经 `escapeHtml()` 转义；助手 Markdown 由 marked 内部处理，但仍要确保传入文本未被人为污染
- **Toast**：操作反馈统一调 `showToast(msg, type)`（`success` / `error`），**禁止** `alert()` / `confirm()`（删除等破坏性操作可保留 `confirm()`）
- **错误展示**：列表/卡片型容器内错误用 `<p style="color:var(--error);">`，操作型错误用 `showToast(msg, 'error')`

### 编码约束
- **零构建**：不引入打包工具与 ES Module，新增第三方库放到 `static/vendor/` 并通过 `<script>` / `<link>` 引用；如必须引入构建工具需在 design 中评估
- **DOM 操作**：模板字符串拼 `innerHTML` 前所有动态值必须 `escapeHtml()`；如需大量动态 DOM，优先 `document.createElement` + `textContent`
- **样式主题**：颜色、间距、圆角优先复用 `:root` CSS 变量，**禁止**在元素上硬编码 `#xxxxxx` 颜色值
- **JSDoc 注释**：所有函数必须中文 JSDoc 注释，覆盖含义、参数、返回值与异常分支；按现有 `// ===` 分块顺序追加新功能
- **缓存破坏**：`index.html` 中引用 `app.js` / `style.css` / `vendor/*` 时附带 `?v=N` 查询参数；修改对应文件后**手动 +1**，否则浏览器缓存会导致前端不刷新
- **租户/用户读取**：统一通过 `getEntCode()` / `getUserId()`，**禁止**在多处分别 `document.getElementById('entCodeSelect').value`
- **API 路径**：统一通过 `const API = '/api'` 前缀，**禁止**硬编码 `'/api/xxx'` 字符串

## 安全
- 网关层负责认证；本服务信任 `X-Ent-Code` / `X-User-Id` Header
- LLM API Key 通过环境变量注入（`DEEPSEEK_API_KEY` / `DASHSCOPE_API_KEY` / `GOOGLE_GENAI_API_KEY`），禁止写入日志
- 计费/账户敏感字段（余额、价格、用量）日志记录需脱敏
- ERP SQL 必须经 `BaseTool` 多租户隔离，杜绝跨租户数据泄漏

## Artifact 生成规则
- **proposal**：必须指明涉及的业务域（chat / billing / conversation / tool / config / vo）；触及租户隔离、计费扣费、SQL 多租户的改动须单独评估风险
- **design**：接口须包含路径、请求/响应、Controller/Service 职责与类名；涉及 ERP 表的须列出表结构和需要的 `ent_code` 隔离方式；新增 `@Tool` 方法须列出触发关键词与返回示例
- **tasks**：实现顺序参考分层：表结构/向量元数据 → JdbcTemplate 查询封装 → Service → Controller → 前端联调（更新 `static/index.html` / `static/app.js` / `static/style.css`，并把对应资源的 `?v=N` 版本号 +1）；新增 `@Tool` 类先写 BaseTool 子类与 SQL，再注册到 `ErpAssistantService` 的 ToolCallback 列表
- **前端改动**：仅涉及前端时无需新建 spec，可在 change 内直接列出受影响的 `static/*` 文件、是否新增 API 调用（必须经 `apiCall` / `apiPost`）、是否影响主题变量与缓存版本号
