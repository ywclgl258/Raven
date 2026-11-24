# Raven 构建环境与工具链审计报告 (`audit-env.md`)

> 审计时间：2026-11-24 | 审计范围：本机 Windows 开发环境、`d:\Raven` 工作区、Android SDK / 模拟器、`%USERPROFILE%\.gradle` 缓存，以及只读参照 `D:\rule34` 构建配置。

## 1. 核心结论（Executive Summary）

1. **工作区就绪**：`d:\Raven` 当前为空目录，尚未初始化 Git 仓库；Git `2.54.0.windows.1` 已安装且全局身份已配置（`kakka` / `ywclgl258@gmail.com`）。
2. **硬件与 JDK**：主机配置强劲（AMD Ryzen 9 8940HX 16C/32T、31.2 GB RAM、`D:` 剩余 112.4 GB），仅安装了 **Microsoft OpenJDK `17.0.19+10-LTS`**（无 JDK 21、无 Android Studio）。对 **Gradle `9.1.0` + AGP `9.0.1`** 完全够用。
3. **Android SDK & 模拟器已就绪（但未加入 `PATH` 且未设 `ANDROID_HOME`）**：
   - SDK 位于 `D:\Android\Sdk`，已安装 `platforms;android-35`、`platforms;android-36`、`build-tools;36.0.0`、`platform-tools` (`37.0.1`)、`cmdline-tools;latest` (`12.0`)、`emulator` (`37.2.13`)，所有 License 已接受。
   - **当前已有 1 台 API 36 (Android 16) 模拟器在线运行**：`emulator-5554`（AVD `test_x86_64`，`pixel_6`，`1080x2400`，`420 dpi`，`x86_64`），可直接用于后续可视化与 instrumented 验证。
   - `android` CLI 与 Android Studio **均未安装**（`PATH` 中 `C:\Users\ywclg\AppData\AndroidCLI` 为失效残留路径）；`adb` / `emulator` / `sdkmanager` 需使用 `D:\Android\Sdk` 绝对路径或在会话内注入 `ANDROID_HOME` 与 `PATH`。
4. **网络畅通 & 缓存可复用**：Google Maven、Maven Central、Gradle Plugin Portal、Gradle Distributions 经代理（`127.0.0.1:7897`）与直连均返回 `200/303`；本机已缓存 `gradle-9.1.0-all`、`AGP 9.0.1`、`Kotlin 2.2.10`、`OkHttp 4.12.0`、`Coil 3.3.0`、`Media3 1.9.2`、`Coroutines 1.10.2` 等核心包。
5. **关键避坑与推荐基线**：
   - **严禁盲目拉取最新 AndroidX/Coil**：实测 `compose-bom:2026.08.00+`（`ui:1.12+`、`material3:1.5.0`）、`core-ktx:1.19.x`、`lifecycle:2.11.x`、`navigation-compose:2.10.x`、`hilt-navigation-compose:1.4.0`、`coil3:3.6.x` 的 AAR 均要求 `minCompileSdk=37`（AndroidX 同时要求 `minAndroidGradlePluginVersion=9.1.0`），在本机 `android-36` + `AGP 9.0.1` 下会**直接构建失败**。
   - **AGP 9.0.1 内置 Kotlin 与 KSP 约束**：AGP 9.0 默认启用 built-in Kotlin，**禁止**应用 `org.jetbrains.kotlin.android` 与 `kotlin-kapt`；同时 KSP 需 `>= 2.3.1`（推荐 `2.3.14`）才能兼容 AGP 9 built-in Kotlin 任务注册。
   - **本机全局 `kotlin.incremental=false` 影响**：`C:\Users\ywclg\.gradle\gradle.properties` 全局关闭了 Kotlin 增量编译以规避 Windows 文件句柄锁问题，因此 Raven **强烈建议按功能/层级做合理多模块拆分**，并开启 Gradle Build Cache 与并行构建，避免单模块全量重编拖慢迭代。
   - **推荐工具链**：**Gradle `9.1.0` + AGP `9.0.1` + Kotlin `2.3.21`（通过根 `buildscript` 覆盖 AGP 内置 `2.2.10`）+ KSP `2.3.14` + `compileSdk = 36` + `Compose BOM 2026.06.01`（Material3 `1.4.0`）+ Room `2.8.5` + Media3 `1.11.1` + Coil3 `3.4.0`（或缓存 `3.3.0`）+ OkHttp `4.12.0`**。

