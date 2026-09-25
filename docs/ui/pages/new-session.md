# ④ 新建/续接会话（New Conversation）

> 文档索引见 [../README.md](../README.md)；Design Tokens 与组件库见 [../design-principles.md](../design-principles.md)。章节编号沿用拆分前的 `ui_design.md`，未重新编号。

### 6.4 ④ 新建/续接会话（New Conversation，底部 Sheet）🟢

**目的**：开始一个新对话，或续接电脑上已有的 Claude 会话。

**关键元素**（Sheet 内，分两段）：
1. 标题「新建会话」+ 关闭。
2. **Workspace**（只读）：显示当前选中的 Workspace 名，**不可在 Sheet 内切换**。新会话固定归属当前
   Workspace；要在其他 Workspace 建会话，先去 ⑧ 工作区管理切换当前 Workspace。
3. **方式 A · 新建空会话**：
   - Agent 选择（单选，来自 `GET /api/agents`）。
   - **权限模式**：默认继承当前 Session 所属 Workspace 的全局默认值，可在这里覆盖为 `ask` / `acceptEdits` / `bypass`。
4. **方式 B · 续接本机 Claude 会话**：
   - 会话列表来自 `GET /api/claude-sessions?workspaceId=<当前Workspace>`；
   - 每项显示摘要 `summary` + cwd + 相对时间。
5. 主按钮「创建/开始」：**固定在 Sheet 底部**，不随内容滚动。Sheet 主体（Agent / 权限模式 / 会话
   列表）内部滚动，列表再长也不会把主按钮挤出屏幕。

**交互**：选择即高亮；创建成功后自动进入该会话并收起 Sheet。两种方式互斥。
新会话在 `POST /api/chat` 里带 `workspaceId`、`runtime`/`claudeSessionId`、`permissionMode`；服务端校验 Workspace 有效并写入 `sessions.workspace_id`。

> 权限模式是 **Session 级**，不是全局只读配置。新建 Session 默认 `ask`；全局 `default_permission_mode` 可配置为 `acceptEdits`。已有 Session 的权限模式在 ⑩ 会话权限设置里修改。
