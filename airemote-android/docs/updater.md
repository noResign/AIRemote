# airemote Android 自更新 SDK 技术方案（`:lib-updater`）

> 本文档是「客户端自托管 OTA 更新」能力的**技术方案 / 施工前评审稿**，先把边界、协议、
> 公开 API 想清楚，再动手。
>
> - 定位：把「检查更新 → 下载 → 校验 → 调起安装」抽成**无 UI 的 headless 模块** `:lib-updater`，
>   当前 App 直接用，未来客户端复用（方案 A：先留在本仓库，出现第二个消费者再抽独立仓库发 Maven）。
> - 代码位置：`airemote-android/lib-updater/`（包名 `com.airemote.updater`）
> - 设计源：`CLAUDE.md`（Android 分层 / 一个文件一个顶层类型 / 传输契约）
> - 状态：**待评审**（见 §10 待决策项）

---

## 1. 目标与范围

### 1.1 要解决的痛点

后端（daemon）改完能直接部署/命令行跑，但 Android 客户端改完要测，得先出包、传到手机、再装。
目标是打通一条顺滑的循环：

```
改代码 → push → CI 出包上传 OSS → 手机上「检查更新」→ 下载 → 安装 → 测
```

### 1.2 范围

1. **`:lib-updater` headless 模块**：检查更新、下载 APK、sha256 校验、调起系统安装器。
2. **测试包 / 正式包分通道**：`alpha`（测试包，只装开发者手机）与 `prod`（正式包，对外分发）。
3. **OSS 静态清单 + APK**：一份跨端 `manifest.json` 契约，CI 生成。
4. **App 侧接入**：FileProvider 声明 + 设置页「检查更新」入口 + 更新弹窗。

**不在范围**：iOS 更新 SDK（本期只定协议，实现留到 iOS 客户端时）、增量/差分包下载、
静默后台安装（违反本 App 安全底线）、热更新（只做整包 OTA）。

---

## 2. 总体架构：SDK 管机制，App 管策略和皮肤

这是本方案的核心分界。SDK 只做「机制」，不碰 UI、不替 App 决定何时检查。

| 归属 | 内容 |
|---|---|
| **SDK（`:lib-updater`）** | 拉清单、解析、版本比较、channel 校验、下载（进度回调）、sha256 校验、调起安装器；事件状态机 + 回调 |
| **App（`:app`）** | 注入配置（manifest URL / channel / 当前版本 / FileProvider authority）；决定何时检查（启动/手动）；渲染弹窗/进度条/changelog；强更策略 |

```
┌─ :app ────────────────────────────────┐
│  BuildConfig  →  UpdaterConfig         │
│  Compose UI  ←  Flow<UpdateEvent>      │
│  FileProvider(manifest 声明)            │
└──────────────┬────────────────────────┘
               │ implementation
┌──────────────▼────────────────────────┐
│  :lib-updater（headless，无 UI）       │
│  check / download / verify / install   │
│  依赖：okhttp + coroutines + androidx.core│
└────────────────────────────────────────┘
```

> **与 `:lib-network` 的一个刻意差异**：`:lib-network` 不引用任何 `android.*`；但 `:lib-updater`
> 必须引用 `androidx.core`（`FileProvider`）并接收 `Context`（调起安装 intent）。这是职责决定的，
> 不是越界。

---

## 3. 传输契约：manifest JSON（跨端）

**这是独立于 daemon `types/api.ts` 的第二份跨端契约**。服务端不是 daemon 端点，而是 OSS 上的
静态文件（CI 生成）。Android SDK 与未来 iOS SDK 共用同一份 schema。

```json
{
  "channel": "alpha",
  "versionCode": 42,
  "versionName": "0.3.0-alpha",
  "apkUrl": "https://<oss>/alpha/airemote-42-alpha.apk",
  "apkSize": 25165824,
  "sha256": "a3f1…",
  "changelog": "修复 xxx & 新增 yyy",
  "minVersionCode": 30,
  "publishedAt": "2026-09-13T08:00:00Z"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `channel` | string | ✅ | 通道标识，客户端校验与自己烧录的 channel 一致，防串包 |
| `versionCode` | long | ✅ | 必须严格大于本地才提示更新（防降级） |
| `versionName` | string | ✅ | 展示用 |
| `apkUrl` | string | ✅ | APK 下载地址，**必须 HTTPS** |
| `apkSize` | long | ✅ | 字节数，用于进度与预展示 |
| `sha256` | string | ✅ | APK 文件 SHA-256（hex，小写），下载后校验 |
| `changelog` | string | ⬜ | 更新说明，纯文本/简短 markdown |
| `minVersionCode` | long | ⬜ | 低于此版本的客户端**强制更新**（本期只留字段，见 §10-6） |
| `publishedAt` | string | ⬜ | 发布时刻，展示用 |

**OSS 目录布局**：

```
oss://your-bucket/
├─ alpha/
│  ├─ manifest.json
│  └─ airemote-42-alpha.apk
└─ prod/
   ├─ manifest.json
   └─ airemote-42.apk
