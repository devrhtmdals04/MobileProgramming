import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

from server import Handler, MAX_BYTES, Server


class BridgeTest(unittest.TestCase):
    def setUp(self):
        self.server = Server(("127.0.0.1", 0), Handler)
        self.server.token = "test-token"
        self.server.job = threading.Lock()
        self.server.cli = Path("unused")
        self.server.model = Path("unused")
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def request(self, path="/transcribe", body=b"sample", headers=None):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=5)
        connection.request("POST", path, body=body, headers=headers or {"Authorization": "Bearer test-token"})
        response = connection.getresponse()
        result = response.status, json.loads(response.read())
        connection.close()
        return result

    def test_unauthenticated_upload_does_not_start_model(self):
        with patch("server.transcribe") as model:
            self.assertEqual(401, self.request(headers={"Authorization": "Bearer wrong"})[0])
            model.assert_not_called()

    def test_rejects_oversized_upload_before_reading_body(self):
        self.assertEqual(413, self.request(headers={"Authorization": "Bearer test-token", "Content-Length": str(MAX_BYTES + 1)})[0])

    def test_busy_does_not_queue_lecture_audio(self):
        self.server.job.acquire()
        self.assertEqual(409, self.request()[0])
        self.server.job.release()

    def test_success_removes_audio_and_releases_slot(self):
        paths = []
        def convert(audio, work, *_):
            paths.append(work)
            self.assertEqual(b"sample", audio.read_bytes())
            return {"text": "이진수는 0과 1을 사용합니다."}
        with patch("server.transcribe", side_effect=convert):
            status, body = self.request()
        self.assertEqual(200, status)
        self.assertIn("이진수", body["text"])
        self.assertFalse(paths[0].exists())
        self.assertFalse(self.server.job.locked())

    def test_failure_cleans_audio_and_allows_retry(self):
        paths = []
        def convert(audio, work, *_):
            paths.append(work)
            raise ValueError("인식된 음성이 없습니다.")
        with patch("server.transcribe", side_effect=convert):
            self.assertEqual(422, self.request()[0])
        self.assertFalse(paths[0].exists())
        self.assertFalse(self.server.job.locked())

    def test_unknown_path_is_not_model_load_api(self):
        self.assertEqual(404, self.request(path="/load")[0])


if __name__ == "__main__":
    unittest.main()
