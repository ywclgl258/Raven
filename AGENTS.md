# Raven（渡鸦）仓库协作与发布红线（AGENTS.md）

> 本文档是 `d:\Raven` 仓库的最高优先级工程规则，适用于所有 AI Agent、IDE 助手、CLI 自动化脚本、开发者与 CI/CD 流水线。任何改动、构建与交付都必须严格遵守。

---

## 1. 核心工程定位与不变量

| 维度 | 强制规范 |
|---|---|
| **应用形态** | Kotlin `2.3.21` + Jetpack Compose + 17 模块 Clean Architecture 原生 Android 客户端（`compileSdk = 36` / `targetSdk = 36` / `minSdk = 24` / JDK 17 / Gradle Wrapper `9.1.0` / AGP `9.0.1`） |
| **包名边界** | 正式版 `applicationId = com.raven.app`；开发版 Debug `applicationId = com.raven.app.debug`（可并存，禁止混发） |
| **版本元数据唯一源** | 根目录 [release.properties](release.properties) 统一提供 `versionName`、`versionCode`、`releaseCertificate`。**每次正式发布必须严格递增 `versionCode` 并更新 `versionName`** |
| **工具链红线** | Android 模块严禁启用 `org.jetbrains.kotlin.android` 与 `kotlin-kapt`（AGP 9 内置 Kotlin）；仅 `:core:database` 使用 KSP；依赖禁止越过 API 36 / Kotlin 2.3 上限（详见 [docs/ONBOARDING.md](docs/ONBOARDING.md)） |
| **视觉与交互不变量** | 严格遵循 **Dark Obsidian（`#09090B`）× Apple HIG Precision × Netflix Cinema** 体系，唯一品牌强调色 **Raven Crimson `#FF4D58`**；严禁硬编码 `Color(0x...)` 或引入旧版粉红 `#FF8DB4`；严禁套娃 `AlertDialog` |
| **运行时代码红线** | 严禁全局可变 `SiteRegistry.current` 与 `:sites:*` 外跨模块 `when (siteId)`；严禁 `Thread.sleep` 与每请求新建 `OkHttpClient`；`Regex(...)` 中字面量 `}` / `]` 必须显式转义为 `\}` / `\]`（规避 Android ART ICU `PatternNative` 崩溃）；**视频自动播放绝不自动全屏**；凭据统一由 `SessionVault` 加密，严禁外泄至 CDN/更新器/备份 |
| **Git 与发布边界** | **未经用户明确指令，严禁执行 `git push`、打版本 Tag、创建或修改 GitHub Release，或向外部渠道分发安装包** |

---

## 2. 统一打包与签名一致性红线（覆盖安装保障）

