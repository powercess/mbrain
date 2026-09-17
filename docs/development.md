# MBrain 开发与联调

## 远程访问集成验证（2026-09-17）

- Debug / Release APK 构建和 lint 通过（无错误，37 条警告）；两个 APK 均通过四 ABI 库完整性检查。
- 应用与核心传输共 77 项 JVM 测试通过；仓库脚本 14 项测试、工作流 actionlint 通过。
- Android API 35、x86_64 测试设备：实际运行 APK 内置 frpc，使用 ADB 反向转发连接仅监听电脑回环地址的 frps；两条隧道分别通过未认证拒绝、初始化、工具目录和只读电池查询。
- 主动占用手机 8765 后，网关自动绑定空闲端口，两条隧道均跟随实际端口；关闭一条隧道不影响另一条。进程强制停止后映射关闭，重新启动后加密配置和 MCP Token 保留。
- UI：深浅色、空字段校验、保存、等待网关、独立开关、编辑放弃/继续、删除取消/确认、凭据重置通过；重置后旧 Token 返回 401，新 Token 正常调用。
- 限制：尚未验证公网域名/HTTPS 反向代理、ARM 真机、长期后台运行和多 OEM 电池策略。本次测试不代表公网部署已经完成。

## 工程环境

Android Studio 打开仓库根目录，运行 `app`。当前配置为 Kotlin 2.1.20、AGP 8.13.2、Gradle 8.13、compile/target SDK 35、min SDK 28。
使用标准 JDK 17，并将 `JAVA_HOME` 指向 JDK 安装目录；CI 使用 Temurin 17，不依赖特定厂商的自动下载配置。
安装 Android SDK Platform 35 和 Build Tools 35.0.0。SDK 路径通过 `ANDROID_HOME` 或本机 `local.properties` 指定，不提交。设备上的 root、Shizuku、MT 服务无需运行在开发电脑上。

内置 frpc 构建还需要 Python 3.9+、Go 1.26.3 和 Android NDK 28.2.13676358。Gradle 自动构建四种 ABI；`MBRAIN_PYTHON` 可指定 Python 路径。来源、构建和使用说明见[远程访问](remote-access.md)。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :droid-mcp-core:testDebugUnitTest :droid-mcp-shell-core:testDebugUnitTest :droid-mcp-root:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:installDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。
受限环境可将 `GRADLE_USER_HOME` 设为仓库下 `.gradle-user-home`。
Windows 首次依赖转换曾遇到 Gradle 临时目录重命名失败；使用 `--max-workers=1 --no-parallel --no-watch-fs`，待文件锁释放后重试。

## 提交前检查与持续集成

### 分支与发布

- `dev` 是默认分支和开发集成分支。所有功能、修复、文档和工作流修改均从最新 `dev` 新建独立分支，再通过 PR 合并到 `dev`；不得直接在 `dev` 或 `main` 提交、推送修改。自动化代理使用 `agent/` 分支前缀。
- `dev` 和 `main` 均要求 PR、`build` 与 `branch-policy` 检查通过且分支保持更新，禁止强推与删除；管理员同样受保护规则约束。当前不强制额外人员批准，单人维护也必须经过 PR 和 CI。
- `main` 接收本仓库 `dev` 的 PR。合并需要通过 `build` 和 `branch-policy` 检查，不允许直接推送、强推或删除。
- 准备发布时，在功能分支更新 `app/build.gradle.kts` 的 `versionName`（首发为 `0.1.0`）并递增 `versionCode`，新增 `docs/releases/版本号.md` 发行说明，通过 PR 依次合并到 `dev`、`main`。
- 在合并后的 `main` 提交上创建并推送对应 tag（例如 `v0.1.0`）。`Android release` 验证提交属于 `main`、tag 与版本号一致，运行 Release 构建、测试、Lint、包名和 ABI 检查。独立的签名任务验证正式证书后发布 GitHub Release，附件为通用 APK、SHA256 校验文件与证书指纹。
- 当前只接受 `v主版本.次版本.修订版本` 的稳定版本 tag。不要移动已发布 tag。普通合并不自动创建 tag，也不自动发布。

