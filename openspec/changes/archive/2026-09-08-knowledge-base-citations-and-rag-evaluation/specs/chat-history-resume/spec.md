## ADDED Requirements

### Requirement: 成功助手消息必须保存知识库选择和引用快照

系统 SHALL 将知识问答实际使用的 `knowledgeBaseId`、资格过滤后的 `ragDocCount` 和经验证 `citations` JSON 与成功助手消息原子保存。用户消息以及 `cancelled`、`error` 助手消息的引用 MUST 为空；旧消息缺少新字段时 MUST 按空值兼容。

#### Scenario: 成功知识回答保存证据快照
- **WHEN** 一轮知识问答成功完成并产生合法引用
- **THEN** 系统 MUST 在同一条助手消息保存实际知识库 ID、真实召回分片数和完整引用快照
- **AND** 这些字段 MUST 与消息共享相同 `messageId`、`conversationId`、`ent_code` 和 `user_id` 访问边界

#### Scenario: 无合法引用的成功回答
- **WHEN** 知识问答成功但答案没有合法引用
- **THEN** 系统 MUST 保存实际知识库 ID 和真实 `ragDocCount`
- **AND** 引用 MUST 保存为可解析的空数组或空值并在接口中返回 `[]`

#### Scenario: 失败或取消消息不保存引用
- **WHEN** 助手消息以 `cancelled` 或 `error` 状态结束
- **THEN** `rag_citations` MUST 为空
- **AND** 系统 MUST NOT 把尚未完成校验的候选引用写入历史

#### Scenario: 旧消息保持兼容
- **WHEN** 历史消息的 `knowledge_base_id` 或 `rag_citations` 为 `NULL`
- **THEN** 历史接口 MUST 返回可空知识库 ID 和 `citations = []`
- **AND** 前端 MUST 继续正常渲染原消息文本、状态和图表

### Requirement: 历史详情和续聊必须回放不可变引用快照

系统 SHALL 在 `GET /api/conversations/{conversationId}/messages` 中返回已保存的知识库 ID、召回数和引用。历史详情及续聊页面 MUST 直接回放该快照，MUST NOT 为回放访问 PgVector、重新调用 LLM 或根据文档当前内容重建引用。

#### Scenario: 历史详情回放引用
- **WHEN** 用户打开包含引用的历史会话详情
- **THEN** 接口 MUST 在对应助手消息中返回保存时的引用顺序、来源、版本和摘要
- **AND** 页面 MUST 在该消息下渲染相同引用卡片

#### Scenario: 文档替换后历史引用不改变
- **WHEN** 某引用文档在回答后上传了新版本
- **THEN** 旧助手消息 MUST 继续显示回答时保存的文档版本和摘要
- **AND** 系统 MUST NOT 将引用改写为新版本

#### Scenario: 文档删除后历史引用仍可回放
- **WHEN** 某引用文档在回答后被删除
- **THEN** 历史消息 MUST 继续返回原引用快照
- **AND** 页面 MUST NOT 因当前文档不存在而隐藏整条历史回答

#### Scenario: 引用 JSON 损坏或超限
- **WHEN** 历史记录中存在不可解析或超过读取上限的引用 JSON
- **THEN** 系统 MUST 记录不含敏感正文的告警并把该条引用降级为 `[]`
- **AND** 系统 MUST 继续返回同一会话的其他消息

#### Scenario: 引用历史查询保持用户和租户隔离
- **WHEN** 调用方查询、续聊或归档会话
- **THEN** 系统 MUST 继续使用 `ent_code + user_id + conversationId` 联合校验所有权
- **AND** 引用中的文档 ID 或知识库 ID MUST NOT 绕过会话隔离泄漏其他用户数据

### Requirement: 从历史续聊时必须恢复可用的知识库选择

系统 SHALL 在进入历史会话续聊时读取最后一条具有非空 `knowledgeBaseId` 的成功助手消息。若该知识库仍属于当前租户且为 `active`，前端 MUST 将其设为当前知识库；若已停用或删除，历史引用仍 SHALL 回放，但新知识问答 MUST 等待用户选择可用知识库而不能静默切换。

#### Scenario: 恢复仍可用的历史知识库
- **WHEN** 历史会话最后使用的知识库仍为当前租户的 `active` 知识库
- **THEN** 续聊页面 MUST 选中该知识库
- **AND** 后续知识问答 MUST 携带该 `knowledgeBaseId`

#### Scenario: 历史知识库已不可用
- **WHEN** 历史会话最后使用的知识库已停用或删除
- **THEN** 页面 MUST 保留所有历史引用并提示重新选择知识库
- **AND** 系统 MUST NOT 静默改用默认知识库发送下一次 knowledge 问答

#### Scenario: 旧会话没有知识库记录
- **WHEN** 历史会话所有消息的 `knowledgeBaseId` 都为空
- **THEN** 页面 MUST 保持进入续聊前的有效选择或解析当前租户默认知识库
- **AND** 旧历史消息 MUST 继续按无引用方式展示
