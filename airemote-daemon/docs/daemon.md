# airemote daemon 技术方案

> 面向维护者和后续接入客户端的开发者。代码在 `airemote-daemon/`。
> 本文档覆盖：整体设计、实现细节、HTTP/SSE 协议、权限模型、**全部配置参数**、构建与分发。

## 1. 概述

`airemote-daemon` 是一个跑在 macOS/Linux 本机的守护进程：它 `spawn` 本机已安装的编码
agent CLI（目前 Claude Code，架构上预留多 agent），以无头方式执行，把输出解析成统一的
流式事件，通过 HTTP/SSE 推给远程客户端（Android/iOS/网页），并支持会话续接、断线重连、
取消、工具审批、多 agent 并发。

核心定位：**「远程操纵本机 agent」**。因此安全边界（认证、权限审批、工作目录、默认拒绝）
是一等公民，而不是事后补丁。

**关键心智模型**：手机只是遥控器，任务在电脑上执行。**手机断连不影响任务**——daemon 继续
跑，重连后能续上。

### Codex 接入（实验性）

Codex 通过 `codex app-server` 的 stdio JSON-RPC 接入。`GET /api/agents` 同时列出 Claude
和 Codex；安装与登录状态在启动对应运行时检查。续接会话以数据库中的 `runtime` 为准，客户端
后续请求可省略 runtime；显式传入不同 runtime 会返回 `400 runtime_mismatch`。

- `sessions.native_session_id` 保存 Claude session ID 或 Codex thread ID；旧数据库的
  `claude_session_id` 列自动迁移。提供方 ID 不改变客户端使用的 AIRemote 会话 ID。
- 两种运行时共享 `runtimes/active-runs.ts` 的运行注册表，供运行列表、运行中标记、取消、
  断线回放后续直播及删除会话时的清理使用。run 结束后移除注册。
- 生命周期与 Claude 对齐：空闲看门狗（`AIREMOTE_RUN_IDLE_TIMEOUT_SECONDS`）同样作用于
  Codex run，长时间无事件的 app-server 会被取消；取消在握手期（`initialize` / 打开 thread
  期间）也记为 `cancelled`——那时底层会 reject 掉在途的 RPC，不能当成崩溃。
- 命令和文件审批返回 `decision`；`item/permissions/requestApproval` 独立返回
  `permissions` 和 `scope`，拒绝返回空权限，允许只返回请求中包含的权限，允许全部使用
  `session` 作用域。审批超时或 run 清理仍按拒绝处理。
- 文件审批按 `itemId` 关联先前 `item/started` 的 `changes`，通过 `Edit.toolInput.changes`
  向客户端传递多文件路径、修改类型和 diff；缺少前置事件时保留 reason / grantRoot 供查看。
- 权限档案里 Codex 正把 `fileSystem.read/write` 迁到 `entries`，其中路径可能是
  `special`（`root` / `project_roots` / `tmpdir` …）而不是字符串。审批卡片把它展开成路径
  列表时会给这些项可读标签——**显示少于授权**是危险方向，取不到字符串就丢项是不允许的。
- 「允许全部」对 `Permissions` 的语义是「本会话后续**任意**权限扩展都自动批准」（不限于卡片
  上列的路径），撤销只影响后续请求；客户端文案必须写明这一点。
- **stderr 会转发给客户端**：app-server 把自身的运行故障（网络不可达、额度用尽、沙箱起不来、
  鉴权失效）只写在 stderr 上，wire 上没有任何对应通知。`rpc.ts` 按**整行**交付 stderr，
  `session.ts` 只挑含 `ERROR`/`WARN` 的行，去重、截断到 300 字、上限 20 条，以**非终态**
  `error`（`code: 'codex_stderr'`）事件推给客户端。没有这一步，「跑不动」在手机上就是
  一个永远转圈、不给任何原因的「运行中」。完整的 stderr 仍然逐字进 daemon 日志。

尚未覆盖的 app-server 交互：`item/tool/call`（客户端侧工具）、`account/chatgptAuthTokens/refresh`、
`openai/form` 与 `openai/userVerification` 两种 elicitation 模式（按不支持处理，客户端只能拒绝）、
`turn/steer`（中途追加指令）、工具输出的流式增量、以及工作区附加目录（`--add-dir` 的等价物）。
不能视为与 Claude 功能完全一致。协议以本机 `codex app-server generate-json-schema` 为准，
实现里用到的字段都对着它核过。

## 2. 总体架构

```
远程客户端 (Android / iOS / web)
   │  HTTP(S) + Bearer token
   │  POST /api/chat              (发指令，返回 SSE)
   │  GET  /api/runs/:id/stream   (重连：回放 + 续直播)
   ▼
┌────────────────────────────────────────────────────────┐
│ daemon (Node 24 + Express + node:sqlite，零原生依赖)      │
│  ├─ auth.ts           Bearer token（常量时间比较）        │
│  ├─ db.ts             SQLite（会话/消息/运行/事件/审计）   │
│  ├─ permissions.ts    工具审批 + auto-allow + 超时拒绝    │
│  ├─ run-notifier.ts   runId → 多个 SSE 订阅者（fan-out）  │
│  └─ runtimes/engine.ts  spawn agent → 归一化事件 → SSE    │
└────────────────────────────────────────────────────────┘
   │ spawn(cli, [...], { cwd: session.cwd })
   ▼
本机编码 agent（Claude Code 无头 -p，stdin stream-json）
```

数据流：

```
用户发消息 → POST /api/chat
  → 解析会话 → 创建 run → spawn agent（cwd = workspace）
  → agent stdout → createParser → NormalizedEvent → 持久化(events) + SSE 推送 + fan-out
  → agent exit → 发 terminal status → res.end()
```

## 3. 运行时抽象（多 agent 扩展点）