---

## 2. 工作区与系统主机环境

| 检查项 | 实测结果 | 证据 / 备注 |
|---|---|---|
| 目标工作区 | `d:\Raven` 存在且为空目录 | `Get-ChildItem -Force d:\Raven` 无输出 |
| Git 版本 | `git version 2.54.0.windows.1` | `git -C d:\Raven status` 返回 `not a git repository`（需 `git init`） |
| Git 全局配置 | `user.name=kakka`、`user.email=ywclgl258@gmail.com`、`safe.directory=*`、`windows.appendatomically=false` | `git config --global --list` |
| 操作系统 | Windows `10.0.26300`（PowerShell 7 / Windows PowerShell） | `adb version` 与 `PATH` |
| CPU / 内存 | AMD Ryzen 9 8940HX（16 核 / 32 逻辑处理器），`31.2 GB` RAM | `Win32_Processor` / `Win32_ComputerSystem` |
| 磁盘剩余空间 | `C:` 剩余 `49.1 GB`（已用 `249.9 GB`）；`D:` 剩余 `112.4 GB`（已用 `540.5 GB`） | `Get-PSDrive C, D` |
| 辅助命令行工具 | `python` (Python 3.12)、`rg` (ripgrep 15.1.0)、`jq`、`ffmpeg` (8.1.1)、`gh`、`node`、`bun` 均在 `PATH` | `$env:PATH` |

---

## 3. Java / JDK 安装情况

| 检查项 | 实测结果 |
|---|---|
| `JAVA_HOME` | `C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot` |
| `where.exe java` | `C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot\bin\java.exe` |
| `java -version` | `openjdk version "17.0.19" 2026-04-21 LTS` (`Microsoft-13877129`, build `17.0.19+10-LTS`, 64-Bit Server VM) |
| `javac -version` | `javac 17.0.19` |
| 其他 JDK（JDK 21 / JBR） | **未安装**（已检查 `Program Files\Java`、`Adoptium`、`Zulu`、`JetBrains`、`Android Studio\jbr`、`~/.jdks`、`~/.gradle/jdks` 及 Windows Uninstall 注册表，仅此 1 个 JDK） |

**兼容性验证**：
- `com.android.tools.build:gradle:9.0.1` 的 `.module` 声明 `org.gradle.jvm.version=11`（`9.5.2` 要求 `17`）。
- `com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.14` 声明 `org.gradle.jvm.version=11`。
- `org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10 / 2.3.21 / 2.4.21` 声明 `org.gradle.jvm.version=8`。
- 因此现有 **JDK `17.0.19`** 完全满足 Gradle `9.1.0` + AGP `9.0.1` + Kotlin `2.3.21` + KSP `2.3.14` 全栈编译，无需额外安装 JDK 21。项目应统一设定 `JavaVersion.VERSION_17` 与 `JvmTarget.JVM_17`。

---

## 4. Android SDK、CLI 与模拟器 / 设备审计

### 4.1 环境变量与路径陷阱
- `ANDROID_HOME`：**未设置**
- `ANDROID_SDK_ROOT`：**未设置**
- `D:\rule34\local.properties` 实测内容：`sdk.dir=D\:/Android/Sdk`
- `adb`、`emulator`、`sdkmanager`、`avdmanager`：**均不在 `PATH` 中**
- **行动项**：
  1. Raven 初始化时必须创建 `d:\Raven\local.properties`（纳入 `.gitignore`），写入 `sdk.dir=D\:/Android/Sdk`。
  2. 在自动化/终端命令中，显式设置或通过完整路径调用：
     ```powershell
     $env:ANDROID_HOME = "D:\Android\Sdk"
     $env:PATH = "D:\Android\Sdk\platform-tools;D:\Android\Sdk\emulator;D:\Android\Sdk\cmdline-tools\latest\bin;$env:PATH"
     ```

### 4.2 Android SDK 已安装组件（`D:\Android\Sdk`）

通过 `D:\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat --list_installed` 与各 `source.properties` 实测确认：

