# 聊天历史分页加载（技术方案 · 待实施）

> 状态：**方案已定，尚未实施**。本文只描述目标方案与落地清单，不含实现。
> 触发缘由：进入会话首屏要等（`ChatViewModel.reconstructHistory` 逐 run 串行拉事件）。
> 已做的临时缓解：加载期间消息区显示转圈 + 「正在加载对话…」（2026-09-15），**只把等待可视化，没有减少等待**。

## 1. 问题

当前进入一个已有会话的流程：

```
GET /api/sessions/:id          → session + messages + runs（不含事件）
GET /api/runs/:runId/events    → 第 1 个 run 的事件
GET /api/runs/:runId/events    → 第 2 个 run 的事件
...                            → 第 N 个 run 的事件
```

两个问题：

1. **N+1 次串行请求**：20 轮的会话 = 21 次 HTTP 往返，一次接一次地等，首屏耗时随会话长度线性增长。
2. **全量拉取**：不管用户看不看，历史事件全都要拉回来再渲染。

目标形态：**首屏只取最近若干轮，用户往上翻时按需加载更早的**。

## 2. 目标与非目标

**目标**

- 首屏一次请求拿全（含渲染所需的全部事件）
- 向上滚动时按需分页加载更早的历史，前置插入不打乱视口
- 不破坏现有：流式跟随、回到底部、断线重连续流、运行中 run 的 attach、新会话创建

**明确不做**

- **不把 UI 块模型（思考块 / 工具卡 / 提问卡 / 用量）搬到 daemon 去重建**。那套映射
  （`ChatViewModel.buildAssistantFromEvents`）同时服务历史回放**和**实时流，搬到服务端等于
  在协议里冻住 UI 模型、并在两个仓库各维护一份实现。daemon 只负责"按页给事件"，重建仍在客户端。
- 不做 run 内部（消息级）分页；不做历史搜索；不做跨会话。

## 3. 协议设计（daemon）

### 3.1 新端点

```http
GET /api/sessions/:id/history?limit=20&before=<cursor>
```

响应：

```jsonc
{
  "session": { /* 同现有 SessionDto，含 running / runningRunId */ },
  "runs": [
    { /* 同现有 RunDto */, "events": [ /* SseFrame[]，与 /api/runs/:id/events 同形状 */ ] }
  ],
  "nextBefore": "123",   // 下一页游标；没有更早的时为 null
  "hasMore": false
}
```

约定：

- `limit`：**run 条数**（1 run = 1 轮 = 用户消息 + 助手回复），默认 20、上限 100。
- `runs` 按时间**升序**返回（与现在 `sessionDetail.runs` 一致），客户端可直接顺序拼接。
- **游标**：`before = <run 的 rowid>`（不透明字符串）。服务端实现为
  `SELECT ... WHERE session_id = ? AND rowid < ? ORDER BY rowid DESC LIMIT ?`，取完再反转成升序。
  用 rowid 而不是 `started_at`：同毫秒创建的 run 按 `started_at` 排序结果不确定，rowid 与插入顺序严格一致。
- **事件默认内嵌**：分页的意义就是"一次一页"，不再逐 run 拉。若将来要瘦身再加 `withEvents=0`。
- 正在运行的 run 照常返回（事件可能不完整），客户端仍按现有逻辑 `attach(runId)` 续流。
- 首页调用不传 `before`。

### 3.2 保留的旧端点

- `GET /api/sessions/:id`：**保持不动**（兼容旧客户端；新客户端首屏改走 `/history`）。
- `GET /api/runs/:id/events`：保留。用于旧客户端回退路径，以及将来可能的单 run 重放。

### 3.3 契约同步

- `airemote-daemon/src/types/api.ts`：新增 `HistoryResponse`；`RunDto` 增加可选 `events?: SseFrame[]`。
- `airemote-android/lib-network/.../airemote/dto/`：新增对应 DTO，字段名/类型/可空性与 `types/api.ts` 一一对齐。
- 按仓库约定：改 `types/api.ts` 必须同步 Android 侧 DTO。

## 4. 客户端设计（Android）

### 4.1 前提：消息要有稳定 key

现状 `ChatUiMessage` 没有 id，`MessageList` 用 `key = { index }`（`MessageList.kt`）。**前置插入时
index 会整体位移**，index key 会让 Compose 把"刚插入的旧消息"当成原有 item 复用，结果是串位、闪烁、
滚动位置乱跳。