Android 通过 **包名 + 签名证书指纹** 校验覆盖安装合法性。签名一旦漂移，已有用户将无法覆盖升级（触发 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`），只能卸载重装并丢失本地片库、收藏、下载记录与加密会话。所有构建主体必须无条件遵守以下条款：

### 2.1 唯一官方打包入口
- **所有正式 Release APK / AAB 必须且只能通过本仓库 Gradle Wrapper 构建**：
  ```powershell
  # Windows PowerShell
  .\gradlew.bat :app:validateReleaseSigning :app:assembleRelease --no-daemon
  ```
  ```bash
  # Linux / macOS / CI
  ./gradlew :app:validateReleaseSigning :app:assembleRelease --no-daemon
  ```
- `:app:assembleRelease`、`:app:bundleRelease`、`:app:packageRelease` 已在 `app/build.gradle.kts` 中强制挂载 `:app:validateReleaseSigning` 前置校验任务。

### 2.2 唯一外部签名配置源
- 外部签名配置目录由环境变量 `RAVEN_SIGNING_HOME` 指定，读取 `${RAVEN_SIGNING_HOME}/signing.properties`。
- 未设置环境变量时，默认回退至用户主目录：
  - Windows：`%USERPROFILE%\.raven\signing\signing.properties`（当前主开发机目录：`C:\Users\ywclg\.raven\signing`）
  - Linux / macOS：`~/.raven/signing/signing.properties`
- `signing.properties`、`*.jks`、`*.keystore` 必须始终位于**仓库目录之外**（`.gitignore` 已屏蔽），模板与多机/CI 注入方法见 [docs/SIGNING.md](docs/SIGNING.md)。

### 2.3 锁定官方发布证书公钥指纹
- [release.properties](release.properties) 中锁定的唯一官方发布证书元数据：
  - **Certificate SHA-256**：`1F9407377D2D3AB5C04527CF6E587F0B789BA8B971D60AD8F99360D05CA52F3A`
  - **Subject / 算法**：`CN=Raven`，RSA 4096-bit（PKCS12）
  - **签名方案**：APK Signature Scheme **v1 + v2 + v3** 全启用
- 构建时 `app/build.gradle.kts` 会把该指纹写入 `BuildConfig.RELEASE_CERT_SHA256`，供应用内更新器 `RavenUpdater` 与 `AndroidApkSignatureVerifier` 执行端侧校验。

### 2.4 五条绝对禁止项（硬红线）
1. **严禁**生成新的或替代用的 Release Keystore；若发现本机缺失密钥，必须从受信任备份恢复，绝不可重新 `keytool -genkeypair`。
2. **严禁**将 Release 构建回退到 Debug 签名，或产出未签名的 `app-release-unsigned.apk` 作为正式交付物。
3. **严禁**为了绕过 `:app:validateReleaseSigning` 而修改 `release.properties` 中的 `releaseCertificate` 字段或弱化校验任务。
4. **严禁**在 Git 仓库、提交信息、终端输出、日志、聊天记录或命令行参数中提交、读取、打印或回显私钥内容、Keystore 密码或 Key 密码。
5. **严禁**脱离 Gradle 对 APK 进行二次重签（如手工运行 `apksigner sign` / `jarsigner` 替换签名）。

### 2.5 Debug 与 Release 严格隔离
- Debug 构建（`.\gradlew.bat :app:assembleDebug`，包名 `com.raven.app.debug`）仅用于本地开发与真机调试，**严禁作为正式版本或正式更新交付给用户**。
- 若多台开发机之间需要互相覆盖安装 Debug 包，应显式共享同一份开发用 `debug.keystore`，而非在各机随意重新生成或借用 Release 密钥。

### 2.6 交付前强制验签清单
任何正式 APK 在归档、上传 GitHub Release 或交付前，**必须**执行 `apksigner` 验签并逐项核对通过：

```powershell
apksigner verify --verbose --print-certs .\app\build\outputs\apk\release\app-release.apk
```

核对项（缺一不可）：
1. 签名验证通过，且 `Verified using v1 scheme` / `v2 scheme` / `v3 scheme` 均为 `true`。
2. 证书 SHA-256 摘要精确等于 `1F9407377D2D3AB5C04527CF6E587F0B789BA8B971D60AD8F99360D05CA52F3A`（不区分大小写）。
3. APK 包名为 `com.raven.app`，且 `versionCode` 严格大于上一已发布版本、`versionName` 与本次发布说明一致。

---

## 3. 文档导航

- 签名配置、备份、CI 注入与验签实操：[docs/SIGNING.md](docs/SIGNING.md)
- 新开发者接手、环境红线与日常开发命令：[docs/ONBOARDING.md](docs/ONBOARDING.md)
- 17 模块系统架构与工程契约：[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- 设计系统与 UI 架构手册：[docs/DESIGN_AND_UI_GUIDE.md](docs/DESIGN_AND_UI_GUIDE.md)
- 19 站插件架构与新站接入：[docs/SITE_PLUGIN_GUIDE.md](docs/SITE_PLUGIN_GUIDE.md)
- 数据、媒体内核、加密备份与更新器：[docs/DATA_MEDIA_SECURITY.md](docs/DATA_MEDIA_SECURITY.md)
