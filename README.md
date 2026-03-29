# X3player

`X3player` is a standalone local video player for the RayNeo X3 Pro AR glasses.

It is designed for the glasses form factor rather than a phone companion flow:
- local video library from device storage
- binocular playback layout for the X3 display
- temple-touch navigation
- playback resume history
- lightweight, text-first UI for 640x480 per-eye readability

## Status

This is an early public release intended to make local video playback easier for X3 users. The safest test format is:
- MP4 container
- H.264 or H.265 video
- AAC audio

Other Android-supported formats may work, but playback depends on the codecs available on the glasses.

## Build

The intended workflow is Android Studio:
1. Open this folder in Android Studio.
2. Let Gradle sync.
3. Connect the RayNeo X3 Pro with ADB enabled.
4. Press `Run`.

## Install

You can also install the debug APK with ADB:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Copy videos to the glasses storage, ideally into `Movies` or `Downloads`, then launch `X3player`.

## License

Released under the MIT License. See [LICENSE](LICENSE).
