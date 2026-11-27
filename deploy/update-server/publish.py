#!/usr/bin/env python3
"""Atomically publish one signed Raven APK to the Nginx update channel.

The publisher is the gatekeeper of the update channel: whatever it accepts becomes the package
every installed client is offered. Validation therefore happens *before* anything under
``--web-root`` is touched, and it is delegated to the authoritative Android SDK tools rather than
re-implemented here:

* ``aapt2 dump badging`` supplies the real package name, ``versionCode``, ``versionName``, ``split``
  marker and ``minSdkVersion`` straight from the APK's binary manifest.
* ``apksigner verify --verbose --print-certs`` verifies every signing scheme present and prints the
  certificate digests. It is run twice: once for the default range (v2/v3 as used by API 24+ up to
  8.1) and once pinned to ``--min-sdk-version 23 --max-sdk-version 23``, because the default range
  skips JAR (v1) validation when a v2/v3 block exists — yet API 24-27 devices still need a valid
  v1 signature to install the universal package.

Required release facts (all fail-closed):

* package == ``com.raven.app`` (or ``--expected-package``), not a split APK;
* ``versionCode`` / ``versionName`` exactly match ``release.properties``;
* ``minSdkVersion <= 24`` so the universal package really supports API 24-27;
* exactly one signer, whose certificate SHA-256 equals the pinned ``releaseCertificate``;
* v1, v2 and v3 schemes all verified.

If any of ``apksigner`` / ``aapt2`` (and their Java runtime) is unavailable, publishing aborts with
an explicit message — there is no cryptographic fallback path here.

Publication ordering (why clients never see a dangling manifest):

    lock -> stage asset -> fsync asset -> rename asset -> rename manifest -> history ledger -> prune

The manifest is the single pointer clients read, so it is replaced *last*. Dying at any earlier
step leaves the previously published manifest and its asset untouched; the orphan asset is inert
and is pruned by the next successful run. History is recorded only after the manifest swap, so a
failed publication can never consume the keep-previous budget and evict the live manifest's asset.
Previous assets are retained for a grace window so a client that already fetched an older manifest
can still download.

Republishing the same version with different bytes is refused: assets are served with
``Cache-Control: immutable``, so a silently swapped payload would never reach clients that already
cached that URL.
"""

from __future__ import annotations

import argparse
import contextlib
import dataclasses
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import time
import uuid
import zipfile

MIRROR_RELEASE_ID_BASE = 9_000_000
DEFAULT_PACKAGE_NAME = "com.raven.app"
SUPPORTED_MIN_SDK = 24
MAX_PROPERTIES_BYTES = 64 * 1024
MAX_MANIFEST_BYTES = 512 * 1024
MAX_RELEASE_NOTES_CHARS = 8_000
MAX_HISTORY_ENTRIES = 20
DEFAULT_KEEP_PREVIOUS = 2
CHUNK_SIZE = 1024 * 1024
TOOL_TIMEOUT_SECONDS = 180

APKSIGNER_ENV = "RAVEN_APKSIGNER"
AAPT2_ENV = "RAVEN_AAPT2"
BUILD_TOOLS_ENV_VARS = ("ANDROID_BUILD_TOOLS", "ANDROID_HOME", "ANDROID_SDK_ROOT")


class PublishError(Exception):
    """A refused publish. Nothing under the web root has been modified."""


class SignatureError(PublishError):
    """The APK is not a valid, officially signed Raven release build."""


@dataclasses.dataclass(frozen=True)
class ApkIdentity:
    package_name: str
    version_code: int
    version_name: str
    min_sdk: int
    cert_sha256: str
    schemes: tuple[str, ...]
    size: int
    sha256: str


# ---------------------------------------------------------------------------------------------
# File helpers
# ---------------------------------------------------------------------------------------------


def release_properties(path: Path) -> dict[str, str]:
    if not path.is_file():
        raise PublishError(f"release.properties 不存在：{path}")
    if path.stat().st_size > MAX_PROPERTIES_BYTES:
        raise PublishError(f"release.properties 体积异常（上限 {MAX_PROPERTIES_BYTES} 字节）")
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        if separator:
            values[key.strip()] = value.strip()
    return values


