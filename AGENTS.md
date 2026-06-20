# Project Notes For Agents

## Scope
- This repo contains one Android app: `X3player`.
- `X3player` is a standalone local media player for the RayNeo X3 Pro AR glasses.
- It is not a modification of `Everyday_glasses` and does not depend on it at runtime.
- The public GitHub repo is already published under MIT, and `v1.0.0` exists as the first public release with a signed APK asset.

## Product Summary
- Purpose: browse local videos already copied onto the glasses and play them directly on-device.
- Tech stack: single-module Kotlin Android app, Views/XML, Media3, Room, DataStore, coroutines.
- UI target: 640x480 per eye / 1280x480 logical binocular layout.
- Interaction target: temple touch only, with explicit swipe/tap navigation and no cursor.
- Current UX assumptions:
  - the library is local-only
  - the default selection is the first visible newest video when videos exist
  - vertical swipes move through videos or up into the top control rows
  - horizontal swipes move within the selected control row
  - a tap activates the currently highlighted video or control
  - player controls use explicit highlight state rather than generic Android focus
  - the player includes a `CC` control that opens a subtitle menu with `Upload`, `None`, embedded tracks, and uploaded subtitle rows
  - subtitle menu navigation is vertical, `None` is the default highlighted row on open, and double tap exits the menu
  - main controls hide three seconds after the latest interaction ends (including finger release after a swipe) while playing; paused/error/menu states remain visible
  - the advanced menu includes repeated `Rewind 10s` and `Forward 10s` actions

## Codebase Map
- App entry and wiring:
  - `app/src/main/java/com/x3player/glasses/X3PlayerApplication.kt`
  - `app/src/main/java/com/x3player/glasses/AppContainer.kt`
  - `app/src/main/java/com/x3player/glasses/MainActivity.kt`
  - `app/src/main/java/com/x3player/glasses/PlaybackQueueViewModel.kt`
- Library screen:
  - `app/src/main/java/com/x3player/glasses/ui/library/LibraryFragment.kt`
  - `app/src/main/java/com/x3player/glasses/ui/library/LibraryViewModel.kt`
  - `app/src/main/java/com/x3player/glasses/ui/library/VideoListAdapter.kt`
  - `app/src/main/res/layout/fragment_library.xml`
- Player screen:
  - `app/src/main/java/com/x3player/glasses/ui/player/PlayerFragment.kt`
  - `app/src/main/res/layout/fragment_player.xml`
- Subtitle upload and persistence:
  - `app/src/main/java/com/x3player/glasses/data/LocalSubtitleImportScanner.kt`
  - `app/src/main/java/com/x3player/glasses/data/SubtitleImportPreparer.kt`
  - `app/src/main/java/com/x3player/glasses/data/SubtitleRepository.kt`
  - `app/src/main/java/com/x3player/glasses/data/VideoSubtitleDao.kt`
  - `app/src/main/java/com/x3player/glasses/data/UploadedSubtitle*`
  - `app/src/main/java/com/x3player/glasses/util/SubtitleFiles.kt`
- Temple input and explicit menu navigation:
  - `app/src/main/java/com/x3player/glasses/MainActivity.kt`
  - `app/src/main/java/com/x3player/glasses/TempleNavigationHandler.kt`
- Binocular rendering and mirrored output:
  - `app/src/main/java/com/x3player/glasses/binocular/BinocularPlayerLayout.kt`
  - `app/src/main/java/com/x3player/glasses/binocular/BinocularRenderer.kt`
  - other files under `app/src/main/java/com/x3player/glasses/binocular/`
- Media library, settings, and playback persistence:
  - `app/src/main/java/com/x3player/glasses/data/MediaStoreVideoRepository.kt`
  - `app/src/main/java/com/x3player/glasses/data/SettingsRepository.kt`
  - `app/src/main/java/com/x3player/glasses/data/Playback*`
  - `app/src/main/java/com/x3player/glasses/util/ResumePolicy.kt`
  - `app/src/main/java/com/x3player/glasses/util/StoragePermissionHelper.kt`
- UI styling/resources:
  - `app/src/main/res/layout/`
  - `app/src/main/res/drawable/video_row_background.xml`
  - `app/src/main/res/drawable/player_overlay_background.xml`
  - `app/src/main/res/values/`