`src/runtimes/types.ts` 定义 `RuntimeAdapter` 接口——接入新 agent（Codex、OpenCode、
DeepSeek Harness…）只实现它并在 `registry.ts` 注册一行，路由/引擎/持久化/传输全部不动：

| 成员 | 职责 |
|---|---|
| `id` / `name` / `bin` | 标识与可执行文件名 |
| `detect(env)` | 探测版本 / 认证 / 能力 flag（从 `--help` 解析） |
| `buildArgs(ctx)` | 拼 spawn 命令行（各 CLI argv 不同） |
| `createParser(onEvent)` | 把运行时私有 stdout 翻译成 `NormalizedEvent` |
| `encodeUserMessage(text)` | 把 prompt 编码成该运行时的 stdin 格式 |
| `keepStdinOpen` | stream-json 类运行时保持 stdin 打开以支持中途追加 |

通用 `engine.ts` 只负责运行时无关的部分：spawn、stdio 接线、`turn_end` 时关 stdin、
SIGTERM→SIGKILL 取消、退出码分类、**空闲看门狗**。当前唯一实现是 `runtimes/claude/`
（detect / stream / adapter）。

**事件流是 runtime 无关的**：客户端只认 `NormalizedEvent`，不感知底层是哪个 CLI。

## 4. 核心模块

| 文件 | 职责 |
|---|---|
| `index.ts` | 入口：加载 .env → 解析 CLI → loadConfig → 建 Db/registry → 探测 claude → startServer |
| `cli.ts` | `util.parseArgs` 解析 `airemote` 命令行，`--help`/`--version` |
| `config.ts` | env + CLI 合并（CLI 优先）；token 持久化；workspace/超时等默认值 |
| `server.ts` | Express 组合根：CORS → JSON → 鉴权门 → 各路由注册 |
| `routes/*.ts` | HTTP/SSE 边界（health/agent/chat/claude-sessions/runs/sessions/permissions） |
| `runtimes/engine.ts` | 通用 spawn 生命周期（见 §3） |
| `permissions.ts` | `PermissionManager`：pending 请求 + 超时默认拒绝 + 同 Session allow-all 结算 |
| `run-notifier.ts` | `RunNotifier`：runId → 该 run 的多个 SSE 订阅者（emitter + listeners） |
| `session-runs.ts` | 「在跑的 run 按 session 索引」的查询：会话列表的运行标记、`/api/chat` 的同会话守卫 |
| `runtime-info.ts` | 写/刷/删 `<data-dir>/daemon_runtime.json`（见 §14） |
| `command-safety.ts` | Bash 只读命令白名单（只读自动放行，其余询问） |
| `tool-grants.ts` | grant key 映射：MCP 工具 → server 级 `mcp__<server>__*`，其余用 toolName |
| `workspace.ts` / `workspace-service.ts` | Workspace 路径校验/包含关系；目录选择器复用 |
| `permission-hook.ts` | PreToolUse hook 脚本（被 claude 调用，转发审批到 daemon） |
| `claude-sessions.ts` | 枚举 `~/.claude/projects/` 下的 Claude 会话 |
| `sse.ts` | SSE 帧写入 + `isTerminalEvent` 判断（chat 与 stream 复用） |
| `db.ts` | `node:sqlite` 持久化（内置，零原生依赖） |
| `types/api.ts` | 传输契约：`NormalizedEvent` 事件 union + DTO |

## 5. 数据模型（SQLite）

数据根默认 `~/.airemote`（`--data-dir` 可改），下面有 `airemote.sqlite`、`token`
与 `daemon_runtime.json`（运行信息，见 §14）。

| 表 | 内容 |
|---|---|
| `sessions` | 会话：`id`、`runtime`、`claude_session_id`、`workspace_id`、`permission_mode`、`cwd`、`title`、时间戳 |
| `workspaces` | 工作区**主目录**：`id`、`name`、`path`、`is_default`、`enabled`、时间戳 |
| `workspace_dirs` | 工作区**附加目录**（agent 可读写、该工作区所有会话继承）：`workspace_id`、`path`、`created_at`；随工作区级联删除 |
| `workspace_shortcut_dirs` | 文件 Tab 的**浏览书签**（`workspace_id`、`path`、`created_at`；随工作区级联删除）。与 `workspace_dirs` 刻意分开：书签**不授予 agent 任何权限**，只多一个可切换的 tab |
| `session_permission_grants` | Session 级「允许全部」授权：`session_id`、`tool_name`（普通工具名，或 MCP 的 `mcp__<server>__*`）、`created_at` |
| `settings` | 运行期可变配置：`key`、`value`、`updated_at` |
| `messages` | 对话转录：user prompt + assistant 聚合后的可见文本 |
| `runs` | 一次 spawn：`status`（running/succeeded/failed/cancelled）、`exit_code`、`error` |
| `events` | 归一化事件流，键 `(run_id, seq)`，用于断线重连回放 |
| `audit_log` | chat / cancel / permission_decision / rename_session / delete_session 审计 |

> `messages` 只是「聚合可见文本」；**工具卡、思考块、用量这些细节只存在 `events`**，
> 客户端重进会话时需用 `GET /api/runs/:id/events` 回放（Android 已实现）。

## 6. HTTP / SSE 协议

