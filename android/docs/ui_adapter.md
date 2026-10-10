# paboot Android UI 适配方案

> 本文档是 Android 客户端把新版 `docs/ui/`（浅色优先 + 青绿/翡翠）落到
> Jetpack Compose 的**实现适配方案**，也是开工前的施工图。
>
> - 设计源：`docs/ui/design-principles.md`（唯一设计源，所有 token 值从它抄，不臆造）
> - 代码位置：`app/src/main/java/com/noresign/paboot/`
> - 状态：**待评审 / 待决策**（见 §10）

---

## 1. 目标与范围

把 M1 已实现的 Android 客户端从「Material 模板默认紫色 + 无底部导航」改造成与新版
`design-principles.md` 一致：

1. **换肤**：浅色优先 + 青绿 `#0E9F86` 主色 + 翡翠 `success`，并保留暗色主题。
2. **补导航**：底部 Tab Bar（会话 / 文件 / 设置），聊天详情保持 push。
3. **精修**：会话列表按 `cwd` 分组（含分组头折叠/吸顶）、聊天详情对齐 token + 预留语音输入。
4. **去硬编码**：runtime 身份抽成可配置映射，新增 agent 只加一行不改布局。

**不在范围**：文件浏览/查看器（M2）、语音 ASR 实际识别（🟡 待定，本期只做 UI 占位）、
后端改动（分组用现有 `cwd` 字段，纯前端重排）。

---

## 2. 现状诊断（gap 清单）

| # | 现状 | 问题 | 对应设计 |
|---|---|---|---|
| 1 | `ui/theme/Color.kt` 是模板紫色（Purple80/40）；`Theme.kt` 开着 `dynamicColor=true` | 无任何配色落地，且 Android 12+ 被系统壁纸色覆盖 | §4.1 |
| 2 | `ui/theme/Type.kt` 只定义了 `bodyLarge` | 缺 §4.2 语义字号（title/body/caption/code） | §4.2 |
| 3 | 无底部 Tab Bar：`MAIN`=会话列表，设置是 push 路由，`FILE` 路由有定义但未注册 | 信息架构不符 | §3 |
| 4 | 会话列表分组已实现：根目录平铺 + 子目录分组（相对路径、默认折叠），`WorkspaceHeader` 含 folder 图标 / 会话数徽章 / 折叠 / 吸顶 | 已对齐 §6.2（2026-09） | §6.2 |
| 5 | `RuntimeBadge` 硬编码 `when(runtime)` | 新增 agent 需改布局代码 | §5.2 |
| 6 | `ChatScreen` 输入区无麦克风；工具卡状态用 emoji（✓✗）；气泡/代码块用 M3 默认色 | 未对齐 token、未预留语音 | §6.3 / §7.6 |
| 7 | 未引入 `material-icons-extended` | 语义图标无图标源 | §4.4 |

**已具备、无需重做**：MVVM 分层、DTO 与 `api.ts` 对齐、SSE 流式渲染、审批弹窗、断线重连、
会话删除/重命名、新建会话。

---

## 3. 适配总原则（对齐 CLAUDE.md）

1. **`docs/ui/design-principles.md` 是唯一设计源**：token 值从 §4.1/§4.2/§4.3 直接抄。
2. **严格 MVVM**：UI 层不碰网络；token 归 theme 层；列表分组建议下沉 ViewModel（见 §10-4）。
3. **runtime 身份可配置映射**：抽独立映射表，新增 agent 只加一行不改布局。
4. **一个文件一个顶层公开类型**：token 用 `object`，映射用 data class + object 表。
5. **语义优先**：UI 用语义 token/语义图标，不硬编码色值与 emoji。

---

## 4. Design Token → Compose 落地

### 4.1 色彩（双轨制：`DesignColors` + Material3 colorScheme）

Material3 的 `ColorScheme` **没有** `success` / `warning` / `thinking` 槽位，因此采用双轨：

- `object DesignColors`：持有**全部**语义 token（含 M3 没有的 success/warning/thinking/code-bg 等）。
- `Theme.kt`：把 `DesignColors` 映射进 `lightColorScheme` / `darkColorScheme` 的标准槽位。

**语义 token 表（值 = §4.1，浅色默认）**：

