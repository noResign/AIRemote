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

共 **10 个屏幕 + 2 个浮层（新建会话 Sheet / 权限审批）**，并新增一个横跨多个页面的「Workspace 切换器」。

```
┌─ 未连接 ──────────────────────────────────────┐
│  ① 连接服务器页（全屏门禁，无法跳过）            │
└───────────────┬───────────────────────────────┘
                │ 连接成功
┌───────────────▼───────────────────────────────┐
│  底部导航（自绘紧凑 Tab Bar，见 §3.2）           │
│    ② 会话        ⑤ 文件             ⑦ 设置     │
│                                             │
│  ② 会话 Tab
│    ├─ 顶部：Workspace 切换器
│    ├─ 点击会话 ──▶ ③ 聊天详情
│    └─ FAB ──▶ ④ 新建/续接会话（底部 Sheet）
│
│  ③ 聊天详情
│    ├─ 顶部：Workspace / Session / 权限模式
│    ├─ 进入 ──▶ ⑩ 会话权限设置
│    └─ 审批 ──▶ Ⓟ 权限审批浮层
│
│  ⑤ 文件 Tab：改动（默认）/ 全部文件，根目录跟随当前 Workspace
│  ⑦ 设置
│    ├─ Workspace 管理 ──▶ ⑧ 工作区管理
│    └─ 新增工作区 ──▶ ⑨ 目录选择器
└─────────────────────────────────────────────┘
```

| # | 屏幕/浮层 | 类型 | 里程碑 |
|---|---|---|---|
| ① | 连接服务器 | 全屏门禁 | M1 |
| ② | 会话列表 | 主 Tab | M1 |
| ③ | 聊天详情 | 详情页（push） | M1（核心） |
| ④ | 新建/续接会话 | 底部 Sheet | M1 |
| ⑤ | 文件（改动 / 全部文件） | Tab | M2 |
| ⑥ | Diff / 文件查看器 | 子页（push） | M2 |
| ⑦ | 设置 | Tab | M1 |
| ⑧ | 工作区管理 | 子页（push） | M2 |
| ⑨ | 目录选择器 | 子页/全屏 Sheet | M2 |
| ⑩ | 会话权限设置 | 子页（push） | M2 |
| Ⓟ | 权限审批 | 全屏浮层（覆盖在聊天页上） | M1（🔴 安全必需） |

> 交付按里程碑推进，但每个页面从第一天起按生产级质量设计。
> Workspace 切换器是横向组件，出现在 ② 会话、⑤ 文件、③ 聊天顶部信息里。

### 3.1 Workspace 与权限的作用域

- Workspace 是手机端切换的工作环境，替代原来的“daemon 全局唯一 workspace”。
- 一个 Workspace = **一个主目录 + N 个附加目录**。主目录是新 Session 的 cwd；
  附加目录只扩大 agent 的可达范围，不参与 cwd 校验。
- 每个 Session 属于一个 Workspace，Session Cwd 必须位于 Workspace **主目录**内。
- 每个 Session 有自己的权限模式：`ask` / `acceptEdits` / `bypass`。
- 切换 Workspace 后，当前客户端的会话列表、文件根目录、设置页都按新 Workspace 更新。
- 其他 Workspace 中正在运行的 Session 不受影响。

**两种“允许”的作用域刻意不同**（见 `daemon.md` §9）：

| 授权 | 作用域 | 撤销入口 |
|---|---|---|
| 工具授权（「允许全部」） | Session + 工具名 | ⑩ 会话权限设置 |
| 目录授权（越界读取批准 / 手动添加） | **Workspace 级**，校内所有会话共用 | ⑧ 工作区管理 |

理由：目录本来就是工作区的属性，批准一次就该所有会话复用，否则每个新会话都要重批一遍；
而工具授权的风险随会话场景变化，跟着 Session 更合适。

### 3.2 顶部栏与底部 Tab Bar（尺寸）

尺寸以 `docs/ui_preview.html` 为准（紧凑稿），实现时不要套平台默认高度。

**底部 Tab Bar**（会话 / 文件 / 设置）
- 条高约 **61dp + 系统导航栏 inset**；**不用** Material3 `NavigationBar` 的 80dp 默认（它不暴露高度参数，压不到这个尺寸）。
- 每项：icon **24dp** + 上方内边距 7dp、图标与文字间距 3dp、下方内边距 9dp；文字 **10.5sp**。
- 选中：主色 + 文字加粗；未选中：次要文字色 + Medium。
- 顶部 1px 分隔线；条背景用 `surface`（预览稿的毛玻璃为可选增强，见 §4.1.1）。