签名材料保存在 GitHub `release` Environment Secrets：`ANDROID_KEYSTORE_BASE64`（正式 keystore 的 Base64）、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 和 `ANDROID_SIGNING_CERT_SHA256`。环境仅允许 `v*` 标签使用；缺少任一项或证书指纹不匹配都会阻止发布，绝不回退到 Debug 签名。构建与签名使用不同 Runner，签名任务不检出或运行项目代码。私钥与恢复信息保存在仓库之外并另行备份；公开证书指纹见 [signing-certificate.txt](signing-certificate.txt)。

暂存待提交文件后运行 `python scripts/check-source.py`。脚本读取 Git 暂存区的实际 blob，检查密钥、常见 Token、个人路径、私网设备地址和不应提交的产物；只打印文件位置与问题类别，不回显敏感值。它是自动检查的一层，不能替代人工审查。

运行 `python -m unittest discover -s scripts -p 'test_*.py'` 验证扫描器。提交前再运行 `git diff --cached --check`、`git diff --cached --stat` 检查实际变更。

GitHub Actions 在 push、PR 和手动触发时使用 Ubuntu 24.04、JDK 17、Android SDK 35，检查源码、校验 Gradle Wrapper、构建 Debug APK、运行所有已接入模块的 JVM 测试和应用 Lint。工作流关闭 Gradle 缓存，不需要签名或发布密钥，不创建 GitHub Release。

### 下载自动构建

进入仓库 **Actions → Android build → 一次成功运行 → Artifacts**，下载 `mbrain-debug-universal-…`，解压后获得 APK 与 `SHA256SUMS.txt`。构建产物和测试/Lint 报告保留 14 天；手动构建入口为 **Run workflow**（工作流需先存在于默认分支）。

通用 APK 覆盖 `arm64-v8a`、`armeabi-v7a`、`x86_64` 和 `x86`，最低 Android 9.0。CI 检查 APK 中每个原生库是否同时包含四种 ABI；当前原生库来自 AndroidX 依赖。无需按 CPU 下载不同文件。该检查验证打包完整性，不代表四种架构都已进行运行测试。设备上的外部 MCP 程序需要另行匹配设备架构。

自动构建使用临时 Debug 签名和 `com.powercess.mbrain.debug` 包名，仅用于测试，可与正式版并存。不同运行的 Debug 签名可能不同，不能保证覆盖安装同名测试包。正式版固定使用 `com.powercess.mbrain` 和正式签名。早期临时版本曾使用正式包名与 Debug 签名，无法直接覆盖为正式版；卸载会清除配置，操作前需保存需要保留的连接设置。

验证首次克隆时，在新目录克隆后设置标准 `JAVA_HOME`、`ANDROID_HOME` 和全新的 `GRADLE_USER_HOME`，运行 `./gradlew :app:assembleDebug testDebugUnitTest :app:lintDebug --no-build-cache --max-workers=1 --no-parallel --no-watch-fs`。Windows 使用 `gradlew.bat`。不复制旧目录的 `local.properties`、`.local-tools`、`.gradle` 或 `build`。

## 使用流程

1. **首页**启动网关。前台通知和页面均可停止。服务仅监听回环地址，进程退出后不自动恢复。
2. **能力**页面选择 Root / Shizuku 进入详情，打开启用开关即可自动检查并申请 MBrain 自身权限；成功后通过工具目录查看可用工具。拒绝或超时保持关闭；Shizuku 未运行时需先启动服务再打开开关。Root 在应用进程重启后自动恢复检查。
3. **MCP**页面添加本机 HTTP 或托管进程，进入完整编辑页。名称与地址由用户填写，不预设特定服务商。保存后在服务详情点击“连接服务”。
4. **设置 → 地址与凭据**复制网关 Token，给可信 Agent 配置 `Authorization: Bearer <Token>`。首页也有连接入口。
5. 工具目录、工具说明、运行记录均使用独立页面，系统返回回到原页面；设置中的外观支持跟随系统、浅色、深色。