| 语义 token | 浅色 | 深色 | 说明 |
|---|---|---|---|
| `bg` | `#F6F8F7` | `#0E1512` | 页面背景 |
| `surface` | `#FFFFFF` | `#17201C` | 卡片/输入框 |
| `surface2` | `#FFFFFF` | `#1E2823` | 悬浮/Sheet（配阴影） |
| `border` | `#E3EAE6` | `#293430` | 分隔线/描边 |
| `textPrimary` | `#16211D` | `#E7EFEB` | 主文字 |
| `textSecondary` | `#5A6B64` | `#A2B0AA` | 次文字 |
| `textMuted` | `#93A29A` | `#6D7C75` | 弱化/占位 |
| `primary` | `#0E9F86` | `#2BC7A4` | 主按钮/高亮（青绿） |
| `primaryDeep` | `#0A7D6B` | `#4AD8B8` | 按压/激活 |
| `primarySubtle` | `#E2F3EE` | `#12332B` | 选中底色/胶囊 |
| `success` | `#18A058` | `#4CC38A` | 成功/完成（翡翠绿） |
| `warning` | `#B7791F` | `#E0B14A` | 警告/进行中 |
| `error` | `#D6423E` | `#F26D6D` | 错误/失败/危险 |
| `thinking` | `#9AA8A2` | `#85938C` | 思考中文字 |
| `codeBg` | `#F2F5F3` | `#101815` | 代码/工具输出底色 |

**→ Material3 colorScheme 槽位映射**（`Theme.kt`）：

| M3 槽位 | 浅色取值 | 深色取值 |
|---|---|---|
| `primary` / `onPrimary` | `primary` / `#FFFFFF` | `primary` / `#06231A` |
| `primaryContainer` / `onPrimaryContainer` | `primarySubtle` / `primaryDeep` | `primarySubtle` / `primaryDeep` |
| `background` / `onBackground` | `bg` / `textPrimary` | `bg` / `textPrimary` |
| `surface` / `onSurface` | `surface` / `textPrimary` | `surface` / `textPrimary` |
| `surfaceVariant` / `onSurfaceVariant` | `codeBg` / `textSecondary` | `codeBg` / `textSecondary` |
| `outline` / `outlineVariant` | `border` / `border` | `border` / `border` |
| `error` / `onError` | `error` / `#FFFFFF` | `error` / `#FFFFFF` |
| `errorContainer` | `#FBEDED` | `#3A211F` |

> `secondary` / `tertiary` 本期从简：`secondary = primaryDeep`，`tertiary = Claude 暖橙 #D97757`
> （仅作少数字段点缀，不用于主按钮）。`success` / `warning` / `thinking` 由 UI 从
> `DesignColors` 直取，不进 colorScheme。

**必须关闭 dynamicColor**：`PabootTheme` 里去掉 `dynamicColor` 分支，否则 Android 12+
会把青绿主色换成系统壁纸色。

### 4.2 字体（`Type.kt`）

按 §4.2 语义角色补全 `Typography`，映射到 M3 风格槽位：

| 语义角色 | 字号 | 字重 | 落到 M3 style |
|---|---|---|---|
| 页面标题 | 20sp | SemiBold | `titleLarge` |
| 列表/卡片标题 | 16sp | SemiBold | `titleMedium` |
| 正文 | 14sp | Regular | `bodyMedium` |
| 辅助说明 | 12sp | Regular | `labelMedium` / `bodySmall` |
| 代码/路径/token/命令 | 13sp | Regular + `FontFamily.Monospace` | 独立 `codeBody` 语义样式（自定义扩展） |

> 建议新增 `Typography.codeBody`（13sp Monospace），供代码块/路径/命令/工具输出统一使用，
> 替代现在散落的 `bodySmall.copy(fontFamily = FontFamily.Monospace)`。

### 4.3 间距与圆角（`Dimens.kt`）

`object DesignDimens`：

- 基准网格 `4.dp`；间距 `Space8 / Space12 / Space16 / Space20 / Space24`
- 圆角 `RadiusCard = 12.dp`、`RadiusBubble = 16.dp`、`RadiusSheetTop = 24.dp`
- 最小触控 `MinTouch = 48.dp`

### 4.4 质感与层次（`Elevation.kt` + `Brand.kt`）

- `object DesignElevation`：三层阴影（值 = §4.1.1）
  - `elevation1 = 0 1px 2px rgba(22,33,29,.06)`
  - `elevation2 = 0 8px 24px rgba(22,33,29,.10)`
  - `elevation3 = 0 16px 48px rgba(22,33,29,.18)`
  - 落地用 `Modifier.shadow(elevation = …, shape = …)` 或 `AmbientColor`，卡片默认 `elevation1`，Sheet/浮层 `elevation2/3`。
- `object Brand`：
  - `brandGradient = Brush.linearGradient(0f to #0E9F86, 1f to #18C9A6)`
  - 仅用于 Logo、FAB、主 CTA 焦点态、连接页 hero 点缀；**不滥用**。

---

## 5. 图标体系 + Runtime 身份

### 5.1 图标

- `libs.versions.toml` / `app/build.gradle` 加 `androidx.compose.material:material-icons-extended`
  （版本随 compose BOM）。
