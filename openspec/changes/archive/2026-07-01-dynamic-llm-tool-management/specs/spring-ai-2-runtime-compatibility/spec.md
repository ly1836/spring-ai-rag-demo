## MODIFIED Requirements

### Requirement: 三种问答模式必须保持工具调用和 RAG 组合语义

系统 SHALL 在 Spring AI 2.x 中保持 auto、data、knowledge 三种问答模式的能力组合不变。tool calling 的全局配置 MUST 不破坏任一模式的局部语义。auto/data 模式暴露的 ERP tools SHALL 包含现有代码 Tool 与启用的数据库 Tool；knowledge 模式仍 MUST NOT 暴露或执行任何 Tool。

#### Scenario: auto 模式同时启用工具、RAG 和记忆

- **WHEN** 用户以 auto 模式发起问答
- **THEN** 系统 MUST 注入 ChatMemory advisor
- **AND** 系统 MUST 注入 QuestionAnswerAdvisor 进行 RAG 检索
- **AND** 系统 MUST 允许 LLM 调用已注册的代码 ERP tools
- **AND** 系统 MUST 允许 LLM 调用当前启用的数据库 ERP tools

#### Scenario: data 模式启用工具和记忆但不启用 RAG

- **WHEN** 用户以 data 模式发起问答
- **THEN** 系统 MUST 注入 ChatMemory advisor
- **AND** 系统 MUST 允许 LLM 调用已注册的代码 ERP tools
- **AND** 系统 MUST 允许 LLM 调用当前启用的数据库 ERP tools
- **AND** 系统 MUST NOT 注入 QuestionAnswerAdvisor

#### Scenario: knowledge 模式启用 RAG 和记忆但禁用工具

- **WHEN** 用户以 knowledge 模式发起问答
- **THEN** 系统 MUST 注入 ChatMemory advisor
- **AND** 系统 MUST 注入 QuestionAnswerAdvisor 进行 RAG 检索
- **AND** 系统 MUST NOT 暴露或执行任何代码 ERP tool
- **AND** 系统 MUST NOT 暴露或执行任何数据库 ERP tool

### Requirement: ERP tools 必须保持租户隔离和 SQL 访问边界

系统 SHALL 保持所有 ERP Tool 查询的租户隔离和 SQL 访问边界。代码 `@Tool` 查询 MUST 继续通过 `BaseTool` 执行，并继续自动追加当前租户的 `ent_code` 条件。数据库 Tool 查询 MUST 通过动态 Tool SQL 执行器执行等价的 `ent_code` 注入、参数绑定和只读 SQL 校验。升级或扩展 Spring AI tool calling 机制时 MUST NOT 新增绕过租户隔离的 ERP SQL 查询路径。

#### Scenario: 代码工具调用查询自动带租户条件

- **WHEN** LLM 在 auto 或 data 模式下调用任意代码 ERP tool
- **THEN** tool 查询 MUST 通过 `BaseTool.query()` 或 `BaseTool.queryWithAlias()` 执行
- **AND** SQL 参数中 MUST 包含当前请求的 `ent_code`

#### Scenario: 数据库工具调用查询自动带租户条件

- **WHEN** LLM 在 auto 或 data 模式下调用任意数据库 ERP tool
- **THEN** tool 查询 MUST 通过动态 Tool SQL 执行器执行
- **AND** SQL 执行前 MUST 注入当前请求的 `ent_code`
- **AND** SQL 参数中 MUST 包含当前请求的 `ent_code`

#### Scenario: knowledge 模式不会访问 ERP tools

- **WHEN** 用户以 knowledge 模式提出可能触发 ERP 查询的问题
- **THEN** 系统 MUST 不向 LLM 暴露任何代码 ERP tool
- **AND** 系统 MUST 不向 LLM 暴露任何数据库 ERP tool
- **AND** 系统 MUST 不执行任何 ERP SQL 查询

## ADDED Requirements

### Requirement: Spring AI 2.x Tool 注册和 JSON 处理必须保持运行期兼容

系统 SHALL 使用 Spring AI 2.x 推荐的 Tool 注册入口和项目选定的 JSON 处理方式，避免引入即将移除的 ToolCallback 注册 API 或额外 Jackson 2 运行期 Bean。

#### Scenario: 使用非废弃 API 注册 ToolCallback 快照

- **WHEN** `ErpAssistantService` 基于当前 Tool 快照构建带 Tool 的 `ChatClient`
- **THEN** 系统 MUST 使用 Spring AI 2.x 支持 `ToolCallback` 的 `defaultTools`
- **AND** 系统 MUST NOT 使用已废弃并标记移除的 `defaultToolCallbacks`

#### Scenario: 动态 Tool JSON 处理不依赖 Jackson 2 ObjectMapper

- **WHEN** 动态 Tool 校验 JSON Schema、解析 Tool arguments、序列化数据库查询结果或聚合 Tool 调用记录
- **THEN** 系统 MUST 使用 `fastjson2` 完成 JSON 解析与序列化
- **AND** 系统 MUST NOT 为动态 Tool 新增 Jackson 2 `ObjectMapper` 兼容 Bean

### Requirement: 前端助手消息必须保持 Markdown 渲染能力

系统 SHALL 在非流式、流式和历史消息展示中使用 Markdown 渲染助手消息，确保 LLM 基于 Tool 结果输出的表格、列表和代码块能够按 Markdown 展示。

#### Scenario: 助手消息包含 Markdown 表格

- **WHEN** 助手消息内容包含标准 Markdown 表格
- **THEN** 前端 MUST 使用 Markdown 渲染器生成表格 HTML
- **AND** 助手消息气泡 MUST 使用支持 Markdown 样式的容器类

#### Scenario: 流式助手消息实时渲染 Markdown

- **WHEN** 流式问答持续收到助手消息片段
- **THEN** 前端 MUST 用累计文本重新渲染助手消息
- **AND** 最终展示结果 MUST 与非流式 Markdown 渲染语义保持一致

#### Scenario: Markdown 渲染器不可用时安全降级

- **WHEN** Markdown 渲染器未加载或解析失败
- **THEN** 前端 MUST 对原始文本做 HTML 转义后展示
- **AND** 系统 MUST NOT 将未转义文本直接写入助手消息 HTML
