# Files Tab 产品技术方案

> 状态：Draft  
> 范围：Files Tab 的“改动文件”视图，基于 Workspace + `git status`  
> 关联文档：`docs/ui_design.md`、`airemote-daemon/docs/daemon.md`

## 1. 目标

Files Tab 第一版不解决“完整文件管理器”，而是解决：

1. 查看当前 Workspace 下有哪些未提交改动；
2. 按文件查看 diff；
3. 切换 Workspace 后自动刷新；
4. 不区分改动来自人还是 AI；
5. 大项目下不递归扫描全部文件，不引入明显开销。

核心原则：

> Files Tab 默认打开的是“改动”，不是“全部文件”。

## 2. 产品形态

### 2.1 页面结构

```text
Files
  ├─ 顶部：当前 Workspace / 仓库状态
  ├─ 分段：改动（默认） | 全部文件（后续 milestone，置灰）
  ├─ 刷新
  └─ 改动列表
```

改动列表项：

```text
src/auth/token.ts        M    +18 -4
src/auth/session.ts      A    new file
src/config/old.json      D
src/utils/rename.ts      R
```

字段：

| 字段 | 说明 |
|---|---|
| path | 相对 Workspace 的路径 |
| oldPath | rename 时的原路径 |
| status | `modified` / `added` / `deleted` / `renamed` / `untracked` / `conflicted` |
| staged | 是否已 staged |
| additions / deletions | 可选，来自 `git diff --numstat` |
| binary | 是否二进制文件 |
| updatedAt | 文件 mtime（可选） |

点击文件 → 进入 Diff 查看器：

- 等宽字体；
- 行级 +/- 颜色；
- 长按复制；
- 支持大文件截断；
- 二进制显示“暂不支持预览”。

### 2.2 非 Git Workspace

如果 Workspace 不是 Git 仓库：

- 改动 Tab 显示空态：
  - “当前工作区不是 Git 仓库，无法生成改动列表”；
- 后续“全部文件”Tab 仍可独立工作。

### 2.3 子模块 / 嵌套仓库

第一版不处理子模块。

如果 Workspace 下有嵌套 Git 仓库：

- 以 Workspace 对应的主仓库为准；
- 嵌套仓库会显示为普通目录/untracked 目录，不递归展开。

## 3. daemon 技术设计

### 3.1 Git 探测

请求时对 Workspace 路径执行：

```bash
git -C <workspacePath> rev-parse --show-toplevel
```

结果：

- 成功：得到 `gitRoot`；
- 失败：`isGitRepo = false`；
- 命令不存在：返回 `git_unavailable`。

约束：

- `workspacePath` 必须来自已注册且启用的 Workspace；
- 所有 path 参数必须是相对 Workspace 的路径；
- 禁止直接把客户端传入的绝对路径交给 git。

### 3.2 改动列表

使用：

```bash
git -C <gitRoot> status --porcelain=v2 -z --untracked-files=normal
```

为什么用 porcelain v2：

- 机器可读；
- 支持 rename / staged / untracked；
- `-z` 处理包含空格 / 特殊字符的文件名。

状态映射：

| porcelain | 产品状态 |
|---|---|
| `.M` / `M.` / `MM` | modified |
| `A.` / `.A` | added |
| `D.` / `.D` | deleted |
| `R.` / `.R` | renamed |
| `??` | untracked |
| `UU` / `AA` / `DD` | conflicted |

未跟踪文件策略：

- 默认 `--untracked-files=normal`，避免大项目一次列出海量 ignored/untracked 文件；
- 若目录整体未跟踪，列表显示目录项，并允许用户单独请求展开；
- 后续可增加 `includeUntracked=all` 开关。

### 3.3 diff

单文件 diff 使用：

```bash
git -C <gitRoot> diff --no-ext-diff --no-textconv -M --unified=3 -- <relativePath>
```

规则：

