# airemote 移动客户端 UI 设计文档（Android / iOS 通用）

> 本文档面向 UI/UX 设计师（含 AI 设计师）。目标是让设计师**不读代码、不看后端实现**，
> 仅凭本文档就能产出完整的移动端界面设计。
>
> 本文档是 **Android 与 iOS 共用的单一设计源**：页面、组件、状态、交互、Design Tokens
> 全部平台无关；实现侧分别落到 Jetpack Compose（Android）与 SwiftUI（iOS），见 §4.5。
>
> 这是**要长期使用的正式产品（非一次性 MVP）**：所有页面、所有状态都要按生产级质量设计，
> 并预留多 runtime（agent）扩展——当前接入 Claude Code，后续可加 Codex / OpenCode /
> DeepSeek Harness 等（后端插件化已就绪，见 §1.2）。
>
> 文档包含：产品定位、信息架构、逐页规格、全局设计规范（Design Tokens）、组件库、
> 关键交互与状态、数据与界面映射、交付物清单，以及后端接口契约（附录）。
>
> 标注说明：
> - 🟢 = 后端已支持，可直接做
> - 🟡 = 后端**尚未支持**，UI 按此设计，但需后端补能力（见附录 B）
> - 🔴 = 安全边界，设计必须覆盖

---

## 0. 产品一句话

**airemote 是一个"AI 编程遥控器"**：开发者在手机上给跑在**自己电脑上**的编码 agent 发消息、
看流式回复与工具执行卡片；任务始终在电脑上执行，手机只是遥控器。

关键认知：**手机断连不影响任务**——电脑上的 daemon 会继续干活，手机重连后能接着看。

---

## 1. 产品定位与用户

| 维度 | 说明 |
|---|---|
| 产品类型 | 开发者工具（移动端 App，Android 与 iOS 共用一套 UI 规范） |
| 目标用户 | 使用 Claude Code 等编码 agent 的开发者 |
| 核心场景 | 离开电脑后（躺床上 / 通勤 / 开会 / 排队）远程看一眼任务进度、发一句新指令 |
| 竞品参照体验 | 聊天类 App（微信/Telegram）+ 终端/日志查看器 + CI 构建监控 |
| 情绪基调 | 高效、可信、清爽、精致；**不花哨、不娱乐化** |

### 1.1 使用流程（端到端）

```
首次启动 → 连接服务器(地址+token) → 会话列表 → 打开/新建会话
   → 发消息 → 看流式回复 + 工具卡片 → （必要时审批命令）→ 任务跑完 → 查看改动的文件
```

### 1.2 多 runtime（agent）与跨平台（设计必须贯彻的两条主线）

**多 runtime**：后端是插件化的（`GET /api/agents` 枚举已接入的 agent，当前 `Claude Code`）。
UI 必须对「agent 身份」可扩展——每个 agent 由 `id` 唯一标识，UI 侧维护一份 `id → 图标 + 主题色`
的映射，未知 id 有通用兜底（见 §5.2）。聊天流是 **runtime 无关**的（后端已归一化成统一事件），
所以聊天渲染、工具卡片、审批逻辑**一套实现适配所有 agent**，只有「agent 徽章/身份」随 id 变。

**跨平台**：本规范是 Android 与 iOS 共用的单一设计源。设计交付物只描述
「页面/组件/状态/交互 + 语义 Token」，不绑定平台控件；图标用**语义名**（如 `chat_bubble`），
由实现侧分别映射到 Material Symbols（Android）与 SF Symbols（iOS），见 §4.5。

---

## 2. 设计目标与原则

1. **状态第一**：用户最关心"现在跑到哪一步、成功还是失败"，运行状态必须一眼可见。
2. **流式体验顺滑**：文本逐字出现、工具卡实时更新，不能卡顿或跳动。
3. **浅色优先**：默认清爽浅色主题，同时必须提供暗色主题（跟随系统偏好或手动切换）。
4. **拇指操作**：核心操作（发消息、切换会话、返回）都在单手可及区域。
5. **信息密度适中**：不堆砌，但技术信息（路径、token、模型、token 数）要能展开看到。
6. 🔴 **安全可见**：命令审批是产品的安全边界，审批弹窗必须醒目、信息完整、不可误触。

---

## 3. 信息架构与页面地图

共 **7 个屏幕 + 1 个权限审批浮层**。

```
┌─ 未连接 ──────────────────────────────────────┐
│  ① 连接服务器页（全屏门禁，无法跳过）            │
└───────────────┬───────────────────────────────┘
                │ 连接成功
┌───────────────▼───────────────────────────────┐
│  底部导航（平台原生 Tab Bar / NavigationBar）    │
│    ② 会话        ⑤ 文件(后续里程碑)   ⑦ 设置     │
│                                             │
│  会话 Tab ──点击──▶ ③ 聊天详情                  │
│  会话 Tab ──FAB──▶ ④ 新建/续接会话（底部 Sheet）  │
│  聊天页 ──审批──▶ Ⓟ 权限审批浮层（全屏覆盖）       │
│  文件 Tab ──点击文件──▶ ⑥ 文件查看器（后续里程碑） │
└─────────────────────────────────────────────┘
```