```

---

## 4. 模块与包结构

对齐 `:lib-network` 的命名规范（一个文件一个顶层公开类型，sealed 子类随父类型同文件）。

```
lib-updater/
├─ build.gradle                     # android-library + kotlin + serialization 插件
└─ src/
   ├─ main/
   │  ├─ AndroidManifest.xml        # 仅声明 INTERNET（FileProvider 由宿主 App 声明）
   │  └─ java/com/airemote/updater/
   │     ├─ Updater.kt              # 公开入口：check / checkAndDownload / download / install
   │     ├─ UpdaterConfig.kt        # 配置（manifestUrl/channel/版本/authority/client）
   │     ├─ UpdateManifest.kt       # 协议模型（@Serializable，与 §3 一一对齐）
   │     ├─ UpdateEvent.kt          # 事件状态机（sealed interface + 子类）
   │     ├─ UpdateException.kt      # 错误密封（sealed class）
   │     └─ internal/
   │        ├─ ManifestFetcher.kt   # 拉清单 + 解析 + channel 校验
   │        ├─ UpdateDecision.kt    # 版本比较 + 强更判定（纯逻辑，可单测）
   │        ├─ ApkDownloader.kt     # OkHttp 流式下载 + 进度
   │        ├─ ApkVerifier.kt       # sha256 校验（流式计算，避免整包读入内存）
   │        └─ ApkInstaller.kt      # FileProvider + ACTION_VIEW（唯一 Android 耦合点）
   └─ test/java/com/airemote/updater/
      ├─ UpdateDecisionTest.kt      # 版本比较/强更/channel 校验
      ├─ ApkVerifierTest.kt         # sha256
      └─ ManifestFetcherTest.kt     # MockWebServer 拉清单 + 下载
```

**依赖（`build.gradle`）**：

| 依赖 | 配置 | 理由 |
|---|---|---|
| `libs.okhttp` | `api` | `OkHttpClient` 出现在 `UpdaterConfig` 公开签名（可注入 MockWebServer/自定义拦截器） |
| `libs.kotlinx.coroutines.core` | `api` | `Flow` 出现在公开签名 |
| `libs.kotlinx.serialization.json` | `implementation` | 仅内部解析 manifest，序列化类型不外泄 |
| `libs.androidx.core.ktx` | `implementation` | `FileProvider`（`androidx.core:core` 传递提供） |
| `libs.junit` / `libs.kotlinx.coroutines.test` | `testImplementation` | 单测 |
| `mockwebserver`（**需新增到 libs.versions.toml**） | `testImplementation` | 下载/拉清单单测 |

插件：`android-library` + `kotlin-android` + `kotlin-serialization`（与 `:lib-network` 一致）。
`namespace = "com.airemote.updater"`，`minSdk 24`。

`settings.gradle` 增加 `include ':lib-updater'`。

---

## 5. 公开 API 草图（待评审，非最终）

### 5.1 配置

```kotlin
// UpdaterConfig.kt
data class UpdaterConfig(
    val manifestUrl: String,          // flavor 烧入（BuildConfig.UPDATE_MANIFEST_URL）
    val channel: String,              // 校验清单，防串包
    val currentVersionCode: Long,     // BuildConfig.VERSION_CODE
    val currentVersionName: String,   // BuildConfig.VERSION_NAME
    val fileProviderAuthority: String,// "${applicationId}.fileprovider"
    val client: OkHttpClient? = null, // 不传用内置默认
)
```

### 5.2 入口

```kotlin
// Updater.kt
class Updater(private val config: UpdaterConfig) {

    /** 检查更新：拉清单 → channel 校验 → 版本比较，发 UpdateAvailable / NoUpdate */
    fun check(): Flow<UpdateEvent>

    /** 检查并（若有新版本）下载到 destDir，校验 sha256，产出 Downloaded */
    fun checkAndDownload(destDir: File): Flow<UpdateEvent>

