# 手机操作与应用诊断

启用 Root 或 Shizuku 后，在工具目录中选择对应来源。下表省略 `root_` / `shizuku_` 前缀，例如完整名称为 `shizuku_ui_dump`。工具不需要单独启用无障碍服务，也不依赖 AutoX.js。

## 手机操作

| 工具 | 参数与用途 |
| --- | --- |
| `ui_dump` | `offset`、`limit`；读取当前窗口，默认每页 50 个节点，最多 60 个；总遍历上限 600 个 |
| `ui_click` | 按选择器点击节点或可点击的父节点；也可传 `x`、`y` |
| `ui_long_click` | 按选择器长按；坐标长按保持 800ms |
| `ui_set_text` | 选择输入框或它的标签，`value` 为替换内容；支持中文、emoji、换行，空字符串清空 |
| `ui_swipe` | `x1`、`y1`、`x2`、`y2`，`duration_ms` 为 50–3000，默认 350 |
| `ui_navigate` | `action`：`back`、`home`、`recents`、`notifications`、`quick_settings` |
| `ui_wait` | 选择器 + `state=present/absent`，默认等待出现，`timeout_ms` 默认 5000、最多 15000 |
| `clipboard_get` | 读取第一项文本；返回可用性、文本、项数与截断状态，不读取 URI 指向的文件 |
| `clipboard_set` | `value`：写入最多 16000 字符的文本 |

选择器至少提供 `text`、`resource_id`、`description` 之一，多个字段同时提供表示同时满足。可用 `package` 限定目标应用。`text` 和 `description` 默认精确匹配，`contains=true` 改为包含匹配。动作遇到多个匹配时返回错误，使用 `index` 指定匹配结果中的第几个（从 0 开始）；它不是 `ui_dump` 的节点索引。坐标与选择器不能混用。

`ui_dump` 的索引和父索引用于描述当次快照，翻页期间页面应保持稳定；不缓存节点供后续动作复用。单个文字/描述最多返回 200 字符，密码文字不返回。文本写入最多 16000 字符。动作返回 `performed=true` 表示系统接受了动作，业务是否完成应继续用 `ui_wait`、`ui_dump` 或截图验证。

例如：

```json
{"name":"shizuku_ui_set_text","arguments":{"text":"服务名称","package":"com.powercess.mbrain","value":"中文输入 😀"}}
```

```json
{"name":"shizuku_ui_wait","arguments":{"text":"保存成功","timeout_ms":5000}}
```

## 应用诊断

| 工具 | 参数与用途 |
| --- | --- |
| `logs_query` | `lines`（1–300，默认 100）、`pid`、`uid`、`tag`、`level`（默认 I）、`buffer`（默认 main）、`since` |
| `appops_get` | `package`、可选 `op` 和 `user` |
| `appops_set` | `package`、`op`、`mode`（allow/ignore/deny/default/foreground）、可选 `user`；设置后读回 |
| `proc_list` | 可选 `package` 和 `limit`；返回 PID、UID、RSS KiB、进程名，包括包名的冒号子进程 |
| `app_resource_usage` | `package`；返回内存报告，以及系统 CPU 报告中的采样区间、目标应用和总量 |
| `net_status` | 返回 IP、IPv4/IPv6 路由、Wi-Fi 与全局 HTTP 代理，各项带退出码 |
| `net_diagnose` | `host`、`port`（默认 443）；DNS 最多等待 4 秒，最多尝试 3 个地址、每次 TCP 最多 2 秒 |

日志未传 `since` 时取最近若干条；传入 `MM-DD HH:MM:SS.mmm` 后，从该时间开始最多取 `lines` 条。日志是快照，不持续监听。输出超出执行器预算会明确报错，应缩小查询范围。普通诊断文本返回截断标记。

AppOps 默认针对当前 Android 用户；`default` 恢复该项的平台默认策略。网络诊断只进行域名解析与 TCP 握手，不发送 HTTP 请求；DNS/TCP 失败作为诊断结果返回，不能仅凭 MCP 调用成功判断网络畅通。连通性反映所选执行身份，并不等同于目标应用的网络权限或 VPN 路由。

## 执行方式

固定 Java 辅助入口打包在 APK 中，经现有高权限执行器启动 `app_process`，只接受上述固定操作和 JSON 参数，不接受任意代码。UI 操作在两个后端之间串行执行；连接时保留已有无障碍服务，完成后断开。沿用现有命令超时、输出上限与能力关闭行为。

## 验证

应用单元测试覆盖参数边界、字面量 Unicode 参数传递、辅助进程错误、结果解码、日志过滤、AppOps 读回及进程匹配。

构建 Debug APK 和 AndroidTest APK，安装独立测试包后运行：

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
./scripts/smoke-capabilities.ps1 -Package com.powercess.mbrain.dev
```

脚本仅允许测试包名，会重新启动该测试应用，操作未保存的服务表单，不清除数据。诊断测试只返回通过/失败；剪贴板原始对象在设备内存中保存、恢复，不传回主机。测试前让设备保持解锁。使用固定开发包名和同一本地签名覆盖安装，不卸载已有应用。

真实 Shizuku → MCP 链路另由 `CapabilityGatewayTest` 验证。在测试应用中启用并授权 Shizuku，安装对应 AndroidTest APK，然后执行：

```powershell
adb shell am instrument -w -e class com.powercess.mbrain.control.CapabilityGatewayTest -e capabilityGateway true com.powercess.mbrain.dev.test/androidx.test.runner.AndroidJUnitRunner
```

常规开发测试统一使用固定的开发包名。测试在设备内获取自己的网关凭据、初始化 MCP、检查全部 16 项工具并执行读操作及测试应用内的点击；Token 不离开设备。结束后停止测试网关。

### 2026-09-24 验证记录

- Debug 与 AndroidTest APK 构建通过；应用模块 41 项 JVM 测试通过，lint 为 0 错误、33 警告。已有 HTTP 本机测试曾出现一次 socket 超时，复跑通过。构建复用了未修改的四 ABI frpc 产物。
- Android 15 / API 35 已连接设备：`smoke-capabilities.ps1` 通过，覆盖控件树、选择器点击、中文与 emoji 输入、长按、滑动、返回/桌面/最近任务、出现/消失等待与超时、设备内剪贴板保存/恢复，以及全部诊断工具。
- `CapabilityGatewayTest` 通过：真实 Shizuku → 托管执行器 → 辅助进程 / 系统命令 → MCP 返回链路可用，16 项新增工具均出现在网关目录。
- 中文工具名和搜索界面验证通过。测试使用独立 `controlqa` 包，保留原有应用数据；结束时测试网关已停止。
- 尚未覆盖其他 Android 版本、其他 ROM、直接 Root 后端与多显示器；当前 UI 操作仅支持默认显示器。

固定开发包名后的提交前复验：`com.powercess.mbrain.dev` / “MBrain 开发版”安装成功，手机操作与诊断脚本、设备内 Shizuku/MCP 测试再次通过。Debug、AndroidTest、Release 构建、各模块 JVM 测试、Debug/Release lint 及四 ABI 打包检查通过；常规开发不再使用临时包名覆盖脚本。