**顶部栏**
- 用 Material3 `TopAppBar` 默认高度（64dp）+ 状态栏 inset 即可，**不要再额外加状态栏高度**。
- ⚠️ 每个 Tab 页各自带 Scaffold + TopAppBar，而外层 `MainScreen` 的 Scaffold 已经把系统栏 inset 算进了 `innerPadding`；
  外层必须 `consumeWindowInsets(innerPadding)`，否则 inset 会被应用两次 —— 表现为标题上方多一条状态栏高度的空白、底栏上方多一段空隙。

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
| **权限模式徽章** | — | 显示 **Session 级**权限模式，危险模式用 error 色 |
| **Workspace 切换器** | 当前 / 切换中 / 空 | 会话/文件/设置共用，点击进入工作区管理或目录选择 |
| **运行中指示器** | running / idle | 会话列表 & 聊天页的脉冲/转圈 |
| **子目录分组头** | 展开 / 折叠 | 会话列表里 workspace 内子目录会话的段头，见 6.2 |
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
3. Token 输入框：**64 位 hex**，**支持密码隐藏 + 一键显示**（右侧小眼睛图标切换，不用文字按钮）+ 粘贴。
4. 主按钮「连接」（primary，全宽）。
5. 「最近连接」列表（若有历史，卡片列表）：每项显示服务器地址 + 掩码 token（`token ••••••••`，不显示明文）。
   - 只记录**连接成功过**的地址+token，最新在前，最多 5 条；**同一地址只留最新一条**（token 轮换后自动覆盖）。
   - 点击即填入地址+token 并直接发起连接（避免误点后还要手填）；长按弹确认后移除该条。
   - 当前输入框里的地址不一定在列表里（未连接成功过的不记录）。
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

**目的**：查看当前选中 Workspace 下的历史会话（根目录平铺 + 子目录分组）、当前哪个在运行、进入会话。

**关键元素**：
1. 顶栏：标题「会话」+ **Workspace 切换器**（当前 Workspace 名/path；点击打开 Workspace 列表和目录选择）+ 全局连接状态胶囊（connected/offline）。
2. 会话列表（LazyColumn），**只展示当前 Workspace 的 Session**：
   - **当前 Workspace 根目录**（`cwd == workspace.path`）的会话**平铺在顶部**，不显示分组头。
   - **Workspace 内子目录**（`cwd != workspace.path`，通常来自「续接本机会话」）的会话按 `cwd`
     分组，排在根目录会话下方：
     - **子目录分组头**（段头）：`folder` 图标 + 目录**相对路径**（相对当前 Workspace，等宽、单行
       省略）+ 该目录会话数徽章 + 折叠箭头；**默认折叠、可点击展开**；滚动时**吸顶**（sticky）；
       组间按「组内最近活跃时间」倒序（最近活跃的子目录排最前）；**只显示有会话的子目录，空目录
       不出现**。
   - **会话卡片**（平铺列表与组内均按 `lastActiveAt` 倒序）：
     - 标题（`title`，新建会话时自动取首句；无标题显示默认名"未命名会话"）
     - Agent 徽章
     - 相对时间（`lastActiveAt`）
     - 🟢 **运行中指示**（`running=true` 时：左侧脉冲圆点 + 卡片描边 primary，点击可直接进该会话）
   - 子目录已上移到分组头，**卡片内不再重复显示 `cwd`**。
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
- 副信息行：Agent 徽章 · Workspace 名 · cwd · 权限模式徽章（可横向滚动/省略）。
- 右侧「会话设置」入口 → **⑩ 会话权限设置**，可修改权限模式、查看/撤销 grants。
- Workspace 徽章点击后显示当前会话所属 Workspace，但**修改当前 Session 的 cwd/Workspace 不支持原地切换**；需要“在新 Workspace 新建会话”。

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
- **加载历史中**：进入已有会话要重建历史（按 run 逐个回放事件，会话越长越慢），加载期间消息区显示
  居中转圈 + 「正在加载对话…」，**不要留空白页**；加载完成或失败都要移除该指示（失败仍走错误提示）。
  > 根治方案（首屏只取最近 N 轮、向上滚动再加载更早）见 `docs/chat_history_pagination.md`，待实施。

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
2. **Workspace**（只读）：显示当前选中的 Workspace 名，**不可在 Sheet 内切换**。新会话固定归属当前
   Workspace；要在其他 Workspace 建会话，先去 ⑧ 工作区管理切换当前 Workspace。
3. **方式 A · 新建空会话**：
   - Agent 选择（单选，来自 `GET /api/agents`）。
   - **权限模式**：默认继承当前 Session 所属 Workspace 的全局默认值，可在这里覆盖为 `ask` / `acceptEdits` / `bypass`。
4. **方式 B · 续接本机 Claude 会话**：
   - 会话列表来自 `GET /api/claude-sessions?workspaceId=<当前Workspace>`；
   - 每项显示摘要 `summary` + cwd + 相对时间。
5. 主按钮「创建/开始」：**固定在 Sheet 底部**，不随内容滚动。Sheet 主体（Agent / 权限模式 / 会话
   列表）内部滚动，列表再长也不会把主按钮挤出屏幕。

**交互**：选择即高亮；创建成功后自动进入该会话并收起 Sheet。两种方式互斥。
新会话在 `POST /api/chat` 里带 `workspaceId`、`runtime`/`claudeSessionId`、`permissionMode`；服务端校验 Workspace 有效并写入 `sessions.workspace_id`。

> 权限模式是 **Session 级**，不是全局只读配置。新建 Session 默认 `ask`；全局 `default_permission_mode` 可配置为 `acceptEdits`。已有 Session 的权限模式在 ⑩ 会话权限设置里修改。

### 6.5 ⑤ 文件浏览（Files）与 ⑥ Diff/文件查看器 🟡（M2）

**目的**：查看当前 Workspace 的改动文件，并查看单文件 diff；后续可选查看全部文件。

> Files Tab 第一版默认打开「改动」，不做全量文件树递归。

