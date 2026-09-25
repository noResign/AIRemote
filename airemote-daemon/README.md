# airemote-daemon

AIRemote 的守护进程：`spawn` 本机已安装的编码 agent CLI（当前 Claude Code，架构上预留多
agent），以无头方式执行，把输出解析成统一的流式事件，通过 HTTP/SSE 推给远程客户端。

完整技术方案（架构 / 协议 / 权限模型 / 数据模型 / 全部配置参数 / 分发）见
**[docs/daemon.md](docs/daemon.md)**；项目总览见
[AIRemote 仓库](https://github.com/noResign/AIRemote#readme)。

## 前置

- Node `~24`、pnpm 10
- 本机已装并登录 `claude` CLI（`claude auth login`）

> 只是要用、不打算改代码的话，直接 `npm install -g @noresign/airemote` 即可（命令名是
> `airemote`）。下面是**从源码**构建的步骤。

## 构建与运行

```bash
pnpm install
pnpm build            # tsc → dist/index.js（含 permission-hook.js）
node dist/index.js --workspace ~/code/my-project
```

首次启动生成 token 并打印，同时打印本机局域网地址；token 持久化在 `<data-dir>/token`
（默认 `~/.airemote/token`），删掉即轮换。

开发用 `pnpm dev`（tsx watch）。要全局安装成 `airemote` 命令：

```bash
ln -sf "$PWD/dist/index.js" ~/.local/bin/airemote
airemote --help
```

常用 flag：`--host`（默认 `0.0.0.0`）、`--port`（默认 `4780`）、`--workspace`
（默认当前目录）、`--data-dir`（默认 `~/.airemote`）、`--token`、
`--permission-mode`、`--env-file`。环境变量同名 `AIREMOTE_*`，**flag 优先**；
`.env` 在运行目录自动加载，模板见 [.env.example](.env.example)。

## 冒烟测试

```bash
TOKEN=$(cat ~/.airemote/token)
curl -s http://127.0.0.1:4780/api/health
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:4780/api/agent
curl -N -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"prompt":"列出当前目录的文件并总结"}' http://127.0.0.1:4780/api/chat
```

浏览器打开 [client/index.html](client/index.html) 可得到一个零依赖的图形化测试客户端。

## 配置

优先级：**命令行 flag > shell 环境变量 > `.env` 文件 > 默认值**。

| flag | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `--host` | `AIREMOTE_HOST` | `0.0.0.0` | 监听地址（`127.0.0.1` = 仅本机） |
| `--port` | `AIREMOTE_PORT` | `4780` | 端口 |
| `--workspace` | `AIREMOTE_WORKSPACE` | 当前目录 | 初始 Workspace 根目录 |
| `--data-dir` | `AIREMOTE_DATA_DIR` | `~/.airemote` | SQLite + token |
| `--token` | `AIREMOTE_TOKEN` | 自动生成 | Bearer 鉴权密钥 |
| `--permission-mode` | `AIREMOTE_PERMISSION_MODE` | `default`(=ask) | 新 Session 权限模式 seed |
| `--env-file` | `AIREMOTE_ENV_FILE` | `./.env` | `.env` 路径 |

其他常用变量：`AIREMOTE_PERMISSION_TIMEOUT_SECONDS`（审批超时，默认 120）、
`AIREMOTE_RUN_IDLE_TIMEOUT_SECONDS`（空闲看门狗，默认 900，0=禁用）、
`AIREMOTE_TLS_CERT` / `AIREMOTE_TLS_KEY`（同时设置才启用 HTTPS）、
`AIREMOTE_ALLOW_DEPLOY`（允许手机触发部署，默认关闭）。

完整清单见 [.env.example](.env.example) 与 [docs/daemon.md](docs/daemon.md) §10。

## API 速览

非 `/api/health` 的所有路由都要 `Authorization: Bearer <token>`。

| 方法 & 路径 | 说明 |
|---|---|
| `GET /api/health` | 存活 + 版本（唯一免鉴权） |
| `POST /api/chat` | 发指令，返回 SSE 流；断线后 run 继续跑 |
| `GET /api/runs/:id/stream?after=` | 重连：回放 + 续直播 |
| `GET /api/runs/:id/events` | 一次性回放 run 事件 |
| `POST /api/runs/:id/cancel` | 取消运行 |
| `POST /api/permissions/:id/decision` | 审批决定（allow / deny / allow_all） |
| `GET/PATCH /api/sessions/:id/permissions` | Session 权限模式与已授权工具 |
| `GET/POST/PATCH/DELETE /api/workspaces` | 工作区管理 |
| `GET /api/fs/directories` | 目录选择器 |
| `GET /api/changes`、`/api/changes/diff` | Git 未提交改动与单文件 diff |
| `GET /api/files`、`/api/files/content` | 目录懒加载与文本文件内容 |
| `GET /api/claude-sessions` | 枚举 workspace 内的 Claude 会话（可导入续接） |
| `POST /api/deploy` | 触发发布（默认关闭） |

SSE 每帧为 `{ runId, seq, event }`，`event` 是 `NormalizedEvent`：

```text
status | text_delta | thinking_delta | thinking_start | tool_use
tool_result | usage | turn_end | error | permission_request | question
```

完整端点表、请求体与错误形状见 [docs/daemon.md](docs/daemon.md) §6；跨端传输契约的唯一真源是
`src/types/api.ts`。

## 开发

```bash
pnpm typecheck
pnpm test             # Vitest
```

- 接入新 agent：实现 `src/runtimes/types.ts` 的 `RuntimeAdapter`，在
  `src/runtimes/registry.ts` 注册一行；路由 / 引擎 / 持久化 / 传输不动。
- 改协议：先改 `src/types/api.ts`（跨端契约唯一真源），再同步 Android 侧
  `lib-network` 的 `airemote/dto/`，最后更新 `docs/daemon.md`。
- `dist/`、`node_modules/` 不入库，不要手改 `dist/`。

## 安全

远程驱动一个带 shell 权限的 agent 本质等于远程代码执行。所有非 `/api/health` 路由都要
Bearer token；默认权限模式为 `ask`，审批超时或断线默认拒绝；**`workspace` 只是 spawn cwd，
不是沙箱**。公开网络使用请套 HTTPS（`AIREMOTE_TLS_CERT` / `AIREMOTE_TLS_KEY`）或反代 /
SSH 隧道。详见 [docs/daemon.md](docs/daemon.md) §9。

## 发布与安装

**维护者发布**：由作者手动完成（`prepack` 钩子会自动 build，registry 与 access 由
`package.json` 的 `publishConfig` 固定）。版本号取自 `package.json` 的 `version`（semver，
手改递增）；**已发布的版本不可覆写**，改完发新版本。

**用户安装**（需 Node `~24` + 本机已装并登录 Claude Code）：

```bash
npx @noresign/airemote --help           # 免安装试用
npm install -g @noresign/airemote       # 装成全局命令（命令名仍是 airemote）
airemote --workspace /path/to/project
```

包名为什么带 scope、包内容由哪些文件组成，见 [docs/daemon.md](docs/daemon.md) §12。

## License

Apache-2.0，见 [LICENSE](LICENSE)。
