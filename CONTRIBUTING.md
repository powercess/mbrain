# Contributing

Welcome to MBrain! You can contribute by reporting bugs, suggesting features, improving documentation and translations, refining the interface, or developing Android and MCP features. Small fixes can go straight to a pull request. For new capabilities, permission boundaries, or major architectural changes, consider opening an issue first to discuss the use case and approach.

This guide covers getting the source, running the app locally, validating changes, and submitting contributions. For detailed device testing, see the [development guide](docs/development.md). For interface changes, refer to the [design guidelines](DESIGN.md).

## Reporting bugs and suggesting features

Search existing issues before opening a new one, then use the repository's issue templates.

- Bug reports: include the app version, Android version, device model, reproduction steps, and expected and actual behavior. For Root, Shizuku, or external MCP issues, include the relevant environment and connection method.
- Feature requests: describe the problem, the real-world use case, and why existing features do not meet your needs.
- Screenshots and logs: share only what is needed to investigate the issue. Redact tokens, passwords, private file contents, and sensitive device information.

## Getting the source and creating a branch

`dev` is the default development branch. Start all feature, fix, documentation, and workflow changes on a dedicated branch based on the latest `dev`, then submit a pull request targeting `dev`. Do not commit or push directly to `dev` or `main`.

If you do not have write access, fork `powercess/mbrain` on GitHub, then run the following commands, replacing `YOUR_USERNAME` with your username:

```bash
git clone https://github.com/YOUR_USERNAME/mbrain.git
cd mbrain
git remote add upstream https://github.com/powercess/mbrain.git
git fetch upstream dev
git switch -c docs/contributing-guide upstream/dev
```

Choose a branch name that fits your task, such as `fix/issue-description`, `feat/feature-description`, or `docs/topic`. Branches created by automated agents must use the `agent/` prefix.

If you have write access, clone the main repository and run `git fetch origin dev` followed by `git switch -c <branch-name> origin/dev`. Before starting a new task, handle any existing local changes so unrelated edits do not enter the branch.

## Setting up your environment

Documentation-only changes do not require an Android build environment. For app development, open the repository root in Android Studio and prepare the following tools. The repository's build configuration and CI are the source of truth for versions.

| Tool | Current requirement |
| --- | --- |
| JDK | 17; point `JAVA_HOME` to its installation directory and select JDK 17 as Android Studio's Gradle JDK |
| Android SDK | Platform 35, Build Tools 35.0.0, and Platform Tools; command-line installation also requires Command-line Tools |
| Android NDK | 28.2.13676358, used to build the bundled frpc |
| Go | 1.26.3, matching CI |
| Python | 3.9 or later, used for frpc builds and repository checks |
| Gradle | Use the included Wrapper (8.13); no separate installation is needed |
| Test device | A physical device or emulator running Android 9.0 (API 28) or later |

The project uses Kotlin 2.1.20, Android Gradle Plugin 8.13.2, and Jetpack Compose. See the [version catalog](gradle/libs.versions.toml) for dependency versions and [app/build.gradle.kts](app/build.gradle.kts) for app build configuration.

Install the SDK and NDK through Android Studio's SDK Manager, or run the following after adding `sdkmanager` to your PATH:

```bash
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;28.2.13676358"
sdkmanager --licenses
```

Set the local SDK path through `ANDROID_HOME` or the root `local.properties` file. Android Studio usually generates this file. If you create it manually, set `sdk.dir`; Windows paths can use forward slashes. Do not commit machine-specific paths or `local.properties`.

Check that `java -version`, `go version`, and your Python command work. The default Python command is `python` on Windows and `python3` on macOS / Linux. Set `MBRAIN_PYTHON` to use a different Python executable. On macOS / Linux, replace `python` with `python3` in the checks below as needed.

The first build downloads Gradle, Maven dependencies, a pinned version of the frp source, and Go modules. Gradle automatically invokes `scripts/build_frpc.py` to verify the source and compile frpc for four ABIs. Even if you are only debugging the interface, the first app build requires Go, Python, and the NDK. You do not need to download or commit generated binaries manually.

## Building, installing, and running

Run these commands from the repository root.

**Windows PowerShell:**

```powershell
.\gradlew.bat :app:assembleDebug
adb devices
.\gradlew.bat :app:installDebug
```

**macOS / Linux:**

```bash
./gradlew :app:assembleDebug
adb devices
./gradlew :app:installDebug
```

Enable USB debugging on a physical device and authorize your computer, or start an emulator first. You can also select `app` and the target device in Android Studio and run it there.

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. Its package name is `com.powercess.mbrain.debug`, so it can coexist with the release app. Local development does not require release signing materials. If installation fails because of a signature mismatch, first check the source of the installed test package. Uninstalling it will erase its app data.

After opening the app, start the gateway from the home screen and open the connection address and credentials section to get the actual address and token. Basic device information does not require Root. Prepare Root or Shizuku and grant MBrain access separately only when testing capabilities that require elevated permissions.

You can forward the port with ADB and run an MCP smoke test from your computer. This example assumes the app uses the default port, 8765:

```powershell
adb forward tcp:8765 tcp:8765
powershell -ExecutionPolicy Bypass -File scripts/smoke-mcp.ps1
```