| 组件路径 (`sdkmanager` Path) | 版本 / Revision | 位置 | 备注 |
|---|---|---|---|
| `platforms;android-36` | Rev `2`（API 36 / Android 16, Ext 17） | `platforms\android-36` | **推荐 `compileSdk = 36` / `targetSdk = 36`** |
| `platforms;android-35` | Rev `2`（API 35 / Android 15, Ext 13） | `platforms\android-35` | 可用 |
| `build-tools;36.0.0` | `36.0.0` | `build-tools\36.0.0` | 与 API 36 匹配 |
| `build-tools;34.0.0` | `34.0.0` | `build-tools\34.0.0` | 旧版残留 |
| `platform-tools` | `37.0.1`（`adb 1.0.41 / 37.0.1-15733141`） | `platform-tools` | 已就绪 |
| `cmdline-tools;latest` | `12.0` | `cmdline-tools\latest\bin` | 含 `sdkmanager.bat`、`avdmanager.bat`、`apkanalyzer.bat`、`lint.bat`、`retrace.bat`、`screenshot2.bat`（执行时有 SDK XML v4 warning，但不阻塞） |
| `emulator` | `37.2.13`（build `16740729`） | `emulator` | 已就绪 |
| `system-images;android-36;google_apis;x86_64` | Rev `7` | `system-images\android-36\google_apis\x86_64` | 当前在线模拟器镜像 |
| `system-images;android-36;google_apis;arm64-v8a` | Rev `7` | `system-images\android-36\google_apis\arm64-v8a` | 已安装 |
| `system-images;android-24;google_apis;x86_64` | Rev `27` | `system-images\android-24\google_apis\x86_64` | 可用于 `minSdk 24` 向下兼容验证 |
| `ndk;28.2.13676358` / `ndk;27.2.12479018` | `28.2` / `27.2` | `ndk\` | 已安装 |
| `cmake;3.31.1` / `cmake;3.22.1` | `3.31.1` / `3.22.1` | `cmake\` | 已安装 |
| `licenses` | 已完整签署 | `D:\Android\Sdk\licenses` | 含 `android-sdk-license`、`android-sdk-preview-license` 等 8 个许可文件 |

> **注**：`platforms;android-37` **未安装**。若未来升级至 API 37，需先通过 `sdkmanager` 下载 `platforms;android-37` 并将 AGP 升级至 `>= 9.1.0`。

### 4.3 Android CLI、Android Studio 与设备/模拟器状态

| 检查项 | 实测结果 |
|---|---|
| `android` CLI (`android info` / `emulator list` / `studio check`) | **未安装**（`where.exe android` 报错未找到；`PATH` 中的 `C:\Users\ywclg\AppData\AndroidCLI` 目录不存在，全盘 `AppData` 亦无安装目录） |
| Android Studio | **未安装**（标准安装目录与注册表均无记录） |
| `adb devices -l` | **`emulator-5554`** 在线：`device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa transport_id:1` |
| 在线模拟器属性 | AVD=`test_x86_64`、Android `16` (API `36`)、ABI=`x86_64`、分辨率 `1080x2400`、`420 dpi` |
| `emulator -list-avds` / `avdmanager list avd` | 1 个已注册 AVD：`test_x86_64`（`C:\Users\ywclg\.android\avd\test_x86_64.avd`，`pixel_6`，`4` vCPU，`1536M` RAM，`hw.gpu.enabled=no`、`hw.gpu.mode=auto`，`PlayStore.enabled=no`，`sdcard=512 MB`） |
| `D:\Android\Avds` | 存在 4 个历史测试目录（`r34v-all36.XmIHnC`、`r34v-fix24.RlLm8I`、`r34v-fix36.ByvkHW`、`r34v_downloads.avd`），未在默认 `~/.android/avd` 注册 |

**视觉与测试验证建议**：
- 日常 Compose UI / `connectedDebugAndroidTest` / 截图回归可直接复用在线的 `emulator-5554`（API 36）。
- 注意 `test_x86_64` 配置了 `hw.ramSize = 1536M` 且 `hw.gpu.enabled = no`（软渲染或 auto 回退）；若后续测试大分辨率漫画多图解码、Media3 硬解或平板宽屏适配布局，可用已安装的 `system-images;android-36;google_apis;x86_64` 通过 `avdmanager.bat` 额外创建一个 `4096M` RAM + 硬件 GPU（或平板分辨率）的 AVD。

---

## 5. 参照项目 (`D:\rule34`) 工具链与本机 Gradle 缓存审计

### 5.1 `D:\rule34` 现有构建配置（只读实测）

| 配置文件 | 实测配置内容 |
|---|---|
| `D:\rule34\gradle\wrapper\gradle-wrapper.properties` | `distributionUrl=https\://services.gradle.org/distributions/gradle-9.1.0-all.zip` |
| `D:\rule34\gradle.properties` | `org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8`、`android.useAndroidX=true`、`kotlin.code.style=official` |
| `D:\rule34\settings.gradle.kts` | 单模块 `include(":app")`；`RepositoriesMode.FAIL_ON_PROJECT_REPOS`；仓库 `google()` + `mavenCentral()`（插件多加 `gradlePluginPortal()`） |
| `D:\rule34\build.gradle.kts` | `com.android.application` `9.0.1`、`org.jetbrains.kotlin.plugin.compose` `2.2.10`（**无 `org.jetbrains.kotlin.android`**，使用 AGP 9 内置 Kotlin） |
| `D:\rule34\app\build.gradle.kts` | `compileSdk = 36`、`buildToolsVersion = "36.0.0"`、`minSdk = 24`、`targetSdk = 36`、JDK/JVM 17、`isMinifyEnabled = true`、`isShrinkResources = true`、`lint { abortOnError = true }` |