#### 6.5.1 顶部区域

- 标题「文件」。
- Workspace 切换器：显示当前 Workspace 名/path，点击进入 ⑧ 工作区管理。
- 仓库状态：
  - Git 仓库：显示分支名（若后端返回）。
  - 非 Git 仓库：显示「非 Git 仓库」胶囊。
- **没有刷新按钮**，改用手势与自动刷新：
  - **下拉刷新**（`PullToRefreshBox`，只包住列表区，分段控制/根栏不参与手势）；
  - **进入本 tab 自动拉一次**（见 6.5.1.1）；
  - 切根、切分段、切 Workspace 时也会重新拉取。
  - 已知限制：空态/错误态的内容不是可滚动组件，下拉手势可能不触发；错误态有「重试」按钮兜底。
- 下拉指示器只由下拉刷新驱动（`refreshing`），进入 tab 的自动刷新不显示它，
  否则每次切页都会闪一下。

#### 6.5.1.1 浏览根切换（改动 / 全部文件 共用）

两个分段都渲染同一条「根栏」，位于分段控制之下：

```text
根：/home/user/mu2
[AIRemote]  [mu2]  [shared]  [下载 ×]  [＋ 目录]
                                  ↑ 书签可就地移除
```

- 顶部显示当前根的**绝对路径**（等宽、单行省略）——越界后用户仍要知道自己在哪。
  **保留它、但不再配「切换」按钮**（见下）。
- tab 行**始终渲染**（哪怕一个附加目录/书签都没有），否则没有地方加第一个。
- **tab 多了会怎样**：chips 单行**横向滚动**（`LazyRow`），不换行、不折叠、无溢出提示。
  - 「＋ 目录」**钉在行尾、不参与滚动**——否则 tab 一多它就被挤出屏幕，想加新目录得先滑到底。
  - 进入页面 / 切根时，**当前高亮的 chip 自动滚进视野**（已经可见则不动），否则 tab 多了
    以后当前栏停在屏幕外，用户看不出自己在哪一栏。
  - 不采用换行（`FlowRow`）：tab 多时会吃掉好几行正文高度；也不采用折叠成「…」菜单：
    多一次交互，而这里本来就是一排可滑的短标签。
- **tab 有两类，刻意分开**：
  | 类别 | 来源 | 能否就地移除 | 对 agent 的含义 |
  |---|---|---|---|
  | 工作区的根 | 主目录 + ⑧ 里的附加目录 | 否（去 ⑧ 管） | 主目录是 spawn cwd；附加目录是授权 |
  | **浏览书签** | 本页「＋ 目录」新增 | **是，带「×」** | **无——纯入口，不授予任何权限** |
- 「＋ 目录」是**唯一的**新增入口 → 打开 ⑨ 目录选择器（**绝对路径浏览**，因此能到
  工作区之外），文案为「添加浏览目录」/「加为快捷（不改权限）」，且**不禁用**
  「已是工作区」的目录。确认后调 `POST /api/workspaces/:id/shortcuts`，加完直接切过去。
  **去重**：主目录、附加目录、已有书签都算重复，daemon 返回 409 `shortcut_exists`，
  界面提示「该目录已经在 tab 上了」。

  **为什么没有单独的「切换」按钮**：早期根栏右侧有一个「切换」，只切过去看不入 tab。
  它与「＋ 目录」是两个几乎一样的「选一个目录」控件，而导航本身已经被覆盖：
  回到已知的根靠点 chip，根内上下靠面包屑 / 上一级。所以去掉了，只留一个入口。
  代价（已知且接受）：**「去看一眼」会留下一个 tab**，不想要就点「×」；
  尤其是往上一层时会留下父子两个 tab（去重只挡完全相同的路径，不挡父子包含）。
- 书签存**后端**（`workspace_shortcut_dirs`，随工作区走），不落客户端本地——
  换设备/重装后还在，且与工作区绑定。
- tab 行由 `/api/files`、`/api/changes` 响应里的 `roots` + `shortcutDirs` 同步
  （见 `daemon.md` §6）：每次列表加载都刷新，所以在 ⑧ 或聊天审批里新增的目录、
  以及任何来源的书签，都会**自动**出现，不必切工作区。
- **每次进入文件 Tab 自动拉一次**（`LaunchedEffect(Unit) { refresh() }`）：agent 在后台
  改了文件、或别处动了 tab 行，切回来就能看到，不必手点「刷新」。切走时本页离开
  composition，切回来重新进入即再触发。成本可忽略——`/api/files` 是**单层分页**
  （`limit=200` + `nextCursor`），只回当前目录一层，不递归；正在看 diff / 文件内容时
  跳过刷新（页面显示的不是列表）。
- 换 Workspace 时根重置回该工作区主目录（根是页面级状态，不跨工作区保留）。
- 面包屑首段固定为「根目录」（不再叫 `workspace`，因为根不一定是工作区）。

**这里刻意没有「作用域」二级概念。** 早期设计里「改动」Tab 另有一个「选择目录」控件，
用来在根之内选子目录（daemon 的 `dir` 参数）。它与根栏并排出现时是两个几乎一样的
「选一个目录」入口，容易混淆，而表达能力完全被根栏覆盖：原来「根=`~/OpenProject` +
作用域=`repoA`」直接表达成「根=`~/OpenProject/repoA`」即可（`dir` 留空）。
因此客户端**只使用 `root`**，daemon 侧的 `dir` 参数也已一并删除。