| # | 屏幕 | 类型 | 里程碑 |
|---|---|---|---|
| ① | 连接服务器 | 全屏门禁 | M1 |
| ② | 会话列表 | 主 Tab | M1 |
| ③ | 聊天详情 | 详情页（push） | M1（核心） |
| ④ | 新建/续接会话 | 底部 Sheet | M1 |
| ⑤ | 文件浏览 | Tab | M2 |
| ⑥ | 文件查看器 | 子页（push） | M2 |
| ⑦ | 设置 | Tab | M1 |
| Ⓟ | 权限审批 | 全屏浮层（覆盖在聊天页上） | M1（🔴 安全必需） |

> 交付按里程碑（M1 → M2）推进，但**每个页面从第一天起就按生产级质量设计**，不存在"先出个凑合的"。
> M1 交付后「文件」Tab 先显示占位，M2 落地文件浏览/查看器。

---

## 4. 全局设计规范（Design Tokens）

> 以下为建议值，设计师可在此基础上精修，但需保持**浅色优先 + 清爽精致**的统一调性。

### 4.1 色彩（浅色主题为默认）

| Token | 用途 | 浅色（默认） | 深色 |
|---|---|---|---|
| `bg` | 页面背景 | `#F6F8F7` | `#0E1512` |
| `surface` | 卡片/输入框 | `#FFFFFF` | `#17201C` |
| `surface-2` | 悬浮/Sheet/展开块 | `#FFFFFF`（配阴影分层） | `#1E2823` |
| `border` | 分隔线/描边 | `#E3EAE6` | `#293430` |
| `text-primary` | 主文字 | `#16211D` | `#E7EFEB` |
| `text-secondary` | 次文字 | `#5A6B64` | `#A2B0AA` |
| `text-muted` | 弱化/占位 | `#93A29A` | `#6D7C75` |
| `primary` | 主按钮/高亮（青绿） | `#0E9F86` | `#2BC7A4` |
| `primary-deep` | 按压/激活态 | `#0A7D6B` | `#4AD8B8` |
| `primary-subtle` | 选中底色/胶囊 | `#E2F3EE` | `#12332B` |
| `success` | 成功/完成（翡翠绿） | `#18A058` | `#4CC38A` |
| `warning` | 警告/进行中 | `#B7791F` | `#E0B14A` |
| `error` | 错误/失败/危险 | `#D6423E` | `#F26D6D` |
| `thinking` | 思考中文字 | `#9AA8A2` | `#85938C` |
| `code-bg` | 代码/工具输出底色 | `#F2F5F3` | `#101815` |

> 运行态主色用 `primary`（青绿）；成功/失败分别用 `success`（翡翠绿）/`error`；审批弹窗的「拒绝」用 `error`。
> 强调色与成功色同为绿色系，需靠**色相区分**：`primary` 偏青（teal），`success` 偏绿（emerald），避免同屏混淆。

### 4.1.1 质感与层次（"高级感"的关键）

浅色主题的高级感**不靠黑底，靠层次与留白**：

- **阴影分层**：卡片/Sheet 用低透明阴影抬升，而不是粗描边。三层阴影 Token：
  `elevation-1`（卡片，`0 1px 2px rgba(22,33,29,.06)`）、
  `elevation-2`（悬浮/Sheet，`0 8px 24px rgba(22,33,29,.10)`）、
  `elevation-3`（全屏浮层/审批弹窗，`0 16px 48px rgba(22,33,29,.18)`）。
- **描边极淡**：分隔用 1px `border`，只在需要时出现，避免"表格感"。
- **留白充足**：页面左右留白 20，区块间距 24+，让界面"呼吸"。
- **品牌渐变**：`linear-gradient(135deg, #0E9F86 → #18C9A6)` 仅用于 Logo、主 CTA 焦点态、
  连接页 hero 点缀；点到为止，不滥用渐变。
- **毛玻璃（可选）**：顶栏/底栏可用半透明 `surface` + 轻微 backdrop blur，营造悬浮感。

### 4.2 字体（语义字号，跨平台）

| 语义角色 | 字号（Android sp / iOS pt） | 字重 | 字体 |
|---|---|---|---|
| 页面标题 | 20 | SemiBold | 系统默认 |
| 列表标题/卡片标题 | 16 | SemiBold | 系统默认 |
| 正文 | 14 | Regular | 系统默认 |
| 辅助说明 | 12 | Regular | 系统默认 |
| 代码/路径/token/命令/工具输出 | 13 | Regular | **等宽**（JetBrains Mono / SF Mono / Roboto Mono） |