    /** 对已知 manifest 单独下载（写 airemote-<versionCode>.apk，见 §6.1） */
    fun download(manifest: UpdateManifest, destDir: File): Flow<UpdateEvent>

    /** 用 FileProvider 生成 content:// URI 并调起系统安装器，返回是否成功触发 */
    fun install(context: Context, apkFile: File): Boolean

    /** 清空 APK 缓存目录（App 启动时调用，删掉上次遗留的安装包，见 §6.1） */
    fun cleanup(context: Context)
}
```

### 5.3 事件状态机

```kotlin
// UpdateEvent.kt
sealed interface UpdateEvent {
    data object Checking : UpdateEvent
    data object NoUpdate : UpdateEvent
    data class UpdateAvailable(val manifest: UpdateManifest, val forced: Boolean) : UpdateEvent
    data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long) : UpdateEvent
    data class Downloaded(val apkFile: File, val manifest: UpdateManifest) : UpdateEvent
    data class Failed(val error: UpdateException) : UpdateEvent
}
```

### 5.4 错误

```kotlin
// UpdateException.kt
sealed class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(message: String, cause: Throwable? = null) : UpdateException(message, cause)
    class ManifestInvalid(message: String) : UpdateException(message)
    class ChannelMismatch(expected: String, actual: String) : UpdateException(message)
    class ChecksumMismatch(expected: String, actual: String) : UpdateException(message)
    class InstallNotResolved(message: String) : UpdateException(message)
}
```

### 5.5 协议模型

```kotlin
// UpdateManifest.kt
@Serializable
data class UpdateManifest(
    val channel: String,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String,
    val changelog: String? = null,
    val minVersionCode: Long? = null,
    val publishedAt: String? = null,
)
```

> `forced` 判定（`UpdateDecision.kt`，纯函数可单测）：
> `forced = manifest.minVersionCode?.let { config.currentVersionCode < it } == true`

---

## 6. 关键流程

```
check()
  │ 1. GET manifestUrl（HTTPS）
  │ 2. 解析 + channel 校验（不一致 → ChannelMismatch）
  │ 3. 比较 versionCode（≤ 本地 → NoUpdate；> 本地 → UpdateAvailable）
  ▼
download(manifest)
  │ 4. OkHttp 流式下载到 destDir（进度 → DownloadProgress）
  │ 5. 下载完 sha256 流式校验（不一致 → ChecksumMismatch，删临时文件）
  │ 6. 校验通过 → Downloaded(apkFile)
  ▼
install(apkFile)
  │ 7. FileProvider.getUriForFile → content:// URI
  │ 8. ACTION_VIEW + type=application/vnd.android.package-archive
  │    + FLAG_GRANT_READ_URI_PERMISSION + FLAG_ACTIVITY_NEW_TASK
  │ 9. 系统安装器接管（签名不一致 / 版本不高于当前 → 安装器自拒）
```

**App 侧必须补齐的样板（SDK 不做，因为 authority 每 App 唯一）**：

`app/src/main/AndroidManifest.xml`：

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

`app/src/main/res/xml/file_paths.xml`：

```xml
<paths>
    <cache-path name="updates" path="updates/" />
</paths>
```

APK 默认下载到 `context.cacheDir/updates/`。

### 6.1 安装包文件名与清理（防测试反复下载占磁盘）

测试会反复下载，一个包 ~13MB，不清理会越堆越多。

1. **文件名带 versionCode**：`download()` 写到 `destDir/airemote-<versionCode>.apk`。
   每次更新的文件名（也就是 `content://` URI 路径）都不一样——**不要**改回固定文件名：
   曾经用固定的 `latest.apk`，路径永远不变，结果系统安装器在用户点「安装」时报
   「已安装相同版本」而装不上（怀疑安装器按同一路径复用了上次解析出来的包信息）。
2. **每次下载前清理旧包**：`download()` 开头调 `pruneStaleApks()`，把历史包删掉，
   磁盘上最多留两份——**最近下载的一份**和本次要写的一份（`keep`）。只留一份会把
   "最近一份"也删掉，见下面的警告。
3. **下次启动清理**：`Updater.cleanup(context)` 清空整个 `updates/` 目录，App 在
   `AppApplication.onCreate()` 里调一次即可。这是兜底：即使用户中途退出、留下了两份，
   下次启动也会清干净（`cacheDir` 本身也会被系统按需回收）。

