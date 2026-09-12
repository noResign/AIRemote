# CLAUDE.md

本文件是这个仓库的**纲领性**入口指南。进入 `AIRemote/` 前先读这里，再按需读对应子项目的
`README.md` 与 `docs/`。细节以各文档为准，本文件只讲原则与边界。

## 项目简介

**AIRemote** 是一个「远程操纵本机编码 agent」的项目：电脑上跑一个 daemon，用它 spawn 本机
编码 agent（当前 Claude Code），把输出解析成统一流式事件，通过 HTTP/SSE 推给远程客户端
（Android / iOS），并支持会话续接、断线重连、取消、工具审批、多 agent 并发。

运行时被抽象成可插拔的 `RuntimeAdapter`，未来可加 Codex / OpenCode / DeepSeek Harness 等。
客户端**多 runtime 可扩展**：事件流是 runtime 无关的，UI 只对「agent 身份」做可配置映射。

> 安全提醒：远程驱动一个带 shell 权限的 agent 本质等于远程代码执行。认证、权限审批、
> 工作目录、默认拒绝是核心设计目标，不是附加项。

## 目录结构

```
AIRemote/
├─ CLAUDE.md              本文件
├─ docs/
│  └─ ui_design.md        移动端 UI 设计规范（Android/iOS 通用，单一设计源）
├─ airemote-daemon/       daemon 子项目（Node 24 + Express + SQLite）
│  ├─ src/                daemon 源码
│  ├─ client/             极简测试网页客户端（index.html，零依赖）
│  ├─ tests/              Vitest 单测
│  └─ docs/daemon.md      daemon 技术方案（架构/协议/权限/数据模型/端点表）
├─ airemote-android/      Android 客户端（Kotlin + Compose，M1 已实现）
│  ├─ app/                业务上层（MVVM + Compose UI + 仓库编排）
│  └─ lib-network/        网络底座（依赖 + wire DTO + Retrofit API + SSE + LLM 抽象）
└─ airemote-ios/          iOS 客户端（预留，原生 SwiftUI）
```