> 用语义角色（title/body/caption/code）实现，不硬编码字号；两端用各自平台的标准字号刻度对齐。

### 4.3 间距与圆角

- 基准网格 **4**（dp/pt）；常用间距 8 / 12 / 16 / 20 / 24。
- 卡片圆角 **12**；消息气泡 **16**；底部 Sheet 顶部圆角 **24**。
- 最小触控目标 **48**。

### 4.4 图标（语义名，跨平台映射）

- 设计交付物里只写**语义名**；实现侧映射：Android → Material Symbols（Rounded），iOS → SF Symbols（同义）。
- 关键语义名：会话=`chat_bubble`、文件=`folder`、设置=`settings`、发送=`arrow_upward`、
  语音输入=`microphone`、停止语音=`mic_off`、运行中=`spinner`/动画、复制=`content_copy`、
  重连=`sync`、审批=`shield`/`gavel`。

### 4.5 跨平台实现映射（给开发，设计无需关心）

| 关注点 | Android | iOS |
|---|---|---|
| UI 框架 | Jetpack Compose + Material 3 | SwiftUI（+ 对应 HIG） |
| 颜色 | 直接复用 §4.1 的 hex token | 同 token（存入 Asset Catalog） |
| 字号 | sp | pt（按 §4.2 语义角色对齐） |
| 图标 | Material Symbols（Rounded） | SF Symbols |
| 导航容器 | NavigationBar / BottomAppBar | TabView / toolbar |
| 底部 Sheet | ModalBottomSheet | `.sheet` |
| 等宽字体 | JetBrains Mono / Roboto Mono | SF Mono / Menlo |

> 两者共享同一套语义设计，不追求像素级一致，但**信息架构、组件、状态、交互必须完全一致**。

---

## 5. 组件库

设计师需产出以下可复用组件的规范（含各状态）：

| 组件 | 状态 | 说明 |
|---|---|---|
| **消息气泡** | 用户/助手 | 用户右对齐主色底；助手左对齐 surface 底 |
| **工具卡片** | running / done / error | 核心组件，见 5.1 |
| **思考块** | 折叠 / 展开 | 置灰、可点击展开 |
| **运行状态条** | 进行中 / 完成 / 失败 / 取消 | 聊天页顶部横幅，见 6.3 |
| **Runtime 身份** | 徽章 / 图标 / 主题色 | 每个 agent 一套可扩展身份，见 5.2 |
| **权限模式徽章** | — | 显示 daemon 级权限模式，危险模式用 error 色 |
| **运行中指示器** | running / idle | 会话列表 & 聊天页的脉冲/转圈 |
| **工作目录分组头** | 展开 / 折叠 | 会话列表按 cwd 分组的段头，见 6.2 |
| **空态插画** | — | 各列表空态 |
| **连接状态胶囊** | connected / connecting / offline / reconnecting | 全局 |
| **Usage 计量条** | — | token 数 + 成本，消息末尾 |
| **语音输入** | idle / listening / 转写中 / 完成 / 失败 | 聊天输入区麦克风入口，见 6.3 与 7.6 |
| 🔴 **权限审批卡片** | pending / allowed / denied / 超时 | 见 6.7 |

### 5.1 工具卡片（Tool Card）详细规格

展示 agent 正在/已经执行的一次工具调用。字段：`名称` + `图标` + `状态` + `可展开的入参/结果`。

- 头部：工具图标 + 工具名（如 `Write` / `Edit` / `Bash` / `Read` / `WebFetch`）+ 状态图标。
- 状态图标：running = 转圈（primary）；done = ✓（success）；error = ✗（error）。
- 展开区：`input`（入参）与 `result`（结果），等宽字体、代码块样式、可复制、可滚动。
- 工具名 → 图标映射建议：

| 工具名 | 图标 | 工具名 | 图标 |
|---|---|---|---|
| Write | `edit` | Bash | `terminal` |
| Edit | `edit` | Read | `article` |
| Glob | `search` | Grep | `find_in_page` |
| WebFetch / WebSearch | `language` | 其他 | `build` |

### 5.2 Runtime（Agent）身份系统

后端 `GET /api/agents` 返回 `[{id, name, bin}]`，`id` 是稳定标识（当前 `claude`）。
UI 侧维护一份 **`id → 身份`** 映射表，包含：**图标、主题色、展示名**。未知 id 用通用兜底（`build` 图标 + 中性色 + 直接用后端 `name`）。

建议初始映射：

| id | 展示名 | 图标（语义名） | 主题色 |
|---|---|---|---|
| `claude` | Claude Code | `smart_toy` / 品牌图标 | `#D97757`（Claude 暖橙） |
| `codex`（预留） | Codex | `terminal` | 待定 |
| `opencode`（预留） | OpenCode | `code` | 待定 |
| `deepseek-harness`（预留） | DeepSeek Harness | `build` | 待定 |
| *（未知）* | 后端 `name` | `build` | `text-secondary` |

