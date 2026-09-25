# AIRemote

**远程操纵本机编码 agent。** 电脑上跑一个 daemon，把本机安装的编码 agent CLI（当前是
Claude Code）以无头方式 spawn 起来，把输出解析成统一的流式事件，通过 HTTP/SSE 推给手机：
在手机上看流式输出、审批工具调用、浏览文件、切换工作区，也可以随时锁屏离开。

手机只是遥控器，**任务始终在电脑上执行**——断连不影响任务，重连自动续上。

> [English](README.en.md) | 中文

<!-- 顶部主图：截好图放进 docs/media/ 后，取消下面这行的注释
![AIRemote](docs/media/hero.png)
-->

## 快速开始

### 1. 装 daemon

前置：**Node `~24`**，以及本机已装并登录 `claude` CLI（`claude auth login`）。

```bash
npm install -g @noresign/airemote      # 全局命令名是 airemote
airemote --workspace ~/code/my-project
```

- 不想全局安装就用 `npx @noresign/airemote --workspace ~/code/my-project`

首次启动会生成 token 并打印，同时打印手机可以连的局域网地址：

```
token: 3f9c…   ← 手机连的时候要填这个
LAN:   http://192.168.1.20:4780
```

- token 持久化在 `~/.airemote/token`，删掉即轮换；也可以用 `--token` 指定别的
- 完整参数、配置项与 HTTP/SSE 接口见 [airemote-daemon/README.md](airemote-daemon/README.md)。

#### 手机不在同一个局域网？（可选）

daemon 默认只监听局域网。要在外网连进来，两种常见做法：

**SSH 反向隧道**——有一台公网服务器就够了。在**跑 daemon 的电脑**上执行：

```bash
ssh -N -R 4780:127.0.0.1:4780 user@your-server
```

然后手机连 `http://your-server:4780`。三个容易踩的坑：

- `-R` 默认只在服务器的 **loopback** 上监听，手机连不上；需要在服务器 `/etc/ssh/sshd_config`
  里设 `GatewayPorts clientspecified`，并改用 `-R 0.0.0.0:4780:127.0.0.1:4780`
- 隧道断了不会自愈，用 `autossh -M 0 -N -R ...` 或做成 systemd 常驻
- 这种场景建议 daemon 只监听本机（`--host 127.0.0.1`），别让它同时暴露在局域网里

**组网工具**——把电脑和手机加进同一个虚拟内网，之后就像在同一个 LAN 里一样直接用 daemon
打印的地址（或虚拟网 IP）连接，daemon 侧不用改任何配置：

- Tailscale / ZeroTier —— 装好登录即用，手机端有 App，打不通 NAT 时自动走中继
- WireGuard —— 自己搭，最干净，但要维护密钥与路由
- frp / nps —— 自建中转，功能多，配置比 SSH 隧道重
- Cloudflare Tunnel —— 适合只想暴露 Web，注意它等同于把服务放到公网

> ⚠️ 上面任何一种做法都等于**把 daemon 暴露到公网**，而它驱动的是一个带 shell 权限的 agent。
> 至少确认 token 是强随机的；对外网访问建议再套一层 HTTPS（`AIREMOTE_TLS_*`）或反代。

### 2. 编译 Android 客户端

用 Android Studio 打开 `airemote-android/`（JDK 17，`minSdk 24` / `targetSdk 36`），
选 flavor 后直接 Run。命令行等价于：

```bash
cd airemote-android
./gradlew :app:assembleAlphaDebug
```

两个通道：`alphaDebug`（测试）与 `prodDebug`（正式）；正式签名需要
`airemote-android/keystore.properties`。模块划分与签名说明见
[airemote-android/README.md](airemote-android/README.md)。

### 3. 操作步骤

1. 打开 App，填 daemon 地址（就是启动时打印的那个 LAN 地址）和 token，连接
2. 新建会话 → 选工作区目录 → 发一句话
3. agent 在电脑上干活，手机上看流式输出；要跑 Bash 或写文件时手机上会弹审批卡
4. 想走就走——任务在电脑上继续跑，回来重连自动续上

## 功能