The script prompts for a token and checks authentication, initialization, tool listing, and a read-only battery query. Adjust the forwarding command if the port differs, and use the script's `-Endpoint` parameter to specify the computer-side address when needed. With multiple devices, use `adb -s <serial>`. The script requires PowerShell; with cross-platform PowerShell, run `pwsh -File scripts/smoke-mcp.ps1`.

See the [development guide](docs/development.md) for further HTTP / stdio, Root, and Shizuku validation, and the [remote access guide](docs/remote-access.md) for tunnel and frpc testing.

## Finding your way around

| Directory or file | Purpose |
| --- | --- |
| `app/src/main/` | App source and resources: `ui` handles the interface, `gateway` manages the gateway lifecycle, `mcp` integrates external services, `shell` handles commands and file operations, and `data` manages configuration |
| `app/src/test/` | App JVM unit tests |
| `app/src/androidTest/` | Device tests that require an Android environment |
| `vendor/droid-mcp/` | The pinned upstream foundation and its module tests |
| `scripts/` | Build, source scanning, APK inspection, and integration testing scripts |
| `.github/workflows/` | CI and release workflows |
| `docs/` | Development, feature, and release documentation |

Keep each contribution focused on a clear problem and follow the organization of nearby code. For behavior fixes, add tests that reproduce the issue. For interface changes, check light and dark themes, enlarged fonts, and editing states, and include relevant screenshots in the pull request. When changing the upstream foundation, retain its licenses and record local changes in [UPSTREAM.md](vendor/droid-mcp/UPSTREAM.md).

## Validating your changes

Local checks should cover the behavior you changed. A full Android build is not required for every change.

| Change scope | Suggested validation |
| --- | --- |
| Documentation and contribution instructions | Check wording, relative links, example commands, and the diff; no Android build is needed |
| Repository scripts and workflows | Run relevant Python tests; validate workflow changes with actionlint and test any changed CI logic |
| App code, dependencies, and build configuration | Run relevant module tests, builds, and lint; verify device behavior on a physical device or emulator |
| MCP, permissions, tunnels, and other integrations | In addition to relevant tests, follow the integration guides to verify connections, failure handling, and resource cleanup after stopping |

Common Android checks on Windows (replace `.\gradlew.bat` with `./gradlew` on macOS / Linux):

```powershell
.\gradlew.bat :app:assembleDebug testDebugUnitTest :app:lintDebug
```

`testDebugUnitTest` runs JVM tests for configured modules. To validate a single module, specify a task such as `:app:testDebugUnitTest` or `:droid-mcp-core:testDebugUnitTest`. JVM tests do not replace device testing; state the actual devices and scope tested in your pull request.

Run repository script tests with:

```bash
python -m unittest discover -s scripts -p 'test_*.py'
```

Before committing, stage only files related to your contribution and inspect the staged changes:

```bash
git add CONTRIBUTING.md
python scripts/check-source.py
git diff --cached --check
git diff --cached --stat
git diff --cached
```

Replace the example filename with the files you changed. `check-source.py` scans Git's staging area; unstaged changes are not checked. Do not commit tokens, signing materials, personal paths, private device addresses, APKs, build caches, or debug logs. Local debugging artifacts can go in the ignored `.local-tools/` directory.

CI continues to report the `build` and `branch-policy` checks. Documentation-only pull requests skip Android setup and builds while still running lightweight checks such as source scanning. Pushes to `dev` / `main`, manual runs, and releases retain full validation.

## Submitting your contribution

Commit and pull request titles must use `type: description` or `type(scope): description`, with a space after the colon. Descriptions may be in English or Chinese. For example:

```text
docs: improve local development instructions
fix(ui): keep the save button visible
feat(mcp): add connection status feedback
```

When ready, commit and push your dedicated branch, then open a pull request targeting `powercess/mbrain:dev` on GitHub. If you use a fork, push to your own `origin`. Include:

- The problem being solved, related issues, and the resulting behavior.
- The checks you actually ran and their results, plus any unverified areas and why they were not tested.
- Screenshots for interface changes, or reproduction and integration testing steps for protocol and device capabilities.

Continue updating the same branch in response to review feedback. Merge only after required checks pass and branch update requirements are met. Merge and squash commit titles must follow the same format. Do not bypass branch protections.

Regular contributions do not target `main` or create release tags. Only this repository's `dev` branch may open a release integration pull request targeting `main`. Create version tags only on `main` commits and only when a release is explicitly authorized. See [AGENTS.md](AGENTS.md) and the [development guide](docs/development.md) for detailed rules.

## Troubleshooting

- **Gradle uses the wrong Java version:** check both the terminal's `JAVA_HOME` and Android Studio's Gradle JDK, and keep both on JDK 17.
- **SDK, NDK, or Python cannot be found:** verify the SDK path, exact NDK version, and `MBRAIN_PYTHON`. The first bundled frpc build requires the complete toolchain.
- **Initial dependency downloads fail:** check access to the configured dependency repositories, GitHub, and Go module sources. Do not remove source verification to work around download problems.
- **Temporary directory renaming fails on Windows:** wait for file locks to be released, then retry with `--max-workers=1 --no-parallel --no-watch-fs` appended to the Gradle command.
- **A restricted environment cannot write to the default Gradle cache:** set `GRADLE_USER_HOME` to the ignored `.gradle-user-home` directory under the repository.