出现位置：会话列表卡片、聊天页顶栏、新建会话的 agent 选择器、设置页默认 agent。设计时把这套身份做成**可配置的映射**，新增 agent 只需加一行，不改布局。

> 品牌强调色是全局青绿（§4.1 的 `primary`），与各 runtime 的身份色是**两套体系**：
> `primary` 管"产品自身的按钮/焦点"，身份色只用于"标识这是哪个 agent"。Claude 的暖橙与青绿品牌色
> 恰好形成冷暖对比，拉开辨识度。

---

## 6. 页面详细规格

> 每个页面标注：**目的 / 关键元素 / 交互 / 状态**。

### 6.1 ① 连接服务器页（Connection）🟢

**目的**：填写电脑上 daemon 的地址与 token，建立连接。

**关键元素**（自上而下）：
1. 产品 Logo + 名称 + 一句 slogan（"远程指挥你电脑上的编码 agent"）。
2. 服务器地址输入框：`http://192.168.x.x:4780`（等宽字体、带 `http://` 前缀提示；缺 scheme 时自动补 `http://`）。
3. Token 输入框：**64 位 hex**，**支持密码隐藏 + 一键显示** + 粘贴。
4. 主按钮「连接」（primary，全宽）。
5. 「最近连接」列表（若有历史）：点击即填并连接。
6. 底部小字安全提示："请确保手机与电脑在同一局域网，勿将端口暴露到公网。"

**交互**：
- 点「连接」→ 显示 loading（按钮转圈）→ 校验通过进入会话列表；失败显示错误文案 + 保持输入。
- 支持**扫码连接**（若后端提供二维码，见附录 B）：右上角扫码 icon，扫描后自动填好地址+token。

**状态**：
- 校验中：按钮 loading。
- 失败：输入框下方红字错误（区分"无法连接" / "token 无效"）。
- 成功：自动跳转会话列表，token 本地持久化。

---

### 6.2 ② 会话列表（Conversation List）🟢

**目的**：查看所有历史会话（按工作目录分组）、当前哪个在运行、进入会话。

**关键元素**：
1. 顶栏：标题「会话」+ 全局连接状态胶囊（connected/offline）。
2. 会话列表（LazyColumn），**按工作目录（`cwd`）分组**：
   - **工作目录分组头**（段头）：`folder` 图标 + 目录绝对路径（等宽、单行省略）+ 该目录会话数徽章
     + 折叠箭头；**默认展开、可点击折叠**；滚动时**吸顶**（sticky）；组间按「组内最近活跃时间」
     倒序（最近活跃的工作目录排最前）；**只显示有会话的工作目录，空目录不出现**。
   - **会话卡片**（组内按 `lastActiveAt` 倒序）：
     - 标题（`title`，新建会话时自动取首句；无标题显示默认名"未命名会话"）
     - Agent 徽章
     - 相对时间（`lastActiveAt`）
     - 🟢 **运行中指示**（`running=true` 时：左侧脉冲圆点 + 卡片描边 primary，点击可直接进该会话）
   - 工作目录已上移到分组头，**卡片内不再重复显示 `cwd`**。
3. 空态：插画 + "还没有会话" + 「新建会话」按钮。
4. FAB「+」→ 新建会话。

**交互**：
- 点击卡片 → 进入聊天详情。
- 🟢 长按卡片 → 弹出删除确认（`删除` / `取消`，删除为 error 色）→ `DELETE /api/sessions/:id`。
- 下拉刷新。

**状态**：
- 加载中：骨架屏。
- 空态 / 网络错误（可重试）。

> ⚠️ 数据现状说明（供开发，不影响设计）：当前会话列表接口返回 `{id, runtime, cwd, title, createdAt, lastActiveAt}`，
> **没有** running 字段、没有最后一条消息预览、title 恒为 null。设计师仍按理想卡片设计，
> 由开发在附录 B 里补齐后端字段。

---

### 6.3 ③ 聊天详情（Chat，核心页）🟢

**目的**：与 agent 对话，实时看流式回复、思考过程、工具执行、用量与结果。

**顶部栏**：
- 返回按钮。
- 标题（会话标题，🟢 点击可重命名 → `PATCH /api/sessions/:id`）。
- 副信息行：Agent 徽章 · 工作目录 · 权限模式徽章（可横向滚动/省略）。

**消息流**（核心，自上而下）：
1. **运行状态条**（会话运行中时显示）：由 `status` 事件驱动。
   - 进行中：`● 正在执行…`（对应 `label: "starting"`）。
   - 结束态：`✓ 完成`（`succeeded`）/ `✗ 失败`（`failed`）/ `已取消`（`cancelled`），带 `terminal` 标记。
