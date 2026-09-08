## ADDED Requirements

### Requirement: 项目必须维护版本化且覆盖关键风险的 RAG 评测集

系统 SHALL 在源码仓库中维护可审查、可重复的版本化评测 fixture。首个版本 MUST 至少包含 50 条用例：20 条单文档、10 条多文档、10 条同义改写、5 条无答案和 5 条租户/知识库隔离用例。

#### Scenario: 评测集结构完整
- **WHEN** 加载 `rag-eval/v1` 评测集
- **THEN** 每条用例 MUST 具有稳定 `caseId`、问题、目标知识库、期望文档 ID、可回答标志、答案关键事实和分类标签，并 MAY 为安全语义配置禁止出现的关键错误结论短语
- **AND** 各分类数量 MUST 达到规定下限

#### Scenario: 重复用例 ID 被拒绝
- **WHEN** 同一评测版本包含重复 `caseId`
- **THEN** 评测 MUST 在调用模型前失败
- **AND** 报告 MUST 指明重复 ID

#### Scenario: 隔离用例具有干扰文档
- **WHEN** 执行租户或知识库隔离用例
- **THEN** fixture MUST 在非目标租户或非目标知识库准备语义相关的干扰文档
- **AND** 评测 MUST 能识别这些文档是否被错误召回

### Requirement: 真实评测必须通过显式 Maven Profile 隔离运行

系统 SHALL 提供 `rag-eval` Maven Profile，并通过 Failsafe 在 `mvn -Prag-eval verify` 中运行真实 MySQL、PgVector 和 LLM 评测。普通 `mvn test` MUST 保持离线、确定性且不要求任何模型 Key。

#### Scenario: 普通单元测试不运行真实评测
- **WHEN** 开发者执行 `mvn test`
- **THEN** 系统 MUST NOT 连接真实 PgVector、MySQL 或外部 LLM
- **AND** RAG 指标算法、引用校验和 SSE 协议 MUST 由 mock/fake 单元测试覆盖

#### Scenario: 显式 Profile 运行真实评测
- **WHEN** 开发者执行 `mvn -Prag-eval verify` 且依赖配置完整
- **THEN** Failsafe MUST 运行真实评测集
- **AND** 评测 MUST 复用生产文档导入、检索、资格过滤、上下文编号和引用校验组件

#### Scenario: 依赖或 Key 缺失
- **WHEN** 已显式启用 `rag-eval` 但 MySQL、PgVector、模型配置或 API Key 缺失
- **THEN** 构建 MUST 以明确错误失败
- **AND** 系统 MUST NOT 将评测静默标记为跳过或通过

### Requirement: 评测数据必须与生产租户及计费隔离

系统 SHALL 为每次真实评测生成随机测试 `ent_code` 和知识库，结束时按这些标识清理 MySQL 注册数据和 PgVector 向量。评测 MUST NOT 创建用户会话、写入用户聊天历史或扣减租户余额。

#### Scenario: 正常完成后清理
- **WHEN** 真实评测全部执行完成
- **THEN** Runner MUST 删除本次随机租户/知识库的文档向量和注册数据
- **AND** MUST NOT 删除其他租户或其他评测运行的数据

#### Scenario: 中途失败仍尝试清理
- **WHEN** 评测在导入、检索或 LLM 调用阶段失败
- **THEN** Runner MUST 在结束路径尽力执行同一隔离范围的清理
- **AND** 原始失败 MUST 保留为构建失败原因

#### Scenario: 清理失败不得误报通过
- **WHEN** 任一评测文档向量或随机租户关系数据在最终清理后仍无法确认已删除
- **THEN** Runner MUST 继续尝试其余独立清理步骤，并在 JSON 与 Markdown 报告中列出清理失败原因
- **AND** 显式评测 MUST 触发硬门禁失败，不得在遗留评测数据时报告构建通过

#### Scenario: 评测不经过租户余额扣减
- **WHEN** Runner 调用真实模型生成回答或执行 Judge
- **THEN** 系统 MUST NOT 调用生产会话的余额扣减流程
- **AND** 报告 MUST 单独记录实际输入/输出 Token 和估算成本

### Requirement: 评测必须输出可机读和可阅读的完整指标报告

系统 SHALL 在 `target/rag-eval/` 同时输出 JSON 与 Markdown 报告。报告 MUST 包含逐用例结果、Recall@5、MRR@5、空召回率、无答案拒答率、引用有效率、关键事实命中率、关键错误结论、P50/P95 延迟、输入/输出 Token、估算成本、相关性和事实一致性。