**`D:\rule34\app\build.gradle.kts`（L151–184）当前依赖清单**：
- **Compose**：`compose-bom:2025.08.01`（`ui:1.9.0`、`foundation:1.9.0`、`material3:1.3.2`）、`activity-compose:1.9.0`、`lifecycle-runtime-compose:2.8.7`、`io.github.kyant0:backdrop:1.0.0`
- **基础 / 异步**：`core-ktx:1.13.1`、`activity-ktx:1.9.0`、`lifecycle-runtime-ktx:2.7.0`、`kotlinx-coroutines-android:1.9.0`
- **媒体 / 图片**：`media3-*:1.9.2`（`exoplayer`、`exoplayer-hls`、`datasource-okhttp`、`ui`、`database`）、`coil3:3.3.0`（`coil-compose`、`coil-network-okhttp`）
- **网络 / 解析**：`okhttp:4.12.0`、`jsoup:1.18.3`、`gson:2.11.0`
- **测试**：`junit:4.13.2`、`mockwebserver:4.12.0`、`androidx.test:runner:1.6.2`、`androidx.test.ext:junit:1.2.1`、`ui-test-junit4`
- **显著缺失（旧项目债务）**：未使用 Version Catalog (`libs.versions.toml`)、无 Navigation 库、无 DI 框架（Hilt/Koin）、无 Room 数据库、无 DataStore、无 WorkManager、无 `kotlinx-serialization`（仍用 Gson）、无 KSP、无 Paging、无 Turbine/MockK。

### 5.2 本机用户级 Gradle 配置与缓存（`%USERPROFILE%\.gradle`）

1. **`C:\Users\ywclg\.gradle\gradle.properties`（极其重要！）**：
   ```properties
   # Added during debugging: on Windows the Kotlin incremental compiler's
   # cacheable caches-jvm files cannot be closed (file handles still held),
   # failing assembleRelease with "Could not close incremental caches".
   # Disabling incremental compilation makes the build reliable.
   # This is a local machine issue, unrelated to project code; CI unaffected.
   kotlin.incremental=false
   ```
   - **工程含义**：本机每个 Gradle 构建都会受 `kotlin.incremental=false` 影响（单模块内修改任意 Kotlin 文件将触发该模块全量 Kotlin 重编）。
   - **Raven 应对策略**：
     - 保持合理的 Gradle 多模块拆分（如 `:core:model`、`:core:network`、`:core:database`、`:core:designsystem`、`:site-api` / `:sites`、`:feature:*`、`:app`），将变更半径控制在最小模块内。
     - 在 Raven `gradle.properties` 中启用 `org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8`、`org.gradle.parallel=true`、`org.gradle.caching=true`、`org.gradle.configuration-cache=true`。
     - 慎用全项目 KSP 注解处理（Room 仅在 `:core:database` 使用 KSP；DI 若选 Koin 可彻底免去全局 KSP/字节码插桩开销，若选 Hilt 则按模块隔离）。

