# Raven 更新渠道：发布操作手册

> 面向在发布机上执行 `publish.py` 的维护者与自动化 Agent。渠道安全边界见
> [AGENTS.md §2](../AGENTS.md)、客户端校验见 [DATA_MEDIA_SECURITY.md §6.5](../docs/DATA_MEDIA_SECURITY.md)。

---

## 1. 发布机依赖（缺一即拒绝发布）

`publish.py` 不做任何自实现密码学校验，全部委托 Android SDK 官方工具：

| 工具 | 用途 | 失败时行为 |
|---|---|---|
| `apksigner`（build-tools） | 校验 v1/v2/v3 签名并输出证书指纹；默认范围 **加** API 23 范围各跑一次 | 缺工具或验签失败 → `SignatureError`，渠道零改动 |
| `aapt2`（build-tools） | `dump badging` 读取真实包名、`versionCode`、`versionName`、`split`、`minSdkVersion` | 同上 |
| `java`（JDK 17+） | `apksigner` 的运行时 | 同上 |

工具发现顺序：`--apksigner/--aapt2` 显式路径 → `RAVEN_APKSIGNER` / `RAVEN_AAPT2` 环境变量 →
`ANDROID_BUILD_TOOLS` / `ANDROID_HOME` / `ANDROID_SDK_ROOT` → 仓库 `local.properties` 的 `sdk.dir`
→ `PATH`。多个 build-tools 版本时取版本号最大的。

安装（Ubuntu 发布机示例，用仓库同款 build-tools 36.0.0）：

```bash
sudo apt-get install -y openjdk-17-jre-headless unzip
curl -fsSLo /tmp/build-tools.zip https://dl.google.com/android/repository/build-tools_r36_linux.zip
sudo mkdir -p /opt/android-sdk
sudo unzip -q /tmp/build-tools.zip -d /opt/android-sdk
sudo mv /opt/android-sdk/android-* /opt/android-sdk/build-tools/36.0.0 2>/dev/null || true
export ANDROID_HOME=/opt/android-sdk    # 写入发布机的 profile，供 systemd/cron 继承
/opt/android-sdk/build-tools/36.0.0/apksigner version
```

只读发布机（无 SDK）时，把发布动作放到装有 SDK 的可信主机执行，再把产物 rsync 到 web 根；
不要在无 `apksigner` 的机器上跳过校验。

## 2. 正确定义

一次发布写入以下三项，缺任一即为渠道故障：

1. `public/raven/releases/assets/<releaseId>` —— APK 本体，`releaseId = 9000000 + versionCode`；
2. `public/raven/releases/latest` —— GitHub Release 兼容清单，客户端只读这一个指针；
3. `<web_root>/../.raven-update-state/publish-history.json` —— 保留窗口账本，用于决定哪些旧资产还能删。

`publish.py` 的顺序是 **锁 → 落资产 → fsync → 原子改名资产 → 写历史 → 原子改名清单 → 清理超窗资产**。
清单永远最后替换，因此任何中途失败（进程被杀、磁盘满、清单写入抛错）都只留下一个**无人引用的孤儿资产**，
线上仍在服务的旧清单与旧资产完好。

## 3. 发布命令

```bash
python3 deploy/update-server/publish.py \
  --apk app/build/outputs/apk/release/app-release.apk \
  --release-properties release.properties \
  --web-root /var/www/raven-update/public \
  --base-url https://ywclgl258.us.ci:8443/raven/releases \
  --release-notes "本次更新说明"
```

成功时输出 JSON 摘要：`versionName`/`versionCode`/`packageName`/`minSdkVersion`/`signatureSchemes`/
`certSha256`/`size`/`sha256`/`idempotent`/`retainedAssets`/`prunedAssets`/`beforeManifestTag`。
被拒时退出码 `2`，stderr 打印 `{"status":"rejected","reason":...}`，渠道零改动。

常用可选参数：

| 参数 | 默认 | 说明 |
|---|---|---|
| `--expected-package` | `com.raven.app` | 只接受正式包名；Debug 包（`com.raven.app.debug`）必然被拒 |
| `--state-dir` | `<web_root>/../.raven-update-state` | 锁与历史账本位置，须在 web 根之外 |
| `--keep-previous` | `2` | 保留多少个历史版本资产（不含本次），供已取旧清单的客户端下载 |
| `--apksigner` / `--aapt2` | 自动发现 | 显式工具路径 |

## 4. 拒绝规则（全部在写 web 根之前判定）

| 输入 | 结果 |
|---|---|
| 普通 ZIP / 非 APK | 拒：`aapt2` 解析不出 package 或 `apksigner` `DOES NOT VERIFY` |
| Debug 包 | 拒：包名 `com.raven.app.debug` 或证书非官方 |
| 包名 / `versionCode` / `versionName` 与 `release.properties` 不符 | 拒 |
| `minSdkVersion > 24` | 拒：不满足 API 24-27 universal 要求 |
| 拆分 APK（`split=` 非空） | 拒：渠道只发 universal 整包 |
| 证书指纹 ≠ `releaseCertificate` | 拒 |
| v1 / v2 / v3 任一未通过 | 拒（API 24-27 依赖 v1，因此单独以 API 23 范围强制验证） |
| 多签名者 / 多证书 | 拒 |
| 同版本号不同字节重发 | 拒：资产按 `immutable` 缓存，原地换包永远到不了已缓存客户端 |
| 同版本号同字节重发 | 幂等通过：不重写资产，清单仅在实际内容变化时替换 |
| `versionCode` 回退 / 同码改名 / `versionName` 回退 | 拒 |
| 并发第二个发布进程 | 拒：`flock` 未获取到 |

## 5. 回滚

保留窗口内的上一版资产仍在磁盘上，回滚即把清单指回旧版本：

```bash
python3 deploy/update-server/publish.py --apk <旧版 APK> --release-properties <旧版 properties> ...
```

脚本会以更低的 `versionCode` 拒绝（防止已被新清单缓存的客户端拿到旧包）。真正的回滚应先在
`release.properties` 里把 `versionCode`/`versionName` 提到一个**新号**（例如线上是 14，回滚包发 15 带旧代码），
再走正常发布。禁止手工编辑 `latest` 绕过校验。

## 6. 本地自测

```bash
python -m unittest discover -s deploy/update-server/tests -v
```

`RealApkValidationTests` 直接用 Gradle 已产出的 `app-release.apk` / `app-debug.apk` 跑官方工具，
覆盖正例、普通 ZIP、Debug 包、版本不符、版本名不符、异证书、篡改包与缺工具兜底；
`StructureTests` 用桩校验器覆盖顺序、保留窗口、锁、幂等与单调性。所有用例都在临时目录内，不触碰线上。