| 能力 | 说明 |
|---|---|
| 手机上看流式输出 | 文字与思考过程实时刷出来，工具调用渲染成可展开的卡片 |
| 工具审批 | Bash / 写文件 / MCP 调用转发到手机，点同意或拒绝；**超时或断线默认拒绝** |
| 只读白名单 | `ls` / `cat` / `git status` 这类只读命令自动放行，不打扰你 |
| 断线续传 | run 与连接解耦：手机断网、切后台、锁屏都不影响电脑上的任务，回来接着看 |
| 多会话并发 | 多个会话、多个任务并行跑，互不阻塞 |
| 双向续接 | 电脑上 TUI 开的 Claude 会话能导入手机继续；手机开的会话也能用 `claude --resume` 在电脑接续 |
| 工作区 | 手机可新增 / 切换工作区；每个会话绑定工作区目录，越界访问要审批 |
| 文件与 Diff | 手机上直接看工作区的 Git 未提交改动、单文件 diff、目录树与文本文件 |

## 界面

<!--
截图放进 docs/media/，文件名与下面一致后取消注释即可。

| 会话列表 | 聊天 | 审批浮层 |
|---|---|---|
| ![会话列表](docs/media/screenshot-sessions.png) | ![聊天](docs/media/screenshot-chat.png) | ![审批浮层](docs/media/screenshot-approval.png) |

| 新建会话 | 文件 / Diff | 工作区管理 |
|---|---|---|
| ![新建会话](docs/media/screenshot-new-session.png) | ![文件](docs/media/screenshot-files.png) | ![工作区管理](docs/media/screenshot-workspaces.png) |
-->

## 文档

| 文档 | 内容 |
|---|---|
| [airemote-daemon/README.md](airemote-daemon/README.md) | daemon 安装 / 配置 / API / 开发 |
| [airemote-daemon/docs/daemon.md](airemote-daemon/docs/daemon.md) | daemon 完整技术方案：架构 / 协议 / 权限 / 数据模型 / 端点表 |
| [airemote-android/README.md](airemote-android/README.md) | Android 客户端构建 / 签名 / 模块划分 |
| [airemote-ios/README.md](airemote-ios/README.md) | iOS 客户端（预留）：目标形态、技术选型、开发环境 |
| [docs/ui/](docs/ui/README.md) | 移动端 UI 设计（Android / iOS 共用，单一设计源）：设计原则 + 逐页规格 |
| [CLAUDE.md](CLAUDE.md) | 仓库纲领：边界、开发规范、关键设计决策 |

> 权限 / 工作区模型、Files Tab、聊天历史分页等**方案笔记属于本机私有文档**（`docs/local/`，
> 不入库），所以不在上表。

## 安全模型 ⚠️

**`workspace` 不是沙箱。** 它只决定 agent 从哪个目录启动（spawn cwd），不限制它能碰哪些路径
——`Read`/`Write`/`Edit` 在 `acceptEdits` 下可直接读写 workspace 之外的文件。文件级隔离需要
OS 层沙箱（bwrap / firejail / 容器），纯靠 CLI 做不到。

daemon 提供的边界：

- **认证**：所有非 health 的 `/api/*` 都要 Bearer token（常量时间比较）
- **权限模式**：默认 `ask`，`bypass` 必须用户在手机上明确切换
- **默认拒绝**：审批超时或客户端断线 → 拒绝
- **只读白名单**：仅放行无 shell 元字符的只读 Bash
- **审计**：chat / cancel / permission_decision / rename / delete 全部记入 `audit_log`

公开网络上使用请务必套 HTTPS（`AIREMOTE_TLS_*` 或反代）或 SSH 隧道，并保管好 token。
只想本机使用就 `--host 127.0.0.1`。

## 状态

- **daemon**：可用（会话 / 审批 / 工作区 / 文件 均已落地）
- **Android**：已实现（连接、会话列表、聊天、审批、新建会话、设置、文件、工作区管理）
- **iOS**：预留，尚未实现
- **多 runtime**：抽象已就位，当前唯一实现是 Claude Code

## License

Apache-2.0，见 [LICENSE](LICENSE)。
