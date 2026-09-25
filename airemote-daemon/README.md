# airemote-daemon

AIRemote 的守护进程：`spawn` 本机已安装的编码 agent CLI（当前 Claude Code，架构上预留多
agent），以无头方式执行，把输出解析成统一的流式事件，通过 HTTP/SSE 推给远程客户端。

完整技术方案（架构 / 协议 / 权限模型 / 数据模型 / 全部配置参数 / 分发）见
**[docs/daemon.md](docs/daemon.md)**；项目总览见
[AIRemote 仓库](https://github.com/noResign/AIRemote#readme)。

## 前置

- Node `~24`、pnpm 10
- 本机已装并登录 `claude` CLI（`claude auth login`）

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

## 安装

用户侧（需 Node `~24` + 本机已装并登录 Claude Code）：

```bash
npx @noresign/airemote --help           # 免安装试用
npm install -g @noresign/airemote       # 装成全局命令（命令名仍是 airemote）
airemote --workspace /path/to/project
```

版本号取自 `package.json` 的 `version`（semver，手改递增），`--version`、`/api/health`
与 npm 包版本同源。包名为什么带 scope、包内容由什么决定，见
[docs/daemon.md](docs/daemon.md) §12。

## License

Apache-2.0，见 [LICENSE](LICENSE)。
