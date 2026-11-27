import json
import tempfile
import unittest
from pathlib import Path

from app import create_app
from store import FeedbackStore


class FeedbackServiceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.store = FeedbackStore(root / "feedback.sqlite3", admin_password="correct-horse-battery")
        self.log_dir = root / "logs"
        self.app = create_app(store=self.store, log_dir=self.log_dir, static_dir=Path(__file__).parents[1] / "static")
        self.app.secure_cookie = False
        self.client = self.app.test_client()

    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()

    def test_public_submit_redacts_secrets_and_admin_can_update(self):
        response = self.client.post("/api/feedback", json={
            "source": "app",
            "category": "playback",
            "title": "播放中断",
            "body": "切到后台后无法恢复",
            "appVersion": "0.9.2",
            "versionCode": 15,
            "logText": "ok\nAuthorization: Bearer secret-token\npassword=hunter2\n",
        })
        self.assertEqual(201, response.status_code)
        ticket_id = response.get_json()["id"]
        self.assertTrue(ticket_id.startswith("RV-"))

        denied = self.client.get("/api/admin/tickets")
        self.assertEqual(401, denied.status_code)
        bad = self.client.post("/api/admin/login", json={"password": "wrong-password-value"})
        self.assertEqual(401, bad.status_code)
        good = self.client.post("/api/admin/login", json={"password": "correct-horse-battery"})
        self.assertEqual(200, good.status_code)

        listing = self.client.get("/api/admin/tickets")
        self.assertEqual(ticket_id, listing.get_json()["items"][0]["id"])
        log = (self.log_dir / f"{ticket_id}.log").read_text(encoding="utf-8")
        self.assertNotIn("secret-token", log)
        self.assertNotIn("hunter2", log)
        self.assertIn("[redacted]", log)

        updated = self.client.post(f"/api/admin/tickets/{ticket_id}", json={"status": "resolved", "adminNote": "已修复"})
        self.assertEqual("resolved", updated.get_json()["ticket"]["status"])

    def test_rejects_oversized_log(self):
        response = self.client.post("/api/feedback", json={
            "title": "太大",
            "body": "日志过大",
            "logText": "x" * 1_500_001,
        })
        self.assertEqual(413, response.status_code)

    def test_export_contains_open_ticket(self):
        from export_feedback import build_document

        self.store.insert_ticket({"title": "搜索结果重复", "body": "同一作品出现两次", "source": "website"})
        document, ids = build_document(self.store, self.log_dir)
        self.assertIn("搜索结果重复", document)
        self.assertEqual(1, len(ids))
        self.assertIn("给 Agent 的处理约定", document)


if __name__ == "__main__":
    unittest.main()
