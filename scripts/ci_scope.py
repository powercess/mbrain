"""Skip Android builds only for documentation-only pull requests."""
import os
from pathlib import PurePosixPath
import subprocess


def needs_android(event, paths):
    if event != "pull_request" or not paths:
        return True
    for path in paths:
        p = PurePosixPath(path)
        documentation = (
            (len(p.parts) == 1 and p.suffix == ".md")
            or path.startswith("docs/")
            or path in {"vendor/droid-mcp/README.md", "vendor/droid-mcp/UPSTREAM.md"}
        )
        if not documentation:
            return True
    return False


if __name__ == "__main__":
    event = os.environ["GITHUB_EVENT_NAME"]
    paths = []
    if event == "pull_request":
        # Disable rename detection so moving code into docs still requires a build.
        result = subprocess.check_output([
            "git", "diff", "--name-only", "--no-renames", "-z",
            os.environ["PR_BASE_SHA"] + "..." + os.environ["PR_HEAD_SHA"], "--",
        ])
        paths = [p.decode("utf-8", errors="surrogateescape") for p in result.split(b"\0") if p]
    required = needs_android(event, paths)
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write(f"android={str(required).lower()}\n")
    print("Android build required" if required else "Documentation-only PR: lightweight checks only")
