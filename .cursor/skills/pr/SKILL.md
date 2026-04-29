---
name: pr
description: 基于 git 提交历史生成结构完整的 PR 描述。用于创建 pull request 时触发。
---

# 生成 GitHub Pull Request 描述

请基于当前的 git 提交历史或代码变更，生成一份结构完整的 PR 描述。

## 步骤：
1. 运行 `git log main..HEAD --oneline` 查看分支上的所有提交
2. 运行 `git diff main...HEAD --stat` 查看变更文件统计
3. 如有 `openspec/changes/<id>/` 设计文档，阅读 proposal.md 和 design.md 了解需求背景
4. 生成 PR 描述

## 输出格式：

### 变更说明
[描述本次 PR 要解决什么问题、带来了什么功能或修复。如涉及 DDD 重构，说明从哪个架构迁移到什么架构]

### 涉及业务域
[列出本次变更涉及的业务域，如：群组域(biz/group)、联系人域(biz/contact) 等]

### 实现方式
[说明关键技术实现，按 DDD 分层描述：
- **Application 层**：新增/修改了哪些 AppService
- **Domain 层**：新增/修改了哪些领域事件、工厂、校验器、领域服务
- **Infrastructure 层**：新增/修改了哪些 Gateway、CacheManager、PersistenceService
- **Model 层**：新增/修改了哪些 Entity、VO、DTO
- **其他**：数据库变更、配置变更等]

### 设计文档
[如有关联的 openspec 设计文档，列出路径：`openspec/changes/<id>/design.md`]

### AI Contribution
[明确说明 AI 协助的部分：
- 代码生成：哪些模块由 AI 辅助编写
- 设计协助：AI 参与了哪些设计决策
- 代码审查：AI 发现并修复了哪些问题
- 其他协助：文档、重构建议等]

---
AI-Generated: true
AI-Tool: Cursor
