# airemote-android

AIRemote 的 Android 客户端。电脑上的 daemon 负责跑 agent，这个 App 是你的遥控器：
看流式输出、审批工具调用、浏览文件、管理工作区。整体定位与安装步骤见[根 README](../README.md)。

## 前置

- Android Studio（JDK 17）
- `compileSdk 36` / `minSdk 24` / `targetSdk 36`
- 电脑上的 daemon 已经跑起来（拿到地址与 token）

## 构建

用 Android Studio 打开本目录，选 flavor 后直接 Run。命令行等价于：

```bash
./gradlew :app:assembleAlphaDebug    # 测试通道
./gradlew :app:assembleProdDebug     # 正式通道
```

两个 product flavor：

| flavor | applicationId | 用途 |
|---|---|---|
| `alpha` | `com.airemote.airemote.alpha` | 测试通道，可与正式版共存 |
| `prod` | `com.airemote.airemote` | 正式通道 |

### 签名

正式签名需要 `keystore.properties`（从 `keystore.properties.example` 复制填写，该文件不入库）；
没有它只能跑未签名的本地调试包。注意 `app/build.gradle` 在存在 `keystore.properties` 时会把
release 签名同时挂到 `debug` buildType 上。

### 版本号

版本源是 [version.txt](version.txt)（语义化，手改递增）。release 构建据此生成 versionName，
versionCode 取 epoch 秒；debug 固定 versionCode=`1`、versionName=`0.0.0-*`。
**正式包的 versionCode 与 versionName 都必须高于本地 debug 构建**，否则安装器会判降级拒装。


## 模块划分

| 模块 | 职责 |
|---|---|
| `:app` | 业务上层：MVVM（`viewmodel/`）+ Compose UI（`ui/`）+ 仓库编排（`data/repository/`）+ `navigation/` |
| `:lib-network` | 网络底座：okhttp / retrofit / coroutines / kotlinx-serialization 依赖、wire DTO、Retrofit 接口与客户端工厂、SSE 传输、LLM 抽象 |
| `:lib-updater` | 应用内自更新 SDK（仅作者开发期间自用，若需要使用可行接入自己OSS；alpha / prod 双通道，从 OSS 拉 manifest） |

几条关键约定（完整规范见[CLAUDE.md](../CLAUDE.md)）：

- **网络相关的东西全在 `:lib-network`**：`:app` 不自己声明这些依赖，`:lib-network` 也不引用任何 `android.*`，依赖以 `api` 暴露
- `:lib-network` 内部单向分层：`sse/`（通用 SSE 传输）→ `http/`（REST 结果包装）→ `llm/`（供应商抽象）→ `airemote/`（daemon 协议层 + `dto/`）
- wire DTO 按业务域合并成 `<域>Dtos.kt`，与 `airemote-daemon/src/types/api.ts` 一一对齐
- UI 以 [docs/ui/](../docs/ui/README.md) 为唯一设计源；「runtime 身份」（图标 / 色 / 名）是可配置映射，新增 agent 只加一行
- 设置持久化用 MMKV（`SettingsStore`，留在 `:app`）

## 文档

| 文档 | 内容 |
|---|---|
| [docs/ui_adapter.md](docs/ui_adapter.md) | UI 适配说明（Design Token → Compose 的落地） |
| [docs/ui/](../docs/ui/README.md) | 移动端 UI 设计（与 iOS 共用，逐页规格在 `docs/ui/pages/`） |
| [CLAUDE.md](../CLAUDE.md) | 仓库纲领与开发规范 |


## License

Apache-2.0，见 [LICENSE](../LICENSE)。