对应地，「此目录不是 Git 仓库」下的仓库列表（`repos`）点击行为是**把当前根换成那个仓库**，
而不是设置作用域。相对路径由客户端拼成绝对路径。

> 边界：这是**产品能力**而非疏漏——`root` 只校验「存在 + 是目录」，不要求落在工作区内，
> 非默认根会写 `browse_root` 审计日志。见 `daemon.md` §6 / §9。

#### 6.5.2 分段控制

```text
[ 改动 ]   [ 全部文件 ]
```

- 「改动」：默认选中。
- 「全部文件」：M2；单层懒加载浏览，默认忽略重型目录。

#### 6.5.3 改动列表

数据来源：

```http
GET /api/changes?workspaceId=<id>
```

列表项：

```text
src/auth/token.ts        M    +18 -4
src/auth/session.ts      A    new file
src/config/old.json      D
src/utils/rename.ts      R
```

关键元素：

- 状态徽章：
  - `M` modified（warning）
  - `A` added（success）
  - `D` deleted（error）
  - `R` renamed（primary）
  - `?` untracked（muted）
  - `U` conflicted（error）
- 文件路径分两行：第一行**文件名**（等宽字体，保证完整可见），第二行**目录前缀**（只保留最后 3 级，
  更深的以 `…/` 开头）后接状态与增删行数。
  - 省略号**不能**放在文件名那一行的末尾：路径单行显示时被截掉的正是最有信息量的文件名。
  - 目录前缀只保留尾部，是因为靠前的层级（`airemote-android/app/src/main/java/…`）每行都在重复。
- `+additions / -deletions`（daemon 基于 `git diff --numstat` 返回并展示）。
- `staged` 标记：已暂存显示小标签。
- `binary` 标记：二进制文件显示「二进制」，不展示行数。
- 无改动时：
  - 空态插画 + 「当前没有未提交的改动」。
- 非 Git 仓库：
  - 根之下有仓库时：列出这些仓库，点击 = **把当前根换成该仓库**（见 6.5.1.1）。
  - 没有仓库时：空态「此目录不是 Git 仓库」。
- 加载失败：
  - 错误提示 + 重试。

交互：

- 点击文件 → 进入 ⑥ Diff 查看器。
- 下拉刷新。
- 长按路径 → 复制相对路径。

#### 6.5.4 全部文件

全部文件不做递归加载，采用单层懒加载：

- 顶部面包屑：`根目录 / src / auth`（首段不再是 `workspace`，因为根可以切到工作区之外）。
- 当前目录只列一层。
- 默认隐藏：
  - `.git`
  - `node_modules`
  - `dist`
  - `build`
  - `.gradle`
  - `.idea`
  - `target`
  - `coverage`
- 支持「显示隐藏目录」开关。
- 顶部提供一行紧凑搜索框，只过滤当前目录，不递归查询。
- 点击除搜索框外的任意区域，应自动清焦点并收起软键盘。
- 目录项显示「文件夹」；文件项显示大小/修改时间。
- 目录条目多时后端分页；客户端滚动加载下一页。
- 点击目录进入下一层；点击文件进入 ⑥ 文件查看器。

#### 6.5.5 ⑥ Diff / 文件查看器

**目的**：查看单文件改动内容。

顶部：

- 返回按钮。
- 只显示文件名，不显示完整路径。
- 状态徽章：M/A/D/R/?。
- 右上角操作：
  - `全文`：打开该文件完整内容查看器；
  - `并排 / 统一`：切换 diff 展示模式。
- 默认使用 **并排 diff**。

正文（并排 diff）：

- 左右两列分别显示旧内容和新内容；
- 左右作为同一行整体横向滚动，保证两列始终对齐；
- 每行左侧带旧行号，右侧带新行号；
- 行背景统一使用 IDE 深色：
  - 背景：`#1E1E1E`
  - 上下文：`#D4D4D4`
  - 新增：`#89D185`
  - 删除：`#F48771`
  - hunk：`#569CD6`
  - 行号 / 弱化：`#858585`
- 点击任意行：
  - 该行进入展开状态；
  - 左右内容自动换行；
  - 再点收起。
- 超长 diff 截断时显示：「内容过大，已截断」。
- 二进制文件显示：「暂不支持预览二进制文件」。
- 非 Git 仓库或文件未改动时给出对应空态。

正文（统一 diff）：

- 右上角可切换；
- 等宽字体，整行 patch；
- 行级颜色与并排一致；
- 适合需要复制完整 patch 的场景。

### 6.5.6 文件内容查看器

入口：

- 从 ⑥ Diff 顶部「全文」进入；
- 从「全部文件」点击文件进入。

顶部：

- 返回按钮；
- 只显示文件名。

正文：

- 等宽、只读；
- IDE 深色背景；
- 支持「自动换行 / 不换行」切换；
- 不换行时整体横向滚动；
- 支持长按复制；
- 大文件限制大小并提示截断；
- 二进制文件提示不可预览。

交互：

- 从 Diff 进入时，返回应回到原 Diff；
- 点击除搜索框外区域自动收起键盘；
- 搜索框为一行紧凑样式，回车/搜索键也收起键盘。

