# airemote-desktop

AIRemote 的桌面客户端：Electron（主进程 + preload）+ React/Vite 渲染层。
它就是 daemon 的另一个瘦客户端——复用同一份协议与设计 token，不改变 daemon 的独立性。

设计文档（本机私有，不入库）：`docs/local/electron_desktop_plan.md`、`docs/local/desktop_ui_design.md`。

## 运行

```bash
pnpm install          # 首次；会下载 Electron 二进制（约 100MB）
pnpm dev              # 起主进程 + 渲染层（HMR）
pnpm build            # 产出 out/{main,preload,renderer}
pnpm typecheck        # 三份 tsconfig 都要过
pnpm test             # vitest：纯逻辑与守护测试
```

`dev` / `build` 前会自动用 daemon **自己的** tsc 编译一次 daemon：协议类型走
`@noresign/airemote/protocol` 子路径导出，指向 **daemon 的 `dist/types/api.d.ts`**，
源码树里没有这个文件——不先编译 daemon，三份构建都会解析失败。
（直接调 `tsc` 而不是 `pnpm -C ../airemote-daemon build`：daemon 的 `packageManager`
锁在 pnpm 10，本机 corepack 跑的是 11，会直接报版本不匹配。）

## 两个已知的坑

**1. Electron 二进制下载失败（国内常见）**

`pnpm install` 会在 postinstall 里从 GitHub release 拉 Electron 二进制，连不上时报
`TypeError: fetch failed`，装完也用不了。仓库里的 `.npmrc` 已经把这一项指到 npmmirror
（只影响这个文件，其余依赖仍走默认源）。若网络本身能到 GitHub，删掉那行即可。

**2. 连不上本机 daemon：`tokenSource` 不是 `file`**

本机 daemon 若通过 `~/.config/airemote.env` 的 `AIREMOTE_TOKEN` 启动（systemd 默认如此），
客户端**读不到** token，也不会去读 `<data-dir>/token`——那个文件是上一次「生成 token」时留下的旧值，
读了必然 401。此时需要在连接页手动粘贴 token：

```bash
grep AIREMOTE_TOKEN ~/.config/airemote.env
```

## 结构

```
src/shared/   主/渲染两侧共用：契约（type-only）、IPC 类型、路由、纯逻辑（SSE 解析 / 重连 /
              错误文案 / 格式化 / runtime 身份）
src/main/     连接与发现、daemon 代理、流式（RunStreamer + delta 合并）、窗口与自定义 scheme、
              托盘与系统通知、关窗行为
src/preload/  零依赖 contextBridge（tests/preload-sandbox.test.ts 钉死）
src/renderer/ zustand store + 纯 reducer + React UI
tests/        跨进程守护测试（preload 依赖、协议纯度）
```

约定与实现细节见仓库根的 `CLAUDE.md` 与 `docs/local/electron_desktop_plan.md`。
