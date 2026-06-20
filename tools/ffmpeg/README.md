# FFmpeg arm64 decoder

This directory builds the optional Media3 1.9.4 FFmpeg audio extension for the
RayNeo X3 Pro. Platform decoders remain first choice; FFmpeg is used only when
the platform cannot decode the selected track.

## Pinned inputs

- AndroidX Media3 commit `75ccb55ec085d76cbbf12e2f1af8241d378a753a`
- FFmpeg 6.0 commit `ea3d24bbe3c58b171e55fe2151fc7ffaca3ab3d2`
- Android NDK r26b
- Minimum native API 24
- ABI `arm64-v8a`
- Decoders `ac3`, `eac3`, `dca`, `mlp`, and `truehd`

## Build

Run from Linux, macOS, WSL, or Git Bash on Windows with JDK 17, Android SDK 35,
CMake 3.22+, Ninja, Git, GNU Make, and NDK r26b installed. On Windows, the
script can use the GNU Make binary bundled with the NDK:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/26.1.10909125"
./tools/ffmpeg/build-arm64.sh
```

The script writes `app/libs/media3-decoder-ffmpeg-1.9.4.aar`. Gradle detects
that file automatically and sets `BuildConfig.FFMPEG_AUDIO_ENABLED` to true.
Without it, normal builds remain valid and the player reports unsupported codec
names instead of silently playing video.

## LGPL release gate

Do not publish an APK containing the AAR until legal/compliance review is
complete. Run:

```powershell
tools\ffmpeg\package-source.ps1
```

Publish the resulting source ZIP beside the APK. It contains the exact FFmpeg
and Media3 source revisions, their license files, and these build scripts.
Retain build logs and verify the source ZIP can reproduce the shipped AAR.