### 6.6 ⑦ 设置（Settings）🟢

**目的**：查看/修改连接、默认偏好和工作区。

**分组**：
1. **连接**：服务器地址、token、重新连接。
   - token **只读展示且不暴露明文**：已配置显示掩码 `••••••••`，未配置显示「未配置」；修改 token 走 ① 连接页。
2. **工作区**：
   - 显示当前选中的 Workspace path；
   - 「管理工作区」按钮进入 **⑧ 工作区管理**；
   - 不在设置页内嵌工作区 CRUD。
3. **默认偏好**：
   - 默认 Agent（🟡 需模型列表）；
   - 新建 Session 默认权限模式：`ask` / `acceptEdits` / `bypass`。
     点击**立即选中**（乐观更新，不等接口）；`PATCH /api/config` 失败则弹回原选项并在下方显示失败原因。
     成功的刷新要**原地替换**，不要退回 Loading 态，否则整页会闪一下。
4. **客户端更新**：只放「检查更新」按钮，不重复展示版本信息。
5. **关于**：App 版本（`BuildConfig.VERSION_NAME`）、daemon 版本（`GET /api/health` 的 `version`）。
   - 「关于」常驻显示；daemon 未连上时其版本显示「未知」。

**关键交互**：

- 修改默认权限模式 → `PATCH /api/config`（`defaultPermissionMode`）；
- 切换当前 Workspace → 更新客户端本地 `selectedWorkspaceId`，并通知 ② 会话、⑤ 文件刷新；
- 工作区增删改 → 进入 ⑧ 工作区管理。

---

### 6.6.1 ⑧ 工作区管理（Workspace Management）🟡 M2

**目的**：管理 daemon 上注册的工作区，选择当前工作区。

**关键元素**：
1. 顶栏：标题「工作区」+ 新增按钮。
2. 当前工作区卡片：高亮、显示 `check`。
3. 工作区列表：
   - 名称 + `path`（**主目录**）；
   - **附加目录**列表：每个目录一行（`＋ <绝对路径>`，等宽、单行省略）+「移除」按钮；
   - Session 数量；
   - 是否为默认工作区；
   - 是否启用；
   - 操作：设为当前、设为默认、启用/禁用、重命名、**+ 附加目录**、删除。
4. 右上角「+ 新增」→ 打开 ⑨ 目录选择器（新增**工作区**）。
5. 「+ 附加目录」→ 打开 ⑨ 目录选择器（文案「选择附加目录」/「添加此文件夹」，
   且**不禁用**「已是工作区」，因为同一目录可以被工作区引用）。
6. 空态：引导新增工作区。

**规则**：
- 附加目录有两个来源、**同一份存储**：这里手动增删，或聊天里批准 agent 的越界读取（见 6.7）。
  两边必须双向可见，所以本页是目录授权的**唯一撤销入口**。
- 附加目录使该 Workspace **所有会话**（含以后新建的）都能读写该目录——UI 必须让用户看出
  这不是「只给当前对话」；
- 删除附加目录不删除磁盘内容，只是收回 agent 的可达范围；正在跑的 Run 下一次 spawn 才生效；
- 允许嵌套 Workspace（典型场景：daemon `--workspace` 指向根目录，再把其下的子目录注册为独立 Workspace）；
  仅拒绝**同一路径**重复注册；附加目录可等于另一个 Workspace 的主目录；
- 删除有 Session 引用的 Workspace 时阻止或提示先迁移（附加目录随之级联删除）；
- 当前工作区被删除后客户端 fallback 到默认工作区。

---

### 6.6.2 ⑨ 目录选择器（Directory Picker）🟡 M2

**目的**：从 daemon 所在电脑的文件系统中选择一个目录，注册为 Workspace。

**关键元素**：
1. 顶部：当前路径面包屑，可从任意层级点击回退。
2. 目录列表：只展示文件夹；可选显示隐藏目录；**已注册为 Workspace 的子目录标注「已是工作区」**。
3. 返回上一级。
4. 底部按钮「选择当前文件夹」。
   - 当前目录已是 Workspace 时按钮**禁用**并改为「已是工作区」，从源头避免 `workspace_exists` 报错。
5. 安全提示：`workspace 不是沙箱`；选择 `/`、`~` 等宽目录时二次确认。

**交互**：
- 默认从 `~` 开始；
- 点击文件夹进入下一层；
- 选择后调 `POST /api/workspaces`，成功后返回 ⑧ 并高亮新工作区；
- 失败时按 daemon 错误码给出中文提示（`workspace_exists` / `directory_not_found` 等，统一走 `util/ApiErrors.kt`）。

---

### 6.6.3 ⑩ 会话权限设置（Session Permissions）🟡 M2

**目的**：配置单个 Session 的权限模式和“允许全部”授权。

**关键元素**：
1. 顶栏：「权限设置」+ 关闭。
2. 当前模式：三档 Segmented Control / 单选卡片：
   - `ask`：修改类操作询问；
   - `acceptEdits`：自动接受编辑，Bash 等仍询问；
   - `bypass`：全部通过，需红色警告确认。
3. 已授权工具：
   - 列表：`toolName` + 授权时间 + 撤销按钮；
   - 底部「全部撤销」。
