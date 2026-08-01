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

系统 SHALL 使用 `ent_code + source` 作为文档导入覆盖边界。重复导入同一来源时 MUST 删除旧分片后写入新分片，MUST NOT 累加重复向量。

#### Scenario: 重新导入同一来源
- **WHEN** 当前租户重新导入与已有向量 `source` 相同的文档
- **THEN** 系统 MUST 先按 `ent_code + source` 删除旧向量
- **AND** 新文档分片 MUST 按批写入

#### Scenario: 分批写入中途失败
- **WHEN** 任一向量批次写入失败
- **THEN** 系统 MUST 尽力再次按 `ent_code + source` 删除本次已写入分片
- **AND** 系统 MUST 向上抛出导入失败，MUST NOT 返回部分成功

#### Scenario: 已存量向量不自动迁移
- **WHEN** 应用升级到新的分片和覆盖导入逻辑
- **THEN** 系统 MAY 保留升级前已存量向量不变
- **AND** 新逻辑 MUST 在后续手工重新导入时生效
