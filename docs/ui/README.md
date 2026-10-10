# 移动端 UI 设计（Android / iOS 共用）

> 本文档面向 UI/UX 设计师（含 AI 设计师）。目标是让设计师**不读代码、不看后端实现**，
> 仅凭这套文档就能产出完整的移动端界面设计。

这是 **Android 与 iOS 共用的单一设计源**：页面、组件、状态、交互、Design Tokens 全部平台无关；
实现侧分别落到 Jetpack Compose（Android）与 UIKit（iOS），映射见
[design-principles.md](design-principles.md) §4.5。

这是**要长期使用的正式产品（非一次性 MVP）**：所有页面、所有状态都按生产级质量设计，并预留
多 runtime（agent）扩展——当前接入 Claude Code，后续可加 Codex / OpenCode / DeepSeek Harness
等（见 [design-principles.md](design-principles.md) §1.2）。

## 文档结构

| 文件 | 内容 |
|---|---|
| [design-principles.md](design-principles.md) | **总的设计原则**：产品定位 / 设计目标与原则 / 信息架构与页面地图 / Design Tokens / 组件库（§0-§5） |
| [interactions.md](interactions.md) | 关键交互与状态：命令审批流、并发运行、断线重连、流式渲染、状态胶囊（§7） |
| [pages/connect.md](pages/connect.md) | ① 连接服务器页（§6.1） |
| [pages/sessions.md](pages/sessions.md) | ② 会话列表（§6.2） |
| [pages/chat.md](pages/chat.md) | ③ 聊天详情（§6.3，核心页） |
| [pages/new-session.md](pages/new-session.md) | ④ 新建 / 续接会话（§6.4） |
| [pages/files.md](pages/files.md) | ⑤ 文件浏览 + ⑥ Diff / 文件查看器（§6.5） |
| [pages/settings.md](pages/settings.md) | ⑦ 设置 + ⑧ 工作区管理 + ⑨ 目录选择器 + ⑩ 会话权限（§6.6） |
| [pages/permission-approval.md](pages/permission-approval.md) | Ⓟ 权限审批浮层（§6.7，安全边界） |
| [appendix.md](appendix.md) | 数据与界面映射 / 交付里程碑 / 交付物清单 / 后端接口契约（§8-§10 + 附录） |
| [preview.html](preview.html) | 可交互原型，浏览器直接打开 |

页面地图（信息架构）在 [design-principles.md](design-principles.md) §3。

每个页面文档都按 **目的 / 关键元素 / 交互 / 状态** 四段来写。

> **章节编号沿用拆分前的 `ui_design.md`，没有重新编号**——所以源码注释与其它文档里的
> `§4.1`、`§6.2`、`§6.5.1.1` 这类引用依然对得上，只是文件路径变了。

## 标注说明

- 🟢 = 后端已支持，可直接做
- 🟡 = 后端**尚未支持**，UI 按此设计，但需后端补能力（见 [appendix.md](appendix.md) 附录 B）
- 🔴 = 安全边界，设计必须覆盖

## 实现侧

| 平台 | 实现映射 |
|---|---|
| Android | [android/docs/ui_adapter.md](../../android/docs/ui_adapter.md) —— Design Token 落到 Compose 的对应关系 |
| iOS | 待补（预留，UIKit；对齐同一套 token 与页面规格，映射见 [design-principles.md](design-principles.md) §4.5） |

后端协议与权限模型见 [daemon/docs/daemon.md](../../daemon/docs/daemon.md)。
