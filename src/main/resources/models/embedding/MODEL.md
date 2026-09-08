# 本地嵌入模型

- 模型：`sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2`
- 上游修订：`e8f8c211226b894fcb81acc59f3b34ba3efd5f42`
- ONNX 文件：`onnx/model_quint8_avx2.onnx`
- ONNX SHA-256：`98a01d88b7de996cdea58c32ca71208c09968d143798814b2ea09d3439dc334f`
- tokenizer SHA-256：`2c3387be76557bd40970cec13153b3bbf80407865484b209e655e5e4729076b8`
- 输出维度：384
- 最大输入长度：128 token
- 池化方式：attention-mask 平均池化
- 运行要求：x86-64 CPU 支持 AVX2
- 许可证：Apache-2.0

模型与分词器固定为同一上游修订，`model.onnx` 和 `tokenizer.json` 均使用 Git LFS 管理。构建前必须把两个文件完整下载到本地，并依次通过 LFS 对象检查和 `EmbeddingModelResourceTest` 本地资源校验；替换任一资源后必须同步更新资源测试中的固定哈希、`EmbeddingModelMetadata.CURRENT_MODEL_ID`，并重新导入全部受管知识文档，禁止混用不同模型生成的向量。Dockerfile 保持通用 Maven 打包流程，不单独维护模型哈希。
