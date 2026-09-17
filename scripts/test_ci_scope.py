import unittest
from ci_scope import needs_android


class CiScopeTest(unittest.TestCase):
    def test_documentation_only(self):
        self.assertFalse(needs_android("pull_request", ["README.md", "AGENTS.md", "docs/development.md", "vendor/droid-mcp/UPSTREAM.md"]))

    def test_code_and_unknown_paths_require_build(self):
        for path in ["app/src/main/Main.kt", "app/src/main/assets/help.md", "build.gradle.kts", "gradle/libs.versions.toml", ".github/workflows/android.yml", "scripts/ci_scope.py", "unknown", "vendor/droid-mcp/core.kt"]:
            with self.subTest(path=path):
                self.assertTrue(needs_android("pull_request", ["README.md", path]))

    def test_code_deletion_or_rename_requires_build(self):
        self.assertTrue(needs_android("pull_request", ["app/old.kt", "docs/old.kt"]))

    def test_non_pr_and_empty_diff_require_build(self):
        for event in ["push", "workflow_dispatch"]:
            self.assertTrue(needs_android(event, ["README.md"]))
        self.assertTrue(needs_android("pull_request", []))