> ⚠️ **不能在 `install()` 触发后立刻删，也不能把"最近一份"一起删**：系统安装器是异步读
> `content://` URI 的，用户可能还停在安装确认框上（甚至切回 App 又发起一次更新），此时删掉
> 他正在等的那个包，等他点「安装」就读不到文件了。所以清理时始终保留最近下载的那一份，
> 整体清空只放在「下次启动」。

---

## 7. Flavor 通道设计（alpha / prod）

用 Gradle **productFlavor** 区分，不手改代码。**命名避开 buildType 的 `release`**，否则会出现
`releaseRelease` 这种鬼畜变体。

```kotlin
// app/build.gradle
android {
    defaultConfig {
        applicationId "com.airemote.airemote"
        // 这两个默认值只给 debug 用；release variant 由 androidComponents 自动读
        // version.txt / epoch，见下方说明与 §7.1 版本号约定。
        versionCode project.findProperty("versionCode")?.toString()?.toInteger() ?: 1
        versionName project.findProperty("versionName")?.toString()
                ?: "0.0.0-" + new Date().format("yyyyMMddHHmmss")
    }

    buildTypes {
        debug {
            // debug 与 release 共用同一个 keystore，见下方“签名”
            if (hasKeystore) signingConfig signingConfigs.release
        }
        release {
            if (hasKeystore) signingConfig signingConfigs.release
        }
    }

    flavorDimensions += "channel"
    productFlavors {
        alpha {
            dimension "channel"
            applicationIdSuffix ".alpha"          // 测试包与正式包可共存一台手机
            buildConfigField "String", "UPDATE_CHANNEL", "\"alpha\""
            buildConfigField "String", "UPDATE_MANIFEST_URL", "\"https://<oss>/alpha/manifest.json\""
        }
        prod {
            dimension "channel"
            buildConfigField "String", "UPDATE_CHANNEL", "\"prod\""
            buildConfigField "String", "UPDATE_MANIFEST_URL", "\"https://<oss>/prod/manifest.json\""
        }
    }
    buildFeatures { compose = true }  // buildConfig 对 app 默认开启，直接加 buildConfigField 即可
}
```

> 上面 `defaultConfig` 的两个默认值实际只作用于 debug；release variant 会由
> `androidComponents.onVariants` 覆盖为 `version.txt` 里的正式号（alpha 追加时间戳），
> 所以 Android Studio 直接构建 release 包也不会再显示 `0.0.0-*`。

产生的变体：`alphaDebug` / `alphaRelease` / `prodDebug` / `prodRelease`。

> 桌面名称区分：alpha 通过 `app/src/alpha/res/values/strings.xml` 覆盖 `app_name` 为
> 「AIRemote-Beta」，prod 沿用 `main` 里的「AIRemote」，避免两个应用在桌面上分不清。

**签名（最容易翻车，必须统一）**：

- 新旧 APK 签名一致才能覆盖安装。**alpha 和 prod 共用同一个 keystore**；同一个 flavor 下
  **debug 和 release 也共用同一个 keystore**。这样 Android Studio Run 安装的 debug 包
  和应用内自动更新下载的 release 包可以互相覆盖，不会出现
  “已安装了签名冲突的应用”。
- `app/build.gradle` 在存在 `keystore.properties` 时，把 `signingConfigs.release` 同时挂到
  `debug` 和 `release` 两个 buildType 上；缺少 keystore 时 debug 回退系统默认 debug 签名。
- keystore 不进 git：本地放 `keystore.properties`（gitignore），CI 用 secret 注入。
- `.claude/skills/deploy/scripts/release-android.sh` 会在缺少 `keystore.properties` 时直接报错，避免产出未签名包。
- 注意：debug 包从此带 release 签名，**不要对外分发 debug 包**。

**「别人不能下测试包」不用做鉴权**，两层就够：

1. **分发控制**：alpha 的 APK + manifest 放私有 bucket 或不公开路径，不贴链接别人不知道；
2. **安装控制**：alpha 包只装在开发者手机上，别人手机上的 prod 包指向 prod 通道，根本不拉 alpha 清单。

### 7.1 版本号约定

**正式包 versionCode 用 epoch 秒；正式包的 versionCode 和 versionName 都必须高于本地包。**