| 方法 & 路径 | 鉴权 | 说明 |
|---|---|---|
| `GET /api/health` | 否 | 存活 + 版本 + 默认 workspace 根目录 |
| `GET /api/config` | 是 | 全局默认权限模式、默认 Workspace、只读/重启级配置 |
| `PATCH /api/config` | 是 | 更新 `defaultPermissionMode` / `defaultWorkspaceId` |
| `GET /api/workspaces` | 是 | 工作区列表（含 sessionCount 与附加目录 `dirs`） |
| `POST /api/workspaces` | 是 | 新增工作区，`{name?, path}`，校验目录存在且路径未重复（允许嵌套，如根目录工作区下再建子目录工作区） |
| `PATCH /api/workspaces/:id` | 是 | 重命名 / 启停 / 设为默认 |
| `DELETE /api/workspaces/:id` | 是 | 删除工作区（只删注册信息，磁盘不动；级联清附加目录与书签）。`?cascade=1` 时连它的 Session 一起删，否则有 Session 就 409 `workspace_not_empty`；默认工作区一律 409 `workspace_is_default` |
| `POST /api/workspaces/:id/dirs` | 是 | 添加附加目录，`{path}`；校验存在 + 是目录，拒绝「等于主目录」/「已存在」 |
| `DELETE /api/workspaces/:id/dirs` | 是 | 移除附加目录，`{path}`（body；Retrofit 用 `@HTTP` 绕过 `@DELETE` 无 body 的限制） |
| `POST /api/workspaces/:id/shortcuts` | 是 | 加一个浏览书签，`{path}`；去重（主目录/附加目录/已有书签都算重复 → 409 `shortcut_exists`） |
| `DELETE /api/workspaces/:id/shortcuts` | 是 | 移除浏览书签，`{path}`；只删书签，不动任何授权 |
| `GET /api/fs/directories` | 是 | 目录选择器，`?path=<abs>&showHidden=`；**只列目录名，不读文件内容**，无工作区限制 |
| `GET /api/changes` | 是 | 某目录的 Git 未提交改动，`?workspaceId=&root=<abs>` |
| `GET /api/changes/diff` | 是 | 单文件 diff，`?workspaceId=&root=<abs>&path=<relative>` |
| `GET /api/files` | 是 | 单层目录懒加载，`?workspaceId=&root=<abs>&path=&cursor=&limit=&showHidden=&showIgnored=` |
| `GET /api/files/content` | 是 | 读取文本文件，`?workspaceId=&root=<abs>&path=<relative>`；有大小限制与二进制检测 |
| `GET /api/files/raw` | 是 | 原始字节流（图片/视频预览），同参数；不截断、不解码，支持 `Range`（206/416），按扩展名给 `Content-Type` |
| `GET /api/agent` | 是 | 探测 Claude Code（版本/认证/能力/models） |
| `GET /api/agents` | 是 | 已注册运行时列表 `{agents:[{id,name,bin}]}` |
| `POST /api/deploy` | 是 | 提交部署任务，`{channel:'test'|'prod', target?:'auto'|'daemon'|'android'|'all'}` |
| `GET /api/deploy` | 是 | 最近部署任务列表 |
| `GET /api/deploy/:id` | 是 | 查询部署任务状态 |
| `GET /api/claude-sessions` | 是 | 列出指定 Workspace 内的 Claude 会话（`?workspaceId=`） |
| `POST /api/chat` | 是 | 发指令，返回 SSE 流。该 Session 已有在跑的 run → 409 `session_busy`（见 §6） |
| `GET /api/runs` | 是 | 当前运行中的 run 列表（`?workspaceId=` 过滤） |
| `POST /api/runs/:id/cancel` | 是 | 取消运行 |
| `GET /api/runs/:id/events` | 是 | 一次性回放 run 事件，`?after=<seq>` 续传游标 |
| `GET /api/runs/:id/stream` | 是 | SSE：先回放 `?after=<seq>` 后的事件，再续传直播（重连订阅） |
| `GET /api/sessions` | 是 | 会话列表（`?workspaceId=` 过滤；含 `running`/`runningRunId`） |
| `GET /api/sessions/:id` | 是 | 会话 + 消息 + 运行 |
| `PATCH /api/sessions/:id` | 是 | 重命名（body `{title}`） |
| `DELETE /api/sessions/:id` | 是 | 删除会话（先取消进行中的 run，级联删） |
| `GET /api/sessions/:id/permissions` | 是 | Session 权限模式 + 已授权工具 |
| `PATCH /api/sessions/:id/permissions` | 是 | 修改 Session 权限模式（仅对后续 Run 生效） |
| `DELETE /api/sessions/:id/permissions/grants/:toolName` | 是 | 撤销单个工具授权 |
| `DELETE /api/sessions/:id/permissions/grants` | 是 | 撤销全部工具授权 |
| `POST /api/permissions/:id/decision` | 是 | 工具审批决定（allow/deny/allow_all） |
| `POST /api/internal/permissions/create` | 是 | 内部：hook 注册审批请求 |
| `GET /api/internal/permissions/:id/status` | 是 | 内部：hook 轮询决定 |

会话 DTO 含 `workspaceId`、`permissionMode`；Run DTO 含 `workspaceId`（见 `types/api.ts`）。

**改动文件（Files Tab）**：`/api/changes` 基于 `git status --porcelain=v1 -z -- .` 返回指定目录
的未提交改动；`/api/changes/diff` 使用 `git diff --no-ext-diff --no-textconv` 返回单文件 patch。
详见 `docs/ui/pages/files.md`。

**媒体预览（`/api/files/raw`）**：与 `/api/files/content` 同参数、同越界校验（`root` + 相对
`path`），但**不截断、不做 UTF-8 解码**，直接把文件字节流出去——这是图片 / 视频能就地预览的前提。
按扩展名回 `Content-Type`（`image/*`、`video/*`，其余 `application/octet-stream`），并支持
`Range: bytes=…`（开放/后缀形式均可）以 `206` + `Content-Range` 分段返回，越界范围回 `416`——
视频拖进度、大图分块加载都靠它。鉴权仍是 `/api/*` 的 Bearer header，token 不进 URL，
客户端（Coil / ExoPlayer）通过 `AiremoteClient.authedHttpClient` 复用同一份鉴权 OkHttp 客户端。