2. 用户消息：右侧主色气泡。
3. 助手回复块，按事件实时渲染：
   - `thinking_delta` → **思考块**（折叠，默认收起，点击展开，置灰等宽）。
   - `text_delta` → 助手文本（左侧 surface 气泡，**逐字追加**）。
   - `tool_use` / `tool_result` → **工具卡片**（见 5.1，按 `tool_use_id` 更新同一张卡）。
   - `usage` → 消息末尾**用量条**：`↑ 1.2k ↓ 3.4k tokens · $0.18`（次文字，等宽）。
   - 🔴 `permission_request` → **弹出权限审批浮层**（见 6.7，阻塞当前运行直至作答）。
   - `error` → error 卡片（error 色，显示 message）。
4. 出错时：error 卡片（error 色，显示 message）。

**底部输入区**：
- 多行文本框（自动增高，上限约 6 行）。
- 🟡 **语音输入入口**（预留）：输入框**左侧**放麦克风按钮（`microphone`），交互见 7.6。语音转文字后
  **先填入输入框、由用户确认/编辑后再发送**，不直接发出——agent 指令需要精确，避免误识别直接执行。
- 发送按钮（`arrow_upward` 圆形，primary，输入为空时置灰禁用）。
- 运行中：发送禁用（输入可继续键入，供下一条排队），输入框上方提示"任务正在运行…"。
- 🟢 运行中提供「停止」按钮 → `POST /api/runs/:id/cancel`。

**交互**：
- 文本/工具结果支持长按复制。
- 工具卡片点击展开/收起 input+result。
- 思考块点击展开/收起。

**状态**（务必覆盖）：
- 空闲、运行中（含流式）、完成、失败、取消、审批中、断线重连（见第 7 章）。

**一条消息（assistant 回复）的可视化结构**：

```
┌──────────────────────────────────┐
│ ▸ 思考中（可展开）                 │  ← thinking_delta
│ 助手回复正文…（逐字）              │  ← text_delta
│ ┌ Write ✓ ────────────────┐      │  ← tool_use → tool_result
│ │ path: src/a.ts           │      │
│ └──────────────────────────┘      │
│ ↑ 1.2k ↓ 3.4k tokens · $0.18      │  ← usage
└──────────────────────────────────┘
```

---

### 6.4 ④ 新建/续接会话（New Conversation，底部 Sheet）🟢

**目的**：开始一个新对话，或续接电脑上已有的 Claude 会话。

**关键元素**（Sheet 内，分两段）：
1. 标题「新建会话」+ 关闭。
2. **方式 A · 新建空会话**：
   - Agent 选择（单选，来自 `GET /api/agents`，如 `Claude Code`）。
3. **方式 B · 续接本机 Claude 会话**（电脑 TUI 里开过的会话）：
   - 会话列表（来自 `GET /api/claude-sessions`，每项显示摘要 `summary` + 工作目录 + 相对时间）。
4. 主按钮「创建/开始」。

**交互**：选择即高亮；创建成功后自动进入该会话并收起 Sheet。两种方式互斥（单选切换）。
新会话在 `POST /api/chat` 里带 `runtime`（方式 A）或 `claudeSessionId`（方式 B）；工作目录
固定为 daemon 的 `--workspace`。

> 权限模式、工作目录都是 daemon 级全局配置（在设置页展示，只读），**不能按会话选择**。

---

### 6.5 ⑤ 文件浏览（Files）与 ⑥ 文件查看器 🟡（M2）

**目的**：查看 agent 工作区的文件，确认产出。

> 🟡 当前后端**没有**文件列表/读取接口，UI 按此设计，需后端补 `GET /api/files`、`GET /api/files/:path`（见附录 B）。

**列表页**：
- 顶栏标题「文件」+ 当前工作目录路径（可切换）。
- 文件/目录列表（图标区分），等宽字体显示文件名。
- 空态："暂无文件"。

**文件查看器（子页）**：
- 顶栏：文件名 + 关闭。
- 正文：**只读**文本，等宽字体，代码语法高亮（可选）。
- 长按可复制全文。

---

### 6.6 ⑦ 设置（Settings）🟢

**目的**：查看/修改连接与默认偏好。

**分组**：
1. **连接**：服务器地址、token（查看/修改）。
2. **默认偏好**：默认模型、默认 agent（🟡 需后端暴露模型列表）。
3. **信息（只读）**：daemon 版本号（`GET /api/health` 的 `version`）、当前权限模式。
4. **关于**：App 版本、开源许可。

---

### 6.7 Ⓟ 权限审批浮层（Permission Approval）🔴 M1 安全必需

**目的**：agent 想执行**有副作用的 Bash 命令**时，暂停运行，由用户允许/拒绝。这是本产品的**安全边界**。

**触发**：聊天流中出现 `permission_request` 事件，内容为 `{permissionId, toolName, toolInput}`，
其中 Bash 的命令在 `toolInput.command`。