2. **已缓存的 Gradle Wrapper 分发包（`%USERPROFILE%\.gradle\wrapper\dists`）**：
   - `gradle-9.1.0-all`（推荐直接复用，零下载）
   - `gradle-8.11.1-all`

3. **已缓存的关键依赖（`%USERPROFILE%\.gradle\caches\modules-2\files-2.1`）**：
   - 已缓存（热命中）：`com.android.tools.build:gradle` (`8.7.2`, **`9.0.1`**)、`kotlin-gradle-plugin` (`2.2.10`)、`kotlin-compose-compiler-plugin-embeddable` (`2.2.10`)、`compose-bom` (`2025.08.01`)、`material3` (`1.3.2`)、`media3-exoplayer` (`1.9.2`)、`coil3:coil-compose` (`3.3.0`)、`okhttp` (`4.12.0`)、`mockwebserver` (`4.12.0`)、`kotlinx-coroutines-android` (`1.9.0`, **`1.10.2`**)、`lifecycle-runtime-compose` (`2.8.7`, `2.9.0`, `2.9.4`)、`core-ktx` (`1.13.1`, `1.15.0`)、`jsoup` (`1.18.3`)、`gson` (`2.11.0`)、`backdrop` (`1.0.0`)、`junit` (`4.13.2`)、`androidx.test:runner` (`1.6.2`)、`androidx.test.ext:junit` (`1.2.1`)。
   - 未缓存（需首次联网拉取，网络已实测通畅）：KSP、Hilt/Koin、Navigation Compose、Room、DataStore、WorkManager、kotlinx-serialization、Paging、Turbine、MockK、Tink、以及新版本 Compose BOM / Material3 / Media3。

---

## 6. 网络与代理连通性实测

| 检查项 | 实测结果 |
|---|---|
| 环境变量代理 | `HTTP_PROXY=http://127.0.0.1:7897`、`HTTPS_PROXY=http://127.0.0.1:7897` |
| Windows 系统代理 (WinINET) | `ProxyEnable=1`、`ProxyServer=127.0.0.1:7897` |
| 本地代理端口探测 | `Test-NetConnection 127.0.0.1 -Port 7897` -> `TcpTestSucceeded=True` |
| Google Maven (`dl.google.com`) | 经代理 `HTTP 200 OK`；直连 (`--noproxy *`) `HTTP 200 OK` |
| Maven Central (`repo1.maven.org`) | 经代理 `HTTP 200 OK`；直连 `HTTP 200 OK` |
| Gradle Plugin Portal (`plugins.gradle.org`) | 经代理 `HTTP 303 See Other`；直连 `HTTP 303 See Other` |
| Gradle Distributions (`services.gradle.org`) | 经代理 `HTTP 200 OK`；直连 `HTTP 200 OK` |

**说明**：直连与代理均可达（推测系统开启了 TUN/透明路由或直连可达 `[INFERENCE]`）。`C:\Users\ywclg\.gradle\gradle.properties` 中未配置代理参数，**严禁在项目 `d:\Raven\gradle.properties` 中硬编码本机 `127.0.0.1:7897`**；若未来 Gradle 遇网络波动，可在命令行临时传 `-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897`。

---

## 7. 推荐依赖与工具链版本矩阵（已逐项校验 AAR / POM 兼容性）

### 7.1 为什么锁定 `AGP 9.0.1` + `Gradle 9.1.0` + `compileSdk 36` + `Kotlin 2.3.21`

