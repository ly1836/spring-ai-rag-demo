# knowledge-base-management Specification

## Purpose
TBD - created by archiving change knowledge-base-citations-and-rag-evaluation. Update Purpose after archive.
## Requirements
### Requirement: 每个租户必须具有唯一可用的默认知识库

系统 SHALL 为每个使用知识能力的租户提供且仅提供一个默认知识库。首次访问知识库列表、兼容导入、问答或搜索而当前租户尚无知识库时，系统 MUST 在当前 `ent_code` 下创建“默认知识库”，并且并发请求 MUST NOT 创建多个默认知识库。

#### Scenario: 首次访问懒创建默认知识库
- **WHEN** 当前租户没有任何未删除的知识库并首次调用知识库列表
- **THEN** 系统 MUST 创建一个状态为 `active` 的默认知识库
- **AND** 响应 MUST 只返回当前租户的知识库

#### Scenario: 并发首次访问保持唯一
- **WHEN** 同一租户并发触发多个需要默认知识库的请求
- **THEN** 系统 MUST 最终只保留一个默认知识库
- **AND** 所有请求解析到的 `knowledgeBaseId` MUST 相同

#### Scenario: 不同租户默认库相互隔离
- **WHEN** 两个租户分别首次使用知识能力
- **THEN** 系统 MUST 为两个 `ent_code` 分别创建不同的默认知识库
- **AND** 任一租户 MUST NOT 在列表或详情中看到另一租户的知识库

### Requirement: 知识库管理接口必须提供受租户隔离的生命周期操作

系统 SHALL 通过 `/api/knowledge-bases` 提供列表、创建、更新、启停、设为默认和删除能力。`KnowledgeBaseController` MUST 仅绑定参数、调用 `KnowledgeBaseService` 并返回 `RespVO<T>`；所有读写 MUST 以 `ent_code` 为条件，跨租户对象 MUST 按不存在处理。

#### Scenario: 创建知识库
- **WHEN** 当前租户向 `POST /api/knowledge-bases` 提交合法且未重名的 `name` 和可选 `description`
- **THEN** 系统 MUST 返回包含新 `knowledgeBaseId`、`active` 状态和默认标记的成功响应
- **AND** 新知识库 MUST 只属于当前 `ent_code`

#### Scenario: 非法创建参数
- **WHEN** 知识库名称为空、只含空白或超过规定长度
- **THEN** 系统 MUST 通过全局异常处理返回 `PARAM_ERROR`
- **AND** 系统 MUST NOT 写入知识库记录

#### Scenario: 同租户知识库重名
- **WHEN** 当前租户创建或重命名为一个未删除的同名知识库
- **THEN** 系统 MUST 返回 `BIZ_ERROR`
- **AND** 不同租户使用相同名称 MUST 不受影响

#### Scenario: 并发创建或重命名保持名称唯一
- **WHEN** 同一租户并发创建或重命名为相同知识库名称
- **THEN** 系统 MUST 在租户锁内串行执行名称校验与写入
- **AND** 最终 MUST 只有一个未删除知识库使用该名称

#### Scenario: 更新并设为默认
- **WHEN** 当前租户更新一个存在的知识库并将其设为默认
- **THEN** 系统 MUST 在一个 MySQL 事务中取消原默认标记并设置新默认标记
- **AND** 事务完成后当前租户 MUST 仍只有一个默认知识库

#### Scenario: 停用默认知识库被拒绝
- **WHEN** 用户尝试将当前默认知识库状态改为 `inactive`
- **THEN** 系统 MUST 返回 `BIZ_ERROR`
- **AND** 默认知识库 MUST 保持 `active`

#### Scenario: 删除知识库的保护边界
- **WHEN** 用户尝试删除默认知识库或仍包含未删除文档的知识库
- **THEN** 系统 MUST 返回 `BIZ_ERROR`
- **AND** 系统 MUST NOT 隐式级联删除文档或向量

#### Scenario: 删除空的非默认知识库
- **WHEN** 当前租户删除一个存在、非默认且不含未删除文档的知识库
- **THEN** 系统 MUST 在租户锁和知识库行锁之后按最新状态执行保护校验
- **AND** 系统 MUST 将知识库软删除并从后续列表中排除

#### Scenario: 跨租户知识库操作
- **WHEN** 当前租户用另一租户的 `knowledgeBaseId` 查询、更新、设为默认或删除
- **THEN** 系统 MUST 返回与对象不存在一致的 `BIZ_ERROR`
- **AND** 响应 MUST NOT 泄漏对象名称、状态或所属租户

### Requirement: 知识库文档必须具有稳定身份、版本和可观察状态

系统 SHALL 通过知识库文档子资源管理文档。文档 MUST 使用稳定 `documentId` 标识逻辑文档，每次替换创建递增版本，并暴露 `processing`、`ready`、`failed`、`superseded` 或 `deleted` 状态、当前版本、分片数、错误摘要和更新时间。

#### Scenario: 上传新文档
- **WHEN** 当前租户向 `POST /api/knowledge-bases/{knowledgeBaseId}/documents` 上传合法文件
- **THEN** 系统 MUST 创建稳定 `documentId` 和版本 `1`
- **AND** 系统 MUST 先登记 `processing`，成功后转为 `ready`

#### Scenario: 替换文档创建新版本
- **WHEN** 当前租户向 `PUT /api/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/content` 上传合法替换文件
- **THEN** 系统 MUST 保持 `documentId` 不变并创建递增版本
- **AND** 新版本成功前上一 `ready` 版本 MUST 继续可用于检索