#### Scenario: 计算 Recall 和 MRR
- **WHEN** 可回答用例执行完成
- **THEN** `Recall@5` MUST 按前 5 条召回命中的期望文档比例计算后取平均
- **AND** `MRR@5` MUST 按首个期望文档在前 5 条中的排名倒数计算后取平均

#### Scenario: 输出延迟和用量
- **WHEN** 一条真实用例完成或失败
- **THEN** 报告 MUST 记录端到端延迟和可观测 usage
- **AND** 汇总 MUST 给出 P50/P95、Token 合计及按已配置 Provider 单价估算的成本

#### Scenario: Provider 单价缺失
- **WHEN** 模型存在 token usage 但未配置对应 Provider 单价
- **THEN** 报告 MUST 保留 Token 数据并把成本标记为不可用
- **AND** 系统 MUST NOT 按零成本报告

#### Scenario: LLM Judge 仅报告
- **WHEN** `RelevancyEvaluator` 或 `FactCheckingEvaluator` 产生评分或自身调用失败
- **THEN** 报告 MUST 展示相关性和事实一致性结果或失败原因
- **AND** 首版 Judge 结果 MUST NOT 单独决定默认构建是否通过

#### Scenario: 确定性关键事实检查
- **WHEN** Runner 完成一条模型回答
- **THEN** 报告 MUST 展示该用例命中的关键事实数和关键事实总数，并聚合关键事实命中率
- **AND** 关键事实和禁止短语的比较 MUST 忽略大小写、空白、标点及 Markdown 符号
- **AND** 该确定性检查 MUST NOT 改变 LLM Judge 仅报告的规则

#### Scenario: 用例中途失败时报告不得误报完整结果
- **WHEN** 真实评测因单条用例执行失败而提前结束
- **THEN** JSON 与 Markdown 报告 MUST 显示已完成用例数、总用例数和具体失败原因
- **AND** 部分评测结果 MUST 触发硬门禁失败，不得被标记为完整评测通过

### Requirement: 安全隔离和证据一致性必须作为硬门禁

系统 SHALL 将跨租户/跨知识库召回、无效引用、引用不属于本轮召回和命中 fixture 明确配置的关键错误结论视为硬失败。若存在已提交基线，当前 Recall@5 相比基线下降超过 5 个百分点也 MUST 使显式评测失败。

#### Scenario: 出现跨边界召回
- **WHEN** 任一隔离用例召回非目标租户或非目标知识库文档
- **THEN** `mvn -Prag-eval verify` MUST 失败
- **AND** 报告 MUST 列出用例、文档和越界类型

#### Scenario: 出现无效引用
- **WHEN** 任一回答引用不存在的编号或不属于本轮实际召回的分片
- **THEN** 显式评测 MUST 失败
- **AND** 引用有效率 MUST 如实计算，不得在报告阶段删除失败样本
- **AND** 证据归属 MUST 按 `knowledgeBaseId + documentId + documentVersion + chunkId + chunkIndex` 完整身份比对

#### Scenario: Recall 相对基线回归
- **WHEN** 当前 Recall@5 比同版本基线下降超过 5 个百分点
- **THEN** 显式评测 MUST 失败并显示当前值、基线值和差值

#### Scenario: 回答命中关键错误结论
- **WHEN** 任一回答在规范化后以肯定语义包含该用例明确配置的禁止短语
- **THEN** 显式评测 MUST 失败
- **AND** JSON 与 Markdown 报告 MUST 列出用例 ID 和命中的原始禁止短语
- **AND** 禁止短语紧邻“并非”“不能”“不可”等明确否定词时 MUST NOT 计为命中，但回答后续再次肯定同一禁止短语时仍 MUST 失败

#### Scenario: 质量目标状态可见
- **WHEN** 评测完成且未触发硬失败
- **THEN** 报告 MUST 分别标注 Recall@5 是否达到 85%、无答案拒答率是否达到 90%、引用有效率是否达到 100%
- **AND** 首版报告目标未达标 MUST 可与硬门禁失败清晰区分

#### Scenario: 无答案回答必须明确拒绝推测
- **WHEN** 本轮证据没有明确包含问题所需的对象、数值、日期或步骤
- **THEN** 模型 MUST 明确说明资料不足、无法确认、未找到或未包含所需信息
- **AND** 系统 MUST NOT 用相近主题、常识或未提供的事实补全答案