**非仓库目录返回 `repos`**：`git rev-parse --show-toplevel` 只向上找仓库，所以根目录自身不在任何
仓库时（如 `~/OpenProject` 下面平铺着一堆仓库），根目录看改动会直接 `isGitRepo=false`。
此时额外返回 `repos`：**只看直接子目录一层**（跳过 `.git`/`node_modules` 等 `IGNORED_DIRS`，
命中仓库不再下钻），返回的是相对当前根的路径，空数组表示该目录下没有仓库。
客户端点其中一个仓库 = **把根换成那个仓库**（见下面的「浏览根」）。

> 曾经另有一个 `dir` 参数，用来「在根之内缩窄到某个子目录」。它已被移除：`root` 完全覆盖了它
> 的表达能力——原来「根=`~/OpenProject` + `dir=repoA`」等价于「根=`~/OpenProject/repoA`」——
> 而两个并列的「选目录」入口在实践中只会造成混淆。`resolveScopeDir` 保留，但只做
> 「存在 + 是目录」校验（不存在/非目录报 `directory_not_found` / `not_a_directory`）。

**浏览根（`root`）**：`/api/files`、`/api/files/content`、`/api/changes`、`/api/changes/diff`
都接受可选的 `root=<绝对路径>`，**缺省 = 工作区主目录**（此时行为与加该参数前完全一致）。
给了 `root` 时只校验「存在 + 是目录」，**不要求落在工作区内**——文件 Tab 因此能浏览工作区之外
的目录。这条能力是显式决定的（见 §9「workspace 不是沙箱」），非默认 `root` 会写 `browse_root`
审计日志。响应回显 `root`，客户端据此把相对路径配对到正确的根。

`/api/files` 与 `/api/changes` 还回显 `roots`（该工作区的全部根，主目录在前）与
`shortcutDirs`（浏览书签）——客户端渲染的 tab 行因此能**跟随每次列表加载自动同步**，不必
自己轮询工作区状态（否则在工作区管理页或审批流里新增的目录，要等切一次工作区才会出现）。

**注意 `roots` 与 `shortcutDirs` 的语义差别**：`roots` 里的目录对 agent 是可达的（主目录是
spawn cwd，附加目录走 `--add-dir`），而 `shortcutDirs` **只是文件 Tab 的书签**，不进
`--add-dir`、不参与任何可达性判定。两者分开存就是为了让
「我想在这儿留个入口」和「我允许 agent 碰这里」不会互相污染。

**不变式：同一个目录不会同时是根和书签**（否则 tab 行上会重复出现两次）。两个方向都要管：

- 给已有的根（主目录/附加目录/已有书签）加书签 → 路由 **409 `shortcut_exists`**；
- 把已有书签的目录改成授权目录 → `Db.addWorkspaceDir` **顺手删掉该书签**（放在数据层）。
  另外启动时有一次幂等清理，抹掉早期版本留下的重复行。

**附加目录（`workspace_dirs`）与 `--add-dir`**：一个工作区 = 主目录 + N 个附加目录。
附加目录现在只有**一个来源**：手机在「工作区管理」里手动增删（早期版本还会在批准越界读取时
自动写入，该机制已随读取门控一并移除，见 §7.1）。

spawn 时把「主目录 + 附加目录」中**除 cwd 之外**的部分逐个传给 `claude --add-dir`。
`--add-dir <directories...>` 是**变长参数**，因此它必须排在 argv 最末；prompt 走 stdin
（`--input-format stream-json`），不是位置参数，这一点不能改。
存储在磁盘上已消失的目录在 spawn 时被过滤掉（`existingDirs`），但**不**从配置里静默删除。

Codex 在每次 `turn/start` 时将 cwd 和有效附加目录传入
`sandboxPolicy: { type: "workspaceWrite", writableRoots: [...] }`，保持网络默认受限；
新会话和续接会话都重新读取目录授权，因此增删授权在下一轮生效。书签不授予写权限。
`bypass` 使用 `dangerFullAccess`，不受这些目录限制。

Codex 的 `usage` 统一为当前 Run 的累计 `input_tokens` / `output_tokens`，包含工具调用前后
的各次模型请求，不包含此前轮次。优先使用续接时的线程累计快照作为基线；服务端未发送
快照时，首个用量更新的 `total - last` 用于恢复基线。重复快照不重复计数，结束事件保留合计。

**上下文窗口占用**与 `usage` 是两回事，走 `usage` 事件上的两个**兄弟字段**（不在 `usage` 里）：

- `contextTokens`：当前装了多少（「下一次请求会重发多少输入」），客户端顶栏显示的就是它；
- `contextWindow`：窗口总容量，用来算百分比。**`null` = 占用已知、容量未知**（Claude Code
  从不报窗口，猜一个会给出错误的百分比）；**字段缺省 = 本帧不含此信息**，客户端保留上一个
  已知值。这两个语义必须区分，不要用 0 兜底。

放进 `usage` blob 是不行的：blob 是「单调累计的跑量」（codex 按轮累加、claude 取 result 帧），
而上下文占用是**瞬时值、压缩后会回落**，混进去会污染消息末尾的用量条。

| runtime | 占用算法 | 窗口 |
|---|---|---|
| codex | `thread/tokenUsage/updated` 的 `last.inputTokens`（已是最近一次请求的完整输入，含缓存部分） | `modelContextWindow` |
| claude | 最近一条主回合 assistant 消息的 `input_tokens + cache_read_input_tokens + cache_creation_input_tokens`（三段互不相交，故相加） | 无 |

claude 每来一条主回合 assistant 帧就发一次（值相同则跳过；子代理帧有自己的窗口，不参与），
`result` 帧带上最后一次的值以便历史重建确定性收尾。这些帧 `usage: null`，所以客户端不会
把它们当成本轮累计用量。


SSE 每帧 `{ runId, seq, event }`，`seq` 单调递增（重连游标）。`event` 是 `NormalizedEvent`：

```
status | text_delta | thinking_delta | thinking_start | tool_use
tool_result | usage | turn_end | error | permission_request | question
```

