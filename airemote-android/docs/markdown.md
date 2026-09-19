# 聊天 Markdown 渲染

> 状态：已实现。把 AI（Claude）回复的 Markdown 在聊天正文里渲染成富文本，并支持流式追加。
> 相关代码：`app/src/main/java/com/airemote/airemote/ui/chat/ContentBlocks.kt`、`MessageList.kt`。
> 上游参考：`mikepenz/multiplatform-markdown-renderer`（本地 clone 可用来查源码/API）。

---

## 1. 目标与范围

- 助手回复正文是 Markdown（标题 / 列表 / 粗斜体 / 行内代码 / 代码块 / 链接 / 引用 / 表格），需要渲染成富文本。
- 必须支持**流式**：token 逐个追加时实时排版，且不能闪。
- 只作用于助手正文块 `ContentBlock.Text`；思考块、工具卡片、用户消息保持原样。
- 主题跟随 `docs/ui_design.md` 的 Design Token（青绿主色系）。

## 2. 选型

| 候选 | 结论 |
|---|---|
| `com.mikepenz:multiplatform-markdown-renderer` | **采用**——Compose 原生，`-m3` 变体自带 Material3 主题 |
| Markwon（`io.noties.markwon`） | 否——View 库，Compose 里要 `AndroidView` 包 `TextView`；上游 2021-03 后停更 |
| `com.halilibo.compose-richtext` | 否——维护一般 |

最终使用 `com.mikepenz:multiplatform-markdown-renderer-m3`，版本 **0.29.0**。

### 2.1 版本是硬约束（最重要的选择依据）

该库是 Compose Multiplatform（KMP）库，**它编译时用的 Kotlin 版本必须能被项目的 Kotlin 编译器读取**。

- 0.45.0 依赖 `kotlin-stdlib 2.4.10`，项目是 Kotlin 2.1.0 → 编译期报 "compiled by a newer Kotlin"，直接编不过。
- 0.29.0 的 `gradle/libs.versions.toml` 是 `kotlin = "2.1.0"`、`compose = "1.7.6"`，与项目完全对齐。

> 查法：`git show <tag>:gradle/libs.versions.toml`。不要只看 README 里的版本号。
> 另：只有 `-m3` / `-m2` 变体带主题默认值，core 模块不含。

## 3. 接入步骤

### 3.1 加依赖

`gradle/libs.versions.toml`：

```toml
[versions]
markdownRenderer = "0.29.0"

[libraries]
markdown-renderer-m3 = { group = "com.mikepenz", name = "multiplatform-markdown-renderer-m3", version.ref = "markdownRenderer" }
```

`app/build.gradle`：

```groovy
implementation libs.markdown.renderer.m3
```

### 3.2 渲染入口

助手正文块原先是一个普通 `Text`，现在换成 `TextBlock(text, streaming)`：

```kotlin
@Composable
internal fun TextBlock(text: String, streaming: Boolean) {
    // 全程都用 Markdown 渲染：流式期间若先纯文本、结束再排版，会在结束时整体重排"闪一下"。
    // 流式期间正文每帧都在变，所以按 ~200ms 采样，限制 Markdown 的重解析频率。
    val content = if (streaming) rememberSampledText(text) else text
    // 自定义表格组件；remember 住，避免每次重组都新建 MarkdownComponents 连累整棵树重组
    val components = remember { markdownComponents(table = { model -> ChatMarkdownTable(model) }) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        // 设计规范：助手气泡用 surface（白）；代码/表格才用 code-bg
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.widthIn(max = 320.dp),
    ) {
        Markdown(
            content = content,
            colors = chatMarkdownColors(),
            typography = chatMarkdownTypography(),
            // 默认会给内容挂 animateContentSize()，流式下每 200ms 触发一次尺寸动画，看起来一直在闪
            animations = markdownAnimations(animateTextSize = { this }),
            components = components,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}
```

`MessageList.kt` 里传入是否仍在流式：

```kotlin
is ContentBlock.Text -> TextBlock(block.text, streaming = !message.done)
```

`message.done == false` 表示这条助手消息仍在被 token 追加。

### 3.3 流式节流

流式正文每帧都在变，如果每个 token 都重新解析 Markdown 会抖。用固定间隔采样最新文本（**节流**，不是 debounce）：

