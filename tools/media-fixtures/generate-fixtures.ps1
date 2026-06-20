param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "output"),
    [int]$DurationSeconds = 300
)

$ErrorActionPreference = "Stop"
if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) {
    throw "ffmpeg must be installed and available on PATH."
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$video = Join-Path $OutputDirectory "video.mp4"
$srt = Join-Path $OutputDirectory "external-five-minute.srt"
$vtt = Join-Path $OutputDirectory "external-five-minute.vtt"
$ass = Join-Path $OutputDirectory "external-five-minute.ass"
$ttml = Join-Path $OutputDirectory "external-five-minute.ttml"

function Format-SrtTime([int]$seconds, [string]$separator = ",") {
    $time = [TimeSpan]::FromSeconds($seconds)
    return "{0:00}:{1:00}:{2:00}${separator}000" -f [math]::Floor($time.TotalHours), $time.Minutes, $time.Seconds
}

$srtCues = New-Object System.Collections.Generic.List[string]
$vttCues = New-Object System.Collections.Generic.List[string]
$cueNumber = 1
for ($start = 0; $start -lt $DurationSeconds; $start += 30) {
    $end = [math]::Min($start + 5, $DurationSeconds - 1)
    $label = "Cue at ${start} seconds"
    $srtCues.Add("$cueNumber`n$(Format-SrtTime $start) --> $(Format-SrtTime $end)`n$label`n")
    $vttCues.Add("$(Format-SrtTime $start '.') --> $(Format-SrtTime $end '.')`n$label`n")
    $cueNumber += 1
}
$finalStart = [math]::Max(0, $DurationSeconds - 5)
$finalEnd = [math]::Max($finalStart, $DurationSeconds - 1)
$srtCues.Add(
    "$cueNumber`n$(Format-SrtTime $finalStart) --> $(Format-SrtTime $finalEnd)`nFinal five-minute cue`n"
)
$vttCues.Add(
    "$(Format-SrtTime $finalStart '.') --> $(Format-SrtTime $finalEnd '.')`nFinal five-minute cue`n"
)
Set-Content -Path $srt -Value ($srtCues -join "`n") -Encoding utf8
Set-Content -Path $vtt -Value ("WEBVTT`n`n" + ($vttCues -join "`n")) -Encoding utf8
Set-Content -Path $ass -Encoding utf8 -Value @"
[Script Info]
ScriptType: v4.00+
[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, Bold, Italic, Alignment
Style: Default,Arial,28,&H00FFFFFF,0,0,2
[Events]
Format: Layer, Start, End, Style, Text
Dialogue: 0,0:00:00.00,0:00:05.00,Default,ASS opening cue
Dialogue: 0,0:04:55.00,0:04:59.00,Default,ASS final five-minute cue
"@
Set-Content -Path $ttml -Encoding utf8 -Value @"
<?xml version="1.0" encoding="UTF-8"?>
<tt xmlns="http://www.w3.org/ns/ttml"><body><div>
<p begin="00:00:00.000" end="00:00:05.000">TTML opening cue</p>
<p begin="00:04:55.000" end="00:04:59.000">TTML final five-minute cue</p>
</div></body></tt>
"@

& ffmpeg -hide_banner -loglevel error -y -f lavfi -i "color=c=black:s=640x480:r=24:d=$DurationSeconds" `
    -an -c:v libx264 -preset veryfast -t $DurationSeconds -pix_fmt yuv420p $video

$codecs = [ordered]@{
    "aac" = @("aac")
    "ac3" = @("ac3")
    "eac3" = @("eac3")
    "dts" = @("dca", "-strict", "-2")
    "truehd" = @("truehd", "-strict", "-2")
    "opus" = @("libopus")
    "flac" = @("flac")
}
foreach ($name in $codecs.Keys) {
    $output = Join-Path $OutputDirectory "audio-$name.mkv"
    $arguments = @("-hide_banner", "-loglevel", "error", "-y", "-i", $video,
        "-f", "lavfi", "-i", "sine=frequency=440:duration=$DurationSeconds",
        "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a") + $codecs[$name] +
        @("-t", "$DurationSeconds", $output)
    & ffmpeg @arguments
}

& ffmpeg -hide_banner -loglevel error -y -i $video `
    -f lavfi -i "sine=frequency=440:duration=$DurationSeconds" `
    -f lavfi -i "sine=frequency=880:duration=$DurationSeconds" `
    -map 0:v:0 -map 1:a:0 -map 2:a:0 -c:v copy -c:a:0 aac -c:a:1 ac3 `
    -metadata:s:a:0 title="AAC platform fallback" -metadata:s:a:1 title="AC-3 fallback" `
    -t $DurationSeconds (Join-Path $OutputDirectory "audio-mixed-aac-ac3.mkv")

& ffmpeg -hide_banner -loglevel error -y -i (Join-Path $OutputDirectory "audio-aac.mkv") -i $srt `
    -map 0 -map 1 -c copy -c:s srt (Join-Path $OutputDirectory "embedded-srt.mkv")
& ffmpeg -hide_banner -loglevel error -y -i (Join-Path $OutputDirectory "audio-aac.mkv") -i $ass `
    -map 0 -map 1 -c copy -c:s ass (Join-Path $OutputDirectory "embedded-ass.mkv")
& ffmpeg -hide_banner -loglevel error -y -i (Join-Path $OutputDirectory "audio-aac.mkv") -i $srt `
    -map 0 -map 1 -c copy -c:s mov_text (Join-Path $OutputDirectory "embedded-mov-text.mov")

Write-Output "Generated five-minute fixtures in $OutputDirectory"
