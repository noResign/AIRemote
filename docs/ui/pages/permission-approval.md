# Ⓟ 权限审批浮层（Permission Approval）

> 文档索引见 [../README.md](../README.md)；Design Tokens 与组件库见 [../design-principles.md](../design-principles.md)。章节编号沿用拆分前的 `ui_design.md`，未重新编号。

### 6.7 Ⓟ 权限审批浮层（Permission Approval）🔴 M1 安全必需

**目的**：agent 想执行需要审批的工具时，暂停运行，由用户允许/拒绝。这是本产品的**安全边界**。

**触发**：聊天流中出现 `permission_request` 事件，内容为
`{permissionId, toolName, toolInput, status}`。

> `Read` / `Grep` 不纳入审批（读工作区外不弹框），所以本浮层不会再收到这两个工具。

**按工具类型渲染**：

| toolName | 展示内容 |
|---|---|
| `Bash` | command 全文，等宽、可滚动、长按复制 |
| `Write` | 文件路径 + 内容预览 |
| `Edit` / `MultiEdit` | 文件路径 + diff |
| 其他 | 通用 `toolInput` JSON 展示 |

**界面**（全屏覆盖浮层，modal，不可通过点外部关闭）：
1. 头部：警示图标 + 标题「Claude 请求执行」。
2. 工具名（如 `Bash` / `Write`）。
3. **工具参数主体**：按上表渲染。
4. 安全提示小字："此操作可能修改文件或系统，请确认安全后再允许。"
5. 超时提示：`超时未处理将自动拒绝`（不写死 120 秒，超时值由 daemon 配置决定）。
6. 操作按钮：
   - **允许**（primary）→ `{decision: "allow"}`；
   - **允许全部（本 Session）**（secondary）→ `{decision: "allow_all"}`；
   - **拒绝**（error）→ `{decision: "deny", reason?}`。
7. 拒绝时展开可选理由输入框。

**“允许全部”说明**：

- 作用域：当前 Session 后续所有 **同一个 toolName**（MCP 工具是**整个 Server**）；
- 例如：Bash 弹窗点“允许全部” → 本 Session 后续所有 Bash 免问；
- 不会自动放行 Write/Edit 等其他工具；
- 可在 ⑩ 会话权限设置里撤销。

**状态**：
- pending：等待用户作答；
- 已处理：浮层关闭；
- 超时：浮层自动消失，并在消息流里显示“命令审批超时，已自动拒绝”。
