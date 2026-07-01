## Context

当前 `ErpAssistantService` 在构造函数中接收 `List<BaseTool>`，并通过 `ChatClient.Builder.defaultTools(Object...)` 把 `com.example.rag.tool` 包下的固定 `@Tool` Bean 注册给 LLM。这个模式对原有 8 大 ERP Tool 简单直接，但不支持运行期新增、禁用或修改 Tool；默认 provider 的 `baseChatClient` 也是构造期固定对象，无法感知数据库配置变化。

现有 `a_chat_message` 已有 `tool_calls` 与 `tool_calls_count` 字段，历史数据中也存在 JSON 示例，但当前保存助手消息时仍传入 `null, 0`。因此本变更需要同时解决动态 Tool 注册和 Tool 命中追踪两个问题，并且必须保持多模型路由、auto/data/knowledge 三种模式、RAG、计费前后置和现有代码 Tool 兼容。

## Goals / Non-Goals

**Goals:**

- 支持全局数据库定义 SQL 查询类 Tool，并通过管理页面维护。
- 应用启动和配置变更后都能刷新 ToolCallback 快照，让后续 LLM 问答使用最新 Tool。
- 原有 `com.example.rag.tool` 代码 Tool 继续可用，且不需要改变每个既有 Tool 方法。
- 数据库 Tool 执行时必须基于当前 `TenantContext.requireEntCode()` 做结果隔离，即使 Tool 定义本身是全局的。
- 记录每次 Tool 调用明细，并把助手消息的 Tool 调用聚合字段补齐。
- 继续通过 `ModelRegistry` 进行多模型路由，问答前后继续走 `BillingService` 配额校验和 token 扣费。

**Non-Goals:**

- 首期不支持写入型 SQL、DDL、多语句、存储过程、HTTP Tool、MCP Tool 或脚本执行。
- 首期不做租户级 Tool 定义覆盖；所有租户看到同一批启用 Tool，但执行结果按租户隔离。
- 不把数据库 Tool 改造成 Java `@Tool` 注解方法；统一生成 `ToolCallback`。
- 不改变现有 `/api/ask`、`/api/ask/stream`、`/api/models`、`/api/hints` 的请求与响应形态。

## Decisions

### Decision 1: 数据库 Tool 以 `ToolCallback` 方式注册，而不是运行期生成 `@Tool` Bean

数据库 Tool 定义无法在编译期提供 Java 方法，因此使用 Spring AI 的 `ToolCallback` 作为统一抽象：

- 原有代码 Tool：通过 `MethodToolCallbackProvider.builder().toolObjects(codeToolBeans).build()` 转换为 `ToolCallback`。
- 数据库 Tool：由 `DatabaseToolCallbackFactory` 根据 `a_llm_tool` 生成 `ToolCallback`。
- 统一包装：所有 ToolCallback 外层套 `LoggingToolCallback`，记录调用日志并收集本轮聚合信息。

替代方案是继续使用 `defaultTools(Object...)` 注册代码 Tool，再通过 request options 追加数据库 Tool。该方式会让默认 Tool 来源分散，且不利于统一日志包装和刷新缓存，因此不采用。

### Decision 2: 使用 Tool 快照版本驱动 ChatClient 缓存失效

新增 `ToolRegistryService` 维护不可变快照：

```text
ToolSnapshot {
  long version
  List<ToolCallback> callbacks
  List<String> descriptions
}
```

`ErpAssistantService` 不再持有构造期固定 `baseChatClient` 作为唯一带 Tool 客户端，而是在 `resolveClient(modelId)` 时读取当前 `ToolSnapshot.version`：

- 默认 provider 和非默认 provider 都用 `provider + toolVersion` 作为缓存键。
- 管理端写入 Tool 配置成功后调用 `ToolRegistryService.refresh()`，版本递增。
- 后续问答命中新版本缓存；旧 ChatClient 可自然淘汰，不影响进行中的请求。

### Decision 3: 动态 Tool 表全局配置，执行流水按租户隔离

新增表：

```sql
CREATE TABLE IF NOT EXISTS a_llm_tool (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    tool_name     VARCHAR(64)  NOT NULL COMMENT 'Tool 名称',
    tool_desc     VARCHAR(500) NOT NULL COMMENT 'Tool 描述',
    input_schema  TEXT         NOT NULL COMMENT 'Tool 入参 JSON Schema',
    sql_template  TEXT         NOT NULL COMMENT '只读 SQL 模板',
    table_alias   VARCHAR(32)           COMMENT '主表别名，用于拼接租户条件',
    result_limit  INT          NOT NULL DEFAULT 50 COMMENT '最大返回行数',
    status        VARCHAR(10)  NOT NULL DEFAULT 'active' COMMENT '状态: active/inactive',
    remark        VARCHAR(500)          COMMENT '备注',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tool_name (tool_name),
    INDEX idx_status (status)
) COMMENT 'LLM 动态 Tool 定义表';
```