```kotlin
/** 每 [intervalMs] 采样一次最新文本；流式期间用它限制 Markdown 的解析频率。 */
@Composable
private fun rememberSampledText(text: String, intervalMs: Long = 200L): String {
    val latest = rememberUpdatedState(text)
    val sampled by produceState(initialValue = text) {
        while (true) {
            delay(intervalMs)
            value = latest.value
        }
    }
    return sampled
}
```

### 3.4 配色（跟随 Design Token）

```kotlin
/** Markdown 配色：跟随设计 token（代码/行内代码/表格统一 code-bg 底；行内代码与链接用主色文字）。 */
@Composable
private fun chatMarkdownColors() = markdownColor(
    text = MaterialTheme.colorScheme.onSurface,
    codeText = MaterialTheme.colorScheme.onSurface,
    inlineCodeText = MaterialTheme.colorScheme.primary,
    linkText = MaterialTheme.colorScheme.primary,
    codeBackground = MaterialTheme.colorScheme.surfaceVariant,
    inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant,
    dividerColor = MaterialTheme.colorScheme.outlineVariant,
    tableText = MaterialTheme.colorScheme.onSurface,
    tableBackground = MaterialTheme.colorScheme.surfaceVariant,
)
```

### 3.5 排版

库的 m3 默认把 `h1` 映射到 `displayLarge`（57sp）、正文 `bodyLarge`（16sp），手机上大得离谱，必须覆盖。约定：h1/h2 = 正文色加粗，h3 及以下 = 主色青绿。

```kotlin
@Composable
private fun chatMarkdownTypography() = markdownTypography(
    h1 = MaterialTheme.typography.titleMedium.copy(
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Bold,
    ),
    h2 = MaterialTheme.typography.titleMedium.copy(
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Bold,
    ),
    h3 = MaterialTheme.typography.titleSmall.copy(color = MaterialTheme.colorScheme.primary),
    h4 = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold),
    h5 = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold),
    h6 = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold),
    text = MaterialTheme.typography.bodyMedium,
    paragraph = MaterialTheme.typography.bodyMedium,
    ordered = MaterialTheme.typography.bodyMedium,
    bullet = MaterialTheme.typography.bodyMedium,
    list = MaterialTheme.typography.bodyMedium,
    code = CodeBody,
    inlineCode = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
    quote = MaterialTheme.typography.bodyMedium,
    link = MaterialTheme.typography.bodyMedium.copy(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
    ),
)
```

### 3.6 自定义表格组件

库默认的表格单元格是**单行 + 省略号**（长内容只剩 `…`），列宽固定，阅读体验差。通过覆盖 `table` 组件自定义：

```kotlin
val components = remember { markdownComponents(table = { model -> ChatMarkdownTable(model) }) }
```

```kotlin
/**
 * 自定义表格组件：库默认的单元格是单行 + 省略号（长内容只剩 "…"），且列宽固定不随内容，
 * 这里改成单元格最多 4 行换行、列宽固定以便各行列对齐、整表可横向滚动。
 */
@Composable
private fun ChatMarkdownTable(model: MarkdownComponentModel) {
    val content = model.content
    val baseStyle = model.typography.text
    val cellPadding = LocalMarkdownDimens.current.tableCellPadding

    Surface(
        color = LocalMarkdownColors.current.tableBackground,
        shape = RoundedCornerShape(LocalMarkdownDimens.current.tableCornerSize),
    ) {
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            model.node.children.forEach { section ->
                when (section.type) {
                    GFMElementTypes.HEADER, GFMElementTypes.ROW -> {
                        val header = section.type == GFMElementTypes.HEADER
                        Row(
                            modifier = if (header) {
                                // 表头用 primary-subtle 底色，和正文行区分开
                                Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                            } else {
                                Modifier
                            },
                        ) {
                            section.children.forEach { cell ->
                                if (cell.type != GFMTokenTypes.CELL) return@forEach
                                Text(
                                    text = content.buildMarkdownAnnotatedString(cell, baseStyle),
                                    style = if (header) baseStyle.copy(fontWeight = FontWeight.Bold) else baseStyle,
                                    color = if (header) MaterialTheme.colorScheme.onPrimaryContainer
                                            else LocalMarkdownColors.current.tableText,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.width(160.dp).padding(cellPadding),
                                )
                            }
                        }
                    }
                    GFMTokenTypes.TABLE_SEPARATOR -> HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}
```

