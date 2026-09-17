# 远程访问

设置 → 远程访问 → 添加隧道，填写自建 frps 的地址、连接端口、认证 Token 和映射端口。支持保存并同时启用多个隧道，可连接同一服务器的不同端口或不同服务器。每条隧道独立启停、重试和删除。编辑前先关闭对应隧道。

网关启动后连接所有已启用隧道；网关停止后关闭全部 frpc 进程。保留启用选择，但不在开机或应用进程死亡后自行启动网关。frpc 自动重连网络，进程异常退出后以 1–30 秒退避重启。后台持续运行仍受设备电池策略影响。

本机优先监听 `127.0.0.1:8765`，端口被占用时由操作系统分配端口。地址与凭据、ADB 命令及所有隧道均使用实际端口。启动失败不会连接隧道，避免把其他程序误暴露出去。

## 服务器示例

在服务器运行 frps（Token 使用自行生成的随机值）：

```toml
bindPort = 7000
proxyBindAddr = "127.0.0.1"
auth.method = "token"
auth.token = "REPLACE_WITH_YOUR_TOKEN"
transport.tls.force = true
allowPorts = [{ start = 18080, end = 18089 }]
```

由同一台服务器上的 Caddy 提供公网 HTTPS：

```caddy
phone.example.com {
    reverse_proxy 127.0.0.1:18080
}
```

App 对应填写映射端口 `18080`，公网 MCP 地址 `https://phone.example.com/mcp`。公网地址是可选的连接信息，不会代替用户配置 DNS、证书或反向代理。服务端控制端口需要可达；映射端口仅供本机反向代理使用。

隧道状态“隧道已建立”表示 frps 已成功创建映射，不代表公网域名及 HTTPS 已验证。服务器的 frp Token 与 AI 客户端使用的 MCP Bearer Token 是两份独立凭据。frp TLS 保护隧道；客户端到服务器这段使用 HTTPS。当前沿用 frp 默认服务端证书信任行为，未提供自定义 CA 或 mTLS 配置。

## 凭据与权限

隧道配置及持久 MCP Token 使用 Android Keystore 的 AES-GCM 加密保存，应用备份关闭。重启网关不再改变 MCP Token；在“地址与凭据”中重置可使旧 Token 失效。所有入口共享网关鉴权和已启用工具权限，不提供每隧道独立工具权限。

frpc 启动配置仅写入应用私有、排除备份的临时目录，停止时删除，下次启动清理残留。日志仅保留最多 40 条分类后的状态，不展示原始服务器日志或凭据。客户端配置包含 MCP Token，请只交给可信客户端。

## 构建与来源

- 官方源代码：[fatedier/frp v0.71.0](https://github.com/fatedier/frp/tree/v0.71.0)，Apache-2.0；源码归档 SHA256 固定在 `scripts/build_frpc.py`。
- 安装 Python 3.9+、Go 1.26.3、Android NDK 28.2.13676358。Gradle `preBuild` 自动调用构建脚本；可通过 `MBRAIN_PYTHON` 指定 Python 可执行文件。
- 四种 ABI 均采用 `GOOS=android`、NDK API 28 编译；ELF 链接按 16 KiB 页对齐。二进制生成在构建目录，以 `libfrpc.so` 随 APK 打包并提取至安装的原生库目录，通过独立进程执行，无需 Root。
- 不改写 frp 隧道协议或业务源码。未启用 frpc Web 管理端口；构建时提供占位嵌入页面，省去未使用的 Web 控制台。升级内核需要重新构建 APK。
- 构建思路参考 [AceDroidX/frp-Android](https://github.com/AceDroidX/frp-Android)，未复制其应用实现。

## 验证范围

单元测试覆盖端口占用回退、实际监听端口、凭据重置、多隧道独立停止、端口变更重启、配置校验和日志脱敏。真机与服务器的端到端测试结果应以具体 PR 的验证记录为准；打包支持不等同于所有架构和 OEM 均已实测。