**流的不变量**：终局 `status` 之前，每个 `tool_use` 都有配对的 `tool_result`。run 可能在工具还没返回时
就结束（取消 / 崩溃 / 空闲看门狗），此时引擎补发一帧合成结果（`interrupted: true`、`isError: true`，
content 说明未返回结果）。客户端因此可以把「没有配对的 tool_use」一律当成"仍在运行"，不必各自兜底；
历史回放读到的也是同一份自洽的流。

`POST /api/chat` 请求体：`{ prompt, sessionId?, workspaceId?, claudeSessionId?, model?, runtime?, permissionMode? }`。

- 新会话使用 `workspaceId`（缺省用默认 Workspace）作为 cwd；`permissionMode` 可选 `ask` / `acceptEdits` / `bypass`，缺省用 `default_permission_mode`。
- 续接已有会话沿用 Session 自己的 `workspace_id + cwd`，并**每次续接都重新校验**是否仍在该 Workspace 内，否则 400 `cwd_not_allowed`。
- `permissionMode` 是 Session 级配置，切换后只影响后续 Run。
- **一个 Session 同时只跑一个 Run**：该 Session 已有在跑的 run 时，请求返回 409
  `session_busy`（响应带 `runId`，即正在跑的那个）。否则第二个 run 会 `--resume` 同一个
  native session id，两个 agent 进程写同一份会话记录。客户端在 run 期间禁用发送只是礼貌，
  多端（桌面 + 手机）可能同时发出，所以由 daemon 兜底。此检查排在 runtime 探测之前，
  避免为必然被拒的请求去 spawn CLI。

**断线续接**：`/api/chat` 客户端断开后，run **继续在 daemon 上运行**（不因断线取消）；重连
用 `GET /api/runs` 找运行中的 run，再 `GET /api/runs/:id/stream?after=<seq>` 回放 + 续直播；
显式停止用 `POST /api/runs/:id/cancel`。**不同 Session 之间**多 run 并发无全局锁
（同一 Session 见上面的 409 守卫）。

## 7. 权限审批

每个 Session 有自己的权限模式：

| 模式 | Write/Edit | Bash 只读 | Bash 有副作用 |
|---|---|---|---|
| `ask` | 询问 | 自动 | 询问 |
| `acceptEdits` | 自动 | 自动 | 询问 |
| `bypass` | 自动 | 自动 | 自动，不注入 hook |

模式到 Claude Code `--permission-mode` 的映射：

```text
ask          -> default
acceptEdits  -> acceptEdits
bypass       -> bypassPermissions
```

`Read` / `Grep` **不纳入门禁**：读工作区外的文件不审批（见 §7.1）。`ask` 模式下 PreToolUse
hook 的 matcher 为 `Bash|Write|Edit|MultiEdit|NotebookEdit|mcp__.*`；`acceptEdits` 匹配
`Bash|mcp__.*`；`bypass` 不注入 hook。

`Glob` **不纳入**：它只返回文件名、不返回内容，却是探索阶段最频繁的调用，
每加一个受门禁的工具就多一次 hook 进程 spawn。

### 7.1 读取不门控

`Read` / `Grep` 不纳入 PreToolUse matcher；在 `ask` / `acceptEdits` 下，engine 同时在
传给 Claude Code 的 `--settings` 中注入 `permissions.allow: ["Read", "Grep"]`。
仅移除 hook 不会解除 CLI 自身的目录检查，显式允许规则才使工作区外的读取默认无需审批。
因此读工作区外的文件——包括 agent 自己的 `~/.claude/...` 记忆文件——默认**不审批、
不弹框，也不往工作区加任何目录**。用户或管理员显式配置的 `deny` / `ask` 规则仍优先，
其他 CLI / OS 限制也不被这份允许规则覆盖。配置在下一次 spawn 时生效。

> 安全提示：这意味着 agent 可无审批读取本机任意文件（含密钥、配置）。远程驱动一个带 shell
> 的 agent 时，这是刻意的取舍——之前那套「越界读取→审批→目录授权」的机制已整体移除
> （`permission-paths.ts`、hook 的离线放行、`AIREMOTE_ALLOWED_DIRS` 均已删除）。

**MCP 工具**（`mcp__<server>__<tool>`）与 Bash/Write/Edit 是平级的顶层工具，不属于其中任何
一类，但**在 `ask` 和 `acceptEdits` 下都纳入远程审批**：MCP 工具能调用外部服务、产生任意副
作用，不属于「编辑类」语义，静默放行会留下绕过审批的口子。只有 `bypass` 模式不审批。

```
claude 要执行工具 → hook(permission-hook.js) → POST /api/internal/permissions/create
  → daemon 广播 permission_request（SSE）→ 客户端决定 → POST .../decision
  → hook 轮询 status → permissionDecision allow/deny → claude 放行/阻止
```

审批策略（`routes/permissions.ts` + `command-safety.ts`）：

1. **Session grant**（用户点过「允许全部 Bash」）→ 直接放行，不再广播；
2. **只读 Bash 白名单**（`ls`/`cat`/`grep`/`node --version`/`git status`…，且不含 shell
   元字符 `| > & ; $()` 等）→ 自动放行；
3. 其余（修改/写入/删除/未知）→ 广播给客户端，弹「允许 / 拒绝 / 允许全部」；
4. **超时或断线 → 默认拒绝**（deny-by-default）。

规则：

- `allow_all` 写入 `session_permission_grants`，作用域为 **当前 Session + 同一个 grant key**：
  普通工具是 toolName 本身（`Bash`、`Write`…），**MCP 工具是 server 级通配**
  `mcp__<server>__*`——一个 server 往往暴露几十个工具，逐个批准没法用；key 映射见
  `tool-grants.ts`（`toGrantKey` / `grantKeyCandidates`）；