- 用 `Icons.Rounded.*` 对应 §4.4 的 Material Symbols（Rounded）。
- 语义名 → 图标映射表（§4.4 + §5.1）：

| 语义名 | Material Icons Extended | 语义名 | Material Icons Extended |
|---|---|---|---|
| `chat_bubble` | `Icons.Rounded.ChatBubble` | `terminal` | `Icons.Rounded.Terminal` |
| `folder` | `Icons.Rounded.Folder` | `edit` | `Icons.Rounded.Edit` |
| `settings` | `Icons.Rounded.Settings` | `article` | `Icons.Rounded.Article` |
| `send` | `Icons.AutoMirrored.Rounded.Send` | `search` | `Icons.Rounded.Search` |
| `microphone` | `Icons.Rounded.Mic` | `shield` | `Icons.Rounded.Shield` |
| `mic_off` | `Icons.Rounded.MicOff` | `content_copy` | `Icons.Rounded.ContentCopy` |
| `sync` | `Icons.Rounded.Sync` | `smart_toy` | `Icons.Rounded.SmartToy` |
| `add` | `Icons.Rounded.Add` | `check` / `close` | `Icons.Rounded.Check` / `Close` |
| `spinner` | `CircularProgressIndicator`（非图标） | `stop` | `Icons.Rounded.Stop` |

### 5.2 Runtime 身份（`ui/identity/RuntimeIdentity.kt`）

抽成**可配置映射**，替换 `SessionListScreen` / `ChatScreen` 里硬编码的 `when(runtime)`：

```kotlin
data class RuntimeIdentity(
    val id: String,
    val displayName: String,
    val icon: ImageVector,      // Icons.Rounded.*
    val color: Color,
)

object RuntimeIdentities {
    private val table = mapOf(
        "claude" to RuntimeIdentity("claude", "Claude Code", Icons.Rounded.SmartToy, Color(0xFFD97757)),
        // 未来新增：codex / opencode / deepseek-harness 各加一行
    )
    val fallback = RuntimeIdentity("?", "agent", Icons.Rounded.Build, DesignColors.textMuted)
    fun of(id: String): RuntimeIdentity = table[id] ?: fallback.copy(id = id, displayName = id.ifBlank { "agent" })
}
```

出现位置：会话列表卡片、聊天页顶栏、新建会话 agent 选择器、设置页默认 agent。

---

## 6. 导航结构（底部 Tab Bar）

目标信息架构（§3）：

```
connect（门禁，startDestination）
  └─ main（底部 Tab Bar 容器：会话 / 文件 / 设置）
        ├─ 会话 tab  ──点击卡片──▶ conversation（push，进入后 Tab 隐藏）
        ├─ 文件 tab（M2 占位）
        └─ 设置 tab
```

- 新增 `ui/MainScreen.kt`：`Scaffold(bottomBar = NavigationBar { 会话/文件/设置 })`，
  内含一个 `NavHost`（或 `rememberSaveable` 的选中态 + 三个 composable）。
- `navigation/MainNavGraph.kt`：`MAIN` → `MainScreen`；`CONVERSATION` 保持 push；
  `FILE` 由 `AppRoutes.FILE` 收进 tab（不再 push）；设置从 push 改为 tab。
- `AppRoutes.kt`：`navigateToSettings` 相应调整；新增 tab 枚举。
- 聊天详情进入后隐藏底部 Tab（push 页自带顶栏返回键，符合「二级页不显示 Tab」）。

---

## 7. 分阶段实现方案

### P0 · 主题地基（先做，一切的前提）

**改动文件**：
- 重写 `ui/theme/Color.kt` → `object DesignColors`（light + dark 两套，§4.1 值）
- 重写 `ui/theme/Theme.kt` → 关闭 dynamicColor；`lightColorScheme`/`darkColorScheme` 映射（§4.1）
- 补全 `ui/theme/Type.kt` → §4.2 语义字号 + `codeBody`
- 新增 `ui/theme/Dimens.kt`、`ui/theme/Elevation.kt`、`ui/theme/Brand.kt`

**验收**：编译通过；全 App 颜色从紫色变青绿浅色；切暗色后为深绿黑。

### P1 · 图标 + Runtime 身份 + 公共组件

**改动文件**：
- `gradle/libs.versions.toml`、`app/build.gradle`（加 `material-icons-extended`）
- 新增 `ui/identity/RuntimeIdentity.kt`
- 新增 `ui/component/AgentBadge.kt`、`ConnectionStatusCapsule.kt`、`RunningIndicator.kt`

**验收**：编译通过；公共组件可用，替换散落的硬编码。

### P2 · 底部 Tab Bar 导航骨架

**改动文件**：
- 新增 `ui/MainScreen.kt`（Tab 容器）
- 重构 `navigation/MainNavGraph.kt`、`navigation/AppRoutes.kt`
- 新增 `ui/FilesScreen.kt`（M2 占位：「文件」Tab）