| | versionCode | versionName |
|---|---|---|
| 正式包（prod release） | `epoch 秒`（release variant） | `version.txt` 的语义化版本，如 `1.0.0` |
| alpha 包（alpha release） | `epoch 秒`（release variant） | `<version>-alpha.<yyyyMMddHHmmss>` |
| 本地包（debug） | `1`（固定低值） | `0.0.0-<yyyyMMddHHmmss>`（UTC） |

- **版本源**：`airemote-android/version.txt`，语义化版本，手改递增：
  修 bug 加 patch（`1.0.0 → 1.0.1`），加功能加 minor（`1.0.1 → 1.1.0`），大改加 major（`1.1.0 → 2.0.0`）。
  release variant 构建（Android Studio 的 Build APK / release-android.sh）都会自动读它；
  `VERSION_NAME=` 或 `-PversionName=` 可临时覆盖。
- **alpha 通道**：`<version>-alpha.<yyyyMMddHHmmss>`。时间戳保证同一版本多次发 alpha 也严格升序；
  它相对本地 debug 包的 `0.0.0-*` 仍然更高，因此测试包也能远程下载安装。
- **本地构建**：debug variant 固定 `0.0.0-<yyyyMMddHHmmss>`（UTC），恒低于任何 `1.x` 正式版本。
- **versionCode**：release variant 取 epoch 秒，debug variant 固定为 `1`。这样正式包在
  versionCode 和 versionName 两个维度都恒高于本地 debug 包，无论本地测试包是什么时候构建的，
  应用内更新下载的正式包都不会被判降级。
- **Android Studio 直接构建**：`Build > Build APK(s)` 选 release variant 也会按正式包规则生成
  `versionName=1.0.0` / `1.0.0-alpha.<时间戳>`；只有 debug variant 是 `0.0.0-*`。

**为什么 versionCode / versionName 都要管**：Android 安装器首先按 versionCode 判降级；
我们检查当前 ColorOS 的 `OppoPackageInstaller`，`replace_lower_version` 弹窗也是由
`apkVersionCode < installedVersionCode` 触发，versionName 只用于弹窗展示和同 code 场景。
但不同 ColorOS 版本或定制安装器的实现可能参考 versionName，历史事故里也出现过本地包
versionName 比发布包大、弹窗信息误导的情况。因此约定：正式包的 versionCode 和 versionName
都必须高于本地包。现在正式包 code=`epoch`、name=`1.0.0+`；本地 code=`1`、name=`0.0.0-*`。

**有意接受的代价**：
- 装了正式包之后再装本地构建会被拦（两个字段都更小），需要先卸载，或 `adb install -r -d`。
- 旧版本地构建（versionName `1.0` / `1.0-alpha`，versionCode 是 epoch）比新本地包大，
  第一次覆盖装新本地包也会被判降级，卸载一次即可。
- 本地包之间 versionCode 相同（都是 `1`），Android 允许相同 versionCode 覆盖安装；如果某些
  定制安装器要求严格递增，则需要卸载或走 `-d`。

---

## 8. 安全设计

这是 RCE 工具的更新通道，本身是攻击面，底线如下：

| 项 | 做法 |
|---|---|
| 传输加密 | manifest 与 APK 均 **HTTPS**（禁止 http） |
| 完整性 | 下载后 **sha256** 校验，不一致即弃（防传输损坏/中间篡改） |
| 签名 | **系统安装器**验签，篡改过的 APK 无法覆盖安装（SDK 不重复验签） |
| 防串包 | manifest `channel` 必须等于本地烧录 channel |
| 防降级 | 仅 `versionCode` 严格大于才提示更新 |
| 不静默安装 | 永远弹系统安装确认，用户手动点 |
| 防清单被换 | 私有 bucket / HTTPS + 签名 + sha256 三重兜底：清单 URL 被换，最坏引导下载一个被 sha256+签名双重拒绝的包 |

> ⚠️ 现状：App `usesCleartextTraffic="true"`（为本地 daemon 明文连接）。更新通道必须 https，
> 建议后续用 `network_security_config.xml` 把 cleartext 收窄到本地 daemon（`10.0.2.2`/`localhost`/
> 局域网 IP），对外一律 https。列为决策项 §10-4。

---

## 9. 分阶段实现

### P0 · 模块骨架 + 协议 + 决策逻辑（纯 JVM，先可单测）

- `settings.gradle` 加 `:lib-updater`；`build.gradle`（插件 + 依赖）；`libs.versions.toml` 加 `mockwebserver`。
- `UpdateManifest.kt` / `UpdaterConfig.kt` / `UpdateException.kt` / `internal/UpdateDecision.kt`。
- 单测：`UpdateDecisionTest`（版本比较 / 强更 / channel 校验）。