经过对 Google Maven 与 Maven Central 真实 `.pom` / `.module` / `.aar` (`aar-metadata.properties`) 的逐项校验，发现两条硬性兼容性边界：
1. **API 37 / AGP 9.1 断层**：`compose-bom:2026.08.00+`（含 `ui:1.12+`、`material3:1.5.0`）、`core-ktx:1.19.x`、`lifecycle:2.11.x`、`navigation-compose:2.10.x`、`hilt-navigation-compose:1.4.0`、`coil3:3.6.x` 均要求 `minCompileSdk=37`（AndroidX 同时要求 `minAndroidGradlePluginVersion=9.1.0`）。锁定 **`compileSdk = 36` + `AGP 9.0.1` + `Gradle 9.1.0`** 可零额外 SDK/Wrapper 下载、即刻开跑。
2. **AGP 9.0 Built-in Kotlin + KSP 2.3 协同**：
   - `AGP 9.0.1` 默认开启 built-in Kotlin（不再使用 `org.jetbrains.kotlin.android`，也禁止 `kotlin-kapt`），其 POM 默认携带 `kotlin-gradle-plugin:2.2.10`。
   - 但 AGP 9 built-in Kotlin 需要 **KSP `>= 2.3.1`**（旧版 `2.2.10-2.0.2` 存在任务注册兼容问题），且 KSP `2.3.14`、Hilt `2.60.1`、Koin `4.2.2`、Coil3 `3.4.0`、Serialization `1.10.0` 均基于 Kotlin `2.3.x` 编译。
   - **官方推荐做法**：保持 `AGP 9.0.1` + `Gradle 9.1.0`（命中本机缓存），在根 `build.gradle.kts` 通过 `buildscript { dependencies { classpath(libs.kotlin.gradle.plugin) } }` 将内置 KGP 升级为 **`2.3.21`**（同时 `compose` / `serialization` 编译器插件均锁定 `2.3.21`）。
   - *备选零升级方案*：若想完全复用缓存中的 Kotlin `2.2.10`，则需降级配套为 `KSP 2.3.6` + `Hilt 2.59.2`（或 `Koin 4.1.1`）+ `Serialization 1.9.0` + `Coil3 3.3.0`，且不可使用 `Coil3 >= 3.5.0` 或 `Serialization >= 1.13.0`（二者以 Kotlin `2.4.x` 编译，会导致 Kotlin `2.2` 元数据不兼容错误）。**推荐直接采用下表 `Kotlin 2.3.21` 主方案。**

### 7.2 Raven 推荐版本矩阵（`gradle/libs.versions.toml` 基准）

> 以下每个版本均已通过真实 Maven 仓库 `.pom` / `.module` / `.aar` 验证：`JVM <= 17`、`minCompileSdk <= 36`、`minAndroidGradlePluginVersion <= 9.0.1`、Kotlin 元数据与 `2.3.21` 完全兼容。