启用的高权限工具可执行实际修改；拥有网关 Token 的客户端可以使用全部已启用工具。
Root 与 Shizuku 分别注册，互不覆盖，不自动把一个后端的失败重试到另一个后端。
Shizuku 通过 root 启动时可以返回 UID 0，通过 ADB 启动时一般是 shell UID，应以 `id` 为准。
KernelSU 等管理器可能对未授权应用隐藏 `su`，需在管理器中单独允许 MBrain；其他应用已获 root 不代表 MBrain 已授权。

## 当前工具

| 来源 | 工具 |
|---|---|
| 设备（始终启用） | `get_device_info`、`get_battery_info`、`get_connectivity`、`get_storage_info` |
| 应用（默认启用） | `list_installed_apps`、`get_app_info`、`launch_app` |
| Root / Shizuku（默认关闭） | `root_exec` / `shizuku_exec`；安装、卸载、清数据、停止、禁用/启用应用；权限、设置、前台窗口、截屏等 |
| 文件（随对应高权限组启用） | `{root,shizuku}_file_{list,read,write,mkdir,copy,move,delete}` |
| 外部 MCP | `mcp_<连接ID前8位>_<原工具名>_<名称哈希>`，避免跨服务名称冲突 |

命令支持 shell 语法，最长执行约 30 秒；stdout 上限 128KiB，stderr 上限 32KiB。超出预算返回错误，不把无限输出累积进内存。
文件读取最多 48KiB，返回 base64 与截断标记；写入为最多 48KiB 的 UTF-8 文本，默认拒绝覆盖。
复制/移动目标必须不存在，删除工具只删除单文件或空目录。Shell 命令本身并无路径沙箱。
应用可见性遵循 Android 包可见性规则；原生应用列表不保证包含所有后台服务包。

## HTTP MCP（例如 MT）

MT 仅作为普通 HTTP MCP 服务的联调示例，应用没有 MT 专属入口或默认配置。在 MT 中开启 MCP 后，手动添加 HTTP 服务。此前联调地址为 `http://127.0.0.1:8787/mcp`，实际地址和 Token 以该服务的配置为准。
只接受 localhost / 127.0.0.1 / ::1，不跟随 HTTP 重定向，不允许连接 MBrain 当前实际监听端口。
支持初始化、工具列表分页、会话头、JSON 与 SSE POST 响应，以及工具调用。
完整 inputSchema/outputSchema、显式 null 参数、content、structuredContent、isError 均保留；MT 自身权限错误不会被吞掉。
上游请求失败不自动重试写操作。服务重启或会话失效后点击“断开/连接”重新初始化；工具变更也通过重新连接刷新。
当前聚合的是 MCP tools，不代理 prompts/resources，也不提供 sampling/elicitation。

## 托管 stdio MCP

在“添加服务 → 托管进程”中逐项填写可执行文件与参数。例如原 argv：

```json
["/绝对路径/mcp-server", "--stdio"]
```

对应“可执行文件”填写 `/绝对路径/mcp-server`，“参数 1”填写 `--stdio`。参数不做 shell 分词，无需额外引号；空参数和包含空格的参数也原样保留。

- **APP**：使用 MBrain 应用身份，受 Android 沙箱和可执行文件限制。
- **ROOT / SHIZUKU**：要求相应能力已启用且已授权，可运行对应身份能访问的程序。
- 不内置 Node/Python。Termux 等运行时需要自行准备，并按运行时需要显式传入解释器、环境或包装脚本；不能保证直接跨应用执行就能运行。
- stdout 必须是逐行 JSON-RPC；stderr 单独消费。单条消息限制 2MiB，单请求等待约 30 秒。
- 关闭连接或停止网关会关闭直接托管的进程与管道。不会回滚已执行的操作，也不能保证撤销服务自行派生的脱离进程或已提交到 MT 的任务。
- 无自动崩溃重启；失败后通过界面重新连接。

设备测试夹具（不依赖 Python/Node）：

```powershell
adb push scripts/fixtures/stdio-smoke.sh /data/local/tmp/mbrain-stdio-smoke.sh
```

在“托管进程”中将可执行文件设为 `/system/bin/sh`，参数 1 设为 `/data/local/tmp/mbrain-stdio-smoke.sh`，选择已授权的 SHIZUKU 后连接。
应出现一个 `smoke_echo` 工具，调用返回 `mbrain-stdio-ok`。测试结束移除该连接并删除夹具。