- 查 grant 时 MCP 工具会同时匹配 server 级通配与早期写入的精确名字，旧 grant 继续有效；
- 「允许全部」会把当前已 pending 的、同一 grant key 覆盖的请求一并放行，并广播最终状态；
- grant 持久化到 SQLite，daemon 重启/App 重连后仍有效；
- 用户可在 Session 权限设置中撤销单条或全部；
- 切换权限模式不会清空已有 grant；切到 `bypass` 时 grant 暂时不生效，切回后继续生效；
- 权限模式只影响后续 Run，当前 Run 已经在使用的 hook/模式不会热切换。

- **超时三层对齐**：审批决策窗口由 `AIREMOTE_PERMISSION_TIMEOUT_SECONDS` 统一控制（默认 120 秒），
  daemon 定时器、hook 轮询兜底、Claude hook 的 `timeout` 都从它推导。
- **审批解决广播**：被审批的请求如果已经广播给客户端，之后无论用户决策、`allow-all`、超时还是
  run 清理导致状态变化，daemon 都会向该 run 的 SSE 推送一条同 `permissionId` 的状态帧。

审批状态会**写回 `events` 表**：`permission_request` 事件携带 `status` 字段（`pending` /
`allowed` / `denied` / `timed_out`）。

## 8. 会话与跨端续接

- Claude Code 的所有会话（TUI 与 headless）统一存 `~/.claude/projects/<cwd>/<id>.jsonl`，
  会话 id 就是文件名；`claude --resume <id>` 续接。
- daemon 自己生成 `--session-id`，续接用 `--resume`，并把 `claude_session_id` 存进
  `sessions` 表。
- `GET /api/claude-sessions` 枚举**workspace 内**的会话，客户端可「导入」一个 TUI 会话继续
  （`POST /api/chat` 带 `claudeSessionId`）。
- 双向互通：手机会话 → 电脑 `claude --resume <id>`；电脑 TUI 会话 → 手机导入继续。

## 9. 安全模型

- **认证**：非 health 的 `/api/*` 全要 Bearer token（`auth.ts` 常量时间比较）。
- **传输**：默认 HTTP 绑 `0.0.0.0`（局域网）；跨网络访问建议用组网工具放进加密内网（见 README
  「手机电脑不在同一个局域网」），内网穿透只适合自用。
- **权限**：Session 级 `ask` / `acceptEdits` / `bypass`；审批 + 只读白名单 + 默认拒绝（见 §7）。
- **工作目录**：`--workspace` 只在**工作区表为空时**注册一次（首次安装），之后不再自动重建——
  用户删掉的工作区不会在重启后自己回来，目录不存在也不会让启动抛错；缺工作区时客户端可随时新增。
  每个 Session 绑定 `workspace_id + cwd`，续接和导入都要校验 cwd 位于该 Session 所属 Workspace 内，
  否则 400 `cwd_not_allowed`。**注意**：这条校验针对工作区**主目录**（新 Session 的 cwd 就取自主目录）；
  工作区还可能有附加目录（§6），它们不参与 cwd 校验，只扩大 agent 的可达范围。
- **删除工作区**：默认只删注册信息（行 + 附加目录 + 浏览书签），磁盘上的目录和文件一律不动；
  有 Session 时 409 `workspace_not_empty`，`?cascade=1` 才会连 Session（含聊天记录、run、事件、
  会话级授权）一起删。级联会先取消这些 Session 在跑的 run。**级联不删**
  `~/.claude/projects/<cwd>/<id>.jsonl`——那是 Claude Code 自己的会话记录，删了会破坏电脑上的续接，
  所以同一目录重新加成工作区后，`GET /api/claude-sessions` 仍会列出它们。
- **默认工作区不可删**（409 `workspace_is_default`）：它是客户端没指定工作区时的落点，删了新会话
  无处落地。要删就先把另一个设为默认。这条顺带保证了「永远删不掉最后一个工作区」——最后一个必然
  是默认的那个，所以「零工作区」这个状态不可达，`resolveWorkspaceForRequest` 不会拿到 null。
- **Workspace 嵌套**：允许父子包含关系（典型场景：daemon `--workspace` 指向根目录，之后把其下的
  子目录注册成独立 Workspace）。Session 的归属靠自身 `workspace_id`，不从路径前缀推导；前缀只用于
  校验 cwd 位于所属 Workspace 内，因此嵌套不影响归属正确性。唯一的语义放宽：查询外层 Workspace 的
  TUI 会话列表时会包含落在内层 Workspace 里的会话（外层视为「其下所有内容」）。
- **防御性超时**：run 空闲看门狗（`AIREMOTE_RUN_IDLE_TIMEOUT_SECONDS`，默认 900 秒、0=禁用）。
- **审计**：chat / cancel / permission_decision / rename_session / delete_session 记入 `audit_log`。
- **CORS**：`Access-Control-Allow-Origin: *` 仅为让网页客户端可用；真正边界是 token。

### ⚠️ 重要边界：`workspace` 不是沙箱

`workspace` 只决定 **Claude 进程从哪个目录启动（spawn cwd）**，**不是文件系统沙箱**。
Claude 启动后，访问别的目录由「工具权限」决定，与 workspace 无关。
下表为**实测**行为（2026-09，Claude Code 当前版本）：

| 工具 | 行为 |
|---|---|
| Read / Grep | **不受 AIRemote 门禁**（matcher 不含它们），读任意路径都放行（§7.1） |
| Glob | 不受 AIRemote 门禁（matcher 不含它），只返回文件名 |
| Write / Edit / MultiEdit | `ask` 下弹审批；`acceptEdits` / `bypass` 下**直接放行**，可写任意路径 |
| Bash 只读（`cat`/`ls`/`grep`…） | 自动放行，**完全不看路径**——`cat <工作区外文件>` 一样放行 |
| Bash 有副作用 | 弹审批（审批的是命令本身，不专门按目录拦截） |

