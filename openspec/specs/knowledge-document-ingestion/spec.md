# knowledge-document-ingestion 规格

## Purpose

定义知识库文档导入从正文提取、分片校验到向量分批写入的资源边界，确保最终分片符合实际嵌入模型 token 上限，并规范同租户同来源文档的覆盖导入、失败清理和存量兼容语义。
## Requirements
### Requirement: 知识库文档导入必须具有可预期的资源边界

系统 SHALL 在保留 500MB 单文件和 550MB 整次请求限制的同时，对文档提取文本、最终分片数和单批向量写入数施加应用级上限。超限必须拒绝本次导入，MUST NOT 以一个超大尾分片代替剩余内容。

#### Scenario: 提取文本超过字符预算
- **WHEN** Tika 解析的文档正文超过 500 万个字符
- **THEN** 系统 MUST 在提取阶段终止本次导入
- **AND** 系统 MUST NOT 进入向量写入

#### Scenario: 最终分片超过数量预算
- **WHEN** 实际嵌入 token 二次切分后仍需要超过 2 万个分片
- **THEN** 系统 MUST 拒绝本次导入
- **AND** 系统 MUST NOT 丢弃未处理内容后返回成功

#### Scenario: 向量分批写入
- **WHEN** 文档通过提取文本和分片数校验
- **THEN** 系统 MUST 以每批最多 100 个分片写入向量库
- **AND** 系统 MUST NOT 将全部分片作为一个嵌入批次处理

### Requirement: 最终分片必须符合实际 ONNX 模型 token 上限

系统 SHALL 使用当前 ONNX 嵌入模型的 WordPiece tokenizer 对分片进行未截断编码校验。每个最终分片在包含特殊标记后 MUST 不超过 128 token。

#### Scenario: CL100K 分片超过 WordPiece 边界
- **WHEN** `TokenTextSplitter` 生成的分片在实际 WordPiece tokenizer 下超过 128 token
- **THEN** 系统 MUST 继续拆分该文本
- **AND** 所有最终分片 MUST 通过实际 tokenizer 边界校验后才能写入

### Requirement: 同租户同来源必须采用覆盖导入

系统 SHALL 将文档导入边界扩展为受管知识库中的稳定文档版本。每个向量 MUST 携带 `ent_code`、`knowledge_base_id`、`document_id`、`document_version`、`chunk_id`、`chunk_index` 和 `source`；重复导入同一知识库、同一来源时 MUST 复用稳定 `documentId` 并创建递增版本，MUST NOT 把重复来源累加为多个可用逻辑文档。

#### Scenario: 重新导入同一知识库的同一来源
- **WHEN** 当前租户通过兼容入口重新导入默认知识库中与已有文档 `source_name` 相同的来源
- **THEN** 系统 MUST 复用原 `documentId` 并创建下一版本
- **AND** 新版本成功前上一 `ready` 版本 MUST 继续可检索
- **AND** 新版本成功后上一版本 MUST 转为 `superseded`

#### Scenario: 不同知识库允许同名来源
- **WHEN** 当前租户向两个不同知识库分别导入同名文件
- **THEN** 系统 MUST 创建两个不同的稳定文档身份
- **AND** 任一知识库的检索、替换或删除 MUST NOT 影响另一知识库

#### Scenario: 显式替换不得占用其他文档来源
- **WHEN** 用户显式替换一个稳定文档，但上传来源名已属于同一知识库中的另一个未删除文档
- **THEN** 系统 MUST 在创建 `processing` 版本前返回 `BIZ_ERROR`
- **AND** 原文档、目标文档及其向量 MUST 保持不变

#### Scenario: 分批写入中途失败
- **WHEN** 任一向量批次写入失败
- **THEN** 系统 MUST 尽力按 `ent_code + knowledge_base_id + document_id + document_version` 删除本次已写入分片
- **AND** 系统 MUST 将本次版本标记为 `failed` 并向上抛出导入失败，MUST NOT 返回部分成功
- **AND** 上一 `ready` 版本 MUST 保持不变

