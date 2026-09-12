# 权限与工作区产品技术设计

> 状态：Draft  
> 范围：daemon 协议、数据模型、Android/iOS 客户端信息架构  
> 关联文档：`airemote-daemon/docs/daemon.md`、`docs/ui_design.md`

## 1. 背景与目标

当前 AIRemote 已有：

- Session / Run 模型；
- 单 workspace 根目录；
- Bash PreToolUse 远程审批；
- “允许全部”当前只在内存中按 Run/Session 记录。

本设计要解决：

1. 每个 Session 有独立的权限模式，模式在当前 Session 的后续 Run 中持续生效；
2. “允许全部”改为 Session 级、持久化、可撤销；
3. 手机上可切换权限模式；
4. 手机上可通过目录浏览器添加、切换工作区；
5. 为多工作区、daemon 动态配置打基础。

## 2. 核心概念

| 概念 | 含义 |
|---|---|
| Run | 一次 `/api/chat`，Claude 进程从启动到结束 |
| Session | 跨多个 Run 的连续会话，拥有自己的权限模式、工作区和 cwd |
| Workspace | 一个已注册的绝对目录根节点；手机端切换的工作区，新建 Session 和文件浏览都基于它 |
| Session Cwd | Session 实际启动目录，必须位于所属 Workspace 内 |
| Permission Grant | Session 级的同工具“允许全部”授权，可持久化、可撤销 |

关键约束：

> `--permission-mode` 与 cwd 都是 spawn 参数。  
> Session 配置改变后，只对下一个 Run 生效；当前 Run 不热切换。

## 3. 权限模型

### 3.1 作用域

权限模式是 **Session 级**：

- 每个 Session 保存自己的 `permission_mode`；
- 同一个 Session 后续 Run 都继承该模式；
- 不同 Session 互不影响；
- 新建 Session 的默认模式来自全局配置。

全局配置：

```text
settings.default_permission_mode = ask | acceptEdits | bypass
```

默认值：

```text
ask
```

### 3.2 模式定义

| 模式 | 文件读取 | Write/Edit | Bash 只读命令 | Bash 有副作用命令 |
|---|---|---|---|---|
| `ask` | 自动 | 询问 | 自动 | 询问 |
| `acceptEdits` | 自动 | 自动 | 自动 | 询问 |
| `bypass` | 自动 | 自动 | 自动 | 自动 |

说明：

- `ask` 模式下 Write/Edit 也必须经过审批；
- `acceptEdits` 是当前默认行为的对齐：编辑自动放行，Bash 等仍需询问；
- `bypass` 下不注入 PreToolUse permission hook；
- 只读 Bash 白名单继续保留，适用于 `ask` 与 `acceptEdits`。

### 3.3 “允许全部”

“允许全部”表示：

> 当前 Session 内，后续所有 **同一个 toolName** 都自动同意。

规则：

- 作用域：`session_id + tool_name`；
- 例子：Bash 弹窗点“允许全部” → 本 Session 后续所有 Bash 免问；
- 不会自动放行其他工具，例如 Write/Edit；
- daemon 重启、App 重连后仍有效；
- Session 删除时级联删除；
- 用户可以在 Session 权限页撤销单条或全部。

模式切换时 grant 保留：

- 切到 `bypass` 时 grant 暂时不生效；
- 切回 `ask` / `acceptEdits` 后继续生效；
- UI 需要显示已授权工具，并提供撤销入口。

### 3.4 模式切换时机

- Session 模式修改只影响 **下一个 Run**；
- 当前 Run 已经启动的 Claude 进程仍使用旧模式；
- 若当前 Run 正在等待审批，切换模式不会自动处理该 pending；
- 如需中途生效，用户应停止 Run 并重新发起。

### 3.5 数据模型

```text
sessions
  id
  permission_mode   ask | acceptEdits | bypass
  workspace_id
  cwd

session_permission_grants
  session_id
  tool_name
  created_at
  PRIMARY KEY (session_id, tool_name)

settings
  key
  value
  updated_at
```

### 3.6 API

```http
GET   /api/sessions/:id/permissions
      -> { mode, grants: [{ toolName, createdAt }] }

PATCH /api/sessions/:id/permissions
      { mode: "ask" | "acceptEdits" | "bypass" }
      -> 仅影响后续 Run

DELETE /api/sessions/:id/permissions/grants/:toolName
DELETE /api/sessions/:id/permissions/grants

POST  /api/permissions/:id/decision
      { decision: "allow" | "deny" | "allow_all" }
```

### 3.7 安全边界

- 默认模式为 `ask`；
- `bypass` 必须由用户在明确确认后设置；
- `allow_all` 只按工具名授权，用户需要理解 Bash 等工具的范围；
- 所有模式切换、grant 新增/撤销写入 audit log。

## 4. 工作区模型

### 4.1 不注册嵌套工作区

不允许两个 Workspace 存在包含关系。

例如以下组合不合法：

```text
/home/renbin/code
/home/renbin/code/projectA
```

规则：

- Workspace 作为根目录注册；
- 子目录可以作为 Session 的 cwd，但不再单独注册为 Workspace；
- 新增 Workspace 时，如果路径位于已注册 Workspace 内，应提示用户改选或先移除外层 Workspace。

### 4.2 数据模型

```text
workspaces
  id
  name
  path           # realpath 后的绝对路径
  is_default
  enabled
  created_at
  last_used_at
```

Session 绑定 Workspace：

```text
sessions.workspace_id
sessions.cwd
```

约束：

- 新 Session 的 cwd 必须位于所选 Workspace 内；
- resume 校验基于 Session 自己的 Workspace，而不是全局当前 workspace；
- 删除 Workspace 前检查是否仍有 Session 引用；
- 默认 Workspace 只影响新建 Session。