- **airemote-daemon/**：负责 `/api/*`、spawn agent、会话/run 持久化、权限审批、SSE 流。
- **airemote-android/**：已实现 M1（连接/会话列表/聊天/审批/新建会话/设置）。
- **传输契约**：跨端共享的 DTO、SSE 事件 union、错误形状放 `airemote-daemon/src/types/api.ts`。

## 技术栈

- **daemon**：Node `~24`（ESM）、TypeScript（strict）、运行时依赖仅 `express`、存储用
  `node:sqlite`（零原生依赖）、测试 Vitest、dev 用 `tsx`。
- **Android**：Kotlin、Jetpack Compose + Material 3、Retrofit + kotlinx-serialization、
  OkHttp（SSE 手写解析）、MMKV、Navigation Compose、MVVM；多模块（`:app` 业务 + `:lib-network` 通用网络层）。
- **iOS**：预留，原生 SwiftUI，与 Android 共用 `docs/ui_design.md` 一套规范。


## 开发规范

### 通用

- **命名（文件）**：一个文件一个**顶层公开类型**，文件名与该类型同名（`SseSource` →
  `SseSource.kt`、`NormalizedEvent` → `NormalizedEvent.kt`）；sealed 的**嵌套子类**、
  该类型的**配套顶层函数**随父类型放同一文件，不单独拆。文件夹按领域分组（`sse/`、`llm/`）。
- **例外：wire DTO 按域合并**。同一份协议的传输模型不搞「一类型一文件」，而是按业务域收成几个
  `<域>Dtos.kt`（`AgentDto` + `AgentsResponse` 同放 `AgentDtos.kt`），统一进 `<协议>/dto/` 包，
  **不再**在 `dto/` 下按域建子目录。理由：一个实体和它的列表包装永远同生共死，拆开只是噪音。
- **传输契约**：改 `types/api.ts` 前先想清对 Android/iOS 两端的影响；Android 侧 DTO 与之一一
  对齐（字段名/类型/多态判别）。
- **文档同步**：行为/协议/安全模型变化时，同步对应文档（daemon → `airemote-daemon/docs/daemon.md`；
  UI → `docs/ui_design.md`）。

### daemon

- **接入新 agent**：实现 `src/runtimes/types.ts` 的 `RuntimeAdapter`，在
  `src/runtimes/registry.ts` 注册一行；路由/引擎/持久化/传输不得改。
- **路由归属**：daemon 域端点放 `src/routes/<domain>.ts`，只有进程级元数据（health）留在
  `server.ts`。
- **安全边界**：认证、`--permission-mode`、PreToolUse hook、只读白名单、工作目录、
  默认拒绝都属安全边界，改动必须保持 deny-by-default。
- **Claude Code API 不稳**：CLI flag 随版本变，能力必须先 `--help` 探测再传参
  （`runtimes/claude/detect.ts`），不要假设某 flag 恒存在。
- **数据路径**：daemon 数据根默认 `~/.airemote`（`--data-dir` 可改），SQLite/token 都在其下。
- **配置**：env 变量可写入 `.env`（参考 `.env.example`，运行目录自动加载，flag 优先）。
- **测试/产物**：测试放 `tests/`；`dist/`、`node_modules/` 不入库，不要手改 `dist/`。
- **提交前**：至少 `pnpm typecheck` + `pnpm test`（涉及入口/打包再加 `pnpm build`）。

### Android

- **模块**：`:app`（业务）+ `:lib-network`（网络底座，包名 `com.airemote.network`）。
  **网络相关的东西全在 `:lib-network`**：okhttp / retrofit / coroutines / kotlinx-serialization
  依赖、wire DTO、Retrofit 接口与客户端工厂、SSE 传输、网络错误包装。`app` 不自己声明这些依赖，
  直接用 `:lib-network` 的类型（依赖以 `api` 暴露）。`:lib-network` 不引用任何 `android.*`，
  也不放业务编排、UI、MMKV。
- **`:lib-network` 内部分层（依赖单向向下，不引 okhttp-sse）**：
  1. `sse/` 通用 SSE 传输——`SseSource` 接口 + `OkHttpSseSource`（裸 OkHttp 长连接）+ 纯逻辑
     `SseParser`（可单测），不含业务语义；
  2. `http/` REST 结果包装——`NetworkResult` + `safeApiCall`；
  3. `llm/` 大模型供应商抽象——`LlmProvider`（`Flow` 流式 + 非流式）、`OpenAiCompatLlmProvider`
     （OpenAI 兼容协议）、`RoutingLlmProvider` + `ModelResolver`（按 model 路由，依赖倒置）；
  4. `airemote/` daemon 协议层——`AiremoteApi`（Retrofit）、`AiremoteClient`（工厂）、
     `AiremoteStream` + `ChatStreamEvent`、`dto/`（5 个按域分的 `<域>Dtos.kt`，与 `types/api.ts`
     一一对齐）。
- **分层（`:app`）**：`data/repository`（业务编排，直接调 `:lib-network`）、`data/local`（MMKV 设置）、
  `model`（页面模型）、`viewmodel`、`ui`、`navigation`，严格 MVVM，UI 层不碰网络。
- **传输契约**：改 `types/api.ts` 时同步改 `:lib-network` 的 `airemote/dto/`（字段名/类型/多态判别
  一一对齐）；跨 module 的 DTO 属性不能智能转换，先用局部变量接再判空。
- **持久化**：MMKV（`SettingsStore`，留在 `:app`）。
- **UI**：以 `docs/ui_design.md` 为唯一设计源；「runtime 身份」（图标/色/名）做成可配置映射，
  新增 agent 只加一行不改布局。
- **注意**：用户没有主动提出帮忙运行android项目，再更改后请不要主动使用.gradlew命令运行，用户自己运行测试

## 关键设计决策（速览）

- **权限审批**：每个 Session 独立模式 `ask` / `acceptEdits` / `bypass`；`ask` 下 Bash/Write/Edit
  经 PreToolUse hook 远程审批，Bash 内只读白名单自动放行；「允许全部」为 Session + toolName 级
  持久化 grant，默认拒绝、超时自动拒绝。
- **工作空间**：daemon 启动把 `--workspace` 注册为第一个 Workspace；手机可新增/切换 Workspace。
  Session 绑定 `workspace_id + cwd`，cwd 必须位于所属 Workspace 内。
- **多 agent 并发 + 断线续传**：run 与连接解耦；`/api/chat` 客户端断开后 run 继续跑，
  事件按 `(run_id, seq)` 持久化，`GET /api/runs/:id/stream?after=` 回放+续直播，显式停止用
  `POST /api/runs/:id/cancel`。
- **会话**：`--session-id`/`--resume` 管理 Claude 会话，`claude_session_id` 持久化；
  `GET /api/claude-sessions` 枚举 workspace 内会话，实现 TUI↔远程双向续接。
- **token**：持久化在 `<data-dir>/token`，启动复用（删文件即轮换）。

## git提交规范

遵循 [Conventional Commits]，格式 `<type>(<scope>): <subject>`，type 全小写英文。

- **type**：`feat` 新功能、`fix` 修复 bug、`refactor` 重构（不改行为）、`docs` 文档、`chore`
  杂务（依赖/构建/脚本）、`test` 测试、`perf` 性能。不用 `modify`——改动不是「新功能」就归
  `fix`/`refactor`。
- **scope**：可选，标注受影响子项目：`daemon` / `android` / `ios` / `docs`；跨端或全局改动
  省略 scope。
- **subject**：中文，简洁说明「做了什么」，如果改动设计多个角度修改，仅描述主要1～2个修改，以&连接；不加句号、不以大写开头；冒号后空一格；
  （`feat: xxx`，不是 `feat:xxx & xxx`）。
- **push**：目前仅一人开发，无需git pull，直接使用git push推送远端
