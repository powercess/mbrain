import importlib.util
import pathlib
import subprocess
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name("check-source.py")
spec = importlib.util.spec_from_file_location("source_check", SCRIPT)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class SourceCheckTest(unittest.TestCase):
    def test_artifacts_and_keys_are_rejected(self):
        for path in ("release.apk", "app/build/file.txt", ".env", "signing.properties", "chat-export-example.md"):
            self.assertTrue(checker.inspect(path, b"example"), path)
        key = ("-----BEGIN " + "PRIVATE KEY-----").encode()
        self.assertTrue(checker.inspect("key.txt", key))

    def test_personal_path_is_rejected(self):
        path = ("C:/" + "Users/" + "example/project").encode()
        self.assertTrue(checker.inspect("notes.md", path))

    def test_documentation_images_do_not_allow_other_binary_exports(self):
        png = b"\x89PNG\r\n\x1a\n"
        self.assertFalse(checker.inspect("assets/screenshots/home.png", png))
        for path in ("screenshots/home.png", "assets/screenshots/session.bin", "assets/screenshots/release.apk"):
            self.assertTrue(checker.inspect(path, png), path)

    def test_localhost_and_dummy_fixture_are_allowed(self):
        self.assertFalse(checker.inspect("Test.kt", b'token = "secret"; endpoint = "http://127.0.0.1:8765/mcp"'))

    def test_scans_staged_blob_and_does_not_print_secret(self):
        with tempfile.TemporaryDirectory() as directory:
            def git(*args):
                subprocess.run(["git", *args], cwd=directory, check=True, capture_output=True)
            git("init")
            path = pathlib.Path(directory) / "config.txt"
            path.write_text("safe example", encoding="utf-8")
            git("add", "config.txt")
            secret = "ghp_" + "A" * 36
            path.write_text(secret, encoding="utf-8")
            def scan():
                return subprocess.run([__import__("sys").executable, str(SCRIPT.resolve())], cwd=directory, capture_output=True, text=True)
            self.assertEqual(0, scan().returncode)
            git("add", "config.txt")
            result = scan()
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn(secret, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
