# 内网穿透

在 **设置 → 内网穿透 → 配置服务器** 填写已有 frps 的名称、地址、端口和 Token。当前只支持配置一台服务器；点击服务器入口直接编辑，所有隧道共用此配置。MBrain 内置 frpc，不需要 Root 或 Shizuku。

## 配置隧道

返回内网穿透页，点击“添加隧道”，填写名称、本地目标和公网访问端口：

- **本应用 MCP**：转发本机 MCP 网关 `127.0.0.1:8765`，运行时跟随网关实际监听端口，无需填写本地端口。
- **自定义服务**：填写本地地址和端口，默认地址为回环地址。
- **公网访问端口**：frps 对外提供此隧道的 TCP 端口，与服务器连接端口、本机网关端口分别配置。
- **自定义公网地址**：可选。留空时，MCP 自动使用 `http://服务器地址:公网访问端口/mcp`；存在额外端口映射或域名时可填写完整 HTTP 地址覆盖，用于显示和复制客户端配置。

当前仅提供基础 TCP 转发，不提供 HTTPS 路由、客户端插件或证书管理。frpc 到 frps 的底层连接默认使用 TLS，与隧道转发类型独立，无需额外界面设置；兼容要求 TLS 的 frps。服务器需允许对应映射端口。

## 运行与编辑

- 每条隧道独立启停。MCP 隧道在网关关闭时等待，网关启动后连接；自定义服务隧道独立运行。
- 修改服务器前，先关闭关联隧道。删除服务器前，先删除关联隧道。
- frpc 网络重连与进程退避重启保持启用；应用进程死亡或设备重启后，隧道保持关闭。
- 服务器连接、隧道注册和本地端口状态分别显示。“隧道已注册”不代表公网应用访问已验证。
- MCP 继续使用网关 Bearer Token；服务器 Token 与 MCP Token 是不同凭据。

旧配置中的服务器会自动整理。若旧版本曾配置多台服务器，当前仅使用第一台，其余配置保留在加密存储中，对应隧道需重新编辑保存以使用当前服务器。旧 HTTPS、TLS 和插件配置不会自动改为明文转发；需编辑服务器或隧道，确认基础 TCP 参数后保存。

配置与 Token 继续使用 Android Keystore 的 AES-GCM 加密保存，禁止备份；读取失败时保留原数据并禁止覆盖。运行期间的 frpc 配置仅保存在应用私有临时目录，停止后清理。

## 最小 frps 示例

```toml
bindPort = 7000
auth.method = "token"
auth.token = "replace-with-your-token"
transport.tls.force = true
allowPorts = [{ start = 18080, end = 18090 }]
```

## 构建与验证

内核固定为 frp v0.71.0，使用 `scripts/build_frpc.py` 构建四种 Android ABI。许可证保存在 `assets/licenses/frp-LICENSE.txt`。

`scripts/smoke-frpc.py` 使用仅监听电脑回环地址的临时 frps，通过 ADB reverse 验证共享服务器的两条 TCP 隧道、MCP 鉴权、流式响应、独立启停及运行目录清理。使用独立 QA 包名，不覆盖日常应用。

```shell
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -I scripts/frpc-qa.init.gradle
python scripts/smoke-frpc.py --serial <设备序列号> --package com.powercess.mbrain.frpcqa
```