**因此读取从来不构成边界**：`Read` 与 `cat` 都放行，终点一致——AIRemote 对读取不做任何拦截。
曾评估给只读 Bash 白名单补路径校验以堵住这条，**已决定不做**——读命令放行的影响可接受，
而 agent 本就有 Bash 权限，堵住 `cat` 收益有限。（`env` 泄露 `AIREMOTE_TOKEN` 同理：
读命令本来就放行，摘掉 `env` 只是装饰。**但注意这不是「同一个值」**——见 §10.4，
env 命中时 `<data-dir>/token` 里可能是旧值。）

要做到文件级物理隔离，需要 OS 层沙箱（bwrap / firejail / 容器 / sandbox-exec），纯靠 Claude
Code CLI 做不到。`workspace` 的准确语义是「**从哪个目录启动**」，不是「只能碰哪些目录」。

### 授权的作用域

只剩一个「允许」：工具授权（「允许全部」），作用域 **Session + toolName**（MCP 为 server 级
通配），落点 `session_permission_grants`，在 Session 权限页撤销。

早期还有一类「目录授权」——批准越界读取后把目录记到工作区；随读取门控一并移除了。
现在 `workspace_dirs` 只是「手动添加的附加目录」（见 §6），不来自任何审批。

## 10. 配置参数参考

优先级：**命令行 flag > 已存在的 shell 环境变量 > `.env` 文件 > 默认值**。
所有变量都可用 `--env-file <path>` 指定 `.env` 加载（默认 `./.env`）。

### 10.1 命令行 flag

| flag | 默认 | 说明 |
|---|---|---|
| `--host <host>` | `0.0.0.0` | 监听地址；`127.0.0.1` = 仅本机 |
| `--port <port>` | `4780` | 端口（1–65535） |
| `--workspace <path>` | 当前目录 | 初始 Workspace 根目录（首次启动注册为默认 Workspace） |
| `--data-dir <path>` | `~/.airemote` | 数据根（SQLite + token + `daemon_runtime.json`） |
| `--token <token>` | 自动生成并持久化 | Bearer 鉴权密钥 |
| `--permission-mode <mode>` | `default` | 初始 Workspace 默认权限模式 seed（见下） |
| `--env-file <path>` | `./.env` | `.env` 文件路径 |
| `-h, --help` | — | 帮助 |
| `-v, --version` | — | 版本 |

### 10.2 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `AIREMOTE_HOST` | `0.0.0.0` | 监听地址 |
| `AIREMOTE_PORT` | `4780` | 端口 |
| `AIREMOTE_WORKSPACE` | 当前目录 | 初始 Workspace 根目录（首次启动注册为默认 Workspace） |
| `AIREMOTE_DATA_DIR` | `~/.airemote` | 数据根 |
| `AIREMOTE_TOKEN` | 自动生成 | 鉴权密钥 |
| `AIREMOTE_PERMISSION_MODE` | `default` | 新 Session 默认权限模式的 seed；`default` 对应产品模式 `ask` |
| `AIREMOTE_PERMISSION_TIMEOUT_SECONDS` | `120` | 工具审批决策窗口（秒，超时自动拒绝；`0`/非法值回退 `120`） |
| `AIREMOTE_RUN_IDLE_TIMEOUT_SECONDS` | `900` | 空闲看门狗（无事件多少秒自动取消；`0` = 禁用） |
| `AIREMOTE_TLS_CERT` | 无 | TLS 证书路径（与 KEY 同设才启用 TLS） |
| `AIREMOTE_TLS_KEY` | 无 | TLS 私钥路径 |
| `AIREMOTE_ENV_FILE` | `./.env` | `.env` 路径（等价 `--env-file`） |

### 10.3 权限模式

产品模式的来源：

```text
--permission-mode / AIREMOTE_PERMISSION_MODE (seed)
  -> settings.default_permission_mode
  -> 新 Session 的 permission_mode
  -> Claude Code --permission-mode
```

| 产品模式 | Claude 参数 | 含义 |
|---|---|---|
| `ask` | `default` | **默认**。Write/Edit/Bash 修改类操作询问；只读 Bash 自动放行 |
| `acceptEdits` | `acceptEdits` | 自动接受文件编辑；Bash 等命令仍询问 |
| `bypass` | `bypassPermissions` | 完全放开（⚠️ 危险，需用户在手机上明确切换） |


### 10.4 token 解析顺序

1. `--token` flag 或 `AIREMOTE_TOKEN` env → 直接使用；
2. 否则读 `<data-dir>/token`（存在则复用，保证重启不变）；
3. 否则生成 64 位 hex（32 字节随机），写入 `<data-dir>/token`（权限 0600）。

删掉 `<data-dir>/token` 即轮换 token。

⚠️ **文件只在「生成」那一步写入**，所以当第 1 条命中时，`<data-dir>/token` 里留着的是
**上一次生成时的旧值**，与当前生效的 token 不同。因此：**读 `<data-dir>/token` 来取 token 之前，
必须先确认当前 token 确实来自文件**——`daemon_runtime.json` 的 `tokenSource` 就是给这件事用的
（`file` 才可读该文件；`env` / `flag` 时不要读，会拿到旧的、必然 401）。

### 10.5 `.env` 示例

```ini
# 参考 airemote-daemon/.env.example
AIREMOTE_HOST=0.0.0.0
AIREMOTE_PORT=4780
AIREMOTE_WORKSPACE=/path/to/your/workspace
AIREMOTE_PERMISSION_MODE=default
AIREMOTE_RUN_IDLE_TIMEOUT_SECONDS=900
# AIREMOTE_TOKEN=
# AIREMOTE_TLS_CERT=/path/cert.pem
# AIREMOTE_TLS_KEY=/path/key.pem
```

## 11. 目录结构