4. 说明文案：
   - 权限模式只对后续 Run 生效；
   - 当前正在运行的 Run 不受影响。

**交互**：
- 切模式 → `PATCH /api/sessions/:id/permissions`；
- 撤销 → `DELETE /api/sessions/:id/permissions/grants/:toolName`；
- 成功之后刷新 UI；如果当前模式为 `bypass`，已授权工具显示为“已暂停”。

### 6.7 Ⓟ 权限审批浮层（Permission Approval）🔴 M1 安全必需

**目的**：agent 想执行需要审批的工具时，暂停运行，由用户允许/拒绝。这是本产品的**安全边界**。

**触发**：聊天流中出现 `permission_request` 事件，内容为
`{permissionId, toolName, toolInput, status}`。

**按工具类型渲染**：

| toolName | 展示内容 |
|---|---|
| `Bash` | command 全文，等宽、可滚动、长按复制 |
| `Write` | 文件路径 + 内容预览 |
| `Edit` / `MultiEdit` | 文件路径 + diff |
| `Read` | **文件路径**（路径本身就是要批准的东西，所以顶到最前） |
| `Grep` | 搜索目录 + pattern + glob |
| 其他 | 通用 `toolInput` JSON 展示 |

**界面**（全屏覆盖浮层，modal，不可通过点外部关闭）：
1. 头部：警示图标 + 标题。
   - 普通工具：「Claude 请求执行」；
   - `Read` / `Grep`：**「Claude 请求读取工作区外的内容」**（性质不同，别让用户以为要执行命令）。
2. 工具名（如 `Bash` / `Write` / `Read`）。
3. **工具参数主体**：按上表渲染。
4. 安全提示小字：
   - 普通工具："此操作可能修改文件或系统，请确认安全后再允许。"
   - `Read` / `Grep`：**必须写明作用域**——"此路径不在工作区内。允许后会把该目录加入当前工作区，
     该工作区所有会话（含以后新建的）都不再询问；可在「工作区管理」里撤销。"
     否则用户会以为只影响当前这个对话（知情同意的一部分，见 `daemon.md` §7.1）。
5. 超时提示：`超时未处理将自动拒绝`（不写死 120 秒，超时值由 daemon 配置决定）。
6. 操作按钮：
   - **允许**（primary）→ `{decision: "allow"}`；
   - **允许全部（本 Session）**（secondary）→ `{decision: "allow_all"}`。
     **仅普通工具提供**；`Read` / `Grep` 不显示这个按钮（见下）；
   - **拒绝**（error）→ `{decision: "deny", reason?}`。
7. 拒绝时展开可选理由输入框。

**为什么 `Read` / `Grep` 卡片没有「允许全部」**：

两者是**目录级**门禁：这里的「允许」= 把这个目录记到工作区上，该工作区所有会话共用。
而「允许全部」走的是既有的 Session 工具授权，语义是**本 Session 内读任意路径都不再询问**——
范围大得多，且**不写目录授权**，等于把整套目录记录机制架空。

实测踩过一次坑：用户在 Read 卡片上习惯性点了中间那个位置（与 Bash 卡片的肌肉记忆一致），
结果本 Session 直接获得全盘读取权，且 `workspace_dirs` 始终为空、「批准过的目录」列表里
什么都没有，看起来像功能坏了。所以对目录门禁的工具**只留「允许 / 拒绝」两个按钮**，
把「允许」的含义收窄成唯一解释。

daemon 侧也把这条路堵死了：收到针对目录门禁工具的 `allow_all` 直接 **400
`allow_all_unsupported`**，并且这类工具不吃 Session 级 tool 授权。所以「本会话内读任意路径」
这种授权无法被创建，目录记录机制不会被架空。见 `daemon.md` §7。

**“允许全部”说明（普通工具）**：

- 作用域：当前 Session 后续所有 **同一个 toolName**；
- 例如：Bash 弹窗点“允许全部” → 本 Session 后续所有 Bash 免问；
- 不会自动放行 Write/Edit 等其他工具；
- 可在 ⑩ 会话权限设置里撤销。

**状态**：
- pending：等待用户作答；
- 已处理：浮层关闭；
- 超时：浮层自动消失，并在消息流里显示“命令审批超时，已自动拒绝”。

## 7. 关键交互与状态（设计重点，务必体现）

### 7.1 🔴 命令审批流（安全边界）

1. agent 发起需要审批的工具调用：
   - `ask` 模式：`Bash`、`Write`、`Edit` 等修改类工具；
   - `acceptEdits` 模式：`Bash` 等命令类工具；
   - `bypass` 模式：不弹审批。
2. daemon 暂停该 Run，向当前 Session 的客户端推 `permission_request`。
3. 客户端弹出审批浮层（6.7），阻塞该会话的输入与后续流。
4. 用户选择 允许 / 允许全部 / 拒绝。
5. 客户端 `POST /api/permissions/:id/decision` 回传决策。
6. daemon 放行或拒绝该工具，Run 继续；超时未答自动拒绝。

设计要点：
- 浮层要完整展示工具参数：Bash 显示 command，Write/Edit 显示文件路径和内容/diff；
- “允许全部”明确写清作用范围：**本 Session 后续同工具**；
- 允许和拒绝要有明显视觉权重区分，避免误触。

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
> Workspace 相关请求可带 `workspaceId`；客户端把 `selectedWorkspaceId` 存在本地。

