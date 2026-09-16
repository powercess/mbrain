# MBrain

连接 AI Agent 与手机能力的 Android 网关。

MBrain（Mobile Brain）计划基于 droid-mcp 二次开发，逐步聚合 Android 原生能力、root、Shizuku、MT MCP 及本地 MCP 服务，通过统一 MCP 入口供 Agent 调用。

## 开发原则

- 优先复用 droid-mcp 的现有模块、服务和示例应用，先跑通真实设备上的调用链路。
- 先实现基本可用功能，再根据实际使用逐步完善权限、稳定性和架构。
- 需求文档中的架构与阶段作为演进方向，不作为首次可用的前置条件，不预先锁定细碎版本计划。
- 保留必要的本地访问限制、认证、执行反馈与停止能力。

## 当前状态

仓库初始化完成，尚未导入基座代码。下一步评估并引入 droid-mcp，编译安装，连接实际 Agent，验证最需要的手机工具。

## 资料

- [原始需求参考](docs/requirements-reference.md)：保留原 McpHub 需求草案，技术假设与优先级待结合基座实现验证；开发方式以上述原则为准。
- [droid-mcp](https://github.com/stixez/droid-mcp)：计划采用的开发基座。
