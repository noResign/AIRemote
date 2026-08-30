# airemote daemon 技术方案

> 面向维护者和后续接入客户端的开发者。代码在 `airemote-daemon/`。

## 1. 概述

`airemote-daemon` 是一个跑在 macOS/Linux 本机的守护进程：它 `spawn` 本机已安装的
编码 agent CLI（目前是 Claude Code，架构上预留多 agent），以无头方式执行，把输出
解析成统一的流式事件，通过 HTTP/SSE 推给远程客户端（Android/iOS/网页），并支持
会话续接、取消、工具审批。

核心定位：**「远程操纵本机 agent」**。因此安全边界（认证、权限审批、工作目录）是
一等公民，而不是事后补丁。

## 2. 总体架构

```
远程客户端 (Android / iOS / web)
   │  HTTPS + Bearer token
   │  POST /api/chat            (指令)
   │  SSE  /api/chat            (实时事件流)
   ▼
┌──────────────────────────────────────────────────────┐
│ daemon (Node 24 + Express + node:sqlite)              │
│  ├─ auth: Bearer token（常量时间比较）                 │
│  ├─ session store: SQLite（会话/消息/运行/事件/审计）  │
│  ├─ permission manager: 工具审批 + auto-allow + 只读白名单 │
│  └─ runtime engine: spawn agent → 归一化事件 → SSE     │
└──────────────────────────────────────────────────────┘
   │ spawn(cli, [...], { cwd: workspace })
   ▼
本机编码 agent（Claude Code 无头 -p）
```

## 3. 运行时抽象（多 agent 扩展点）

`src/runtimes/types.ts` 定义了 `RuntimeAdapter` 接口——接入新 agent（Codex、OpenCode、
DeepSeek Harness…）只实现它并注册，路由/引擎/持久化/传输全部不动：

| 方法 | 职责 |
|---|---|
| `detect(env)` | 探测版本 / 认证 / 能力 flag（从 `--help` 解析） |
| `buildArgs(ctx)` | 拼 spawn 命令行（各 CLI argv 不同） |
| `createParser(onEvent)` | 把运行时私有 stdout 翻译成 `NormalizedEvent` |
| `encodeUserMessage(text)` | 把 prompt 编码成该运行时的 stdin 格式 |
| `keepStdinOpen` | stream-json 类运行时保持 stdin 打开以支持中途追加 |

通用 `engine.ts` 只负责与运行时无关的部分：spawn、stdio 接线、`turn_end` 时关 stdin、
SIGTERM→SIGKILL 取消、退出码分类。当前唯一实现是 `runtimes/claude/`（detect /
stream / adapter）。

## 4. 核心模块

| 文件 | 职责 |
|---|---|
| `index.ts` | 入口：解析 CLI → loadConfig → 建 Db/registry → 探测 claude → startServer |
| `cli.ts` | `util.parseArgs` 解析 `airemote` 命令行参数，`--help`/`--version` |
| `config.ts` | env + CLI 合并（CLI 优先）；token 持久化；workspace/host 默认值 |
| `server.ts` | Express 组合根：CORS → JSON → 鉴权门 → 各路由注册 |
| `routes/*.ts` | HTTP/SSE 边界（health/agent/chat/claude-sessions/runs/sessions/permissions） |
| `runtimes/engine.ts` | 通用 spawn 生命周期（见 §3） |
| `permissions.ts` | `PermissionManager`：pending 请求 + 超时默认拒绝 + auto-allow 规则 |
| `run-notifier.ts` | `RunNotifier`：runId → 该 run 的 SSE 发送函数（带外事件路由） |
| `command-safety.ts` | Bash 只读命令白名单（只读自动放行，其余询问） |
| `permission-hook.ts` | PreToolUse hook 脚本（被 claude 调用，转发审批到 daemon） |
| `claude-sessions.ts` | 枚举 `~/.claude/projects/` 下的 Claude 会话 |
| `db.ts` | `node:sqlite` 持久化（内置，零原生依赖） |
| `types/api.ts` | 传输契约：`NormalizedEvent` 事件 union + DTO |

## 5. 数据模型（SQLite）