- Tests:
  - `app/src/test/`
  - `app/src/androidTest/`

## Where To Start By Task
- Temple gesture or menu-navigation bug: start in `MainActivity.kt`, `TempleNavigationHandler.kt`, then the relevant fragment.
- Library filter, sort, or default-selection bug: start in `LibraryFragment.kt`, `LibraryViewModel.kt`, then `MediaStoreVideoRepository.kt`.
- Playback-control, queue, or resume bug: start in `PlayerFragment.kt`, `PlaybackQueueViewModel.kt`, `ResumePolicy.kt`, and the `PlaybackProgress*` classes.
- Subtitle upload, import, selection, or persistence bug: start in `PlayerFragment.kt`, then `LocalSubtitleImportScanner.kt`, `SubtitleImportPreparer.kt`, `SubtitleRepository.kt`, `VideoSubtitleDao.kt`, and `PlaybackDatabase.kt`.
- One-eye playback or binocular regression: start in `BinocularPlayerLayout.kt` and `fragment_player.xml`.
- Storage permission or missing-video scan issue: start in `StoragePermissionHelper.kt` and `MediaStoreVideoRepository.kt`.
- Focus highlight or selected-state visuals: start in the fragment layout plus `VideoListAdapter.kt` or `PlayerFragment.kt`.

## Important Implementation Notes
- The player layout currently relies on mirrored whole-view duplication for binocular playback.
- `fragment_player.xml` uses `TextureView`-based playback rather than `SurfaceView`; if one-eye playback regresses, check this before larger rewrites.
- The library and player both use explicit internal selection/highlight state; do not assume standard Android focus navigation is the source of truth.
- Subtitle upload now scans `MediaStore` entries and directly enumerates `Documents`, `Download`, and `Movies`, copies imports into app-managed storage, then persists the chosen subtitle per video via Room.
- Subtitle selection is Room database version 3 and persists `NONE`, `EMBEDDED`, or `EXTERNAL` per video. All external tracks remain attached to the current media item.
- `.sub` imports are normalized into a Media3-supported playback format at import time, `.vvt` is accepted as a WebVTT alias, and `.txt` is accepted only when timed subtitle content is detected.
- Embedded forced/default tracks are selected only when no saved preference exists; otherwise subtitles start off.
- Media3 is pinned to `1.9.4` with compile SDK 35.
- The video repository verifies each MediaStore content URI can be opened before exposing it, so stale rows for moved or deleted files do not appear in the library.
- Optional arm64 FFmpeg audio support is enabled only when `app/libs/media3-decoder-ffmpeg-1.9.4.aar` exists. Build and LGPL source-package tooling lives under `tools/ffmpeg/`.
- The app is intentionally minimal for v1: local playback only, no phone companion flow, and no playlists.

## Build And Release
- Primary workflow is Android Studio.
- CLI has already been validated for `testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest`, `connectedDebugAndroidTest`, and signed `assembleRelease`.
- On this machine, the working CLI environment is:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
```

- Signed release builds use a gitignored `keystore.properties` in the repo root; `app/build.gradle` reads it automatically if present.
- The actual release keystore is stored outside the repo under the user's `.android\keystores` directory.
- Never commit the keystore or `keystore.properties`.
- Never publish an APK containing the FFmpeg decoder AAR until the matching source bundle and LGPL review described in `tools/ffmpeg/README.md` are complete.
- Signed release build command:

```powershell
.\gradlew.bat assembleRelease
```

## Device Constraints
- Target device: RayNeo X3 Pro running RayNeo AI OS 2.0.
- Relevant hardware constraints: 640x480 per eye, up to 60 Hz, temple touch input, limited battery/thermal headroom.
- Prefer readable text, obvious focus states, and simple gestures over dense or cursor-heavy UI.

## Context Hygiene
- Do not start with a full repo reread unless the task is broad or architectural.
- Most changes should stay within one slice: library, player, binocular, or data/persistence.
- Reuse the task-to-file map above before doing comprehensive exploration.
- Update this AGENTS.md file before preparing your response to the user if codebase changes make its content obsolete.
