# 自查报告

- Change ID: dynamic-llm-tool-management
- Latest Review Time: 2026-07-01 17:29:07
- 变更范围：当前 git diff 覆盖动态 LLM Tool 管理、ToolCallback 注册刷新、动态 SQL 执行与租户隔离、Tool 命中流水、Controller 包迁移、前端工具管理页、fastjson2 替换、README 中英文文档、OpenSpec 主 spec 同步和 archived change。
- OpenSpec 材料：已读取并归档 `openspec/changes/archive/2026-07-01-dynamic-llm-tool-management/` 下的 `proposal.md`、`design.md`、`tasks.md`、`specs/**`；主 spec 已同步 `openspec/specs/dynamic-llm-tools/spec.md` 和 `openspec/specs/spring-ai-2-runtime-compatibility/spec.md`。

## 执行记录

| 时间 | 变更范围摘要 | 结论 |
| --- | --- | --- |
| 2026-07-01 16:29:00 | 审查动态 Tool 管理、SQL 校验、租户隔离、Tool 日志、Spring AI 2 Tool 注册、前端入口和 OpenSpec 文档一致性 | 有风险通过 |
| 2026-07-01 16:32:56 | 按要求精简测试缺口描述，仅保留本地审查结论 | 通过 |
| 2026-07-01 17:18:12 | 复核当前 `git diff HEAD`、OpenSpec 材料和本轮本地验证输出，确认新增日志和当前代码仍一致 | 通过 |
| 2026-07-01 17:29:07 | 提交前复核归档后主 spec、README 中英文同步、最终验证命令和当前 git diff | 通过 |

## 问题清单

无新的阻塞或中高风险问题。

| 状态 | 严重级别 | 文件/行号 | 问题 | 建议 |
| --- | --- | --- | --- | --- |
| 已解决 | 中 | `src/main/java/com/example/rag/tool/dynamic/SqlToolValidator.java:21` | 动态 Tool 的主表别名曾缺少格式校验，可能导致运行期 SQL 拼接风险或 SQL 语法错误。 | 已增加 `[A-Za-z_][A-Za-z0-9_]*` 校验，并补充合法/非法别名单测。 |
| 已解决 | 低 | `src/main/resources/db/init/conversation-billing-schema.sql:270` | Tool 调用流水按租户和创建时间分页查询，曾缺少 `(ent_code, created_at)` 组合索引。 | 已新增 `idx_ent_created_at (ent_code, created_at)`。 |
| 已解决 | 中 | `src/main/java/com/example/rag/config/JacksonCompatibilityConfig.java` | 曾存在暂存区残留的 Jackson 2 `ObjectMapper` 兼容 Bean，与 fastjson2 替换目标冲突。 | 已从暂存区移除，当前源码与测试中无 Jackson 2 ObjectMapper 残留。 |

## OpenSpec 一致性

- `dynamic-llm-tools`：实现已覆盖全局 `a_llm_tool` 管理、启动加载、增删改刷新、动态 SQL 参数绑定、租户条件注入、Tool 命中日志、助手消息聚合和前端工具管理页。
- `spring-ai-2-runtime-compatibility`：`ErpAssistantService` 使用 Tool 快照和 `defaultTools` 构建带 Tool 的 `ChatClient`，避开已废弃的 `defaultToolCallbacks`；knowledge 模式继续使用无 Tool client。
- 原 `com.example.rag.tool` 注解方式保留：代码 Tool 仍由 `MethodToolCallbackProvider` 从 `BaseTool` 子类构建，外层只增加日志包装。
- OpenSpec 文档已同步最终实现：实际 DDL、`ToolRefreshResult`、`active/inactive`、fastjson2、主表别名校验、单层 SELECT 边界和组合索引均已写入 change 文档。
- OpenSpec 归档已完成：`dynamic-llm-tools` 主 spec 已创建，`spring-ai-2-runtime-compatibility` 主 spec 已更新，归档目录为 `openspec/changes/archive/2026-07-01-dynamic-llm-tool-management/`。
- README 中英文已同步本次新增功能：动态 Tool 管理、Tool 命中追踪、动态 Tool 表、调用日志、租户隔离 SQL 执行、初始化示例和包结构均已记录。

## 非功能审查

- 并发：`ToolRegistryService.refresh()` 使用同步方法发布不可变 `ToolSnapshot`，`ErpAssistantService` 以 `provider + snapshot.version()` 缓存 ChatClient，刷新只影响后续请求；`ToolCallRecorder` 使用 `ConcurrentHashMap` 和 `CopyOnWriteArrayList` 按 traceId 聚合，非流式和流式最终都会清理 traceId。
- 安全：动态 SQL 只允许单层只读 SELECT，拒绝写入、DDL、多语句、复杂查询块和未声明参数；Tool 参数使用 `?` 绑定；执行时强制读取 `TenantContext.requireEntCode()` 并注入 `ent_code`；前端新增表格动态字段通过 `escapeHtml()` 或 `safeText()` 渲染。
- 边界：已覆盖空 SQL、写 SQL、多语句、CTE、子查询、非法别名、缺少租户上下文、SQL 自带 LIMIT、尾部子句含参数、Tool 执行异常和结果行数统计。
- 性能：Tool 快照缓存避免每次请求重建 ChatClient；调用日志表补充租户加时间组合索引；本地代码审查未发现明显的热点路径退化。
- 回归风险：Controller 迁移到 `com.example.rag.controller` 后路径保持不变；原代码 Tool 和三种问答模式语义保留；`RespVO` Jackson 3 兼容测试保留，动态 Tool 内部 JSON 改为 fastjson2。

## 测试缺口

- 本地提交门禁已覆盖核心代码路径：`mvn test` 71 个测试通过，`openspec validate --all --strict` 6 个 spec 通过，`git diff --check` 无空白错误，Jackson 2 残留检查无命中，Spring AI Tool 注册 API 检查未命中废弃 API。
- 浏览器手工操作、真实 LLM Tool 命中对话、双租户端到端查询对照和压测仍属于后续联调/验收层面的补充验证项；本地单元、MockMvc、静态契约、启动验证和 spec 校验已覆盖当前提交所需关键路径。

## 结论

- 结果：通过
- 摘要：当前实现与 OpenSpec 已对齐，核心安全和兼容性风险已通过代码约束、单元测试、启动验证和代码审查覆盖。