**界面**（全屏覆盖浮层，modal，不可通过点外部关闭）：
1. 头部：警示图标（`shield`/`gavel`）+ 标题「Claude 请求执行命令」。
2. 工具名（如 `Bash`）。
3. **命令全文**：等宽字体、`code-bg` 底色、可滚动、长按复制，最大高度约屏幕 40%。
4. 安全提示小字："此命令可能修改文件或系统，请确认安全后再允许。"
5. 倒计时提示："120 秒内未处理将自动拒绝。"（后端超时即拒绝）
6. 三个操作按钮（自下而上，符合拇指操作）：
   - **允许**（primary）：仅本次放行 → `{decision: "allow"}`
   - **允许全部**（secondary）：本次 run 内该工具（如 Bash）后续都不再询问 → `{decision: "allow_all"}`
   - **拒绝**（error）：可选填理由 → `{decision: "deny", reason}`
7. 拒绝时展开一个可选的理由输入框。

**交互**：点击任一按钮 → 调 `POST /api/permissions/:id/decision` → 成功后关闭浮层，运行继续/中止。

**状态**：
- pending：等待用户作答（倒计时中）。
- 已处理：浮层关闭；若超时未答，浮层自动消失并在消息流里显示"命令审批超时，已自动拒绝"。

---

## 7. 关键交互与状态（设计重点，务必体现）

### 7.1 🔴 命令审批流（安全边界）

1. agent 发起有副作用的 Bash → daemon 暂停该 run，向客户端推 `permission_request`。
2. 客户端弹出审批浮层（6.7），**阻塞**该会话的输入与后续流。
3. 用户选择 允许 / 允许全部 / 拒绝。
4. 客户端 `POST /api/permissions/:id/decision` 回传决策。
5. daemon 放行或拒绝该命令，run 继续；超时（120s）未答则自动拒绝。

设计要点：浮层要**醒目、完整展示命令全文、按钮防误触**（允许和拒绝不能贴太近、拒绝无需二次确认但要有视觉权重区分）。

### 7.2 运行状态（多会话 / 多 agent 并发）

🟢 后端**支持多 run 并发**（无全局锁），并已提供 `GET /api/runs` 列出当前运行中的 run，
用于「手机上几个 agent 同时跑、来回切换看进度」。

设计表现：
- 会话列表：运行中的会话直接显示 running 指示（脉冲圆点 + primary 描边），数据来自
  `GET /api/sessions` 的 `running` / `runningRunId`（🟢 已实现）。
- 聊天页：运行中显示状态条 + 禁用输入。
- 建议加一条全局「N 个任务进行中」常驻指示条（点击展开运行中 run 列表，可跳转/切换；数据来自 `GET /api/runs`）。

### 7.3 断线重连（不丢进度）

🟢 后端已支持：`/api/chat` 客户端断开后 **run 继续在 daemon 上跑**（不再因断线取消），
事件按 `(run_id, seq)` 持久化；重连时用 `GET /api/runs/:id/stream?after=<seq>` 先回放错过的帧、再续上直播。

手机断网/锁屏后任务照跑。重连时：
1. 用 `GET /api/runs` 找到仍在运行的 run。
2. `GET /api/runs/:id/stream?after=<lastSeq>` 回放断线期间的事件并**续上直播**，顶部显示"任务仍在执行中"。
3. 全局连接状态胶囊从 `connected` → `offline` → `reconnecting` → `connected`，且**不打断阅读**。

### 7.4 流式渲染体验

- 文本**逐字追加**，不整段刷新；列表滚动需"贴底跟随"，用户上滑查看历史时**暂停自动滚底**。
- 工具卡片按 `tool_use_id` 定位更新，状态 running → done/error 原地变化，不新增卡片。
- 思考块默认折叠，避免刷屏。

### 7.5 全局状态胶囊

| 状态 | 视觉 | 出现位置 |
|---|---|---|
| connected | 绿点 + "已连接" | 会话列表顶栏 |
| connecting | 转圈 + "连接中…" | 连接页/重连时 |
| offline | 红点 + "已断开，点击重连" | 各页顶栏 |
| reconnecting | 琥珀转圈 + "重连中…" | 断线后 |

### 7.6 🟡 语音输入流（预留）

**目的**：躺卧/通勤等不便打字时，口述指令转文字。**核心原则：语音只负责"转文字"，不直接执行**——
识别结果先填入输入框，由用户确认/编辑后再点发送，避免误识别导致错误操作。

**交互（按住说话，推荐）**：
1. 输入框左侧**长按/按住**麦克风 → 弹出**录音浮层**（覆盖输入区，不打断上方聊天流）。
2. 浮层显示**实时波形/音量** + 提示"正在聆听…松开结束"，支持上滑取消。
3. 松手 → 进入"转写中"（转圈）→ 文字填入输入框、光标定位末尾，用户可编辑。
4. 确认无误后点发送，走与手动输入**完全相同的流程**（含 🔴 命令审批）。