| 类别 | 制品 / 工具 | 推荐版本 | 实测兼容依据 (`minCompileSdk` / `minAGP` / `Kotlin`) | 备注（含本机缓存状态） |
|---|---|---|---|---|
| **构建工具** | Gradle Wrapper | **`9.1.0`** | 适配 AGP `9.0.1`、JDK 17 | **已缓存** (`gradle-9.1.0-all`) |
| **构建工具** | Android Gradle Plugin | **`9.0.1`** | `jvm=11`、内置 Kotlin、API 36 | **已缓存**；勿再应用 `kotlin-android` |
| **语言 / 编译** | Kotlin (`kgp` / `compose` / `serialization`) | **`2.3.21`** | `jvm=8`；通过根 `buildscript` 覆盖 AGP 内置 `2.2.10` | 2.3 稳定末版，与 KSP/Hilt/Coil/Koin 零冲突 |
| **注解处理** | KSP (`com.google.devtools.ksp`) | **`2.3.14`** | `jvm=11`、`kotlin-stdlib:2.3.20` | 满足 AGP 9 built-in Kotlin (`>= 2.3.1`) |
| **SDK 目标** | `compileSdk` / `targetSdk` / `minSdk` | **`36` / `36` / `26`** (或 `24`) | `android-36` & `build-tools:36.0.0` 已安装 | 若需与 `D:\rule34` 保持同下限可设 `minSdk = 24`（已有 API 24 镜像） |
| **UI 框架** | `androidx.compose:compose-bom` | **`2026.06.01`** | `ui:1.11.4`、`foundation:1.11.4`、`material3:1.4.0` (`minCompileSdk=35`, `minAGP=8.6.0`) | **API 36 最高兼容 BOM**（`2026.08.00+` 需 API 37） |
| **UI 适配** | `material3-adaptive-navigation-suite` / `adaptive` | **`1.4.0`** / **`1.2.0`** | 均 `minCompileSdk=35`, `minAGP=8.6.0` | 平板/折叠屏导航栏与双栏自适应 |
| **UI 图标** | `material-icons-extended` | 由 BOM 托管 (`1.7.8`) | 兼容 API 36 | 注意 Release R8 裁剪（`isMinifyEnabled=true`） |
| **核心 KTX** | `androidx.core:core-ktx` | **`1.18.0`** | `minCompileSdk=36`, `minAGP=8.9.1`, `kotlin:2.1.20` | 勿升 `1.19.x`（需 API 37） |
| **Activity** | `androidx.activity:activity-compose` | **`1.13.0`** | `minCompileSdk=36`, `minAGP=8.9.1`, `kotlin:2.1.20` | 支持完整 Edge-to-Edge |
| **Lifecycle** | `androidx.lifecycle:lifecycle-runtime-compose` / `viewmodel-compose` | **`2.10.0`** | `minCompileSdk=35`, `minAGP=8.6.0`, `kotlin:2.0.21` | 勿升 `2.11.x`（需 API 37） |
| **导航** | `androidx.navigation:navigation-compose` | **`2.9.8`** | `minCompileSdk=35`, `minAGP=8.6.0`（支持类型安全 `@Serializable` 路由） | 勿升 `2.10.x`（需 API 37）；若架构团队明确选 Nav3，可用 `navigation3:1.2.2` (`minCompileSdk=36`) |
| **依赖注入 (推荐二选一)** | **方案 A：Hilt** (`hilt-android` + `hilt-compiler`)<br>`androidx.hilt:hilt-navigation-compose` / `hilt-work` / `hilt-compiler` | **`2.60.1`**<br>**`1.3.0`** | `Hilt 2.59+` 官方支持 AGP 9 + KSP（`2.60.1` 依赖 `kotlin-bom:2.3.21`）；`androidx.hilt:1.3.0` (`minCompileSdk=35`) | **勿升 `hilt-navigation-compose:1.4.0`**（需 API 37 + AGP 9.1）；禁用 `kapt` 改用 `ksp` |
| **依赖注入 (轻量替代)** | **方案 B：Koin** (`io.insert-koin:koin-bom`) | **`4.2.2`** | `minCompileSdk=1`, `kotlin:2.3.20`；零 KSP/字节码插件 | 在本机 `kotlin.incremental=false` 环境下增量构建最快 |
| **数据库** | `androidx.room:room-runtime` / `room-ktx` / `room-compiler` / `room-paging` | **`2.8.5`** | `minCompileSdk=34`, `minAGP=8.1.1`, `kotlin:2.1.20/2.2.0` | 可配合 `androidx.room` Gradle 插件 `2.8.5` 导出 Schema |
| **配置存储** | `androidx.datastore:datastore-preferences` | **`1.3.0`** | `minCompileSdk=34`, `minAGP=8.1.1`, `kotlin:2.2.20` | 替代 `SharedPreferences` |
| **后台任务** | `androidx.work:work-runtime-ktx` | **`2.12.0`** | `minCompileSdk=35`, `minAGP=8.6.0`, `kotlin:2.1.20` | 统一下载中心 / 订阅更新检查 |
| **分页** | `androidx.paging:paging-compose` / `paging-runtime` | **`3.5.3`** | `minCompileSdk=35`, `minAGP=8.6.0`, `kotlin:2.1.20` | 列表流分页（按需选用） |
| **视频播放** | `androidx.media3:media3-*` (`exoplayer`, `exoplayer-hls`, `exoplayer-dash`, `datasource-okhttp`, `ui`, `ui-compose`, `database`, `session`) | **`1.11.1`** | `minCompileSdk=36`, `minAGP=1.0.0`；`datasource-okhttp` 依赖 `okhttp:4.12.0` | 较 `D:\rule34` (`1.9.2`, 已缓存) 升级 2 个小版本 |
| **图片加载** | `io.coil-kt.coil3:coil-compose` / `coil-network-okhttp` / `coil-gif` | **`3.4.0`** (或 **`3.3.0`**) | `3.4.0`: `minCompileSdk=35`, `kotlin:2.3.10`, `okhttp:4.12.0`；`3.3.0`: **已缓存**, `kotlin:2.2.0` | **勿升 `3.6.x`**（需 API 37）；`3.5.0` 为 Kotlin 2.4 编译，不建议 |
| **网络** | `com.squareup.okhttp3:okhttp` / `logging-interceptor` / `mockwebserver` | **`4.12.0`** | **已缓存**；与 `media3-datasource-okhttp:1.11.1` 及 `coil-network-okhttp:3.4.0` 依赖完全一致 | 规避 OkHttp 5.x 与 Media3/Coil3 传递依赖混用风险 |
| **序列化** | `org.jetbrains.kotlinx:kotlinx-serialization-json` | **`1.10.0`** | `kotlin-stdlib:2.3.0` | 替代 Gson；勿升 `1.13.0`（Kotlin `2.4.10` 编译） |
| **协程** | `org.jetbrains.kotlinx:kotlinx-coroutines-android` / `test` | **`1.10.2`** (或 `1.11.0`) | `1.10.2` **已缓存** (`kotlin:2.1.0`)；`1.11.0` (`kotlin:2.2.20`) | 均完全兼容 |
| **HTML 解析** | `org.jsoup:jsoup` | **`1.18.3`** (或 `1.24.1`) | `1.18.3` **已缓存**；`1.24.1` 为最新稳定版 | 用于站点 HTML 解析器 |
| **加密备份（按需）** | `com.google.crypto.tink:tink-android` | **`1.24.0`** | 纯 Java/Android 库 | 或使用标准 JDK/Android `javax.crypto` AES-GCM + PBKDF2 零额外依赖实现兼容格式 |
| **玻璃拟态（按需）** | `io.github.kyant0:backdrop` | **`1.0.6`** (或缓存 `1.0.0`) | `1.0.6`: `minCompileSdk=36`, `kotlin:2.3.10`, Compose 1.10+ | 若 Raven 采用原生 Material 3 表面层级而非 LiquidGlass，可直接移除 |
| **测试栈** | `junit:junit` / `kotlinx-coroutines-test` / `turbine` / `mockk` / `robolectric`<br>`androidx.test:runner` / `androidx.test.ext:junit` | `4.13.2` / `1.10.2` / `1.2.1` / `1.14.12` / `4.18.1`<br>`1.6.2` (或 `1.7.0`) / `1.2.1` (或 `1.3.0`) | 均兼容 API 36 与 JDK 17 | `junit:4.13.2`、`mockwebserver:4.12.0`、`runner:1.6.2`、`ext:junit:1.2.1` 已缓存 |

