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
[描述本次 PR 要解决什么问题、带来了什么功能或修复。如涉及架构调整，说明从哪个访问/分层方式迁移到什么方式]

### 涉及业务域
[列出本次变更涉及的业务域，如：chat、billing、conversation、tool、config、vo、dao、static 等]

### 实现方式
[说明关键技术实现，按本项目分层描述：
- **Controller 层**：新增/修改了哪些接口
- **Service 层**：新增/修改了哪些业务编排
- **DAO / Tool / Config 层**：新增/修改了哪些数据访问、工具或配置
- **VO / Entity 层**：新增/修改了哪些请求响应对象或持久化实体
- **前端静态资源**：是否修改 `static/app.js`、`style.css`、`index.html` 及缓存版本号
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