**状态**：

| 状态 | 视觉 |
|---|---|
| idle | 麦克风图标（`text-secondary`，与输入框同高） |
| listening | 录音浮层：波形动画 + "正在聆听…松开结束" |
| 转写中 | 转圈 + "识别中…" |
| 完成 | 文字填入输入框（短暂高亮提示"可编辑"） |
| 失败 | toast：区分「无麦克风权限」/「未识别到内容」/「网络错误」 |

**设计/技术待定（🟡，见附录 B）**：ASR 在**客户端系统能力**（iOS `SFSpeechRecognizer` /
Android `SpeechRecognizer`）还是 **daemon 服务端 ASR** 做，需产品决策。前者零后端改动、体验最快；
后者跨端一致、但引入后端依赖与数据链路。无论哪端，**识别出的文字都是普通 prompt**，复用现有
鉴权/审批/白名单，不新增安全面；但语音是敏感数据，若走云端 ASR 须在隐私说明中披露。

---

## 8. 数据与界面映射（API → UI）

> 全部端点均在 `/api` 下；除 `/api/health` 外均需请求头 `Authorization: Bearer <token>`。

| 界面数据 | 来源 |
|---|---|
| 服务器地址 / token | 用户输入，本地持久化 |
| daemon 版本号 | `GET /api/health`（无需鉴权） |
| 可用 agent 列表（`Claude Code`…） | `GET /api/agents` |
| 会话列表（标题/runtime/工作目录/时间） | `GET /api/sessions` |
| 单个会话的消息历史 + runs | `GET /api/sessions/:id` |
| 续接本机 Claude 会话列表 | `GET /api/claude-sessions` |
| 流式事件（状态/文本/思考/工具/用量/审批/结束） | SSE：`POST /api/chat`（body `{sessionId?, claudeSessionId?, prompt, model?, runtime?}`） |
| 停止运行中的任务 | `POST /api/runs/:id/cancel` |
| 命令审批决策（允许/拒绝/允许全部） | `POST /api/permissions/:id/decision`（body `{decision, reason?}`） |
| 文件列表 / 文件内容 | 🟡 `GET /api/files`、`GET /api/files/:path`（**待后端实现**） |
| 断线补齐事件（续传） | `GET /api/runs/:id/events?after=seq` |

---

## 9. 交付里程碑（正式产品，增量上线）

> 这是长期维护的正式产品，按里程碑增量交付，但**每个页面从设计到实现都按生产级标准**，不留"临时版"。

**M1（首批可用）**：
- ① 连接页、② 会话列表、③ 聊天详情、④ 新建/续接会话、⑦ 设置、Ⓟ 权限审批。
- 覆盖：连接、列会话（含运行指示）、建会话（选 agent）、会话重命名/删除、发消息、流式回复、思考块、工具卡片、用量、**命令审批**、停止任务、断线重连。

**M2（增强）**：
- ⑤ 文件浏览 + ⑥ 文件查看器、扫码连接、消息搜索、多端观看（同一会话多设备同看）。

> 建议设计师**优先交付 M1 的 6 个屏幕**（含权限审批浮层）+ 组件库 + Runtime 身份映射；M2 页面先出线框即可，但同样要符合生产级规范。

---

## 10. 交付物清单（对设计师的要求）

请设计师产出：

1. **信息架构图**确认（可对本文档第 3 章提出优化）。
2. **高保真页面稿**（浅色为默认，附暗色对照）：
   - 连接页、会话列表、聊天详情、新建/续接会话、设置、**权限审批浮层** —— 各含关键状态。
3. **组件库**：消息气泡、工具卡片（running/done/error）、思考块、状态条、运行指示器、Runtime 身份（§5.2）、权限模式徽章、空态、**权限审批卡片**。
4. **交互说明**：流式追加、工具卡状态流转、**命令审批流**、断线重连、**语音输入流（按住说话→转写→确认发送）** 的动效示意。
5. **Design Tokens**：最终色板（暗/浅）、语义字体/间距/圆角、语义图标名。
6. **Runtime 身份映射**：§5.2 的 `id → 图标 + 主题色 + 展示名` 表，含未知 id 兜底。
7. **跨平台适配**：竖屏手机为主；小屏（≤360dp/pt 宽）排版策略；说明两端（Android/iOS）同一设计如何落到各自控件。

---

## 附录 A：后端接口契约（真实，供开发/设计师对照）