#### Scenario: 替换失败保留上一可用版本
- **WHEN** 新版本在提取、切分、嵌入或向量写入阶段失败
- **THEN** 系统 MUST 将新版本标记为 `failed` 并记录安全、截断的错误摘要
- **AND** 上一 `ready` 版本 MUST 保持可检索
- **AND** 本次失败版本已写入的向量 MUST 被尽力清理

#### Scenario: 查询文档列表
- **WHEN** 当前租户调用 `GET /api/knowledge-bases/{knowledgeBaseId}/documents`
- **THEN** 响应 MUST 按稳定文档聚合并返回来源名、当前版本及状态、分片数、错误摘要和时间
- **AND** `superseded` 与 `deleted` 版本 MUST NOT 被误报为当前可用版本

#### Scenario: 旧模型可用版本提示重新导入
- **WHEN** 稳定文档存在数据库状态为 `ready` 的版本，但该版本没有记录当前 `embedding_model`
- **THEN** 文档列表 MUST 返回 `requiresReindex = true`
- **AND** 前端 MUST 将其显示为“需重新导入”并保留替换入口
- **AND** 系统 MUST NOT 根据向量维度、来源名或旧状态猜测并回填当前模型身份

#### Scenario: 停用知识库拒绝新导入
- **WHEN** 用户向状态为 `inactive` 的知识库上传新文档或替换版本
- **THEN** 系统 MUST 返回 `BIZ_ERROR`
- **AND** 系统 MUST NOT 创建 `processing` 版本或写入向量

#### Scenario: 删除文档
- **WHEN** 当前租户删除一个存在的稳定文档
- **THEN** 系统 MUST 将该文档全部版本标记为 `deleted`
- **AND** 系统 MUST 按 `ent_code + knowledgeBaseId + documentId` 尽力删除向量
- **AND** 该文档 MUST 不再进入检索资格集合

#### Scenario: 文档参数与基础设施错误语义
- **WHEN** 上传文件为空、格式不支持、超过请求或导入资源边界
- **THEN** 系统 MUST 返回 `PARAM_ERROR`
- **AND** 当已通过参数校验但数据库、解析器或向量基础设施异常时，系统 MUST 返回 `SYSTEM_ERROR`，且已登记版本 MUST 可查询为 `failed`

### Requirement: 兼容导入入口必须代理当前租户默认知识库

系统 SHALL 保留现有 `/api/load` 和 `/api/upload` 路径。两者 MUST 将文档导入当前租户默认知识库；默认库内相同 `source_name` 的重复导入 MUST 复用稳定 `documentId` 并创建下一版本，而不是累加独立逻辑文档。

#### Scenario: 兼容上传首次导入
- **WHEN** 当前租户通过 `/api/upload` 上传默认库中不存在的来源
- **THEN** 系统 MUST 在默认知识库创建新稳定文档和版本 `1`

#### Scenario: 兼容上传重复来源
- **WHEN** 当前租户通过 `/api/upload` 重复上传默认库中同名来源
- **THEN** 系统 MUST 为原 `documentId` 创建下一版本
- **AND** 成功后 MUST 只有新版本具有检索资格

#### Scenario: 旧向量升级边界
- **WHEN** 应用从 4.0.0 之前版本升级且 PgVector 中存在仅含 `ent_code/source` 的向量
- **THEN** 受管知识库检索 MUST NOT 把这些向量作为可引用证据
- **AND** 管理员 MUST 通过兼容入口重新导入需要保留的文档

### Requirement: 内置前端必须支持知识库和文档管理

系统 SHALL 在现有零构建静态前端中提供知识库选择、创建、启停、设为默认、文档上传/替换/删除、状态与错误摘要展示。所有非流式请求 MUST 复用统一 `apiCall`，所有动态字符串 MUST 转义后渲染。

#### Scenario: 切换当前知识库
- **WHEN** 用户在聊天页面选择一个当前租户的 `active` 知识库
- **THEN** 后续知识问答和搜索请求 MUST 携带对应 `knowledgeBaseId`
- **AND** 页面 MUST 更新当前知识库状态但不清空既有历史引用快照

#### Scenario: 知识库列表加载失败
- **WHEN** 前端刷新当前租户的知识库列表失败
- **THEN** 前端 MUST 清空内存中的旧知识库列表和当前知识库 ID
- **AND** 在列表重新加载并选择有效知识库前，知识问答、搜索和文档写入 MUST NOT 使用上一次成功加载的知识库
- **AND** 页面中已经展示的历史引用快照 MUST 保持不变

#### Scenario: 展示处理和失败状态
- **WHEN** 文档列表包含 `processing` 或 `failed` 版本
- **THEN** 前端 MUST 显示对应状态
- **AND** `failed` 文档 MUST 显示服务端返回的安全错误摘要

#### Scenario: 动态值安全渲染
- **WHEN** 来源名、知识库名或错误摘要包含 HTML 特殊字符
- **THEN** 前端 MUST 将其作为文本显示
- **AND** 系统 MUST NOT 执行其中的标签或脚本

#### Scenario: 静态资源缓存更新
- **WHEN** 本迭代修改 `app.js` 或 `style.css`
- **THEN** `index.html` MUST 同步递增对应静态资源缓存版本
- **AND** 项目 MUST NOT 引入 npm、CDN 或前端构建工具