### 7.3 AGP 9.0.1 + Kotlin 2.3.21 + KSP 2.3.14 根配置关键范式

供脚手架搭建直接参考，避免踩 AGP 9 built-in Kotlin 的常见坑：

```kotlin
// d:\Raven\build.gradle.kts（关键片段）
buildscript {
    dependencies {
        // 覆盖 AGP 9.0.1 默认内置的 Kotlin 2.2.10 -> 2.3.21
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    // 若采用 Hilt：
    alias(libs.plugins.hilt) apply false
    // 注意：禁止声明或应用 org.jetbrains.kotlin.android 及 kotlin-kapt！
}
```

并在所有 Android 模块中用 `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }`（而非已废弃的 `kotlinOptions`）配置字节码目标，初始化后立即运行 `.\gradlew.bat buildEnvironment` 验证 `kotlin-gradle-plugin` 已解析为 `2.3.21`。

---

## 8. 下一步环境初始化检查清单

1. 在 `d:\Raven` 初始化 Git 仓库与 `.gitignore`（忽略 `local.properties`、`.gradle/`、`build/`、`*.keystore`、`signing.properties`）。
2. 生成 `d:\Raven\local.properties`：`sdk.dir=D\:/Android/Sdk`。
3. 复用 `gradle-9.1.0-all.zip` Wrapper（可从 `D:\rule34\gradle\wrapper` 对齐版本，不要修改 `D:\rule34`）。
4. 首次同步构建后，运行 `.\gradlew.bat :app:assembleDebug` 并通过 `D:\Android\Sdk\platform-tools\adb.exe -s emulator-5554 install -r ...` 在线冒烟验证 `emulator-5554`（API 36）。