def _fsync_dir(path: Path) -> None:
    """Make the rename durable; POSIX only, best effort elsewhere."""
    if not hasattr(os, "O_DIRECTORY"):
        return
    try:
        fd = os.open(path, os.O_DIRECTORY)
    except OSError:
        return
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _ensure_regular_file(path: Path, *, what: str) -> None:
    """Refuse symlinks/special files so a crafted path cannot redirect a write."""
    try:
        mode = os.lstat(path).st_mode
    except FileNotFoundError:
        return
    if not stat.S_ISREG(mode):
        raise PublishError(f"{what} 不是普通文件（疑似符号链接或特殊文件）：{path}")


def atomic_write_text(path: Path, text: str, *, mode: int = 0o644) -> None:
    _ensure_regular_file(path, what="目标文件")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.part")
    try:
        with temporary.open("w", encoding="utf-8", newline="\n") as output:
            output.write(text)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, path)
        _fsync_dir(path.parent)
    finally:
        temporary.unlink(missing_ok=True)


def atomic_json(path: Path, value: object, *, mode: int = 0o644) -> None:
    atomic_write_text(path, json.dumps(value, ensure_ascii=False, indent=2) + "\n", mode=mode)


def load_published_manifest(manifest_path: Path) -> dict | None:
    """Read the currently published manifest under a hard size cap; never follow a symlink."""
    if manifest_path.is_symlink():
        raise PublishError(f"已发布清单是符号链接，拒绝发布：{manifest_path}")
    if not manifest_path.is_file():
        return None
    if manifest_path.stat().st_size > MAX_MANIFEST_BYTES:
        raise PublishError(f"已发布清单体积异常（上限 {MAX_MANIFEST_BYTES} 字节）")
    try:
        value = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise PublishError(f"已发布清单无法解析：{error}") from error
    if not isinstance(value, dict):
        raise PublishError("已发布清单不是 JSON 对象")
    return value