```
airemote-daemon/
├─ src/
│  ├─ index.ts           入口（.env → CLI → config → server）
│  ├─ server.ts          Express 组合
│  ├─ config.ts / cli.ts / log.ts / network.ts / cors.ts / auth.ts / sse.ts / workspace.ts
│  ├─ db.ts              SQLite 持久化
│  ├─ permissions.ts     权限注册表
│  ├─ run-notifier.ts    runId → 多 SSE 订阅者
│  ├─ command-safety.ts  只读命令白名单
│  ├─ tool-grants.ts     grant key 映射（MCP → server 级通配）
│  ├─ permission-hook.ts PreToolUse hook 脚本（→ dist/permission-hook.js）
│  ├─ claude-sessions.ts 枚举本机 Claude 会话
│  ├─ session-title.ts   从首条 prompt 生成会话标题
│  ├─ types/api.ts       传输契约
│  ├─ routes/            HTTP/SSE 边界
│  └─ runtimes/          运行时抽象 + claude 适配器 + 通用 engine
├─ client/index.html     极简测试网页客户端（零依赖）
├─ tests/                Vitest 单测（stream / permissions / command-safety / workspace / session-title）
├─ docs/daemon.md        本文件
├─ .env.example          配置模板
├─ package.json / tsconfig.json / vitest.config.ts
└─ dist/                 构建产物（`airemote` bin 指向 dist/index.js）
```

## 12. 构建 / 运行 / 分发

```bash
cd airemote-daemon
pnpm install
pnpm build          # tsc + chmod → dist/index.js（含 permission-hook.js）
pnpm typecheck
pnpm test

# 全局命令（一次性）
ln -sf "$PWD/dist/index.js" ~/.local/bin/airemote
airemote --help
```

**公开分发（npm 公共仓）**。包内容由 `package.json` 的 `files` 白名单决定（`dist` / `docs`
/ `client` / `.env.example`，外加 npm 固定携带的 `README.md`、`LICENSE`、`package.json`），
所以测试客户端与本文档都会随包发布。

版本号取自 `airemote-daemon/package.json` 的 `version`（semver，手改递增：修 bug 加 patch，
加功能加 minor，大改加 major）。`airemote --version`、`/api/health` 和 npm 包版本都读取同一来源。
**发布动作由作者手动完成**：`prepack` 钩子会自动 build，registry 与 access 由 `package.json`
的 `publishConfig` 固定；已发布的版本不可覆写，改完发新版本。

用户侧（需 Node `~24` + 本机已装并登录 Claude Code）：

```bash
npx @noresign/airemote --help             # 免安装试用
npm install -g @noresign/airemote         # 装成全局命令（命令名仍是 airemote）
airemote --workspace /path/to/project --token <strong-token>
```

`.env` 配置（运行目录下自动加载）：

```bash
cp .env.example .env && 编辑 .env
airemote
```

## 13. 已知限制 / 后续

- 只读 Bash 白名单是内置默认，可考虑做成配置文件（`.airemote/policy.json`）可增减。
- 权限模式与「允许全部」均为 **Session 级**；切换 Workspace 或新建 Session 不会继承旧 Session 的 grant。
- 权限审批依赖 Claude Code 的 PreToolUse hook 格式，需随 CLI 版本演进同步探测/适配。
- **`workspace` 不是沙箱**（见 §9）——文件级隔离需 OS 层沙箱。
- 会话列表无「最后一条消息预览」字段（客户端暂用标题/时间/运行态）。
- Android 客户端已实现 M1；iOS 预留。传输契约（`types/api.ts`）已冻结，两端据此接入。

## 14. 运行信息文件（`daemon_runtime.json`）

**目的**：让同机的客户端（桌面端 / CLI / 部署脚本）不必猜端口就能找到本机 daemon。

写在 `<data-dir>/daemon_runtime.json`，在 server **开始接受连接之后**写入；退出
（`SIGTERM` / `SIGINT`）时删除。

```json
{
  "pid": 3087570,
  "startedAt": 1759930734000,
  "serverId": "8fa14743-5888-4f1f-9cfd-50d2fb934a84",
  "hostname": "ubuntu",
  "listen": "127.0.0.1:4780",
  "tls": false,
  "version": "1.7.0",
  "dataDir": "/home/renbin/.airemote",
  "tokenSource": "env"
}
```

| 字段 | 说明 |
|---|---|
| `pid` | daemon 进程号，用于存活判断 |
| `startedAt` | 本次启动时间（ms） |
| `serverId` | **该 daemon 的稳定身份**：首次启动生成、持久化在 `settings` 表，重启不变。用于「同一台机器换了地址（LAN / 隧道 / 以后的 relay）」时识别为同一个 host |
| `hostname` | 展示用 |
| `listen` | **可直接连接的** `host:port`。绑定 `0.0.0.0` / `::` 会翻译成 `127.0.0.1` / `[::1]`——原样给出是连不上的 |
| `tls` | 据此选 `http` / `https` |
| `version` | daemon 版本 |
| `dataDir` | 便于判断两个 daemon 是否共用同一个数据根 |
| `tokenSource` | 见 §10.4：**只有 `file` 时** `<data-dir>/token` 才是权威值 |

**约定**：

- **不含 token**（秘密只在 `<data-dir>/token`，0600），所以这个文件是 0644、可直接读。
- **文件存在 ⟹ 已在接受连接**：不存在「写了但还没 listen」的中间态，所以不需要
  `listen: null` 这种占位。
- **心跳就是 mtime**（没有 heartbeat 字段）：daemon 每 **30s** 刷新一次文件 mtime；
  读方以 **mtime 比现在老 90s 以上** 判为过期（崩溃 / 硬杀留下的残留文件）。
- ⚠️ **它是协调信息，不是权威**：被改过的地址、被复制的 `serverId`、被复用的 `pid`、
  地址抢占，都在它能保证的范围之外。所以存活判断要三条件同时成立——
  `mtime 新鲜` ∧ `pid 存活` ∧ `该地址的 /api/health 返回 airemote`。**鉴权仍然只认 token。**
- 数据根不可写时只 WARN、不影响启动；客户端的发现流程退化为试探候选地址。