## 电脑端验证

```powershell
adb devices
adb forward tcp:8765 tcp:8765
powershell -ExecutionPolicy Bypass -File scripts/smoke-mcp.ps1
```

按提示输入 Token。脚本验证未认证请求被拒绝、初始化、核心工具列表、电池查询。
多个设备时使用 `adb -s <serial>`。端口冲突时以应用显示的实际端口为准。Token 加密持久保存，重置后旧 Token 失效。

## 代码结构

- `app/.../ui`：四页导航、授权入口、连接配置。
- `app/.../gateway`：前台服务、配置变更、工具注册、连接生命周期和内存活动记录。
- `app/.../shell`：受控子进程、Root / Shizuku 执行、文件操作。
- `app/.../mcp`：HTTP / stdio JSON-RPC 客户端与工具代理。
- `app/.../data`：应用私有 SharedPreferences 配置（包括上游 Token，备份关闭）。
- `vendor/droid-mcp`：固定提交的基座模块，来源与修改见 `UPSTREAM.md`。

## 2026-09-17 验证

- 106 项 JVM 测试通过：应用桥接/文件测试 9、核心 55、shell-core 27、root 15。
- Android lint 无错误，仍有上游及开发版的警告。
- API 35 模拟器：四页导航、服务启停、Shizuku 授权与 `id`（UID 0）、文件写读删、应用详情通过。
- MT：49 工具发现、只读工作区调用成功，文件策略拒绝的 content / structuredContent / isError 正确透传。
- stdio：实际启动、发现工具、通过网关调用成功；停止网关后进程和 HTTP 监听关闭。
- 直接 Root 代码与基座测试通过，但该设备尚未对 MBrain 开放 `su`，未宣称直接 Root 真机验证完成。
- 尚未验证：长期后台运行、多 OEM、真实 Agent 的全面兼容性、具体 Termux Node/Python 服务。对外传输仍沿用基座协议实现，不宣称通过最新 MCP 全套合规测试。

## 2026-09-17 UI 重构验证

设计规范见根目录 `DESIGN.md`。UI 拆为主题/通用组件、导航容器、概览与能力、MCP 配置、工具与凭据详情五部分。

- Debug APK 构建、应用模块 9 项 JVM 测试与 Android lint 通过；lint 无错误，保留依赖版本等警告。
- MuMu API 35：四页导航、能力详情、工具目录与完整工具说明、网关启动、服务详情通过。
- 编辑页：空名称校验、校验后连续输入、放弃/继续编辑、保存后进入详情、移除取消/确认通过。修复错误提示消失导致输入框丢失焦点的问题。
- 浅色/深色切换、系统栏图标、1.3 倍字体、横竖屏切换后的草稿恢复通过；检查的运行时日志未发现 AndroidRuntime 崩溃。
- 测试服务 `UI-Connection-QA` 已移除，旋转和字体设置已恢复。原有 MT 配置保留。
- 本轮设备重启后 MT 与 Shizuku 未恢复可用状态；验证了 MT 连接失败的详情反馈与重试入口，未将本轮 UI 检查记作新的 MT / Shizuku 成功调用测试。

## 后续权限联调与源码边界

- Root / Shizuku 已改为单开关自动申请权限；后续设备联调中，两种 MCP 执行通道均返回 UID 0，关闭后移除工具、重新开启恢复工具、进程重启恢复状态均通过。此前 Root 未验证的记录仅描述当时的设备状态。
- 版本目录只保留当前七个基座模块与应用实际引用的依赖，不预置未来模块的 SDK 声明。
- `rikka.shizuku` 属于 Shizuku SDK；RikkaHub 仅作为 UI 参考，没有引入其业务源码。上游来源与许可证保留在 `vendor/droid-mcp`。
- 旧 MT 默认值仅供 v1 配置迁移，新建 MCP 连接无厂商预设；迁移逻辑不能作为无用代码删除。
- 调试截图、session、构建日志留在 Git 忽略的 `.local-tools/`；不放进 `app/src/main`，不配置为 APK 资源。
