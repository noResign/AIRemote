# 自定义皮肤

桌面端的皮肤机制有意做得「薄」：**加一套皮肤 = 改几行代码**，不需要动架构。
本文件就是那份说明。想加皮肤的人照下面走即可，不用读别处。

## 先分清两个轴

| 轴 | 管什么 | 落在哪 |
|---|---|---|
| `theme` | **颜色**（浅色 / 深色 / 跟随系统） | `data-theme` → `ui/tokens.css` 的 CSS 变量 |
| `skin` | **动效层**（背景动画、鼠标波纹…） | `data-skin` + `ui/SkinFx.tsx` 挂载的 canvas |

皮肤**不是**「另一套配色」，而是**叠在明暗之上的一个特效层**。两者独立：选了动效皮肤，
底色仍由用户的浅色/深色设置决定。所以「只换色、不加动效」也是一套合法皮肤（`fx: 'none'`）。

## 加一套「只换配色」的皮肤

两步：

**1. 在 `src/renderer/src/ui/tokens.css` 写一组变量**

```css
:root[data-skin='neon'] {
  --bg: #0b0b14;
  --surface: #14142a;
  --primary: #ff2fb0;
  /* …想覆盖哪个 token 就写哪个；没写的沿用当前 theme 的值 */
}
```

**2. 在 `src/renderer/src/ui/skins.ts` 的 `SKINS` 加一行**

```ts
export const SKINS = [
  { id: 'default', label: '默认', fx: 'none' },
  { id: 'ripple',  label: '涟漪', fx: 'ripple' },
  { id: 'neon',    label: '霓虹', fx: 'none' },   // ← 新增
] as const satisfies readonly SkinEntry[];
```

`SkinId` 是从 `SKINS` **推导**出来的，所以不用另外声明类型。改完保存，设置页
「外观 → 皮肤」里就会出现这一项，**挂载、持久化、降级全部自动生效**。

## 加一套「带动效」的皮肤

三步（配色可选，同上）。

**1. 写一个特效实现**，放 `src/renderer/src/ui/fx/<名字>.ts`：

```ts
/** 返回一个 disposer：必须真正停掉自己（取消 rAF、摘掉所有监听）。 */
export function startMyFx(canvas: HTMLCanvasElement): () => void {
  // …在 canvas 上画东西…
  return () => {
    // 清理
  };
}
```

**2. 在 `src/renderer/src/ui/SkinFx.tsx` 注册**：

- 给 `FxId` 加上 `'myfx'`（在 `ui/skins.ts` 里）
- 在 `EFFECTS` 表里挂一行：

```ts
const EFFECTS: Partial<Record<FxId, (canvas: HTMLCanvasElement) => () => void>> = {
  ripple: startRipple,
  myfx: startMyFx,   // ← 新增
};
```

**3. 在 `SKINS` 里用上它**：`{ id: 'my', label: '我的', fx: 'myfx' }`。

现成的参考实现是 `ui/fx/ripple.ts`（鼠标涟漪），它把下面这些约定都示范了一遍。

## 写 `fx` 实现要遵守的约定

- **必须返回一个能停干净自己的 disposer**（`cancelAnimationFrame` + `removeEventListener`）。
  `SkinFx` 靠它做三件事：窗口切后台时暂停、切换皮肤时卸载、React StrictMode 下反复挂载——
  留着一个还在跑的循环或监听，就会漏。
- **鼠标事件监听 `window`，不是 canvas**：特效层是 `pointer-events: none`，canvas 收不到事件。
- **颜色别每帧读**。从 `getComputedStyle(canvas).getPropertyValue('--primary')` 取一次缓存起来，
  用 `MutationObserver` 盯 `document.documentElement` 的 `data-theme` / `data-skin` 变化再重读
  （`getComputedStyle` 可能触发样式重算，每帧一次是纯浪费）。
- **空闲时不要空转 rAF**。没有东西要画就停掉循环，等 `pointermove` 之类的输入再唤醒；
  桌面端一开一整天，空转的帧就是电池。`ripple.ts` 里的 `schedule()` 就是这个模式。
- **分辨率按 `devicePixelRatio` 缩放**，否则高分屏上会糊。

## 页面上新增「铺满屏」的容器时

页面铺底写的是 `--page-bg`，不是 `--bg`：有特效时它会被设成 `transparent`（见 `tokens.css`），
好让画布透出来。所以**新加一个铺满屏的容器时，背景用 `var(--page-bg)`**，否则它会把特效层挡在下面——
这正是最初 `.main` / `.chat-scroll` 挡住涟漪的原因。

区分两者：

- `--page-bg`：**页面底色**（该让特效透出来）。
- `--bg`：叠在 surface 之上的**小控件**（只读框、徽章）的底色，要保持不透明——它得盖住背后的东西。

## 这些不用你管（`SkinFx` 已处理）

- `prefers-reduced-motion: reduce` 时整个特效层**不挂载**——不是「挂载了但不动」。
- 窗口隐藏（`visibilitychange`）时拆掉特效，切回来重建。
- 皮肤选择的持久化（localStorage）、`data-skin` 的同步、设置页的列表。

## 三处扩展点，别的都别碰

| 想改 | 改这 |
|---|---|
| 配色 | `ui/tokens.css` → `:root[data-skin='<id>'] { … }` |
| 有哪些皮肤 | `ui/skins.ts` → `SKINS` 一行 |
| 特效行为 | `ui/fx/<name>.ts` + `ui/SkinFx.tsx` 的 `EFFECTS` 表 |

`App.tsx`、`store/appearance.ts`、`SettingsPage.tsx` 里的皮肤相关代码是通用管线，
加皮肤**不需要**动它们。
