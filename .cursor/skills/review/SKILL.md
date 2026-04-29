---
name: review
description: 对代码变更进行全面审查：按变更所在模块差异化检查；im 模块含 DDD 与 IM 专属约束，其他模块以通用规范与各模块 openspec 为准。用于代码审查、检查变更时触发。
---

# 代码审查

请对提供的代码变更进行全面的审查。

## 审查前准备

1. 根据变更文件路径判断**所属模块**（例如路径前缀 `im/`、`wallet/`、`system/`、`common/`）。
2. 阅读 `openspec/config.yaml` 获取通用约束，再阅读 `openspec/context/{module}.md`（如 `openspec/context/im.md`、`openspec/context/wallet.md`）获取模块专属架构约束。
3. **仅当变更位于 `im/` 模块**（或明确为 IM 服务代码）时，才执行下文「im 模块专属」中的 DDD、融云策略、领域事件与 IM 实体等检查；**wallet、system、common 等模块不套用 im 的 DDD 硬性条目**，以其 `openspec/context/{module}.md` 与 `openspec/config.yaml` 为准。

---

## 审查重点（全模块通用）

### 1. 功能性缺陷

- 逻辑错误或边界条件缺失
- 业务逻辑与需求不符
- 错误处理不完整
- Entity 是否按模块约定继承 `BaseEntityWithStringId`（见 `openspec/config.yaml` 与 `openspec/context/{module}.md`）

### 2. 安全问题

- 潜在的安全漏洞（注入、越权、信息泄露等）
- JWT Secret / API Key 等敏感信息是否写入日志
- SQL 是否参数化（JPA 参数绑定 / MyBatis `#{}` 占位符）
- 内部 API 是否有 `@RequireSignature` 签名校验（若该模块采用此模式）
- Entity 是否直接暴露给前端（必须转 VO）

### 3. 性能与事务

- JPA N+1 查询
- 缺失的数据库索引
- Redis 缓存策略是否合理
- 事务范围是否合理；**禁止**在本地事务内包含第三方或跨服务调用（见 `openspec/config.yaml` 通用编码约束）
- 非 im 模块：`@Transactional` 放在合适的 Service 层即可，以 `openspec/context/{module}.md` 与既有分层为准

### 4. 代码质量

- 是否使用 `@RequiredArgsConstructor` 构造器注入（禁止 `@Autowired` 字段注入，禁止 `SpringContextHolder.getBean()`，与 `openspec/config.yaml` 通用编码约束一致）
- 代码可读性与维护性
- 重复代码

---

## im 模块专属（仅当变更在 `im/` 下时检查）

### DDD 架构合规

- 新代码是否放在 `biz.{domain}` 包下（禁止在顶层 controller/service/entity 新增）
- 是否遵循分层依赖：Controller → AppService → Domain → Infrastructure → Repository
- Controller 是否仅调用 AppService，不包含业务逻辑
- Domain 层是否依赖了 Infrastructure（禁止）
- IM 服务商调用是否通过 `ChatApiStrategy`（禁止直接调用融云 SDK）
- 命名是否符合 IM 规范（AppService / Factory / Validator / Gateway / CacheManager 等，详见 `openspec/context/im.md`）

### 事务与领域事件（im DDD 约定）

- `@Transactional` 是否主要在 AppService（或设计允许的 Infrastructure）层，Domain 层无事务注解
- 领域事件是否使用 `record` + `sealed interface`；监听器是否用 `@EventListener` + `@Async`（与 `openspec/context/im.md` 一致）

### IM 数据与实体

- IM 本库实体是否有 `@Table` 注解；外部引用实体是否误加 `@Table`（见 `openspec/context/im.md`）

---

## 输出要求

- 使用中文回复
- **先简要说明**本次审查覆盖的模块，以及是否启用了「im 模块专属」检查
- 按实际启用的类别组织发现的问题：
  - 全模块：**功能性缺陷**、**安全问题**、**性能与事务**、**代码质量**
  - 仅 im 时追加：**DDD 与 IM 专属**（可将 DDD、融云、领域事件、IM 实体合并在此类下）
- 每个问题请注明：
  - **位置**：文件及行号（如可获取）
  - **问题描述**：具体是什么问题
  - **风险等级**：高/中/低
  - **建议修改**：具体的修复建议
- 如未发现问题，请明确说明「未发现重大问题」

## 审查范围

- 如未指定具体代码，请审查最近变更的文件
- 如已选中代码片段，仅审查选中部分
