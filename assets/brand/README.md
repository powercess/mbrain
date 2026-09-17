# MBrain Logo

`mbrain-logo.svg` 为正式矢量源文件，128 × 128 viewBox，背景透明。实心闪电保留简洁外形，端点和转折使用短曲线柔化，整体顺时针倾斜 9°。配色仅使用石墨灰，渐变正面、深色薄侧面和低透明度投影提供轻微立体感。

`mbrain-logo-monochrome.svg` 为相同轮廓的纯色版，用于小尺寸、通知与 Android 13+ 主题图标；不包含阴影，避免系统把阴影一起转为实心遮罩。桌面和关于页使用立体版，白色底面保证深色主题中的识别度。

统一修改 `scripts/generate-brand.py`，执行 `py scripts/generate-brand.py` 同步 SVG 和 Android VectorDrawable。所有素材均为原生矢量，无嵌入位图、字体或 SVG 滤镜。桌面前景缩放 0.68、平移 20.48，保留安全边距。

`preview.html` 展示主标志、桌面蒙版及小尺寸效果。Windows 可运行 `powershell -ExecutionPolicy Bypass -File scripts/render-brand.ps1` 从 SVG 路径渲染 `mbrain-logo-preview.png`。