- 未 staged 的改动优先展示；
- staged 改动可用 `--cached` 补充；
- untracked 文件用 `git diff --no-index /dev/null <file>` 或直接返回文件内容；
- 二进制文件不返回 patch，返回 `binary = true`；
- diff 超过限制（例如 512 KB）时截断；
- `--no-ext-diff --no-textconv` 防止仓库配置触发外部程序。

## 4. API 设计

### 4.1 改动列表

```http
GET /api/changes?workspaceId=<id>
```

响应：

```json
{
  "workspaceId": "ws-1",
  "workspacePath": "/home/me/project",
  "isGitRepo": true,
  "gitRoot": "/home/me/project",
  "files": [
    {
      "path": "src/auth/token.ts",
      "oldPath": null,
      "status": "modified",
      "staged": false,
      "binary": false
    }
  ]
}
```

非 Git 仓库：

```json
{
  "workspaceId": "ws-1",
  "isGitRepo": false,
  "files": []
}
```

### 4.2 文件 diff

```http
GET /api/changes/diff?workspaceId=<id>&path=src/auth/token.ts
```

响应：

```json
{
  "path": "src/auth/token.ts",
  "status": "modified",
  "binary": false,
  "truncated": false,
  "patch": "@@ -1,3 +1,3 @@\n..."
}
```

错误码：

| code | 场景 |
|---|---|
| `workspace_not_found` | Workspace 不存在/禁用 |
| `not_git_repo` | Workspace 不是 Git 仓库 |
| `file_not_changed` | path 不在当前改动集合 |
| `path_outside_workspace` | 路径越界 |
| `git_unavailable` | daemon 所在机器没有 git |
| `diff_too_large` | diff 超过限制 |

## 5. 安全边界

- 只接受相对路径；
- 服务端对 path 做 `path.resolve` + 越界校验；
- `git` 调用使用 `execFile`，不拼 shell 字符串；
- 禁用 external diff / textconv；
- 只读操作，不提供文件写接口；
- diff 输出有大小上限；
- 所有 Workspace 访问都带鉴权。

## 6. Android 设计

### 6.1 数据层

新增：

```text
ChangesRepository
  suspend fun changes(workspaceId: String?): NetworkResult<ChangesResponse>
  suspend fun diff(workspaceId: String?, path: String): NetworkResult<DiffResponse>
```

监听 `WorkspaceSelection.selectedId`：

- Workspace 切换后自动重新拉改动列表；
- 如果当前有打开的文件 diff，回到列表。

### 6.2 FilesScreen

状态：

- Loading
- Empty：无改动
- NotGit：非 Git 仓库
- Error
- Content

顶部：

- Workspace path；
- 仓库状态（Git / 非 Git）；
- 刷新按钮。

列表：

- 状态徽章：M / A / D / R / ?；
- 相对路径；
- 可选 additions/deletions。

### 6.3 FileDiffScreen

- 顶部：文件名 + 状态；
- 正文：patch，等宽；
- 行级颜色：
  - `+` 绿色
  - `-` 红色
  - `@@` 主色
- 二进制：直接提示不可预览；
- 截断：提示“内容过大，已截断”。

## 7. 性能

- 改动列表 = 一次 `git status`，不遍历全文件树；
- diff = 按需单文件执行，不批量计算；
- 大仓库下 `git status` 可能偏慢：
  - 加短 TTL 缓存（例如 2 秒）；
  - 前端显示加载态；
  - 后续可增加 `refresh=true` 强制刷新。
- 不上递归目录树，不预加载全部文件。

## 8. 实现顺序

1. daemon `GET /api/changes`
2. daemon `GET /api/changes/diff`
3. Android `ChangesRepository`
4. FilesScreen 改动列表
5. FileDiffScreen
6. 非 Git / 二进制 / 大 diff 状态
7. 测试：临时 git 仓库覆盖 M/A/D/R/?? / 空格文件名 / 越界路径

## 9. 暂不实现

- 按 Session / Run 归属改动；
- 人改 vs AI 改区分；
- 全部文件递归树；
- 文件编辑；
- revert / checkout；
- 子模块独立处理。