### 4.3 目录选择器

手机端新增 Workspace 时使用目录浏览器：

1. 默认从 `~` 开始；
2. 展示当前目录下的子目录；
3. 用户逐层进入；
4. 使用“选择当前文件夹”确认；
5. 可选显示隐藏目录。

服务端接口草案：

```http
GET /api/fs/directories?path=/home/renbin&showHidden=false
```

响应：

```json
{
  "path": "/home/renbin",
  "parent": "/home",
  "entries": [
    { "name": "OpenProject", "path": "/home/renbin/OpenProject", "isWorkspace": false }
  ]
}
```

安全要求：

- 只列目录，不做文件内容读取；
- 路径使用 `realpath` 规范化；
- 处理权限不足、目录不存在等错误；
- workspace 不是沙箱，选择 `/`、`~` 等宽目录时需明确警告。

### 4.4 API

```http
GET    /api/workspaces
POST   /api/workspaces             { name?, path }
PATCH  /api/workspaces/:id         { name?, isDefault?, enabled? }
DELETE /api/workspaces/:id

GET    /api/fs/directories?path=...
```

### 4.5 切换语义与客户端上下文

手机端切换的是 **Workspace**，不是 Session Cwd。

- Workspace 是用户可见的工作环境；
- Session Cwd 由 Session 自己保存，通常就是所选 Workspace 根目录；后续如需支持子目录，再作为高级能力；
- 用户切换 Workspace 后：
  - 会话 Tab 按新 Workspace 过滤并重新拉取；
  - 文件 Tab 根目录切到新 Workspace 并重新拉取；
  - 设置页展示新 Workspace 信息和默认配置；
  - 正在其他 Workspace 运行的 Session 不受影响。

建议第一版把“当前选中 Workspace”作为 **客户端本地状态** 保存在 App 里（如 `selectedWorkspaceId`），请求时显式传给 daemon：

```http
GET  /api/sessions?workspaceId=<id>
GET  /api/runs?workspaceId=<id>
GET  /api/claude-sessions?workspaceId=<id>
GET  /api/files?workspaceId=<id>&path=<path>

POST /api/chat
     { sessionId?, workspaceId?, prompt, ... }   # 新建 Session 时使用
```

这样有两个好处：

- 不引入全局 daemon “当前 Workspace”状态，多个客户端可以各自切换；
- 为后续“多 Workspace 来回切换查看运行中的 Session”直接打基础。

daemon 仍然保留 `default_workspace_id`，用于：

- 客户端没有本地选中值时；
- 新建客户端首次连接时；
- 全局默认工作区。

### 4.6 数据读写原则

客户端 `selectedWorkspaceId` 只是请求上下文，服务端数据归属必须由 DB 决定：

- 列表读取：如果传 `workspaceId`，按 `sessions.workspace_id` 过滤；
- 新建 Session：使用请求里的 `workspaceId`，或 fallback 到 `default_workspace_id`；
- 继续已有 Session：使用 `session.workspace_id`，忽略客户端当前选中的其他 Workspace；
- 单 Session / 单 Run 操作：以数据自身归属为准，不因客户端切换 Workspace 而失效；
- 文件访问：必须由服务端用 Workspace root + 相对路径做 canonicalize 和越界校验；
- Workspace 被删除/禁用后，客户端需要重新拉取列表并 fallback 到默认或第一个可用 Workspace；
- 切换 Workspace 时，客户端需要取消旧请求或丢弃过期响应，避免旧 Workspace 数据覆盖新页面。

## 5. 动态配置

### 5.1 可变配置

存入 `settings` 表，手机上可改：

- `default_permission_mode`
- `default_workspace_id`
- 其他轻量偏好

### 5.2 重启级配置

继续走 env / CLI，不通过手机热改：

- host
- port
- token
- dataDir
- TLS

### 5.3 API

```http
GET   /api/config
PATCH /api/config
      { defaultPermissionMode, defaultWorkspaceId }
```

`GET /api/config` 同时返回只读信息，标记 `restartRequired`。

## 6. UI 影响

需要更新 `docs/ui_design.md`，主要涉及：

1. **SettingsScreen**
   - 连接
   - 当前选中的 Workspace
   - 默认 Workspace
   - 默认权限模式
   - 工作区管理
   - 只读/高级信息

2. **WorkspaceSettingsScreen**
   - 工作区列表
   - 默认工作区
   - 新增工作区
   - 删除/禁用
   - Session 数量

3. **DirectoryPickerScreen**
   - 面包屑
   - 目录列表
   - 返回上一级
   - 选择当前文件夹

4. **NewSessionSheet**
   - 选择 Workspace
   - 选择权限模式
   - 默认继承全局配置

5. **ChatScreen / SessionSettings**
   - 显示当前 Session 的 Workspace、cwd、权限模式
   - 修改权限模式
   - 查看/撤销 grants

6. **PermissionDialog**
   - 根据工具类型渲染：
     - Bash：command
     - Write：文件路径 + 内容预览
     - Edit：文件路径 + diff
   - “允许全部”文案提示作用范围：当前 Session 后续同工具。

## 7. 迁移策略

1. 启动时把当前 `config.workspace` 注册为默认 Workspace；
2. 给 `sessions` 增加 `workspace_id` 和 `permission_mode`；
3. 现有 Session 的 `cwd` 在默认 Workspace 下 → 绑定默认 Workspace；
4. 不在默认 Workspace 下的旧 Session → 标记为未归属，UI 提示用户手动指定，或按历史 cwd 创建 Workspace；
5. 现有 Session 的 `permission_mode` 初始化为全局默认值；
6. 现有内存中的 auto-allow 不迁移；
7. 旧 API 暂时保留兼容，后续逐步收敛到新接口。