#### Scenario: 成功替换后的旧版本清理
- **WHEN** 新版本全部分片写入并成功转为 `ready`
- **THEN** 系统 MUST 将上一可用版本转为 `superseded`
- **AND** 系统 MUST 在状态事务提交后尽力删除上一版本向量
- **AND** 即使旧向量清理暂时失败，检索资格过滤也 MUST 排除该版本

#### Scenario: 并发版本乱序完成
- **WHEN** 较高版本已先转为 `ready`，较低的 `processing` 版本随后才完成向量写入
- **THEN** 系统 MUST 保持较高版本为唯一当前 `ready` 版本
- **AND** 迟到的较低版本 MUST 转为 `superseded` 并尽力清理自身向量

#### Scenario: 已存量向量不自动迁移
- **WHEN** 应用升级到 4.0.0 且存在只含 `ent_code/source` 的旧向量
- **THEN** 系统 MAY 保留旧向量数据不变，但 MUST 从受管知识库检索中排除
- **AND** 新元数据和版本逻辑 MUST 在后续手工重新导入时生效

### Requirement: 导入状态切换必须避免跨库长事务和知识空窗

系统 SHALL 在短 MySQL 事务中登记 `processing` 版本，在事务外执行正文提取、切分、嵌入和 PgVector 写入，再在短事务中完成版本晋级。系统 MUST NOT 在 MySQL 事务中执行 Tika、嵌入模型或向量库长耗时操作。

#### Scenario: 导入开始可观察
- **WHEN** 文件通过基础参数校验并开始导入
- **THEN** 系统 MUST 先提交一条 `processing` 版本记录
- **AND** 文档列表 MUST 能观察到该状态

#### Scenario: 新文档导入成功
- **WHEN** 新文档的全部分片写入成功
- **THEN** 系统 MUST 在短事务中把版本转为 `ready` 并记录实际 `chunk_count`
- **AND** 后续检索才可以把该版本作为合格证据

#### Scenario: 新文档导入失败
- **WHEN** 不存在上一可用版本的新文档导入失败
- **THEN** 系统 MUST 把版本转为 `failed` 并保存安全错误摘要
- **AND** 该文档 MUST 不具有任何可检索版本

#### Scenario: 处理中向量不可见
- **WHEN** 部分批次已经写入 PgVector 但 MySQL 版本仍为 `processing`
- **THEN** 检索资格过滤 MUST 排除这些分片
- **AND** 文档状态查询 MUST NOT 将其误报为 `ready`

### Requirement: 分片元数据必须形成可验证证据身份

系统 SHALL 为同一文档版本内的每个最终分片生成唯一 `chunk_id` 和从 0 开始、按正文顺序递增的 `chunk_index`。所有元数据值 MUST 与 MySQL 注册记录一致，并保留原 `source` 以兼容展示；新生成向量还 MUST 写入当前 `embedding_model` 身份，禁止与旧模型向量混用。

#### Scenario: 分片身份稳定且有序
- **WHEN** 一个文档版本被切分为多个最终分片
- **THEN** 每个分片 MUST 具有不同 `chunk_id`
- **AND** `chunk_index` MUST 从 0 连续递增并反映正文顺序

#### Scenario: 向量元数据与注册记录一致
- **WHEN** 文档版本完成向量写入
- **THEN** 每个分片的租户、知识库、文档 ID 和版本 MUST 能映射到同一条 MySQL 版本记录
- **AND** 任一字段缺失或不一致的分片 MUST NOT 通过检索资格过滤
- **AND** `source` MUST 与 MySQL 注册来源一致，`chunk_id` MUST 非空且 `chunk_index` MUST 为非负整数

#### Scenario: 新嵌入模型向量携带模型身份
- **WHEN** 文档使用当前多语言嵌入模型完成分片向量化
- **THEN** 每个分片 MUST 写入固定且非空的 `embedding_model`
- **AND** 受管检索 MUST 排除缺少该字段或字段值属于旧模型的向量