| 端点 | 方法 | 鉴权 | 返回/说明 |
|---|---|---|---|
| `/api/health` | GET | 否 | `{ok, service, version}` |
| `/api/sessions` | GET | 是 | `{sessions:[{id, runtime, cwd, title, createdAt, lastActiveAt, running, runningRunId}]}` |
| `/api/sessions/:id` | GET | 是 | `{session, messages, runs}` |
| `/api/sessions/:id` | PATCH | 是 | 重命名，body `{title}` → `{ok, session}` |
| `/api/sessions/:id` | DELETE | 是 | 删除会话 → `{ok, id}` |
| `/api/chat` | POST | 是 | SSE 流，帧 `{runId, seq, event}`；body `{sessionId?, claudeSessionId?, prompt, model?, runtime?}` |
| `/api/runs` | GET | 是 | `{runs:[{id, sessionId, runtime, model, status, prompt, startedAt}]}`（当前运行中的 run） |
| `/api/runs/:id/cancel` | POST | 是 | `{ok, id}` |
| `/api/runs/:id/events` | GET | 是 | `{runId, events:[{runId, seq, event}]}`；`?after=<seq>` 续传游标（一次性回放） |
| `/api/runs/:id/stream` | GET | 是 | SSE：先回放 `?after=<seq>` 后的事件，再续传直播 |
| `/api/agents` | GET | 是 | `{agents:[{id, name, bin}]}` |
| `/api/agent` | GET | 是 | `{agent:{…检测信息…}}` |
| `/api/claude-sessions` | GET | 是 | `{sessions:[{sessionId, cwd, summary, messageCount, lastActiveAt}]}` |
| `/api/permissions/:id/decision` | POST | 是 | `{ok, permission}`；body `{decision:"allow"|"deny"|"allow_all", reason?}` |

**流事件（`event` 字段 union）**：

| 事件 type | 关键字段 | UI 用途 |
|---|---|---|
| `status` | `label`(`starting`/`succeeded`/`failed`/`cancelled`), `terminal` | 运行状态条 |
| `text_delta` | `delta` | 助手正文逐字追加 |
| `thinking_delta` / `thinking_start` | `delta` | 思考块 |
| `tool_use` | `id, name, input` | 工具卡片（running） |
| `tool_result` | `toolUseId, content, isError` | 工具卡片（done/error） |
| `usage` | `usage, costUsd, durationMs` | 用量条 |
| `turn_end` | `stopReason` | 单轮结束 |
| `error` | `code, message, terminal` | 错误卡片 |
| `permission_request` | `permissionId, toolName, toolInput` | 🔴 审批浮层 |

---

## 附录 B：后端现状与待补项（UI 依赖、但后端还没实现的能力）

| 能力 | UI 依赖 | 状态 |
|---|---|---|
| 命令审批（Bash 有副作用时询问） | Ⓟ 审批浮层 | 🟢 **已支持** |
| 停止任务 | 聊天页「停止」按钮 | 🟢 已支持 |
| 事件回放（一次性补齐） | 7.3 断线重连 | 🟢 已实现（`GET /api/runs/:id/events?after=seq`） |
| 进行中 run 的实时续订阅（续上直播） | 7.3 断线重连 | 🟢 已实现（`GET /api/runs/:id/stream?after=seq`） |
| 运行中 run 列表（切换几个 agent） | 7.2 运行状态 | 🟢 已实现（`GET /api/runs`） |
| 会话列表里的 running 状态 | 会话列表运行指示 | 🟢 已实现（`GET /api/sessions` 返回 `running` + `runningRunId`） |
| 会话最后一条消息预览 | 会话列表卡片 | 🟡 未实现 |
| 会话标题生成 / 重命名 / 删除 | 列表标题、聊天页重命名、长按删除 | 🟢 已实现（新建自动取首句为标题；`PATCH`/`DELETE /api/sessions/:id`） |
| 全局运行锁（同一时刻一个 run） | 7.2 运行状态 | 🟡 未强制，需后端加 |
| 文件浏览 / 文件内容 | ⑤⑥ 文件页 | 🟡 未实现 |
| 模型列表 | 设置页默认模型 | 🟡 `GET /api/agent` 可返回 models，需确认 |
| 扫码连接（二维码） | 连接页扫码 | 🟡 未实现 |
| 按会话选择权限模式 | — | ⛔ daemon 级全局配置，非会话级 |
| 语音输入（ASR 转文字） | 聊天输入区麦克风 | 🟡 未实现，需决策客户端系统 ASR / daemon 服务端 ASR |

---

## 附：关键术语对照

| 术语 | 含义 |
|---|---|
| daemon | 跑在电脑上的守护进程，真正执行任务 |
| agent / runtime | 编码 CLI（当前为 Claude Code，未来可加 codex 等） |
| 会话（session / conversation） | 一次持续的对话，绑定 runtime/工作目录 |
| run | 一次「发消息 → 跑完」的执行单元；一个会话可包含多次 run |
| 工作区（workspace） | agent 干活的**唯一**根目录（daemon 级 `--workspace`，会话固定在其下） |
| 权限模式（permission mode） | daemon 级全局配置，控制 agent 能做什么（改文件/只读/任意命令） |
| token | 连接鉴权密钥（64 位 hex），**不是** LLM 计费的 token |
