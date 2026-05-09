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
- `feat`: 新功能（新增 Controller / Service / Tool / DAO / 前端交互等）
- `fix`: 修复缺陷
- `refactor`: 重构（如数据访问层迁移、服务拆分、配置整理）
- `docs`: 文档变更（openspec 设计文档等）
- `style`: 格式调整（不影响逻辑）
- `test`: 测试相关
- `chore`: 构建/依赖/配置变更

## 描述规范：
- 优先使用中文
- 指明涉及的业务域（如：chat、billing、conversation、tool、config、vo、dao、static）
- 说明变更的核心内容

## 示例：

```
refactor: 计费域 — 将 JdbcTemplate 查询迁移为 MyBatis-Plus Mapper

AI-Generated: true
AI-Tool: Cursor
Reviewed-by: developer
```
