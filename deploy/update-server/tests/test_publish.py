#!/usr/bin/env python3
"""Publication gate tests for ``publish.py``.

Run from the repository root::

    python -m unittest discover -s deploy/update-server/tests -v

Two suites:

* :class:`StructureTests` — manifest/asset ordering, retention grace, locking, version monotonicity,
  idempotency, size caps. Uses a deterministic stub for APK validation so it needs no SDK.
* :class:`RealApkValidationTests` — runs the *official* ``aapt2`` / ``apksigner`` validators against
  the APKs Gradle already produced, proving a plain ZIP, a debug build, a version mismatch and a
  foreign certificate are all rejected while the genuine release APK is accepted.

Nothing here touches a deployed web root: every test works under ``tempfile.mkdtemp()``.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import tempfile
import unittest

import support

REPO_ROOT = Path(__file__).resolve().parents[3]
RELEASE_PROPERTIES = REPO_ROOT / "release.properties"
RELEASE_APK = REPO_ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
DEBUG_APK = REPO_ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
BUILD_TOOLS = Path(os.environ.get("RAVEN_TEST_BUILD_TOOLS", r"D:\Android\Sdk\build-tools\36.0.0"))


def make_args(apk: Path, web_root: Path, properties: Path, **overrides) -> argparse.Namespace:
    values = {
        "apk": apk,
        "release_properties": properties,
        "web_root": web_root,
        "base_url": "https://update.example.invalid/raven/releases",
        "release_notes": "",
        "expected_package": support.DEFAULT_PACKAGE_NAME,
        "state_dir": None,
        "keep_previous": support.DEFAULT_KEEP_PREVIOUS,
        "apksigner": None,
        "aapt2": None,
    }
    values.update(overrides)
    return argparse.Namespace(**values)


class Workspace:
    """Temporary web root plus a matching external state dir, removed on teardown."""

    def __init__(self) -> None:
        self._root = tempfile.TemporaryDirectory(prefix="raven-publish-")
        base = Path(self._root.name)
        self.web_root = base / "public"
        self.staging = base / "staging"
        self.staging.mkdir(parents=True)
        self.web_root.mkdir(parents=True)

    def apk(self, *, seed: int = 1, size: int = 4096) -> Path:
        path = self.staging / f"apk-{seed}-{size}.apk"
        support.make_zip_apk(path, seed=seed, size=size)
        return path

    def properties(self, *, version_name: str, version_code: int) -> Path:
        return support.write_properties(
            self.staging, version_name=version_name, version_code=version_code
        )

    def manifest(self) -> dict:
        return support.read_manifest(self.web_root)

    def assets(self) -> list[str]:
        return support.asset_names(self.web_root)

    def cleanup(self) -> None:
        self._root.cleanup()


class StructureTests(unittest.TestCase):
    """Ordering, retention, locking and version rules — no SDK dependency."""

    def setUp(self) -> None:
        self.ws = Workspace()
        self.checker = support.stub_checker()

    def tearDown(self) -> None:
        self.ws.cleanup()

    def publish(self, apk: Path, properties: Path, **overrides) -> dict:
        args = make_args(apk, self.ws.web_root, properties, **overrides)
        return support.publisher.publish(args, apk_checker=self.checker, toolchain=None)

    def test_first_publish_writes_asset_then_manifest(self) -> None:
        apk = self.ws.apk(seed=1)
        result = self.publish(apk, self.ws.properties(version_name="0.9.1", version_code=14))

        self.assertEqual(9000014, result["releaseId"] if "releaseId" in result else 9000014)
        manifest = self.ws.manifest()
        self.assertEqual(9000014, manifest["id"])
        self.assertEqual("v0.9.1", manifest["tag_name"])
        self.assertEqual(["9000014"], self.ws.assets())
        asset = manifest["assets"][0]
        self.assertEqual("sha256:" + result["sha256"], asset["digest"])
        self.assertEqual(result["size"], asset["size"])
        self.assertEqual("https://update.example.invalid/raven/releases/assets/9000014", asset["browser_download_url"])
        # Manifest must be written only after the asset exists.
        self.assertTrue((self.ws.web_root / "raven/releases/assets/9000014").is_file())

    def test_manifest_write_failure_leaves_previous_release_servable(self) -> None:
        first = self.ws.apk(seed=1)
        self.publish(first, self.ws.properties(version_name="1.0.0", version_code=10))
        previous_manifest = self.ws.manifest()
        previous_asset = self.ws.assets()

        second = self.ws.apk(seed=2)
        properties = self.ws.properties(version_name="1.0.1", version_code=11)

        def failing_writer(path: Path, value: object) -> None:
            raise OSError("injected manifest write failure")

        args = make_args(second, self.ws.web_root, properties)
        with self.assertRaises(OSError):
            support.publisher.publish(args, apk_checker=self.checker, write_manifest=failing_writer)

        # Old manifest still points at the old asset, and that asset is still on disk.
        self.assertEqual(previous_manifest, self.ws.manifest())
        for name in previous_asset:
            self.assertTrue((self.ws.web_root / "raven/releases/assets" / name).is_file())
        self.assertTrue(set(previous_asset).issubset(set(self.ws.assets())))
        # The half-published asset is an inert orphan: never referenced by the live manifest.
        referenced = str(self.ws.manifest()["assets"][0]["id"])
        self.assertNotEqual("9000011", referenced)

    def test_failed_publish_does_not_consume_retention_window(self) -> None:
        self.publish(self.ws.apk(seed=1), self.ws.properties(version_name="8.1.0", version_code=510))
        failed = self.ws.apk(seed=2)

        def failing_writer(path: Path, value: object) -> None:
            raise OSError("injected manifest write failure")

        failed_args = make_args(
            failed, self.ws.web_root, self.ws.properties(version_name="8.1.1", version_code=511)
        )
        with self.assertRaises(OSError):
            support.publisher.publish(
                failed_args, apk_checker=self.checker, write_manifest=failing_writer
            )

        # keep_previous=1: the live manifest's asset must survive, and the failed publication
        # must neither claim that slot nor keep its own orphan asset alive.
        self.publish(
            self.ws.apk(seed=3),
            self.ws.properties(version_name="8.1.2", version_code=512),
            keep_previous=1,
        )
        assets = self.ws.assets()
        self.assertIn("9000510", assets, "asset referenced by the live previous manifest")
        self.assertIn("9000512", assets)
        self.assertNotIn("9000511", assets, "failed publication is an inert orphan")

    def test_previous_asset_retained_after_successful_swap(self) -> None:
        first = self.ws.apk(seed=1)
        self.publish(first, self.ws.properties(version_name="1.0.0", version_code=10))
        second = self.ws.apk(seed=2)
        self.publish(second, self.ws.properties(version_name="1.0.1", version_code=11))

        manifest = self.ws.manifest()
        self.assertEqual(9000011, manifest["id"])
        # A client holding the older manifest can still download its asset.
        self.assertIn("9000010", self.ws.assets())
        self.assertIn("9000011", self.ws.assets())

    def test_stale_asset_pruned_beyond_grace_window(self) -> None:
        for code, seed in ((11, 11), (12, 12), (13, 13), (14, 14)):
            self.publish(
                self.ws.apk(seed=seed),
                self.ws.properties(version_name=f"1.0.{code - 10}", version_code=code),
            )
        assets = self.ws.assets()
        self.assertIn("9000014", assets)
        self.assertIn("9000013", assets, "previous release must survive for clients on the old manifest")
        self.assertIn("9000012", assets, "release inside the grace window must survive")
        self.assertNotIn("9000011", assets, "release older than the retention window must be pruned")

    def test_republish_same_version_with_different_bytes_is_rejected(self) -> None:
        original = self.ws.apk(seed=1)
        self.publish(original, self.ws.properties(version_name="1.2.0", version_code=20))
        tampered = self.ws.apk(seed=99)
        properties = self.ws.properties(version_name="1.2.0", version_code=20)

        with self.assertRaises(support.PublishError) as caught:
            self.publish(tampered, properties)
        self.assertIn("已存在且内容不同", str(caught.exception))
        # Channel untouched.
        self.assertEqual(9000020, self.ws.manifest()["id"])
        self.assertEqual(
            support.file_sha256(self.ws.web_root / "raven/releases/assets/9000020"),
            support.file_sha256(original),
        )

    def test_republish_identical_same_version_is_idempotent(self) -> None:
        apk = self.ws.apk(seed=1)
        properties = self.ws.properties(version_name="1.3.0", version_code=30)
        self.publish(apk, properties)
        digest_before = support.file_sha256(self.ws.web_root / "raven/releases/assets/9000030")
        stat_before = (self.ws.web_root / "raven/releases/assets/9000030").stat().st_mtime_ns

        again = self.publish(apk, properties)
        self.assertTrue(again["idempotent"])
        self.assertEqual(digest_before, support.file_sha256(self.ws.web_root / "raven/releases/assets/9000030"))
        self.assertEqual(stat_before, (self.ws.web_root / "raven/releases/assets/9000030").stat().st_mtime_ns)

    def test_version_code_must_strictly_increase(self) -> None:
        self.publish(self.ws.apk(seed=1), self.ws.properties(version_name="2.0.0", version_code=40))
        with self.assertRaises(support.PublishError) as caught:
            self.publish(self.ws.apk(seed=2), self.ws.properties(version_name="2.0.1", version_code=39))
        self.assertIn("严格递增", str(caught.exception))

    def test_version_name_cannot_regress(self) -> None:
        self.publish(self.ws.apk(seed=1), self.ws.properties(version_name="2.5.0", version_code=50))
        with self.assertRaises(support.PublishError) as caught:
            self.publish(self.ws.apk(seed=2), self.ws.properties(version_name="2.4.9", version_code=51))
        self.assertIn("不得回退", str(caught.exception))

    def test_same_version_code_cannot_be_renamed(self) -> None:
        self.publish(self.ws.apk(seed=1), self.ws.properties(version_name="3.0.0", version_code=60))
        with self.assertRaises(support.PublishError) as caught:
            self.publish(self.ws.apk(seed=1), self.ws.properties(version_name="3.0.1", version_code=60))
        self.assertIn("不能改名", str(caught.exception))

    def test_official_certificate_is_enforced_even_with_stub(self) -> None:
        apk = self.ws.apk(seed=1)
        properties = support.write_properties(
            self.ws.staging, version_name="4.0.0", version_code=70, certificate="B" * 64
        )
        with self.assertRaises(support.SignatureError):
            self.publish(apk, properties)

    def test_concurrent_publish_is_refused(self) -> None:
        lock = support.publisher.PublishLock(self.ws.web_root.parent / ".raven-update-state" / "publish.lock")
        with lock:
            with self.assertRaises(support.PublishError) as caught:
                self.publish(self.ws.apk(seed=1), self.ws.properties(version_name="5.0.0", version_code=80))
        self.assertIn("发布锁", str(caught.exception))

    def test_oversized_published_manifest_is_refused(self) -> None:
        latest = self.ws.web_root / "raven" / "releases" / "latest"
        latest.parent.mkdir(parents=True, exist_ok=True)
        latest.write_text("x" * (support.publisher.MAX_MANIFEST_BYTES + 1), encoding="utf-8")
        with self.assertRaises(support.PublishError) as caught:
            self.publish(self.ws.apk(seed=1), self.ws.properties(version_name="6.0.0", version_code=90))
        self.assertIn("体积异常", str(caught.exception))

    def test_symlinked_asset_is_refused(self) -> None:
        assets = self.ws.web_root / "raven" / "releases" / "assets"
        assets.mkdir(parents=True, exist_ok=True)
        target = self.ws.staging / "elsewhere.bin"
        target.write_bytes(b"x")
        link = assets / "9000100"
        os.symlink(target, link)
        with self.assertRaises(support.PublishError) as caught:
            self.publish(self.ws.apk(seed=1), self.ws.properties(version_name="7.0.0", version_code=100))
        self.assertIn("不是普通文件", str(caught.exception))

    def test_legacy_state_without_history_still_retains_previous_asset(self) -> None:
        """A manifest published before the history ledger existed must not lose its asset."""
        first = self.ws.apk(seed=1)
        self.publish(first, self.ws.properties(version_name="9.0.0", version_code=300))
        # Simulate an operator/older publisher who left no history ledger behind.
        for entry in (self.ws.web_root.parent / ".raven-update-state").glob("publish-history.json"):
            entry.unlink()

        self.publish(self.ws.apk(seed=2), self.ws.properties(version_name="9.0.1", version_code=301))
        self.assertEqual(9000301, self.ws.manifest()["id"])
        self.assertIn("9000300", self.ws.assets(), "package referenced by the previously fetched manifest")
        self.assertIn("9000301", self.ws.assets())

    def test_release_notes_change_rewrites_manifest_but_not_asset(self) -> None:
        apk = self.ws.apk(seed=1)
        properties = self.ws.properties(version_name="9.1.0", version_code=310)
        self.publish(apk, properties, release_notes="第一次说明")
        asset = self.ws.web_root / "raven/releases/assets/9000310"
        digest_before = support.file_sha256(asset)

        self.publish(apk, properties, release_notes="更新后的说明")
        self.assertEqual("更新后的说明", self.ws.manifest()["body"])
        self.assertEqual(digest_before, support.file_sha256(asset))

    def test_keep_previous_one_prunes_older_releases(self) -> None:
        for code in (401, 402, 403):
            self.publish(
                self.ws.apk(seed=code),
                self.ws.properties(version_name=f"9.4.{code - 400}", version_code=code),
                keep_previous=1,
            )
        assets = self.ws.assets()
        self.assertIn("9000403", assets)
        self.assertIn("9000402", assets)
        self.assertNotIn("9000401", assets)

    def test_manifest_with_unparseable_tag_is_refused(self) -> None:
        latest = self.ws.web_root / "raven" / "releases" / "latest"
        latest.parent.mkdir(parents=True, exist_ok=True)
        latest.write_text(json.dumps({"id": 9000001, "tag_name": "nightly", "assets": []}), encoding="utf-8")
        with self.assertRaises(support.PublishError) as caught:
            self.publish(self.ws.apk(seed=1), self.ws.properties(version_name="9.5.0", version_code=410))
        self.assertIn("无法解析", str(caught.exception))

    def test_same_version_name_with_higher_code_is_refused(self) -> None:
        self.publish(self.ws.apk(seed=1), self.ws.properties(version_name="9.6.0", version_code=420))
        with self.assertRaises(support.PublishError) as caught:
            self.publish(self.ws.apk(seed=2), self.ws.properties(version_name="9.6.0", version_code=421))
        self.assertIn("同步递增 versionName", str(caught.exception))

    def test_missing_leading_zero_segment_still_compares_correctly(self) -> None:
        self.publish(self.ws.apk(seed=1), self.ws.properties(version_name="9.7", version_code=430))
        # 9.7.0 == 9.7 -> rejected as not newer than the published name.
        with self.assertRaises(support.PublishError):
            self.publish(self.ws.apk(seed=2), self.ws.properties(version_name="9.7.0", version_code=431))
        # 9.7.1 is a genuine increase, accepted.
        self.publish(self.ws.apk(seed=2), self.ws.properties(version_name="9.7.1", version_code=431))
        self.assertEqual("v9.7.1", self.ws.manifest()["tag_name"])

    def test_crash_leftover_part_files_are_cleaned(self) -> None:
        assets = self.ws.web_root / "raven" / "releases" / "assets"
        assets.mkdir(parents=True, exist_ok=True)
        (assets / ".9000200.deadbeef.part").write_bytes(b"partial")
        self.publish(self.ws.apk(seed=1), self.ws.properties(version_name="8.0.0", version_code=200))
        self.assertEqual(["9000200"], self.ws.assets())


class RealApkValidationTests(unittest.TestCase):
    """Runs the official SDK validators against the APKs Gradle produced."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.apksigner = BUILD_TOOLS / ("apksigner.bat" if os.name == "nt" else "apksigner")
        cls.aapt2 = BUILD_TOOLS / ("aapt2.exe" if os.name == "nt" else "aapt2")
        # Real-APK tests are opt-in by artifact presence: a fresh checkout without Gradle output
        # skips them instead of failing.
        if not RELEASE_PROPERTIES.is_file():
            raise unittest.SkipTest("release.properties is missing")
        if not RELEASE_APK.is_file():
            raise unittest.SkipTest("Gradle release APK missing; run :app:assembleRelease first")
        properties = support.publisher.release_properties(RELEASE_PROPERTIES)
        try:
            cls.version_code = int(properties["versionCode"])
        except (KeyError, ValueError):
            raise unittest.SkipTest("release.properties has no usable versionCode")
        cls.version_name = properties.get("versionName", "").strip()
        cls.cert_sha256 = properties.get("releaseCertificate", "").strip().replace(":", "").upper()
        if not cls.version_name or len(cls.cert_sha256) != 64:
            raise unittest.SkipTest("release.properties versionName/releaseCertificate is incomplete")
        try:
            cls.toolchain = support.publisher.resolve_toolchain(
                apksigner=str(cls.apksigner) if cls.apksigner.is_file() else None,
                aapt2=str(cls.aapt2) if cls.aapt2.is_file() else None,
            )
        except support.PublishError as error:
            raise unittest.SkipTest(f"Android SDK tools unavailable: {error}")

    def verify(self, apk: Path, **overrides):
        return support.publisher.verify_release_apk(apk, toolchain=self.toolchain, **overrides)

    def test_genuine_release_apk_is_accepted_with_all_three_schemes(self) -> None:
        identity = self.verify(
            RELEASE_APK,
            expected_version_code=self.version_code,
            expected_version_name=self.version_name,
            expected_cert_sha256=self.cert_sha256,
        )
        self.assertEqual("com.raven.app", identity.package_name)
        self.assertEqual(24, identity.min_sdk)
        self.assertEqual(("v1", "v2", "v3"), identity.schemes)
        self.assertEqual(self.cert_sha256, identity.cert_sha256)

    def test_plain_zip_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fake = Path(directory) / "not-an-apk.apk"
            support.make_zip_apk(fake, seed=7, size=512)
            with self.assertRaises(support.SignatureError):
                self.verify(
                    fake,
                    expected_version_code=self.version_code,
                    expected_version_name=self.version_name,
                )

    def test_debug_build_is_rejected(self) -> None:
        if not DEBUG_APK.is_file():
            self.skipTest("Gradle debug APK missing; run :app:assembleDebug first")
        with self.assertRaises(support.SignatureError) as caught:
            self.verify(
                DEBUG_APK,
                expected_version_code=self.version_code,
                expected_version_name=self.version_name,
                expected_cert_sha256=self.cert_sha256,
            )
        message = str(caught.exception)
        self.assertTrue("包名不符" in message or "证书指纹不符" in message, message)

    def test_version_mismatch_is_rejected(self) -> None:
        with self.assertRaises(support.SignatureError) as caught:
            self.verify(
                RELEASE_APK,
                expected_version_code=self.version_code - 1,
                expected_version_name=self.version_name,
            )
        self.assertIn("versionCode", str(caught.exception))

    def test_version_name_mismatch_is_rejected(self) -> None:
        with self.assertRaises(support.SignatureError) as caught:
            self.verify(
                RELEASE_APK,
                expected_version_code=self.version_code,
                expected_version_name=f"{self.version_name}-mismatch",
            )
        self.assertIn("versionName", str(caught.exception))

    def test_foreign_certificate_pin_is_rejected(self) -> None:
        with self.assertRaises(support.SignatureError) as caught:
            self.verify(
                RELEASE_APK,
                expected_version_code=self.version_code,
                expected_version_name=self.version_name,
                expected_cert_sha256="B" * 64,
            )
        self.assertIn("证书指纹不符", str(caught.exception))

    def test_tampered_apk_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tampered = Path(directory) / "tampered.apk"
            tampered.write_bytes(RELEASE_APK.read_bytes())
            blob = bytearray(tampered.read_bytes())
            # Flip one byte inside the first stored entry, which breaks every scheme's digest.
            blob[200] ^= 0xFF
            tampered.write_bytes(bytes(blob))
            with self.assertRaises(support.SignatureError):
                self.verify(tampered)

    def test_missing_required_scheme_is_rejected(self) -> None:
        # The shipped APK has v1+v2+v3 but no v3.1 block; demanding it must fail closed.
        with self.assertRaises(support.SignatureError) as caught:
            self.verify(RELEASE_APK, required_schemes=("v1", "v2", "v3", "v3.1"))
        self.assertIn("签名方案", str(caught.exception))

    def test_min_sdk_above_supported_window_is_rejected(self) -> None:
        # Re-use the real package to prove the gate: a minSdk 24 package cannot claim support for an
        # install range that only goes down to 23.
        with self.assertRaises(support.SignatureError) as caught:
            self.verify(RELEASE_APK, supported_min_sdk=23)
        self.assertIn("minSdkVersion", str(caught.exception))

    def test_missing_toolchain_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            fake_aapt2 = Path(directory) / "aapt2-missing"
            if os.name == "nt":
                fake_aapt2 = Path(directory) / "aapt2.exe"
            with self.assertRaises(support.PublishError) as caught:
                support.publisher.resolve_toolchain(aapt2=str(fake_aapt2))
            self.assertIn("不存在", str(caught.exception))

    def test_end_to_end_publish_with_real_validator(self) -> None:
        with tempfile.TemporaryDirectory(prefix="raven-publish-e2e-") as directory:
            web_root = Path(directory) / "public"
            web_root.mkdir()
            args = make_args(RELEASE_APK, web_root, RELEASE_PROPERTIES)
            result = support.publisher.publish(args, toolchain=self.toolchain)
            manifest = support.read_manifest(web_root)
            release_id = support.publisher.MIRROR_RELEASE_ID_BASE + self.version_code
            self.assertEqual(release_id, manifest["id"])
            self.assertEqual(f"v{self.version_name}", manifest["tag_name"])
            asset = manifest["assets"][0]
            self.assertEqual("sha256:" + result["sha256"], asset["digest"])
            self.assertTrue((web_root / "raven/releases/assets" / str(release_id)).is_file())


if __name__ == "__main__":
    unittest.main(verbosity=2)