| 界面数据 | 来源 |
|---|---|
| 服务器地址 / token | 用户输入，本地持久化 |
| 当前 Workspace | 客户端 `selectedWorkspaceId` + `GET /api/workspaces` 校验 |
| Workspace 列表 | `GET /api/workspaces` |
| 新增/选择目录 | `GET /api/fs/directories?path=...`、`POST /api/workspaces` |
| daemon 版本号 | `GET /api/health` |
| 全局默认配置 | `GET /api/config`；`PATCH /api/config` 更新默认 Workspace/权限模式 |
| 可用 agent 列表 | `GET /api/agents` |
| 当前 Workspace 的会话列表 | `GET /api/sessions?workspaceId=<id>` |
| 单个会话的消息历史 + runs | `GET /api/sessions/:id` |
| 续接本机 Claude 会话列表 | `GET /api/claude-sessions?workspaceId=<id>` |
| Session 权限模式与 grants | `GET /api/sessions/:id/permissions`；`PATCH /api/sessions/:id/permissions`；`DELETE /api/sessions/:id/permissions/grants/:toolName` |
| 流式事件 | SSE：`POST /api/chat`（body `{sessionId?, workspaceId?, claudeSessionId?, prompt, model?, runtime?}`） |
| 停止运行中的任务 | `POST /api/runs/:id/cancel` |
| 工具审批决策 | `POST /api/permissions/:id/decision`（body `{decision, reason?}`） |
| 当前 Workspace 的改动文件列表 | 🟡 `GET /api/changes?workspaceId=<id>`（**待后端实现**） |
| 改动文件 diff | 🟡 `GET /api/changes/diff?workspaceId=<id>&path=...`（**待后端实现**） |
| 当前 Workspace 的全部文件列表 / 内容 | 🟡 `GET /api/files?workspaceId=<id>&path=...`（**后续里程碑**） |
| 断线补齐事件 | `GET /api/runs/:id/events?after=seq` |

## 9. 交付里程碑（正式产品，增量上线）

> 这是长期维护的正式产品，按里程碑增量交付，但**每个页面从设计到实现都按生产级标准**，不留"临时版"。

**M1（首批可用）**：
- ① 连接页、② 会话列表、③ 聊天详情、④ 新建/续接会话、⑦ 设置、Ⓟ Bash 权限审批。
- 覆盖：连接、列会话（含运行指示）、建会话（选 agent / Workspace 展示）、会话重命名/删除、发消息、流式回复、思考块、工具卡片、用量、**Bash 命令审批**、停止任务、断线重连。

**M2（增强）**：
- ⑤ 文件浏览 + ⑥ 文件查看器；
- ⑧ Workspace 管理 + ⑨ 目录选择器；
- ⑩ 会话权限设置 + Write/Edit 审批；
- Workspace 切换后 Sessions/Files/Settings 刷新；
- 扫码连接、消息搜索、多端观看。

---

## 10. 交付物清单（对设计师的要求）

请设计师产出：

1. **信息架构图**确认（可对本文档第 3 章提出优化）。
2. **高保真页面稿**（浅色为默认，附暗色对照）：
   - 连接页、会话列表、聊天详情、新建/续接会话、设置、**权限审批浮层**；
   - 工作区管理、目录选择器、会话权限设置 —— 各含关键状态。
3. **组件库**：消息气泡、工具卡片（running/done/error）、思考块、状态条、运行指示器、Runtime 身份（§5.2）、权限模式徽章、**Workspace 切换器**、空态、**权限审批卡片**。
4. **交互说明**：流式追加、工具卡状态流转、**命令审批流**、Workspace 切换刷新流、断线重连、**语音输入流（按住说话→转写→确认发送）** 的动效示意。
5. **Design Tokens**：最终色板（暗/浅）、语义字体/间距/圆角、语义图标名。
6. **Runtime 身份映射**：§5.2 的 `id → 图标 + 主题色 + 展示名` 表，含未知 id 兜底。
7. **跨平台适配**：竖屏手机为主；小屏（≤360dp/pt 宽）排版策略；说明两端（Android/iOS）同一设计如何落到各自控件。

---

## 附录 A：后端接口契约（真实 + 规划，供开发/设计师对照）

