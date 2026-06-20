param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "release-source")
)

$ErrorActionPreference = "Stop"
$mediaCommit = "75ccb55ec085d76cbbf12e2f1af8241d378a753a"
$ffmpegCommit = "ea3d24bbe3c58b171e55fe2151fc7ffaca3ab3d2"
$outputRoot = [System.IO.Path]::GetFullPath($OutputDirectory)
$staging = [System.IO.Path]::GetFullPath((Join-Path $outputRoot "x3player-ffmpeg-source"))
$archive = [System.IO.Path]::GetFullPath((Join-Path $outputRoot "x3player-ffmpeg-source.zip"))
$expectedPrefix = $outputRoot.TrimEnd(
    [System.IO.Path]::DirectorySeparatorChar,
    [System.IO.Path]::AltDirectorySeparatorChar
) + [System.IO.Path]::DirectorySeparatorChar

if (-not $staging.StartsWith($expectedPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to prepare source outside the requested output directory."
}

if (Test-Path $staging) {
    Remove-Item -LiteralPath $staging -Recurse -Force
}
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
New-Item -ItemType Directory -Path $staging | Out-Null

git clone -c core.longpaths=true --no-checkout https://github.com/androidx/media.git (Join-Path $staging "media3")
git -C (Join-Path $staging "media3") config core.longpaths true
git -C (Join-Path $staging "media3") checkout $mediaCommit
git clone -c core.longpaths=true --no-checkout https://github.com/FFmpeg/FFmpeg.git (Join-Path $staging "ffmpeg")
git -C (Join-Path $staging "ffmpeg") config core.longpaths true
git -C (Join-Path $staging "ffmpeg") checkout $ffmpegCommit

Copy-Item (Join-Path $PSScriptRoot "build-arm64.sh") $staging
Copy-Item (Join-Path $PSScriptRoot "README.md") $staging
Copy-Item (Join-Path $PSScriptRoot "..\..\app\src\main\assets\third_party_notices.txt") $staging

Get-ChildItem $staging -Directory -Filter .git -Recurse -Force |
    Remove-Item -Recurse -Force

if (Test-Path $archive) {
    Remove-Item -LiteralPath $archive -Force
}
Compress-Archive -Path (Join-Path $staging "*") -DestinationPath $archive
Write-Output $archive
