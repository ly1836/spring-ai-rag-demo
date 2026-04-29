---
name: commit
description: 分析 git 变更并生成规范的提交信息。用于提交代码、生成 commit message 时触发。
---

# 生成 Commit Message

分析当前 git 变更，生成规范的提交信息。

## 步骤：
1. 运行 `git diff --cached` 或 `git diff` 查看变更内容
2. 判断变更类型和涉及的业务域
3. 生成符合规范的 commit message

## 格式：

```
<type>: <中文描述>

AI-Generated: true
AI-Tool: Cursor
Reviewed-by: <git config user.name>
```

## type 选择：
- `feat`: 新功能（新增 AppService / Controller / 领域事件等）
- `fix`: 修复缺陷
- `refactor`: 重构（如将旧版三层架构迁移到 DDD）
- `docs`: 文档变更（openspec 设计文档等）
- `style`: 格式调整（不影响逻辑）
- `test`: 测试相关
- `chore`: 构建/依赖/配置变更

## 描述规范：
- 优先使用中文
- 指明涉及的业务域（如：群组域、联系人域、语音通话域）
- 说明变更的核心内容

## 示例：

```
feat: 群组域 — 新增群公告 AppService 和领域事件

AI-Generated: true
AI-Tool: Cursor
Reviewed-by: developer
```

```
refactor: 联系人域 — 将 MpContactService 重构为 DDD 分层架构

AI-Generated: true
AI-Tool: Cursor
Reviewed-by: developer
```
