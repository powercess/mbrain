"""Check Git's index, not ignored working files. Never print secret values."""
import re
import subprocess
import sys
from pathlib import PurePosixPath


def git(*args):
    return subprocess.check_output(["git", *args])


def inspect(path, data):
    name = PurePosixPath(path)
    blocked_dirs = {".local-tools", ".gradle", ".gradle-user-home", ".idea", "build", "__pycache__"}
    blocked_suffixes = {".apk", ".apks", ".aab", ".jks", ".keystore", ".p12", ".pfx", ".pem", ".key", ".log", ".hprof"}
    blocked_names = {"local.properties", "key.properties", "keystore.properties", "signing.properties", "credentials.json", "secrets.properties"}
    if (set(name.parts) & blocked_dirs or name.suffix.lower() in blocked_suffixes
            or name.name in blocked_names or name.name.startswith(("chat-export-", "rikkahub-session-"))
            or (name.name.startswith(".env") and name.name != ".env.example")):
        return [(0, "local artifact or credential file")]
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        # Documentation images are reviewed before staging; raw device exports stay ignored.
        documentation_image = path.startswith("assets/screenshots/") and name.suffix.lower() == ".png"
        if path != "gradle/wrapper/gradle-wrapper.jar" and not path.startswith("assets/brand/") and not documentation_image:
            return [(0, "unexpected binary file")]
        return []
    patterns = {
        "private key": r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----",
        "GitHub credential": r"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b",
        "AWS access key": r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b",
        "API key": r"\b(?:AIza[A-Za-z0-9_-]{30,}|sk-(?:proj-)?[A-Za-z0-9_-]{32,})\b",
        "JWT": r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b",
        "embedded credential": r'''(?i)(?:token|password|secret|api[_-]?key)\s*[=:]\s*["'][A-Za-z0-9_+/=-]{24,}["']''',
        "personal home path": r"(?i)(?:[A-Z]:[/\\](?:Users|Documents and Settings)[/\\][^\s/\\]+|/(?:Users|home)/[A-Za-z0-9_.-]+/)",
        "private device address": r"\b(?:192\.168\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})\b",
    }
    return [(text.count("\n", 0, match.start()) + 1, label)
            for label, pattern in patterns.items() for match in re.finditer(pattern, text)]


def main():
    entries = git("ls-files", "--stage", "-z").split(b"\0")
    problems = []
    count = 0
    for entry in filter(None, entries):
        meta, raw_path = entry.split(b"\t", 1)
        mode, oid, stage = meta.decode().split()
        path = raw_path.decode("utf-8")
        count += 1
        if stage != "0" or mode not in {"100644", "100755"}:
            problems.append((path, 0, "unmerged file, symlink or submodule requires review"))
            continue
        for line, reason in inspect(path, git("cat-file", "blob", oid)):
            problems.append((path, line, reason))
    for path, line, reason in problems:
        print(f"{path}:{line}: {reason}")
    print(f"Checked {count} indexed files; {len(problems)} findings. Values are never printed.")
    return bool(problems)


if __name__ == "__main__":
    sys.exit(main())
