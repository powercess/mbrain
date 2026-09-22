# 内网穿透

在 **设置 → 内网穿透** 添加隧道。MBrain 内置 frpc，不需要 Root 或 Shizuku；用户需要已有可访问的 frps。每条隧道配置自己的服务器、认证信息和目标地址，可以连接同一台或不同的服务器。

## 支持的转发方式

| 代理类型 | 客户端插件 | 用途 |
| --- | --- | --- |
| TCP | 不使用 | 公网端口转发到指定本地 TCP 端口 |
| HTTPS | 不使用 | 按域名透传 TLS，本地目标已经提供 HTTPS |
| HTTPS 或 TCP | HTTPS → HTTP（`https2http`） | frpc 使用所选证书终止 TLS，再访问本地 HTTP 服务 |
| TCP | TLS → TCP（`tls2raw`） | frpc 终止 TLS，再转发解密后的字节流 |

“本应用 MCP”自动使用正在运行的 MBrain 网关端口。HTTPS 代理应选择 HTTPS → HTTP 插件，因为网关自身提供 HTTP。“自定义服务”可以填写手机上其他服务的地址和端口，不要求服务使用 MCP，也不要求它先加入 MCP 聚合列表。

HTTPS 域名需要解析到 frps，且 frps 已配置 `vhostHTTPSPort`。TCP 需要服务器允许所选映射端口。填写“公网访问地址”只保存供复制的连接信息，不会自动配置 DNS、防火墙或服务器；MBrain MCP 的地址路径应为 `/mcp`。

## 独立运行

- 打开单条隧道开关即启动独立的前台隧道服务，可从通知停止全部隧道。
- 自定义服务隧道不依赖 MCP 网关；停止 MCP 后仍继续运行。
- 指向 MBrain 的隧道在网关未运行时显示“等待 MCP 网关”，网关启动后连接，停止后关闭对应 frpc 进程。
- 每条隧道使用独立进程，启停、故障和重试不会重启其他隧道。编辑前先关闭对应隧道。
- frpc 自动重连网络；进程退出后以 1–30 秒退避重启。应用进程死亡或设备重启后不自动启用任何隧道。
- frps 连接、隧道注册、本地端口可连接性分别显示。本地检测每 15 秒尝试一次 TCP 连接，不发送应用请求；“隧道已注册”不代表公网 DNS、证书或应用协议已验证。

MCP 继续使用已有 Bearer 鉴权。frps Token 与 MCP Token 是独立凭据；MCP Token 持久保存，重启网关不改变；在“地址与凭据”中重置后，旧 Token 失效，需要更新客户端配置。

## 证书配置

在 **内网穿透 → 证书管理** 导入 PEM X.509 证书链和对应私钥。支持未加密的 RSA / EC PKCS#8 私钥，以及 RSA PKCS#1 私钥；证书链的第一张应是与私钥对应的叶证书。CA 可以不附带私钥。

保存时验证证书格式与私钥匹配关系，列表显示到期日期、过期或即将到期状态。点击已保存证书可以替换；替换只重连引用它的隧道。仍被隧道引用的证书不能删除。当前不自动申请或续期证书。

两套 TLS 配置用途不同：

- **服务证书与私钥**用于 `https2http` / `tls2raw`，保护公网客户端访问服务的连接。HTTPS 透传时证书由本地服务管理。
- **frps 连接 TLS**保护 frpc 与 frps 的连接，始终启用加密。可指定信任 CA、TLS 服务器名称及客户端证书，实现身份校验和双向 TLS。未指定 CA 时沿用 frp 默认行为，不验证 frps 证书身份。

配置、Token 和证书私钥使用 Android Keystore 的 AES-GCM 加密保存，应用禁止备份。frpc 必需的明文配置与 PEM 只写入应用私有的 `noBackupFilesDir` 临时目录，进程结束时删除，下次启动清理残留。界面只显示分类后的错误与状态，最多保留 40 条，不展示原始 frpc 日志或凭据。配置读取失败时保留原数据并禁止覆盖。

## 最小 frps 示例

```toml
bindPort = 7000
vhostHTTPSPort = 443
auth.method = "token"
auth.token = "REPLACE_WITH_YOUR_TOKEN"
transport.tls.force = true
allowPorts = [{ start = 18080, end = 18089 }]
```

示例 HTTPS 隧道：目标为本应用 MCP，代理类型为 HTTPS，域名为 `phone.example.com`，插件为 HTTPS → HTTP，选择该域名的证书与私钥，公网地址填 `https://phone.example.com/mcp`。启用隧道并在首页启动 MCP 后，即可复制客户端配置。

## 构建与来源

内核固定为 [fatedier/frp v0.71.0](https://github.com/fatedier/frp/tree/v0.71.0)，Apache-2.0，许可证随 APK 保存在 `assets/licenses/frp-LICENSE.txt`。`scripts/build_frpc.py` 固定源码归档 SHA256，使用 Go 1.26.3、NDK 28.2.13676358 为四种 ABI 构建 Android API 28 可执行文件，并设置 16 KiB ELF 页对齐。

Gradle 的 `preBuild` 自动生成 `libfrpc.so` 并作为原生库打包、安装提取，在应用身份下作为独立进程执行。没有下载后执行的二进制，也不改写上游隧道协议。未启用 frpc Web 管理端口；构建时仅提供占位嵌入页面。运行方式遵循 [Android 对可写目录执行文件的限制](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission)。

## 验证

JVM 测试覆盖配置生成、组合校验、证书匹配、独立启停、MCP 端口变更、证书替换范围、临时文件清理和日志脱敏。

设备测试使用 `scripts/smoke-frpc.py`：创建仅监听电脑回环地址的临时 frps，通过 adb reverse 连接，使用临时证书和双向 TLS，验证四种代理组合、MCP 鉴权与 initialize、HTTPS → HTTP 流式响应、停止网关后的自定义转发、独立停止和清理。测试结束关闭 frps、移除所建 adb reverse 映射；不需要公网服务器。

使用独立的 QA applicationId 构建并安装应用与 androidTest APK 后运行：

```text
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -I scripts/frpc-qa.init.gradle
adb -s <设备序列号> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <设备序列号> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python scripts/smoke-frpc.py --serial <设备序列号> --package com.powercess.mbrain.frpcqa
```

此脚本拒绝对正式包与普通 `.debug` 包运行，避免修改日常使用的数据。四种 ABI 的打包检查不等同于所有架构和 OEM 的运行验证。