`a_llm_tool` 不含 `ent_code`，必须加入 `app.tenant.ignore-tables`，管理接口对所有租户展示同一套配置。

调用日志表：

```sql
CREATE TABLE IF NOT EXISTS a_tool_call_log (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id       VARCHAR(36)  NOT NULL COMMENT '单次问答链路 ID',
    conversation_id VARCHAR(36)          COMMENT '会话 ID',
    message_id     VARCHAR(36)           COMMENT '助手消息 ID',
    ent_code       VARCHAR(32)  NOT NULL COMMENT '租户编码',
    user_id        VARCHAR(32)           COMMENT '用户 ID',
    mode           VARCHAR(20)           COMMENT '问答模式',
    model          VARCHAR(50)           COMMENT '使用模型',
    tool_name      VARCHAR(64)  NOT NULL COMMENT 'Tool 名称',
    tool_type      VARCHAR(20)  NOT NULL COMMENT 'Tool 来源: code/database',
    arguments_json TEXT                  COMMENT 'Tool 入参 JSON',
    result_count   INT          NOT NULL DEFAULT 0 COMMENT '返回结果条数',
    duration_ms    BIGINT                COMMENT '调用耗时（毫秒）',
    status         VARCHAR(10)  NOT NULL DEFAULT 'success' COMMENT '状态: success/error',
    error_message  VARCHAR(500)          COMMENT '错误信息',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_trace_id (trace_id),
    INDEX idx_ent_conversation (ent_code, conversation_id),
    INDEX idx_tool_name (tool_name),
    INDEX idx_created_at (created_at),
    INDEX idx_ent_created_at (ent_code, created_at)
) COMMENT 'LLM Tool 命中流水表';
```

`a_tool_call_log` 是租户隔离表，调用日志列表在 Service 层显式按 `TenantContext.requireEntCode()` 追加 `ent_code` 过滤。

### Decision 4: SQL 执行器只允许只读查询，并统一注入 `ent_code`

数据库 Tool 的 SQL 模板使用命名参数，例如：

```sql
SELECT order_no, order_date, customer_name, total_amount, status
FROM b_sales_order
WHERE customer_name LIKE CONCAT('%', :customerName, '%')
ORDER BY order_date DESC
```

执行器职责：

- 解析 LLM 传入 JSON arguments，只接受 `input_schema` 中声明的字段。
- 将命名参数转换为 `?` 绑定参数，禁止把用户输入拼接进 SQL。
- 校验 SQL 只允许单层 `SELECT`，禁止 `WITH`、子查询、`UNION`/`INTERSECT`/`EXCEPT`、`INSERT`、`UPDATE`、`DELETE`、`DROP`、`;` 多语句等。
- 在 SQL 的 `ORDER BY`、`GROUP BY`、`LIMIT` 之前统一插入 `AND <alias>.ent_code = ?` 或 `AND ent_code = ?`，并把当前 `ent_code` 插入到租户条件对应参数位置。
- `table_alias` 为可选配置；非空时必须是 `[A-Za-z_][A-Za-z0-9_]*` 安全 SQL 标识符，用于 JOIN 场景下拼接 `o.ent_code = ?`。
- 对返回行数做 `result_limit` 限制，避免 Tool 结果过大。
- 动态 Tool JSON Schema、调用参数、查询结果和聚合记录统一使用 `fastjson2` 解析或序列化，避免引入 Jackson 2 `ObjectMapper` 兼容 Bean。

这里不直接调用 `BaseTool.query()`，因为数据库 Tool 没有 Java 子类；但执行器必须提供与 `BaseTool` 等价的租户注入语义。

### Decision 5: Tool 命中日志与会话聚合分离

每次 ToolCallback 调用时立即追加 `a_tool_call_log`，记录成功或失败。为了不让日志失败影响问答主链路，日志写入异常只记录 warn。

本轮请求的 Tool 聚合信息由 `ToolCallRecorder` 使用 `traceId` 作为 key 暂存，`ErpAssistantService` 通过 `ToolContext` 向 ToolCallback 传递 `traceId`、`conversationId`、`mode`、`model`、`entCode` 和 `userId`：