- `sessions` — 会话：`id`、`runtime`、`claude_session_id`（关联 claude 会话）、`cwd`、时间戳。
- `messages` — 对话转录：user 的 prompt + assistant 聚合后的可见文本。
- `runs` — 一次 spawn：status / exit_code / error。
- `events` — 归一化事件流，键 `(run_id, seq)`，用于断线重连回放。
- `audit_log` — chat / cancel / permission_decision 审计。

daemon 数据根默认 `~/.airemote`（`--data-dir` 可改）；`airemote.sqlite`、`token`、
（历史遗留的）`workspaces/` 都在其下。

## 6. HTTP / SSE 协议

| 方法 & 路径 | 鉴权 | 说明 |
|---|---|---|
| `GET /api/health` | 否 | 存活 + 版本 |
| `GET /api/agent` | 是 | 探测 Claude Code（版本/认证/能力） |
| `GET /api/agents` | 是 | 已注册运行时列表 |
| `GET /api/claude-sessions` | 是 | 列出**allowedDirs 内**的 Claude 会话（可导入续接） |
| `GET /api/workspaces` | 是 | 允许的工作目录列表：`{workspaces, default}` |
| `POST /api/chat` | 是 | 发指令，返回 SSE 流 |
| `GET /api/runs` | 是 | 当前**运行中**的 run 列表（手机「几个 agent 在跑」切换用） |
| `POST /api/runs/:id/cancel` | 是 | 取消运行 |
| `GET /api/runs/:id/events` | 是 | 一次性回放 run 事件，`?after=<seq>` 续传游标，返回 `{runId, events}` |
| `GET /api/runs/:id/stream` | 是 | 实时流：先回放 `?after=<seq>` 之后的事件，再续传直播（重连订阅） |
| `GET /api/sessions` | 是 | 会话列表（含 `running`/`runningRunId` 运行指示） |
| `GET /api/sessions/:id` | 是 | 会话 + 消息 + 运行 |
| `PATCH /api/sessions/:id` | 是 | 重命名（body `{title}`） |
| `DELETE /api/sessions/:id` | 是 | 删除会话（先取消进行中的 run，级联删消息/run/事件） |
| `POST /api/permissions/:id/decision` | 是 | 工具审批决定（allow/deny/allow_all） |
| `POST /api/internal/permissions/create` | 是 | 内部：hook 注册审批请求 |
| `GET /api/internal/permissions/:id/status` | 是 | 内部：hook 轮询决定 |

SSE 每帧 `{ runId, seq, event }`，`seq` 单调递增（重连游标）。`event` 是
`NormalizedEvent` union：

```
status | text_delta | thinking_delta | thinking_start | tool_use
tool_result | usage | turn_end | error | permission_request
```

`POST /api/chat` 请求体：`{ prompt, sessionId?, claudeSessionId?, model?, runtime?, cwd? }`。
`cwd` 仅对**新会话**生效，必须是允许目录（`--workspace` + `--allowed-dir`）本身或其子目录，
否则返回 400 `cwd_not_allowed`；续接已有会话沿用会话自身的 cwd。

**断线续接**：`/api/chat` 的客户端断开后，run **继续在 daemon 上运行**（不再因断线取消）；
手机重连后用 `GET /api/runs` 找到运行中的 run，再 `GET /api/runs/:id/stream?after=<seq>` 先回放
错过的帧、再续上直播。显式停止用 `POST /api/runs/:id/cancel`。事件已按 `(run_id, seq)` 持久化，
`/events` 与 `/stream` 复用同一套 `SseFrame` 形状。

## 7. 权限审批

`--permission-mode acceptEdits` 默认自动放行 Read/Write/Edit；通过 `--settings`
注入一个 **PreToolUse hook**（`matcher: "Bash"`），只对 Bash 做远程审批：

```
claude 要跑 Bash → hook(permission-hook.js) → POST /api/internal/permissions/create
  → daemon 广播 permission_request（SSE）→ 客户端决定 → POST .../decision
  → hook 轮询 status → 输出 permissionDecision allow/deny → claude 放行/阻止
```

审批策略（`routes/permissions.ts` + `command-safety.ts`）：

