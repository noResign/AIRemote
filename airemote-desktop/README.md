# airemote-desktop

AIRemote 的桌面客户端：Electron（主进程 + preload）+ React/Vite 渲染层。
它就是 daemon 的另一个瘦客户端——复用同一份协议与设计 token，不改变 daemon 的独立性。

设计文档（本机私有，不入库）：`docs/local/electron_desktop_plan.md`、`docs/local/desktop_ui_design.md`。

## 运行

```bash
pnpm install          # 首次；会下载 Electron 二进制（约 100MB）
pnpm dev              # 起主进程 + 渲染层（HMR）
pnpm build            # 产出 out/{main,preload,renderer}
pnpm dist             # build + 打包当前平台安装包到 release/
pnpm dist:dir         # 只产出 release/<平台>-unpacked/，不打安装包（验证用）
pnpm typecheck        # 三份 tsconfig 都要过
pnpm test             # vitest：纯逻辑与守护测试
```

`dev` / `build` 前会自动用 daemon **自己的** tsc 编译一次 daemon：协议类型走
`@noresign/airemote/protocol` 子路径导出，指向 **daemon 的 `dist/types/api.d.ts`**，
源码树里没有这个文件——不先编译 daemon，三份构建都会解析失败。
（直接调 `tsc` 而不是 `pnpm -C ../airemote-daemon build`：daemon 的 `packageManager`
锁在 pnpm 10，本机 corepack 跑的是 11，会直接报版本不匹配。）

## 打包

配置在 `electron-builder.yml`：linux `AppImage`+`deb`、mac `dmg`+`zip`、win `nsis`。
`pnpm dist` 默认只打**当前平台**，要出别的平台用 `pnpm exec electron-builder --mac` 之类的
（mac 产物需在 macOS 上打）。产物落到 `release/`，命名 `airemote-<version>-<arch>.<ext>`。

几个刻意的设定：

- **不做应用内自更新**，所以没有 `publish` provider、也没有 `latest-*.yml` 清单；
  自更新在 macOS 上需要签名（Squirrel.Mac 的硬约束），而 macOS 决定不签名。
- **`dependencies` 是空的**。renderer 由 Vite 打包、main/preload 由 electron-vite 打包，
  运行时不需要任何 `node_modules`，所以 asar 里只有 `out/` + `package.json`（约 1.3MB）。
  `@noresign/airemote` 是 `link:` 的本地包且只提供类型，必须是 `devDependencies`——
  留在 `dependencies` 里 electron-builder 会把 daemon 整个目录连同它的 `node_modules` 打进包里。
- **图标**是 `build/icon.png`（1024×1024，程序生成，与托盘图标同一渐变）。换图标只改这个文件。
- 版本源是 `package.json` 的 `version`，electron-builder 自动读走，无需另建 `src/version.ts`
  （`about` 页的 `appVersion` 走 Electron 的 `app.getVersion()`）。

验证打包产物（打包后 app 用 `airemote://` 从 asar 里读 SPA，这条链路只有真跑才算数）：

```bash
DISPLAY=:1 ./release/linux-unpacked/airemote-desktop --no-sandbox --user-data-dir=/tmp/AIRemote-test
```

`--no-sandbox` 是 `release/linux-unpacked/` 这个**未打包目录**才需要的：里面 `chrome-sandbox`
没有 setuid 位。AppImage/deb 的安装形态不受影响。另注意 `--user-data-dir`：同一 data dir 上
已有实例在跑（比如 `pnpm dev`）时，单实例锁会让新进程直接退出。

## 三个已知的坑

**1. Electron 二进制下载失败（国内常见）**

`pnpm install` 会在 postinstall 里从 GitHub release 拉 Electron 二进制，连不上时报
`TypeError: fetch failed`，装完也用不了。仓库里的 `.npmrc` 已经把这一项指到 npmmirror
（只影响这个文件，其余依赖仍走默认源）。若网络本身能到 GitHub，删掉那行即可。

**2. `pnpm dist` 卡在 `packaging platform=...` 不动**

不是死锁，是 `@electron/get` 在重新下载 Electron，而它**即使命中缓存也一定要再拉一次
`SHASUMS256.txt` 校验**：这个请求失败就判定缓存无效，回退重下整个 ~118MB 的 zip，
在慢链路上看起来就是卡死。用 `DEBUG='@electron/get:index'` 能直接看到：

```
Cache hit
Downloading https://npmmirror.com/mirrors/electron/v44.4.2/SHASUMS256.txt ...
Artifact in cache didn't match checksums  RequestError: socket hang up
falling back to re-download
```

典型诱因是把 npmmirror（国内源）塞进了翻墙代理，反而更容易被掐断。**`NO_PROXY` 没用**
——electron-builder 用 hpagent，直接读 env 里的代理地址，不认 `NO_PROXY`。所以打包时干脆
把代理摘掉：

```bash
HTTP_PROXY= HTTPS_PROXY= http_proxy= https_proxy= pnpm dist
```

`electron-builder.yml` 里的 `electronDownload.mirror` 是必须的：electron-builder **不读**
`.npmrc` 的 `electron_mirror`，不显式配就会直奔 GitHub。另注：首次打包还要拉 nsis /
appimage / fpm 等辅助二进制（来自 GitHub），那部分可用 `ELECTRON_BUILDER_BINARIES_MIRROR`
指向 npmmirror。

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