- 非流式：`recordAndReturn()` 保存助手消息时读取 recorder，写入 `tool_calls` 和 `tool_calls_count`。
- 流式：`streamWithRecording()` 在 Servlet 线程捕获租户上下文，并在 `doFinally` 恢复上下文后读取 recorder，写入聚合字段。
- knowledge 模式禁用 Tool，不应产生 Tool 记录。

### Decision 6: 管理接口按现有管理端风格实现

新增 Controller：

- `ToolManagementController`，路径 `/api/admin/tools`
- `ToolManagementService`，负责参数校验、Mapper CRUD、刷新 `ToolRegistryService`
- `AdminVO.ToolItem` / `AdminVO.ToolCallLogItem` / `AdminVO.ToolRefreshResult`

接口：

| 方法 | 路径 | 请求 | 响应 |
| --- | --- | --- | --- |
| GET | `/api/admin/tools` | 无 | `RespVO<List<AdminVO.ToolItem>>` |
| POST | `/api/admin/tools` | `AdminVO.ToolItem` | `RespVO<Boolean>` |
| PUT | `/api/admin/tools` | `AdminVO.ToolItem`，必须带 `id` | `RespVO<Boolean>` |
| DELETE | `/api/admin/tools/{id}` | 路径 ID | `RespVO<Boolean>` |
| POST | `/api/admin/tools/refresh` | 无 | `RespVO<AdminVO.ToolRefreshResult>` |
| GET | `/api/admin/tools/call-logs` | `page`、`size`、`toolName` 可选 | `RespVO<List<AdminVO.ToolCallLogItem>>` |

错误语义：

- `PARAM_ERROR`：Tool 名称为空、命名不合法、JSON Schema 非法、SQL 非只读、主表别名非法、返回行数非法、更新缺少 ID。
- `BIZ_ERROR`：Tool 名称重复、删除不存在记录、刷新后出现重复 Tool 名称。
- `SYSTEM_ERROR`：数据库异常或未预期执行异常。

### Decision 7: 前端在顶部新增「工具管理」Tab

`index.html` 顶部导航在「计费管理」右侧增加「工具管理」。页面包含：

- Tool 列表：名称、状态、主表别名、返回行数、更新时间、操作。
- 编辑表单：名称、状态、主表别名、返回行数、描述、input schema、SQL 模板、备注。
- 调用日志：按当前租户查询最近 Tool 命中记录。
- 刷新按钮：主动触发 `/api/admin/tools/refresh`。

所有非流式接口继续走 `apiCall` / `apiPost`，动态值进入 `innerHTML` 前必须 `escapeHtml()`；修改 `index.html` 引用的 `app.js` / `style.css` 版本号。

## Risks / Trade-offs

- [Risk] 动态 SQL 配置错误导致跨租户数据泄漏。→ 执行器统一注入当前 `ent_code`，管理端保存前校验 SQL 和主表别名，只允许单层只读查询，日志记录执行 SQL 对应 Tool 名和参数。
- [Risk] LLM 调用参数与 JSON Schema 不匹配导致 Tool 失败。→ 保存配置时校验 schema 可解析，执行时只绑定 schema 声明字段，缺参时向上抛出参数错误并落错误日志。
- [Risk] Tool 刷新时影响正在进行的问答。→ 使用不可变快照和版本化 ChatClient 缓存，刷新只影响后续请求。
- [Risk] Tool 日志写入失败影响用户问答。→ 日志写入失败不阻断 Tool 结果返回，只记录 warn；助手消息聚合尽力而为。
- [Risk] 过多 Tool 暴露给模型导致 token 成本和选择误判上升。→ 管理端支持将 Tool 置为 `inactive`，描述要求精确；后续可按模块或模式做可见性过滤。

## Migration Plan

1. 在 `conversation-billing-schema.sql` 新增 `a_llm_tool` 与 `a_tool_call_log` 表，并在初始化器幂等键中加入 `a_llm_tool.tool_name`。
2. `application.yml` 的 `app.tenant.ignore-tables` 增加 `a_llm_tool`；`a_tool_call_log` 不忽略租户隔离。
3. 首次启动时创建表并插入少量演示 SQL Tool；已有数据库不会覆盖同名 Tool。
4. 部署后如果需要回滚，禁用所有 DB Tool 或恢复旧版应用；原有代码 Tool 不依赖新增表，问答基础能力可继续工作。

## Open Questions

- Tool 调用日志是否需要前端支持按 `conversationId` 跳转到历史消息。首期可先列表展示，后续再加联动。