1. **auto-allow**（用户点过「允许全部 Bash」）→ 直接放行，不再广播；
2. **只读 Bash 白名单**（`ls`/`cat`/`grep`/`node --version`/`git status`…，且不含 shell
   元字符 `| > & ; $()` 等）→ 自动放行；
3. 其余（写入/删除/未知）→ 广播给客户端，弹「允许 / 拒绝 / 允许全部 Bash」；
4. 超时或断线 → **默认拒绝**（deny-by-default）。

## 8. 会话与跨端续接

- Claude Code 的所有会话（TUI 与 headless）统一存 `~/.claude/projects/<cwd>/<id>.jsonl`，
  会话 id 就是文件名；`claude --resume <id>` 续接。
- daemon 自己生成 `--session-id`，续接用 `--resume`，并把 `claude_session_id` 存进
  `sessions` 表。
- `GET /api/claude-sessions` 枚举**允许目录内**的会话，客户端可「导入」一个
  TUI 会话继续（`POST /api/chat` 带 `claudeSessionId`）。
- 双向互通：手机会话 → 电脑 `claude --resume <id>`；电脑 TUI 会话 → 手机导入继续。

## 9. 安全模型

- 认证：非 health 的 `/api/*` 全要 Bearer token（`auth.ts` 常量时间比较）。
- 传输：默认 HTTP 绑 `0.0.0.0`（局域网）；生产建议 `AIREMOTE_TLS_CERT/KEY` 或反代/SSH 隧道。
- 权限：acceptEdits + Bash 远程审批 + 只读白名单 + 默认拒绝（见 §7）。
- 工作目录：`--workspace`（主目录）+ `--allowed-dir`（可重复，或用 `AIREMOTE_ALLOWED_DIRS`，按 `path.delimiter` 分隔）
  构成**允许目录白名单**；`/api/chat` 的 `cwd` 必须落在白名单内（目录本身或其子目录），否则 400
  `cwd_not_allowed`（deny-by-default，见 `workspace.ts`）。
- 审计：chat / cancel / permission_decision 记入 `audit_log`。
- CORS：`Access-Control-Allow-Origin: *` 仅为让浏览器网页客户端可用；真正边界是 token。

## 10. 目录结构

```
airemote-daemon/
├─ src/
│  ├─ index.ts           入口
│  ├─ server.ts          Express 组合
│  ├─ config.ts / cli.ts / log.ts / network.ts / cors.ts / auth.ts
│  ├─ db.ts              SQLite 持久化
│  ├─ permissions.ts     权限注册表
│  ├─ run-notifier.ts    runId→SSE 路由
│  ├─ command-safety.ts  只读命令白名单
│  ├─ permission-hook.ts PreToolUse hook 脚本（编译为 dist/permission-hook.js）
│  ├─ claude-sessions.ts 枚举本机 Claude 会话
│  ├─ types/api.ts       传输契约
│  ├─ routes/            HTTP/SSE 边界
│  └─ runtimes/          运行时抽象 + claude 适配器 + 通用 engine
├─ client/index.html     极简测试网页客户端（零依赖）
├─ tests/                Vitest 单测（stream / permissions / command-safety）
├─ docs/design.md        （早期设计稿，部分已过时，以本文件为准）
├─ package.json / tsconfig.json / vitest.config.ts
└─ dist/                 构建产物（`airemote` bin 指向 dist/index.js）
```

## 11. 构建与运行

```bash
cd airemote-daemon
pnpm install
pnpm build          # tsc + chmod，产出 dist/index.js（含 permission-hook.js）
pnpm typecheck
pnpm test

# 全局命令（一次性）
ln -sf "$PWD/dist/index.js" ~/.local/bin/airemote

airemote                     # 默认 0.0.0.0:4780，workspace=当前目录，打印 token
airemote --workspace ~/code/foo --port 9000
```

## 12. 已知限制 / 后续

- 只读 Bash 白名单是内置默认，可考虑做成配置文件（`.airemote/policy.json`）可增减。
- auto-allow（「允许全部 Bash」）当前是 **run 级**（一次对话内），不是 session 级。
- 权限审批依赖 Claude Code 的 PreToolUse hook 格式，需随 CLI 版本演进同步探测/适配。
- Android/iOS 客户端尚未实现；传输契约（`types/api.ts`）已冻结，可据此接入。