**验收**：底部三 Tab 可切换；聊天详情 push 后 Tab 隐藏、返回恢复；设置从 push 变 tab。

### P3 · 会话列表分组精修（重点，改动小见效快）

**改动文件**：`ui/SessionListScreen.kt`（+ 可选 `viewmodel/SessionListViewModel.kt`）

- `WorkspaceHeader` 升级：`folder` 图标 + 路径（等宽）+ 会话数徽章 + 折叠箭头
- 用 `LazyColumn` 的 `stickyHeader` 实现吸顶；点击折叠/展开（`rememberSaveable` 记住状态）
- `RuntimeBadge` → `AgentBadge`（P1）
- 卡片颜色对齐 token（`elevation1` 阴影 + `border` 描边 + running 时 `primary` 描边）
- 可选：`groupByCwd` 下沉 `SessionListViewModel`，产出 `List<WorkspaceGroup>` 进 UiState（§10-4）

**验收**：分组头吸顶/折叠/徽章齐全；卡片无 cwd 重复；running 卡片脉冲 + primary 描边。

> ✅ 已实现并调整（2026-09）：分组逻辑下沉 `SessionListViewModel`，产出 `SessionListModel`
> （根目录会话平铺 + 子目录分组）；子目录头显示相对路径、默认折叠；根目录会话置顶。

### P4 · 聊天详情 + 其余页面精修

**改动文件**：
- `ui/ChatScreen.kt`：输入区左侧加麦克风占位（§7.6）；气泡/思考块/工具卡/用量条对齐 token；
  工具卡状态 emoji → 语义图标（`check`/`stop`/spinner）；审批弹窗对齐（shield 图标 + 三键权重）
- `ui/ConnectScreen.kt`：渐变 Logo + hero、输入框 focus 青绿描边、主按钮全宽
- `ui/NewSessionSheet.kt`：radio 选中青绿（`primary` + `primarySubtle`）
- `ui/SettingsScreen.kt`：分组卡片、语义图标、只读信息

**验收**：各屏目视与 `docs/ui/preview.html` 原型一致；语音按钮占位可见（不接识别）。

---

## 8. 实现顺序

```
P0 主题地基 → P1 图标/身份/组件 → P2 Tab 导航骨架 → P3 会话列表分组 → P4 聊天+其余屏
```

**理由**：主题是横切依赖（不做，后面全白调）；图标/身份是组件依赖（会话列表和聊天都要用）；
导航决定页面怎么进；会话列表是已确认重点且改动小、见效快；聊天工作量大放最后；语音只做占位。

---

## 9. 架构合规对照（交付自检）

| 规范要求 | 落地动作 |
|---|---|
| UI 层不碰网络 | 保持 repository/ViewModel 边界；P3 分组下沉 VM |
| 单一设计源 | token 值从 `docs/ui/design-principles.md` §4.1/4.2/4.3 直抄 |
| runtime 可配置映射 | §5.2 `RuntimeIdentities`，新增 agent 只加一行 |
| 一个文件一个顶层公开类型 | token 用 `object`，映射用 data class + object |
| DTO 与 api.ts 对齐 | 现有已对齐，本次不改 DTO |
| 图标用语义名 | §5.1 映射表，UI 不写 emoji 状态 |
| 提交规范 | §11 Conventional Commits |

---

## 10. 待决策项（开工前拍板）

| # | 决策点 | 选项（推荐加粗） |
|---|---|---|
| 1 | 主题策略 | **默认浅色 + 跟随系统** / 设置页三态（浅/暗/系统） |
| 2 | 图标依赖 | **引入 `material-icons-extended`**（体积 +数 MB）/ 自绘 ImageVector |
| 3 | 语音输入 | **本期只做麦克风占位 UI + `RECORD_AUDIO` 权限声明** / 本期不加留接口 |
| 4 | 分组逻辑位置 | **下沉 `SessionListViewModel`**（更纯 MVVM）/ 保持 UI 层 `groupByCwd` |

---

## 11. 提交规范（Conventional Commits，中文 subject）

分阶段提交，每阶段一个 commit：

```text
feat(android): 落地浅色青绿主题 token & 关闭 dynamicColor      # P0
feat(android): 引入 material-icons-extended & 抽 RuntimeIdentity 映射   # P1
feat(android): 重构底部 Tab 导航（会话/文件/设置）            # P2
feat(android): 会话列表按 cwd 分组头折叠吸顶 & 会话数徽章      # P3
feat(android): 聊天输入区预留语音 & 工具卡对齐语义图标         # P4
```

> subject 只描述主要改动，多角度用 `&` 连接，不加句号。