| 端点 | 方法 | 鉴权 | 返回/说明 |
|---|---|---|---|
| `/api/health` | GET | 否 | `{ok, service, version, workspace}` |
| `/api/workspaces` | GET | 是 | `{workspaces:[{id,name,path,isDefault,enabled,sessionCount}]}` |
| `/api/workspaces` | POST | 是 | body `{name?, path}`；新增 Workspace，校验目录、防嵌套 |
| `/api/workspaces/:id` | PATCH | 是 | body `{name?, isDefault?, enabled?}` |
| `/api/workspaces/:id` | DELETE | 是 | 删除/禁用；有 Session 引用时阻止 |
| `/api/fs/directories` | GET | 是 | `?path=<abs>&showHidden=false`；返回目录列表、父目录、面包屑 |
| `/api/changes` | GET | 是 | `?workspaceId=<id>`；基于 `git status` 返回改动文件列表 |
| `/api/changes/diff` | GET | 是 | `?workspaceId=<id>&path=<relativePath>`；返回单文件 diff |
| `/api/config` | GET | 是 | 返回默认权限模式、默认 Workspace、只读/重启级配置 |
| `/api/config` | PATCH | 是 | body `{defaultPermissionMode?, defaultWorkspaceId?}` |
| `/api/sessions` | GET | 是 | `?workspaceId=<id>`；返回该 Workspace 的会话 |
| `/api/sessions/:id` | GET | 是 | `{session, messages, runs}` |
| `/api/sessions/:id` | PATCH | 是 | 重命名，body `{title}` |
| `/api/sessions/:id` | DELETE | 是 | 删除会话 |
| `/api/sessions/:id/permissions` | GET | 是 | `{mode, grants:[{toolName,createdAt}]}` |
| `/api/sessions/:id/permissions` | PATCH | 是 | body `{mode:"ask"|"acceptEdits"|"bypass"}`；仅影响后续 Run |
| `/api/sessions/:id/permissions/grants/:toolName` | DELETE | 是 | 撤销单个工具授权 |
| `/api/sessions/:id/permissions/grants` | DELETE | 是 | 撤销全部授权 |
| `/api/chat` | POST | 是 | SSE 流；body `{sessionId?, workspaceId?, claudeSessionId?, prompt, model?, runtime?, permissionMode?}` |
| `/api/runs` | GET | 是 | `?workspaceId=<id>`；当前运行中的 run |
| `/api/runs/:id/cancel` | POST | 是 | `{ok, id}` |
| `/api/runs/:id/events` | GET | 是 | `?after=<seq>` 一次性回放 |
| `/api/runs/:id/stream` | GET | 是 | SSE：回放 + 续直播 |
| `/api/agents` | GET | 是 | `{agents:[{id,name,bin}]}` |
| `/api/agent` | GET | 是 | 检测信息 |
| `/api/claude-sessions` | GET | 是 | `?workspaceId=<id>`；枚举该 Workspace 内的 Claude 会话 |
| `/api/permissions/:id/decision` | POST | 是 | body `{decision:"allow"|"deny"|"allow_all", reason?}` |

**流事件补充**：

| 事件 type | 关键字段 | UI 用途 |
|---|---|---|
| `permission_request` | `permissionId, toolName, toolInput, status` | 🔴 审批浮层；已决状态帧用于移除旧弹窗 |

## 附录 B：后端现状与待补项（UI 依赖、但后端还没实现的能力）

| 能力 | UI 依赖 | 状态 |
|---|---|---|
| Bash 有副作用时审批 | Ⓟ 审批浮层 | 🟢 已支持 |
| Write/Edit 在 `ask` 模式下审批 | 审批浮层、工具卡片 | 🟡 需扩展 PreToolUse hook matcher |
| 三档 Session 权限模式 | ⑩ 会话权限设置 | 🟡 需新增 Session 字段和 API |
| “允许全部”Session 级持久化、可撤销 | 审批浮层、⑩ 会话权限设置 | 🟡 当前仅内存，需改 DB |
| Workspace 注册与切换 | 工作区管理、顶部切换器 | 🟡 未实现 |
| 目录选择器 | ⑨ 目录选择器 | 🟡 未实现 |
| 按 Workspace 过滤会话 | ② 会话列表 | 🟡 未实现 |
| 改动文件列表（git status） | ⑤ Files 改动 Tab | 🟡 未实现 |
| 单文件 diff | ⑥ Diff 查看器 | 🟡 未实现 |
| 全部文件浏览 / 文件内容 | ⑤ Files 全部文件 Tab | 🟡 后续里程碑 |
| 停止任务 | 聊天页「停止」按钮 | 🟢 已支持 |
| 事件回放 / 断线续传 | 7.3 断线重连 | 🟢 已实现 |
| 运行中 run 列表 | 7.2 运行状态 | 🟢 已实现（需补 `workspaceId` 过滤） |
| 会话列表 running 状态 | 会话列表运行指示 | 🟢 已实现 |
| 会话最后一条消息预览 | 会话列表卡片 | 🟡 未实现 |
| 模型列表 | 设置页默认模型 | 🟡 `GET /api/agent` 可返回 models，需确认 |
| 扫码连接（二维码） | 连接页扫码 | 🟡 未实现 |
| 语音输入（ASR 转文字） | 聊天输入区麦克风 | 🟡 未实现，需决策客户端系统 ASR / daemon 服务端 ASR |

## 附：关键术语对照

| 术语 | 含义 |
|---|---|
| daemon | 跑在电脑上的守护进程，真正执行任务 |
| agent / runtime | 编码 CLI（当前为 Claude Code，未来可加 codex 等） |
| 会话（session / conversation） | 一次持续的对话，绑定 runtime/工作目录 |
| run | 一次「发消息 → 跑完」的执行单元；一个会话可包含多次 run |
| 工作区（workspace） | agent 干活的根目录；daemon 启动时把 `--workspace` 注册为第一个 Workspace，之后可在手机上切换 |
| Session Cwd | Session 实际启动目录，必须位于所属 Workspace 内 |
| 权限模式（permission mode） | **Session 级**配置：`ask` / `acceptEdits` / `bypass` |
| Permission Grant | Session 级“允许全部”授权，按 toolName 持久化，可撤销 |
| token | 连接鉴权密钥（64 位 hex），**不是** LLM 计费的 token |