class PublishLock:
    """Exclusive publisher lock.

    POSIX uses ``flock`` so a released lock cannot be stolen; Windows (local testing only) falls
    back to an ``O_EXCL`` lock file recording the owner pid and reclaims stale entries.
    """

    def __init__(self, path: Path) -> None:
        self._path = path
        self._fd: int | None = None

    def __enter__(self) -> "PublishLock":
        self._path.parent.mkdir(parents=True, exist_ok=True)
        if os.name == "posix":
            import fcntl

            fd = os.open(self._path, os.O_CREAT | os.O_RDWR, 0o600)
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                os.close(fd)
                raise PublishError("另一个发布进程正持有发布锁，请稍后重试") from None
            self._fd = fd
            return self
        for _ in range(2):
            try:
                fd = os.open(self._path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            except FileExistsError:
                if self._reclaim_stale():
                    continue
                raise PublishError("另一个发布进程正持有发布锁，请稍后重试") from None
            os.write(fd, str(os.getpid()).encode("ascii"))
            self._fd = fd
            return self
        raise PublishError("无法获取发布锁")

    def _reclaim_stale(self) -> bool:
        try:
            owner = int(self._path.read_text(encoding="ascii", errors="ignore").strip() or "-1")
        except OSError:
            return True
        if owner <= 0:
            return True
        try:
            os.kill(owner, 0)
        except ProcessLookupError:
            self._path.unlink(missing_ok=True)
            return True
        except OSError:
            return False
        return False

    def __exit__(self, *_exc_info: object) -> None:
        fd = self._fd
        self._fd = None
        if fd is None:
            return
        if os.name == "posix":
            import fcntl

            with contextlib.suppress(OSError):
                fcntl.flock(fd, fcntl.LOCK_UN)
        os.close(fd)
        if os.name != "posix":
            self._path.unlink(missing_ok=True)


# ---------------------------------------------------------------------------------------------
# Android SDK toolchain discovery (authoritative validation, no local crypto)
# ---------------------------------------------------------------------------------------------


def _build_tools_sort_key(path: Path) -> tuple:
    version = path.name
    parts = [int(piece) for piece in re.findall(r"\d+", version)]
    return (*parts, version)


def _sdk_roots() -> list[Path]:
    roots: list[Path] = []
    for variable in BUILD_TOOLS_ENV_VARS:
        value = os.environ.get(variable)
        if value:
            roots.append(Path(value))
    # Gradle's local.properties sits at the repository root; the publisher may live two levels down.
    for parent in (Path(__file__).resolve().parent, *Path(__file__).resolve().parents):
        local_properties = parent / "local.properties"
        if not local_properties.is_file():
            continue
        for line in local_properties.read_text(encoding="utf-8", errors="ignore").splitlines():
            key, separator, value = line.partition("=")
            if separator and key.strip() == "sdk.dir":
                # Gradle escapes Windows drive colons as `D\:/Android/Sdk`.
                roots.append(Path(value.strip().replace("\\:", ":").replace("\\\\", "\\")))
    return roots


def _tool_names(base: str) -> list[str]:
    if os.name == "nt":
        return [f"{base}.bat", f"{base}.exe", f"{base}.cmd", base]
    return [base]


def _build_tools_candidates(base: str, roots: list[Path]) -> list[Path]:
    """Ordered candidate paths for a tool, covering roots and bare build-tools directories."""
    candidates: list[Path] = []
    for root in roots:
        directories = [root / "build-tools"]
        if root.name == "build-tools" or any(root.glob("*apksigner*")):
            directories.append(root)  # the env var already points at build-tools itself
        for build_tools in directories:
            if not build_tools.is_dir():
                continue
            version_dirs = sorted(
                (entry for entry in build_tools.iterdir() if entry.is_dir()),
                key=_build_tools_sort_key,
                reverse=True,
            )
            for version_dir in version_dirs:
                for name in _tool_names(base):
                    candidates.append(version_dir / name)
            for name in _tool_names(base):
                candidates.append(build_tools / name)
    return candidates


def _find_tool(base: str, explicit: str | None, env_name: str) -> Path | None:
    for candidate in (explicit, os.environ.get(env_name)):
        if candidate:
            path = Path(candidate)
            if path.is_file():
                return path
    if explicit or os.environ.get(env_name):
        raise PublishError(f"指定的 {base} 不存在或不是文件：{explicit or os.environ.get(env_name)}")
    for candidate in _build_tools_candidates(base, _sdk_roots()):
        if candidate.is_file():
            return candidate
    for name in _tool_names(base):
        found = shutil.which(name)
        if found:
            return Path(found)
    return None


@dataclasses.dataclass(frozen=True)
class Toolchain:
    apksigner: Path
    aapt2: Path

    @property
    def description(self) -> str:
        return f"apksigner={self.apksigner} aapt2={self.aapt2}"


def resolve_toolchain(apksigner: str | None = None, aapt2: str | None = None) -> Toolchain:
    """Locate the Android SDK tools; abort publishing if either is missing."""
    resolved_apksigner = _find_tool("apksigner", apksigner, APKSIGNER_ENV)
    resolved_aapt2 = _find_tool("aapt2", aapt2, AAPT2_ENV)
    missing = [
        name for name, found in (("apksigner", resolved_apksigner), ("aapt2", resolved_aapt2)) if not found
    ]
    if missing:
        raise PublishError(
            "缺少 Android SDK 工具：" + "、".join(missing) + "。"
            "请在发布机上安装 Android SDK build-tools（含 apksigner 与 aapt2）并设置 "
            "ANDROID_HOME / ANDROID_SDK_ROOT，或用 --apksigner / --aapt2 指定绝对路径。"
        )
    java = shutil.which("java")
    if java is None:
        raise PublishError("缺少 Java 运行时：apksigner 需要 java（JDK 17+）在 PATH 中")
    return Toolchain(apksigner=Path(resolved_apksigner), aapt2=Path(resolved_aapt2))


def _run_checked(command: list[str], *, what: str) -> str:
    try:
        completed = subprocess.run(
            command,
            capture_output=True,
            text=True,
            timeout=TOOL_TIMEOUT_SECONDS,
            check=False,
        )
    except OSError as error:
        raise PublishError(f"{what} 无法执行：{error}") from error
    except subprocess.TimeoutExpired as error:
        raise PublishError(f"{what} 执行超时（{TOOL_TIMEOUT_SECONDS}s）") from error
    output = (completed.stdout + completed.stderr).strip()
    if completed.returncode != 0:
        raise SignatureError(f"{what} 失败（退出码 {completed.returncode}）：\n{output[:2000]}")
    return output


def _badging_identity(toolchain: Toolchain, apk: Path) -> dict[str, str]:
    output = _run_checked([str(toolchain.aapt2), "dump", "badging", str(apk)], what="aapt2 dump badging")
    package_line = next((line for line in output.splitlines() if line.startswith("package: ")), None)
    if package_line is None:
        raise SignatureError("aapt2 未输出 package 元数据，APK 结构异常")
    fields = dict(re.findall(r"(?P<key>[A-Za-z0-9_]+)='(?P<value>[^']*)'", package_line))
    # aapt2 emits `minSdkVersion:'…'`, aapt emits `sdkVersion:'…'`; accept both.
    min_sdk = ""
    for line in output.splitlines():
        for key in ("minSdkVersion", "sdkVersion"):
            match = re.match(rf"{key}:'(?P<value>[^']*)'", line)
            if match:
                min_sdk = match.group("value")
                break
        if min_sdk:
            break
    return {
        "package": fields.get("name", ""),
        "versionCode": fields.get("versionCode", ""),
        "versionName": fields.get("versionName", ""),
        "split": fields.get("split", ""),
        "minSdk": min_sdk,
    }


SIGNER_DIGEST = re.compile(r"Signer #(?P<index>\d+) certificate SHA-256 digest: (?P<digest>[0-9a-fA-F]{64})")
SCHEME_LINE = re.compile(r"Verified using (?P<scheme>v[0-9.]+) scheme[^:]*: (?P<value>true|false)")
SIGNER_COUNT = re.compile(r"Number of signers: (?P<count>\d+)")


def _apksigner_report(
    toolchain: Toolchain,
    apk: Path,
    range_args: list[str],
    *,
    what: str,
) -> tuple[dict[str, bool], set[str]]:
    command = [
        str(toolchain.apksigner), "verify", "--verbose", "--print-certs",
        *range_args, str(apk),
    ]
    output = _run_checked(command, what=what)
    if "DOES NOT VERIFY" in output:
        raise SignatureError(f"{what} 未通过：\n{output[:2000]}")
    schemes = {match.group("scheme"): match.group("value") == "true" for match in SCHEME_LINE.finditer(output)}
    if not schemes:
        raise SignatureError(f"{what} 输出中没有签名方案结果：\n{output[:2000]}")
    signer_counts = {int(match.group("count")) for match in SIGNER_COUNT.finditer(output)}
    if not signer_counts:
        raise SignatureError(f"{what} 输出中没有签名者数量：\n{output[:2000]}")
    # A multi-signer APK cannot be replaced over a single-signer install and would brick upgrades.
    if signer_counts != {1}:
        raise SignatureError(f"{what} 报告签名者数量 {sorted(signer_counts)}，正式渠道要求恰好 1 个签名者")
    digests = {match.group("digest").lower() for match in SIGNER_DIGEST.finditer(output)}
    if len(digests) != 1:
        raise SignatureError(f"{what} 报告了 {len(digests)} 个不同证书，正式渠道要求单证书签名")
    return schemes, digests


def verify_release_apk(
    apk: Path,
    *,
    expected_package: str = DEFAULT_PACKAGE_NAME,
    expected_version_code: int | None = None,
    expected_version_name: str | None = None,
    expected_cert_sha256: str | None = None,
    toolchain: Toolchain | None = None,
    apksigner: str | None = None,
    aapt2: str | None = None,
    required_schemes: tuple[str, ...] = ("v1", "v2", "v3"),
    supported_min_sdk: int = SUPPORTED_MIN_SDK,
) -> ApkIdentity:
    """Validate a candidate release APK with the official SDK tools."""
    apk = apk.resolve()
    if not apk.is_file() or apk.stat().st_size <= 0:
        raise SignatureError(f"APK 不存在或为空：{apk}")
    if not zipfile.is_zipfile(apk):
        raise SignatureError(f"APK 不是有效的 ZIP 包：{apk}")

    tools = toolchain or resolve_toolchain(apksigner, aapt2)
    metadata = _badging_identity(tools, apk)
    package_name = metadata["package"]
    if package_name != expected_package:
        raise SignatureError(f"安装包包名不符：{package_name!r}，期望 {expected_package!r}")
    if metadata["split"]:
        raise SignatureError(f"安装包是拆分 APK（split={metadata['split']!r}），正式渠道只接受 universal 整包")
    try:
        version_code = int(metadata["versionCode"])
    except ValueError:
        version_code = 0
    if version_code <= 0:
        raise SignatureError(f"安装包 versionCode 缺失或非法：{metadata['versionCode']!r}")
    version_name = metadata["versionName"]
    if not version_name:
        raise SignatureError("安装包 versionName 缺失")
    if expected_version_code is not None and version_code != expected_version_code:
        raise SignatureError(
            f"安装包 versionCode={version_code} 与 release.properties={expected_version_code} 不一致"
        )
    if expected_version_name is not None and version_name != expected_version_name:
        raise SignatureError(
            f"安装包 versionName={version_name!r} 与 release.properties={expected_version_name!r} 不一致"
        )
    try:
        min_sdk = int(metadata["minSdk"])
    except ValueError:
        min_sdk = -1
    if min_sdk < 0:
        raise SignatureError("aapt2 未能读出 minSdkVersion，无法确认 API 24-27 兼容性")
    if min_sdk > supported_min_sdk:
        raise SignatureError(
            f"安装包 minSdkVersion={min_sdk} 高于 {supported_min_sdk}，老设备无法安装，不满足 universal 要求"
        )

    # Default range: v2/v3 (used by API 24-27 and all newer devices).
    default_schemes, default_certs = _apksigner_report(
        tools, apk, [], what="apksigner verify（默认范围 v2/v3）"
    )
    # API 23-only range: forces JAR (v1) verification, which API 24-27 installers require.
    legacy_schemes, legacy_certs = _apksigner_report(
        tools, apk, ["--min-sdk-version", "23", "--max-sdk-version", "23"],
        what="apksigner verify（API 23 范围 v1）",
    )

    missing: list[str] = []
    for scheme in required_schemes:
        verified = default_schemes.get(scheme)
        if scheme == "v1":
            verified = legacy_schemes.get("v1", default_schemes.get("v1", False))
        if verified is not True:
            missing.append(scheme)
    if missing:
        raise SignatureError(
            "APK 缺少或未通过以下签名方案：" + "、".join(missing)
            + "（正式更新包必须 v1+v2+v3 全部有效，API 24-27 依赖 v1）"
        )

    verified_schemes = sorted(
        {
            scheme for scheme, ok in default_schemes.items() if ok and scheme in required_schemes
        }
        | {scheme for scheme, ok in legacy_schemes.items() if ok and scheme in required_schemes}
    )
    all_certs = default_certs | legacy_certs
    if not all_certs:
        raise SignatureError("apksigner 未输出任何签名证书摘要")
    if len(all_certs) != 1:
        raise SignatureError(f"APK 使用多个不同证书：{sorted(all_certs)}")
    cert_sha256 = all_certs.pop()
    if expected_cert_sha256 is not None and cert_sha256.lower() != expected_cert_sha256.lower():
        raise SignatureError(
            f"签名证书指纹不符：{cert_sha256.upper()}，期望官方证书 {expected_cert_sha256.upper()}"
        )

    return ApkIdentity(
        package_name=package_name,
        version_code=version_code,
        version_name=version_name,
        min_sdk=min_sdk,
        cert_sha256=cert_sha256.upper(),
        schemes=tuple(verified_schemes),
        size=apk.stat().st_size,
        sha256=_sha256_file(apk),
    )


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(CHUNK_SIZE):
            digest.update(chunk)
    return digest.hexdigest()


# ---------------------------------------------------------------------------------------------
# Publication
# ---------------------------------------------------------------------------------------------


def _stage_asset(apk: Path, assets_dir: Path, release_id: int, expected_sha256: str) -> int:
    """Copy the APK in and fsync it before any manifest can reference it."""
    asset_path = assets_dir / str(release_id)
    temporary = assets_dir / f".{release_id}.{uuid.uuid4().hex}.part"
    digest = hashlib.sha256()
    size = 0
    try:
        with apk.open("rb") as source, temporary.open("wb") as target:
            while chunk := source.read(CHUNK_SIZE):
                target.write(chunk)
                digest.update(chunk)
                size += len(chunk)
            target.flush()
            os.fsync(target.fileno())
        os.chmod(temporary, 0o644)
        actual = digest.hexdigest()
        if actual.lower() != expected_sha256.lower():
            raise PublishError("发布过程中安装包内容发生变化，已中止（未改动任何线上文件）")
        os.replace(temporary, asset_path)
        _fsync_dir(assets_dir)
    finally:
        temporary.unlink(missing_ok=True)
    return size


def _load_history(state_dir: Path) -> list[dict]:
    path = state_dir / "publish-history.json"
    if not path.is_file():
        return []
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    entries = value.get("entries") if isinstance(value, dict) else None
    if not isinstance(entries, list):
        return []
    return [entry for entry in entries if isinstance(entry, dict) and isinstance(entry.get("id"), int)]


def _dedupe_history(entries: list[dict]) -> list[dict]:
    """Keep the first (newest) record per release id; history ordering is publish order."""
    seen: set[int] = set()
    unique: list[dict] = []
    for entry in entries:
        identifier = entry.get("id")
        if not isinstance(identifier, int) or identifier in seen:
            continue
        seen.add(identifier)
        unique.append(entry)
    return unique


def _retention_ids(
    history: list[dict],
    published_id: int,
    keep_previous: int,
    *,
    always_keep: tuple[int | None, ...] = (),
) -> set[str]:
    """Choose assets to retain.

    The newly published asset and the asset referenced by the manifest that was live before this
    run are always retained. Only entries that survived an actual manifest swap reach ``history``,
    so failed/orphan publications cannot consume the keep-previous budget.
    """
    keep = {str(published_id)}
    always = {str(item) for item in always_keep if item is not None}
    keep.update(always)
    kept_previous = 0
    for entry in history:
        if kept_previous >= max(1, keep_previous):
            break
        identifier = str(entry["id"])
        if identifier == str(published_id):
            continue
        # The live previous manifest still consumes one grace-window slot when it is in history;
        # `always` only guarantees it survives when history is missing or reordered.
        keep.add(identifier)
        kept_previous += 1
    return keep


def _prune_assets(assets_dir: Path, keep: set[str]) -> list[str]:
    removed: list[str] = []
    for item in assets_dir.iterdir():
        if not item.is_file() or item.is_symlink():
            continue
        name = item.name
        if name.startswith(".") or name.endswith(".part"):
            item.unlink(missing_ok=True)  # crash leftover; the lock guarantees nobody is writing
            continue
        if not name.isdigit() or name in keep:
            continue
        item.unlink(missing_ok=True)
        removed.append(name)
    return removed


def _version_tuple(value: str) -> tuple[int, ...]:
    digits = re.findall(r"\d+", value.split("-")[0].split("+")[0])
    return tuple(int(part) for part in digits)


def _compare_versions(left: tuple[int, ...], right: tuple[int, ...]) -> int:
    """Pad to a common length so `1.0` and `1.0.0` compare equal instead of 1.0 < 1.0.0."""
    width = max(len(left), len(right))
    padded_left = left + (0,) * (width - len(left))
    padded_right = right + (0,) * (width - len(right))
    return (padded_left > padded_right) - (padded_left < padded_right)


def _manifest(identity: ApkIdentity, *, release_id: int, file_name: str, base_url: str, notes: str) -> dict:
    return {
        "id": release_id,
        "tag_name": f"v{identity.version_name}",
        "name": f"Raven {identity.version_name}",
        "body": notes,
        "html_url": f"{base_url}/latest",
        "draft": False,
        "prerelease": False,
        "assets": [{
            "id": release_id,
            "name": file_name,
            "size": identity.size,
            "browser_download_url": f"{base_url}/assets/{release_id}",
            "digest": f"sha256:{identity.sha256.lower()}",
        }],
    }


def publish(
    args: argparse.Namespace,
    *,
    apk_checker=verify_release_apk,
    write_manifest=None,
    toolchain: Toolchain | None = None,
) -> dict:
    apk = args.apk.resolve()
    if not apk.is_file() or apk.stat().st_size <= 0:
        raise PublishError(f"APK 不存在或为空：{apk}")
    if not re.match(r"^https?://", args.base_url):
        raise PublishError(f"base_url 必须是 http(s) URL：{args.base_url}")

    properties = release_properties(args.release_properties)
    version_name = properties.get("versionName", "").strip()
    try:
        version_code = int(properties.get("versionCode", "0"))
    except ValueError as error:
        raise PublishError(f"release.properties versionCode 不是整数：{error}") from error
    pinned_cert = properties.get("releaseCertificate", "").strip().replace(":", "").upper()
    if not version_name or version_code <= 0:
        raise PublishError("release.properties 必须包含正整数 versionCode 与非空 versionName")
    if not re.fullmatch(r"[0-9A-F]{64}", pinned_cert):
        raise PublishError("release.properties releaseCertificate 必须是 64 位十六进制 SHA-256")

    # Authoritative APK validation — still nothing written under the web root.
    identity = apk_checker(
        apk,
        expected_package=getattr(args, "expected_package", DEFAULT_PACKAGE_NAME),
        expected_version_code=version_code,
        expected_version_name=version_name,
        expected_cert_sha256=pinned_cert,
        toolchain=toolchain,
        apksigner=getattr(args, "apksigner", None),
        aapt2=getattr(args, "aapt2", None),
    )

    release_id = MIRROR_RELEASE_ID_BASE + version_code
    base_url = args.base_url.rstrip("/")
    web_root = args.web_root.resolve()
    releases_dir = web_root / "raven" / "releases"
    assets_dir = releases_dir / "assets"
    state_dir = (
        Path(args.state_dir).resolve() if getattr(args, "state_dir", None) else web_root.parent / ".raven-update-state"
    )
    manifest_path = releases_dir / "latest"
    keep_previous = max(1, int(getattr(args, "keep_previous", DEFAULT_KEEP_PREVIOUS)))
    writer = write_manifest or atomic_json

    safe_version = re.sub(r"[^0-9A-Za-z._-]+", "-", version_name)
    file_name = f"Raven-{safe_version}-universal-release.apk"
    notes = (args.release_notes or "").strip()[:MAX_RELEASE_NOTES_CHARS]

    with PublishLock(state_dir / "publish.lock"):
        previous = load_published_manifest(manifest_path)
        previous_tag = ""
        previous_code = None
        previous_id = None
        if previous is not None:
            previous_tag = str(previous.get("tag_name") or "").strip()
            previous_version = _version_tuple(previous_tag)
            incoming_version = _version_tuple(version_name)
            if not previous_version or not incoming_version:
                raise PublishError(
                    f"版本号无法解析（已发布 tag_name={previous_tag!r}，待发布 {version_name!r}），拒绝发布"
                )
            raw_id = previous.get("id")
            if isinstance(raw_id, int) and raw_id > MIRROR_RELEASE_ID_BASE:
                previous_code = raw_id - MIRROR_RELEASE_ID_BASE
                previous_id = raw_id
            if previous_code is not None and version_code < previous_code:
                raise PublishError(
                    f"versionCode 必须严格递增：待发布 {version_code} 低于已发布 {previous_code}"
                )
            if previous_code is not None and version_code == previous_code and previous_tag != f"v{version_name}":
                raise PublishError(
                    f"versionCode {version_code} 已用于 {previous_tag}，不能改名为 v{version_name}；请递增 versionCode"
                )
            if _compare_versions(incoming_version, previous_version) < 0:
                raise PublishError(
                    f"versionName 不得回退：待发布 v{version_name} 低于已发布 {previous_tag}"
                )
            if (
                previous_code is not None
                and version_code > previous_code
                and _compare_versions(incoming_version, previous_version) == 0
            ):
                raise PublishError(
                    f"versionCode {version_code} 递增但 versionName 仍为 v{version_name}，"
                    "与已发布版本同名会让客户端无法区分，请同步递增 versionName"
                )

        assets_dir.mkdir(parents=True, exist_ok=True)
        os.chmod(assets_dir, 0o755)
        asset_path = assets_dir / str(release_id)
        _ensure_regular_file(asset_path, what="待发布资产")

        same_version = bool(previous_tag) and previous_tag == f"v{version_name}"
        existing_sha = _sha256_file(asset_path) if asset_path.is_file() else None
        if existing_sha is not None and existing_sha.lower() != identity.sha256.lower():
            raise PublishError(
                f"发布 id {release_id} 已存在且内容不同（{existing_sha} != {identity.sha256}）；"
                "资产以 immutable 缓存分发，禁止原地替换同一版本，请递增 versionCode"
            )
        if same_version and existing_sha is None:
            raise PublishError(
                f"已发布 v{version_name} 但缺少对应资产 id {release_id}，渠道状态不一致，拒绝静默补发"
            )
        # Same version + same bytes: no asset rewrite, manifest only if metadata changed.
        idempotent = same_version and existing_sha is not None

        if idempotent:
            size = asset_path.stat().st_size
        else:
            _stage_asset(apk, assets_dir, release_id, identity.sha256)
            size = identity.size

        manifest = _manifest(
            identity, release_id=release_id, file_name=file_name, base_url=base_url, notes=notes
        )
        if size != identity.size:
            # Only possible in the idempotent branch if the file was truncated behind our back.
            raise PublishError(f"资产文件大小 {size} 与发布声明 {identity.size} 不一致，拒绝发布")

        # Record history only after the manifest swap succeeds. A failed publish therefore leaves
        # no history entry to consume the keep-previous budget, and the asset referenced by the
        # still-live manifest is retained explicitly below even if history is missing or stale.
        previous_manifest_text = manifest_path.read_text(encoding="utf-8") if manifest_path.is_file() else None
        new_manifest_text = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
        if previous_manifest_text != new_manifest_text:
            # The manifest is the only pointer clients follow: replace it last, atomically.
            writer(manifest_path, manifest)

        history = [entry for entry in _load_history(state_dir) if entry.get("id") != release_id]
        history.insert(0, {
            "id": release_id,
            "versionCode": version_code,
            "versionName": version_name,
            "sha256": identity.sha256.lower(),
            "publishedAt": int(time.time()),
        })
        if previous_id is not None and previous_id != release_id:
            history.append({
                "id": previous_id,
                "versionCode": previous_code,
                "versionName": previous_tag.removeprefix("v") or None,
                "sha256": None,
                "publishedAt": 0,
            })
        history = _dedupe_history(history)[:MAX_HISTORY_ENTRIES]
        atomic_json(state_dir / "publish-history.json", {"entries": history})

        keep_ids = _retention_ids(
            history,
            release_id,
            keep_previous,
            always_keep=(previous_id,),
        )
        removed = _prune_assets(assets_dir, keep_ids)

    return {
        "versionName": version_name,
        "versionCode": version_code,
        "packageName": identity.package_name,
        "minSdkVersion": identity.min_sdk,
        "signatureSchemes": list(identity.schemes),
        "certSha256": identity.cert_sha256,
        "size": size,
        "sha256": identity.sha256.lower(),
        "idempotent": idempotent,
        "metadata": f"{base_url}/latest",
        "download": f"{base_url}/assets/{release_id}",
        "retainedAssets": sorted(keep_ids),
        "prunedAssets": removed,
        "beforeManifestTag": previous_tag or None,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--release-properties", required=True, type=Path)
    parser.add_argument("--web-root", type=Path, default=Path("/var/www/raven-update/public"))
    parser.add_argument("--base-url", default="https://ywclgl258.us.ci:8443/raven/releases")
    parser.add_argument("--release-notes", default="Raven 正式版更新。")
    parser.add_argument("--expected-package", default=DEFAULT_PACKAGE_NAME)
    parser.add_argument("--state-dir", type=Path, default=None)
    parser.add_argument("--keep-previous", type=int, default=DEFAULT_KEEP_PREVIOUS)
    parser.add_argument("--apksigner", default=None, help="apksigner 绝对路径（默认从 SDK/PATH 发现）")
    parser.add_argument("--aapt2", default=None, help="aapt2 绝对路径（默认从 SDK/PATH 发现）")
    args = parser.parse_args()
    try:
        result = publish(args)
    except PublishError as error:
        print(json.dumps({"status": "rejected", "reason": str(error)}, ensure_ascii=False, indent=2), file=sys.stderr)
        raise SystemExit(2) from error
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