改法：每条消息带稳定 id —— `runId` + 角色（`"<runId>:u"` / `"<runId>:a"`）；无 run 的兜底历史用
`"legacy:<n>"`。`MessageList` 的 `items(key = ...)` 改用该 id。

### 4.2 视口锚定（选定的方案 A）

保留现在的 `reverseLayout = false`：

1. 触发加载前，记下 `listState.firstVisibleItemIndex` 与 `firstVisibleItemScrollOffset`；
2. 前置插入完成后 `scrollToItem(原 index + 本次插入条数, 原 offset)`。

为区分"前置插入"和"尾部追加"，`ChatViewModel` 暴露一个 `prependCount`（最近一次是往头部插了多少条，
UI 消费后清零）；只有 `prependCount > 0` 时才做锚定补偿。

> 备选方案 B（不采用）：整表改成 `reverseLayout = true`，前置插入天然不动视口。但整套
> 「跟随底部 / 回到底部 / 流式跟随」逻辑（`MessageList.kt` 里的 followBottom / DragInteraction 判定）
> 都建立在 `reverseLayout = false` 上，要一起重写，风险大于收益。将来若重做聊天列表可再评估。

### 4.3 触发时机

- 自动：`firstVisibleItemIndex <= 2 && canScrollBackward && hasMore && !loadingOlder` → 拉上一页。
- 手动兜底：列表顶部常驻一行「加载更早的消息」（同时也是加载中的转圈位）；`hasMore == false` 时
  显示「没有更早的消息」或整行隐藏（实施时定）。

### 4.4 必须处理的冲突点

- **`MessageList.kt` 现在只要 `messages.size` 变化就 `scrollToItem(bottom)`**（新消息跳到底部用的）。
  前置插入同样会改变 size，会把正在看历史的用户**拽回底部**。必须改成只在**尾部追加**（新 run、
  首屏加载完成）时跳底部。
- 新会话（`sessionId == null`）：不发 history 请求，保持现状。
- 只有聚合 `messages`、没有 runs 的遗留会话：走现有兜底渲染，不参与分页。
- 加载失败：保留已渲染内容 + snackbar 报错，游标不前进，允许用户重试。
- `finish()` / `stop()` / 权限队列等以 run 为单位的状态不受分页影响（分页只动更早的 run）。

### 4.5 兼容旧 daemon

app 会自动更新、daemon 要手动更新，**两端版本必然错开**：

- 客户端先请求 `/history`；返回 404（旧 daemon）时回退到现有 `sessionDetail` + 逐 run `runEvents`
  的老路径。**老路径代码保留，不删。**
- 服务端不需要为旧客户端做兼容（旧客户端不会调新端点）。

## 5. 改动清单（预估）

| 位置 | 改动 | 量级 |
| --- | --- | --- |
| `daemon/src/db.ts` | `listRunsPage(sessionId, beforeRowid, limit)`；按 run 批量取事件 | ~25 行 |
| `daemon/src/routes/sessions.ts` | 新增 `GET /api/sessions/:id/history` | ~30 行 |
| `types/api.ts` + Android `dto/` | `HistoryResponse`、`RunDto.events` | ~20 行 |
| `ChatViewModel` | 首屏改用 history；游标 / hasMore / prependCount；旧路径回退 | ~60 行 |
| `ChatUiMessage` + `MessageList` | 稳定 key、锚定、顶部加载行、修 "size 变化 → 跳底部" 的触发条件 | ~60 行 |
| `docs/ui_design.md` §6.3 | 补「加载更早的消息」状态与交互 | 几行 |

## 6. 验收

- 首屏请求数 = **1**（看 daemon 日志）；20+ 轮的会话首屏明显变快。
- 滚到顶部触发加载后，**屏幕上原有内容位置不动**（不跳、不闪）。
- 流式回复仍自动跟随底部；用户上滑离开底部后不被打断；「回到底部」按钮正常。
- 断线重连、运行中 run 的续流不受影响。
- 连旧 daemon（无 `/history`）时功能不退化（走回退路径）。
- daemon 单测覆盖分页边界：首页 / 末页 / `hasMore` / 空会话 / 单 run / 游标不跳页不重复。

## 7. 分期

- **P0**：协议 + 首屏一次取最近 20 轮（含事件）。**这一步就能消掉 N+1**，收益最大、UI 风险最小。
- **P1**：向上滚动加载更早 + 顶部「加载更早的消息」行 + 锚定（4.1/4.2/4.3 的完整形态）。
- **P2**（可选）：`limit` 自适应；不含事件的历史行懒加载；已归档 run 的事件压缩。