涉及的公开 API（0.29.0 已核对）：`markdownComponents`、`MarkdownComponentModel`、`LocalMarkdownColors`、`LocalMarkdownDimens`、`String.buildMarkdownAnnotatedString(node, style)`，以及 GFM AST 类型 `org.intellij.markdown.flavours.gfm.GFMElementTypes/GFMTokenTypes`（库以 `api` 暴露）。

## 4. 流式渲染的三个关键决策

| 方案 | 结果 |
|---|---|
| debounce（等文本安静 N ms 再更新） | ❌ 流式文本一直在变，正文会**卡住不动** |
| 流式纯文本、结束再转 Markdown | ❌ 结束时整段重排，**明显闪一下** |
| **全程 Markdown + throttle（固定间隔采样）** | ✅ 实时排版，结束时只补最后一段增量，无缝 |

另外必须关掉库默认的尺寸动画：

```kotlin
animations = markdownAnimations(animateTextSize = { this })   // 默认是 { animateContentSize() }
```

否则每 200ms 内容一变就触发一次尺寸补间动画，动画叠加 = 一直闪。

## 5. 坑清单

| 现象 | 根因 | 解法 |
|---|---|---|
| 编译报 "compiled by a newer Kotlin" | 库版本要求的 Kotlin 高于项目 | 选与项目 Kotlin 对齐的库版本（本项目 0.29.0） |
| 标题巨大 | m3 默认 `h1=displayLarge(57sp)` | 自定义 `markdownTypography()` |
| 流式一直在闪 | 库默认给内容挂 `animateContentSize()` | `animations = markdownAnimations(animateTextSize = { this })` |
| 流式正文卡住 | 用了 debounce | 改 throttle（`produceState` + `rememberUpdatedState`） |
| 结束时整体重排 | 流式纯文本、结束才转 Markdown | 全程 Markdown + 节流 |
| 表格内容显示 `…`、看不到右边 | 库单元格硬编码 `maxLines=1 + Ellipsis`，列宽固定 | 覆盖 `table` 组件：换行 + 横向滚动 |
| 配色偏黑白 | 默认跟随 M3 | 传 `markdownColor(...)` 按设计 token |
| 标题颜色设了不生效 | `markdownColor()` 没有标题槽位 | 用 `TextStyle.copy(color = ...)` |
| `TextDecoration` unresolved | 包名记错 | 在 `androidx.compose.ui.text.style` 下 |
| 整个 markdown 树频繁重组 | 每次重组都新建 `MarkdownComponents` | `remember` 住 `components` |

## 6. 后续演进（真正的丝滑流式）

商用 app 的流式很顺，靠两点：**增量解析（只重解析尾部）+ 单一文本节点**（Spannable / AnnotatedString，更新只做文本增量布局）。
而 0.29.0 是**把每个块编成 Compose 节点 + 整段重解析**，所以再节流也有整块重排的观感。

库从 **0.42.0** 起提供 `rememberStreamingMarkdownState()` / `Flow<String>.collectAsStreamingMarkdownState()`，底层用 IntelliJ Markdown 的 `StreamingMarkdownFile` 做增量解析，是正解。
代价：0.42+ 需要 **Kotlin 2.3+ / 更新的 Compose**，要先升 Android 工具链（Kotlin + Compose BOM，可能连带 AGP）。
升级后本文件第 3.3 节的节流可替换为 `streamingState.append(delta)`。

## 7. 调试方法

- **adb 看实际效果**（比脑补靠谱）：
  ```bash
  ~/Android/Sdk/platform-tools/adb -s <serial> exec-out screencap -p > /tmp/screen.png
  ```
- **查库 API / 默认值直接读本地 clone**：`git show <tag>:<path>`，比网上搜准且快。
- 改配色/排版前先读 `docs/ui_design.md` §4 的 Design Token，别硬编码 hex。

## 8. 相关文件

| 文件 | 作用 |
|---|---|
| `gradle/libs.versions.toml` | `markdownRenderer = "0.29.0"`、`markdown-renderer-m3` |
| `app/build.gradle` | `implementation libs.markdown.renderer.m3` |
| `.../ui/chat/ContentBlocks.kt` | `TextBlock`、`rememberSampledText`、`chatMarkdownColors`、`chatMarkdownTypography`、`ChatMarkdownTable` |
| `.../ui/chat/MessageList.kt` | 传 `streaming = !message.done` |
| `docs/ui_design.md` | 配色 / 字号 Design Token 来源 |
