# CLAUDE.md

本文件是这个仓库的入口指南。进入 `AIRemote/` 前先读这里，然后按需读对应子项目的
`README.md` 与 `docs/`。

## 项目简介

**AIRemote** 是一个「远程操纵本机编码 agent」的项目：在电脑上跑一个 daemon，用它
spawn 本机的 Claude Code（无头方式），把输出解析成统一的流式事件，通过 HTTP/SSE 推给
远程客户端（Android / iOS / 网页），并支持会话续接、取消、工具审批。

当前只接入 Claude Code，但运行时被抽象成可插拔的 `RuntimeAdapter`，未来可加 Codex /
OpenCode / DeepSeek Harness 等。

> 安全提醒：远程驱动一个带 shell 权限的 agent 本质上等于远程代码执行。认证、权限
> 审批、工作目录隔离是这个项目的核心设计目标，不是附加项。

## 目录结构

```
AIRemote/
├─ CLAUDE.md              本文件
├─ .gitignore
├─ docs/                  项目级文档
│  └─ daemon.md           daemon 技术方案（架构/协议/权限/数据模型）
├─ airemote-daemon/       daemon 子项目（Node 24 + Express + SQLite）
│  ├─ src/                daemon 源码（见 docs/daemon.md 的模块表）
│  ├─ client/             极简测试网页客户端（index.html，零依赖）
│  ├─ tests/              Vitest 单测
│  ├─ docs/design.md      早期设计稿（部分过时，以 docs/daemon.md 为准）
│  └─ package.json        包名 `airemote`，bin `airemote` → dist/index.js
├─ airemote-android/      Android 客户端（预留，未实现）
└─ airemote-ios/          iOS 客户端（预留，未实现）
```

- **airemote-daemon/**：唯一有代码的子项目。它负责 `/api/*`、spawn agent、会话持久化、
  权限审批、静态测试客户端。
- **airemote-android/** / **airemote-ios/**：客户端占位目录，尚未实现。客户端接入
  契约在 `airemote-daemon/src/types/api.ts`（`NormalizedEvent` 事件 union + DTO）。

## 技术栈

- Node `~24`（ESM，`"type": "module"`），pnpm `10.x`。
- TypeScript（strict），编译到 `dist/`（NodeNext，相对导入带 `.js` 后缀）。
- 运行时依赖仅 `express`；存储用 Node 内置 `node:sqlite`（零原生依赖）。
- 测试用 Vitest；dev 用 `tsx`。
- 目标平台：macOS / Linux（Windows 为尽力支持）。

## 常用命令

在 `airemote-daemon/` 下：

```bash
pnpm install
pnpm build          # tsc + chmod，产出 dist/index.js 与 dist/permission-hook.js
pnpm typecheck
pnpm test           # Vitest（stream / permissions / command-safety）
pnpm dev            # tsx watch（本地开发）
```

全局运行 daemon（一次性）：

```bash
ln -sf "$PWD/dist/index.js" ~/.local/bin/airemote
airemote                       # 默认 0.0.0.0:4780，workspace=当前目录，打印 token
airemote --workspace ~/code/foo --port 9000
airemote --help
```

## 开发规范

- **运行时接入新 agent**：实现 `src/runtimes/types.ts` 的 `RuntimeAdapter`，在
  `src/runtimes/registry.ts` 注册一行。路由/引擎/持久化/传输不得改。
- **传输契约**：跨端共享的 DTO、SSE 事件 union、错误形状放 `src/types/api.ts`；改契约
  前先想清楚对 Android/iOS 客户端的影响。
- **路由归属**：daemon 域端点放 `src/routes/<domain>.ts`，不要在 `server.ts` 里堆 handler；
  只有进程级元数据（health/version）才留在 `server.ts`。
- **权限/安全改动**：认证、`--permission-mode`、PreToolUse hook、只读白名单、默认拒绝
  都属于安全边界，改动必须保持 deny-by-default，并更新 `docs/daemon.md` §7/§9。
- **Claude Code API 不稳**：CLI flag 会随版本变，能力必须先 `--help` 探测再传参
  （见 `runtimes/claude/detect.ts`），不要假设某个 flag 恒存在。
- **数据路径**：daemon 数据根默认 `~/.airemote`（`--data-dir` 可改），SQLite/token 都在其下；
  不要在代码里另起一套数据路径约定。
- **测试**：测试放 `tests/`（`src/` 保持 source-only）；单测用 Vitest，纯函数优先。
- **构建产物**：`dist/`、`node_modules/` 不入库（`.gitignore` 已覆盖）；不要手改 `dist/`。
- **提交前**：至少 `pnpm typecheck` + `pnpm test`（涉及打包/入口再加 `pnpm build`）。
- **文档同步**：行为/协议/安全模型变化时，同步 `docs/daemon.md`（daemon 侧）与
  本文件（目录/规范侧）。

## 关键设计决策（速览）

- **权限审批**：`--permission-mode acceptEdits` 放行 Read/Write/Edit；注入 PreToolUse
  hook 只拦 `Bash`；Bash 内再分「只读白名单自动放行」和「有副作用才询问」，默认拒绝。
- **会话**：daemon 用 `--session-id`/`--resume` 管理 Claude 会话，`claude_session_id`
  持久化；`GET /api/claude-sessions` 枚举当前 workspace 的会话，实现 TUI↔远程双向续接。
- **token**：持久化在 `<data-dir>/token`，每次启动复用（删文件即轮换）。
