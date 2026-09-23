---
name: mbrain-style
description: 在 MBrain Android 项目新增、修改或审查 Compose 页面、操作行、图标、间距、主题和界面文案时使用，约束公共组件复用并防止样式漂移。不用于其他项目或纯后端修改。
---

# MBrain UI 样式约束

## 先确定依据

- 以本 skill 所在仓库为作用域；从 `.agents/skills/mbrain-style/` 向上三级定位仓库根目录，不使用个人电脑绝对路径。
- 修改前读取根目录 [DESIGN.md](../../../DESIGN.md)，以及 `app/src/main/kotlin/com/powercess/mbrain/ui/DesignSystem.kt` 和目标页面。
- DESIGN.md 是视觉参数的唯一规范来源；DesignSystem.kt 是公共实现。二者不一致时指出具体差异，结合用户最新要求修正本次涉及的部分，不把偶然出现的页面样式当成新规范。
- 用户明确提出的新设计优先于现有约定；无需为普通样式修复再次索要批准。将已采用的公共规范变化同步到 DESIGN.md。

## 同类操作行只用同一实现

设置项、能力与插件入口、服务列表入口、工具入口，以及带状态或开关的设置行，默认复用 `Group`、`ActionRow`、`GroupDivider`。

- 组内使用 GroupDivider；禁止通过 Spacer、Arrangement.spacedBy 或额外 padding 自行模拟组内间隙。
- 首尾外圆角由 Group 裁切，中间小圆角由 ActionRow 提供；只有一个条目时仍包在 Group 中。不要给每一行另加大圆角卡片。
- 页面只提供内容、行为和语义明确的 trailing（状态、开关、进度）；不为单页覆盖圆角、行高、图标尺寸、背景和文字层级。
- 导航行使用默认箭头，状态行使用 StatusPill，开关行复用现有 Switch 模式。不要把整行点击与子开关变成重复触发的操作。
- 行首图标使用现有单色线性图标风格，不使用彩色底框、emoji 或新绘制的装饰图标代替常规行图标。
- 公共组件不足时，先在 DesignSystem.kt 增加最小必要的语义能力，再让同类入口复用；不要复制出页面私有版本，不为假设中的用途增加任意样式参数。
- 静态分组显式放置 GroupDivider；动态列表使用 ScreenList(groupedRows = true)、ListSection 和 groupedItems，传入稳定 key，自动处理首尾圆角和行距。不要逐项再套 Group，也不要把长工具列表放入单个非懒加载 Column。

示例：

```kotlin
Group {
    ActionRow("应用管理", "已启用", Icons.Outlined.Widgets, { open("cap:apps") })
    GroupDivider()
    ActionRow("设备信息", "已启用", Icons.Outlined.PhoneAndroid, { open("cap:device") })
}
```

## 页面、主题与文案

- 列表页面复用 ScreenList，分组标题复用 SectionLabel；四个主页面首组使用 `firstSection = true`，不要用负偏移或逐页补偿顶部留白。
- 颜色读取 MaterialTheme.colorScheme，字体使用 MaterialTheme.typography；不要在业务页硬编码主题色。不要靠固定高度裁剪大字体内容。
- 编辑页复用 EditorSaveBar，标题栏与页面背景一致；全宽次级操作复用 SecondaryAction；完整详情说明使用 ContentCard，提示和错误使用 Note。完整调用名称使用可选择的等宽文本，不截断；复制操作不显示导航箭头。
- 空状态统一使用无图标的 EmptyState，只提供必要信息和有效操作：MCP 无配置时用“暂无服务”与“添加服务”；工具页网关未启动时只提示“启动网关后查看工具”，不显示无效搜索框或装饰图标。
- 汇总入口称“全部工具”，数量写“共 N 个工具”；分来源列表不称“全部”。避免重复标题、营销式欢迎语和不影响操作的技术说明；保留凭据重置、删除等必要后果提示。
- 保留原生水波纹、焦点、禁用状态和可访问性语义；可操作区域不小于规范要求。

## 合理保留不同控件

首页统计卡片、网关启停、保存/添加/删除按钮、输入框、对话框和底部导航各有用途，不强行改成分组操作行。沿用其现有公共组件或 Material 控件。单次任务只迁移授权范围内的同类入口；全项目统一需逐一审查，不能机械替换所有 Surface 或 Button。

## 完成前检查

- 检查 diff 是否新增了业务页私有操作行、重复尺寸常量、硬编码颜色或多余嵌套 padding；如有，优先收敛到现有公共实现。
- 每完成一轮应用功能或 UI 修改，按用户要求 rebuild；使用已连接的测试设备覆盖安装并验证，不卸载或清除数据。构建命令与环境见 `docs/development.md`；通过 adb devices 读取当前连接，不把设备地址写入源码或 skill。
- 普通文案修改做构建与相关页面检查。公共布局、主题或组件修改另做相关 lint，检查受影响页面的浅色/深色、大字体、单项/多项分组和点击行为；四页共享布局修改需逐页核对。只报告实际完成的验证，说明环境限制。
- 截图和构建日志放在忽略的 `.local-tools/`；截图前隐藏 Token 等凭据。覆盖安装可能停止网关，检查并说明实际状态。
- 仅修改 skill/设计文档时验证文档、引用及 skill 格式，不为文档重新构建 APK。
- 完成后汇报改动和验证结果；当前项目协作约定是等待用户明确要求再创建 PR。