**验收**：`UpdateDecisionTest` 绿；不依赖任何 android.* 的部分能跑纯 JVM 单测。

### P1 · 拉清单 + 下载 + 校验

- `internal/ManifestFetcher.kt`（okhttp + kotlinx-serialization 解析 + channel 校验）
- `internal/ApkDownloader.kt`（流式下载 + 进度，写 `airemote-<versionCode>.apk`）、`internal/ApkVerifier.kt`（流式 sha256）
- `UpdateEvent.kt`；`Updater.check()` / `checkAndDownload()` / `download()`。
- 单测：`ManifestFetcherTest` + `ApkVerifierTest`（MockWebServer）。

**验收**：MockWebServer 下全流程事件序列正确；坏 sha256 抛 `ChecksumMismatch`。

### P2 · 安装器 + App 侧 FileProvider + 清理

- `internal/ApkInstaller.kt`（FileProvider + ACTION_VIEW）
- `Updater.cleanup(context)`（清空 updates 缓存目录）
- App manifest 加 `<provider>`；新增 `res/xml/file_paths.xml`；`AppApplication.onCreate()` 调 `cleanup`。

**验收**：真机上下载的 APK 能调起系统安装器并成功安装；重启 App 后 `updates/` 被清空。

### P3 · Flavor + 版本注入

- `app/build.gradle`：`alpha`/`prod` flavor、`buildConfigField`、release variant 自动读
  `version.txt` 并取 `versionCode=epoch`（`-P` 仅作覆盖）、统一 signingConfig。
- `:app` 依赖 `implementation project(':lib-updater')`。

**验收**：`assembleAlphaRelease` / `assembleProdRelease` 出签名包；`applicationId` 后缀正确。

### P4 · App 侧 UI 接入

- 设置页「检查更新」入口 + 更新弹窗（版本号 / changelog / 进度条 / 强制更新态）。
- 订阅 `Updater.check()` Flow，映射到 Compose 状态（走现有 MVVM，UI 不碰网络）。
- 策略：本期只做**手动检查**（设置页「检查更新」按钮），不做启动自动检查。

**验收**：设置页点「检查更新」→ 有新版 → 弹窗 → 下载进度 → 调安装器。

### P5 · CI 出包上传 OSS（可先手动跑通）

- CI / 本地发布：`.claude/skills/deploy/scripts/release-android.sh alpha|prod` → 用
  `version.txt`/epoch 生成 APK → 算 sha256 → 渲染 `manifest.json` 上传对应通道。
- alpha 与 prod 分目录（§3 布局）。

**验收**：push 后 CI 自动出包，手机「检查更新」能拉到新版本。

---

## 10. 待决策项（开工前拍板）

| # | 决策点 | 选项（推荐加粗） |
|---|---|---|
| 1 | flavor 命名 | **`alpha` / `prod`**（避开 buildType `release`）/ `staging` / `release` |
| 2 | alpha 通道隔离 | **私有 bucket 或公开但不宣传的路径（MVP）** / 私有 bucket + 签名 URL / STS 临时凭证 |
| 3 | keystore 管理 | **统一一个 keystore，本地 keystore.properties + CI secret** / alpha 用 debug 签名、prod 另签（不推荐，跨包更新会断） |
| 4 | cleartext 收敛 | **引入 `network_security_config.xml`，只对本地 daemon 放行明文，对外 https** / 暂不动 `usesCleartextTraffic` |
| 5 | 检查时机 | **仅手动「检查更新」按钮**（已实现，不做启动自动检查） / 启动自动检查 + 手动 |
| 6 | 强制更新 | **本期只留 `minVersionCode` 字段，不做强制 UI** / 低版本阻断 + 强更弹窗 |

---

## 11. 提交规范（Conventional Commits，中文 subject）

分阶段提交，每阶段一个 commit：

```text
feat(android): 新增 lib-updater 模块骨架 & manifest 协议模型            # P0
feat(android): lib-updater 拉清单下载与 sha256 校验                     # P1
feat(android): lib-updater 调起系统安装 & app 声明 FileProvider         # P2
feat(android): app 分 alpha/prod 通道 & versionCode 注入               # P3
feat(android): 设置页检查更新入口与更新弹窗                            # P4
chore(android): CI 出包上传 OSS 与生成 manifest                        # P5
```
