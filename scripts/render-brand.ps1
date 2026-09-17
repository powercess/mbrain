$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$brandRoot = Join-Path $PSScriptRoot '../assets/brand'
[xml]$svg = Get-Content -LiteralPath (Join-Path $brandRoot 'mbrain-logo.svg') -Encoding UTF8
$culture = [cultureinfo]::InvariantCulture
function Point($x, $y) { [System.Drawing.PointF]::new([float]$x, [float]$y) }
function Read-Path([string]$data) {
    $path = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $tokens = [regex]::Matches($data, '[MLQZ]|-?\d+(?:\.\d+)?')
    $current = Point 0 0
    $i = 0
    while ($i -lt $tokens.Count) {
        $op = $tokens[$i++].Value
        if ($op -eq 'Z') { $path.CloseFigure(); continue }
        $x = [float]::Parse($tokens[$i++].Value, $culture)
        $y = [float]::Parse($tokens[$i++].Value, $culture)
        $next = Point $x $y
        if ($op -eq 'M') { $current = $next; $path.StartFigure() }
        elseif ($op -eq 'L') { $path.AddLine($current, $next); $current = $next }
        elseif ($op -eq 'Q') {
            $end = Point ([float]::Parse($tokens[$i++].Value,$culture)) ([float]::Parse($tokens[$i++].Value,$culture))
            $c1 = Point ($current.X + ($next.X-$current.X)*2/3) ($current.Y + ($next.Y-$current.Y)*2/3)
            $c2 = Point ($end.X + ($next.X-$end.X)*2/3) ($end.Y + ($next.Y-$end.Y)*2/3)
            $path.AddBezier($current,$c1,$c2,$end); $current = $end
        }
    }
    return ,$path
}
$bitmap = [System.Drawing.Bitmap]::new(768,768)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.Clear([System.Drawing.Color]::White)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.ScaleTransform(6,6)
foreach ($node in $svg.svg.path) {
    $path = Read-Path $node.d
    if ($node.fill -eq 'url(#graphite)') {
        $brush = [System.Drawing.Drawing2D.LinearGradientBrush]::new((Point 32 22),(Point 100 108),[System.Drawing.ColorTranslator]::FromHtml('#50555D'),[System.Drawing.ColorTranslator]::FromHtml('#202329'))
        $blend = [System.Drawing.Drawing2D.ColorBlend]::new(3)
        $blend.Positions = [single[]]@(0,0.5,1)
        $blend.Colors = [System.Drawing.Color[]]@('#50555D','#30343B','#202329' | ForEach-Object { [System.Drawing.ColorTranslator]::FromHtml($_) })
        $brush.InterpolationColors = $blend
    } else {
        $color = [System.Drawing.ColorTranslator]::FromHtml($node.fill)
        if ($node.HasAttribute('opacity')) { $color = [System.Drawing.Color]::FromArgb([int](255*[double]::Parse($node.opacity,$culture)),$color) }
        $brush = [System.Drawing.SolidBrush]::new($color)
    }
    $graphics.FillPath($brush,$path)
    if ($node.HasAttribute('stroke-width') -and [double]::Parse($node.'stroke-width',$culture) -gt 0) {
        $pen = [System.Drawing.Pen]::new($brush,[single]::Parse($node.'stroke-width',$culture))
        $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
        $graphics.DrawPath($pen,$path); $pen.Dispose()
    }
    $brush.Dispose(); $path.Dispose()
}
$bitmap.Save((Join-Path $brandRoot 'mbrain-logo-preview.png'),[System.Drawing.Imaging.ImageFormat]::Png)
$graphics.Dispose(); $bitmap.Dispose()
