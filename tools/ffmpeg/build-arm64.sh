#!/usr/bin/env bash
set -euo pipefail

MEDIA3_COMMIT="75ccb55ec085d76cbbf12e2f1af8241d378a753a"
FFMPEG_COMMIT="ea3d24bbe3c58b171e55fe2151fc7ffaca3ab3d2"
ANDROID_API="24"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
build_root="${FFMPEG_BUILD_DIR:-${repo_root}/tools/ffmpeg/.build}"
media_root="${build_root}/media3"
ffmpeg_root="${media_root}/libraries/decoder_ffmpeg/src/main/jni/ffmpeg"
output_aar="${repo_root}/app/libs/media3-decoder-ffmpeg-1.9.4.aar"

: "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK containing platform 35}"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r26b}"

case "$(uname -s)" in
  Linux*) host_tag="linux-x86_64"; exe_suffix="" ;;
  Darwin*) host_tag="darwin-x86_64"; exe_suffix="" ;;
  MINGW*|MSYS*|CYGWIN*) host_tag="windows-x86_64"; exe_suffix=".exe" ;;
  *)
    echo "Unsupported build host: $(uname -s)" >&2
    exit 1
    ;;
esac
toolchain="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${host_tag}/bin"
cc="${toolchain}/aarch64-linux-android${ANDROID_API}-clang"
cxx="${toolchain}/aarch64-linux-android${ANDROID_API}-clang++"
if [[ ! -x "${cc}" ]]; then
  echo "NDK r26b arm64 compiler was not found under ${toolchain}" >&2
  exit 1
fi
make_cmd="${MAKE:-make}"
if ! command -v "${make_cmd}" >/dev/null 2>&1; then
  ndk_make="${ANDROID_NDK_HOME}/prebuilt/${host_tag}/bin/make${exe_suffix}"
  if [[ -x "${ndk_make}" ]]; then
    make_cmd="${ndk_make}"
  else
    echo "GNU make was not found on PATH or under ${ANDROID_NDK_HOME}/prebuilt/${host_tag}/bin" >&2
    exit 1
  fi
fi

mkdir -p "${build_root}" "$(dirname "${output_aar}")"

if [[ ! -d "${media_root}/.git" ]]; then
  git clone -c core.longpaths=true https://github.com/androidx/media.git "${media_root}"
fi
git -C "${media_root}" config core.longpaths true
git -C "${media_root}" fetch --depth 1 origin "${MEDIA3_COMMIT}"
git -C "${media_root}" checkout --detach "${MEDIA3_COMMIT}"

if [[ ! -d "${ffmpeg_root}/.git" ]]; then
  rm -rf "${ffmpeg_root}"
  git clone https://github.com/FFmpeg/FFmpeg.git "${ffmpeg_root}"
fi
git -C "${ffmpeg_root}" fetch --depth 1 origin "${FFMPEG_COMMIT}"
git -C "${ffmpeg_root}" checkout --detach "${FFMPEG_COMMIT}"

pushd "${ffmpeg_root}" >/dev/null
"${make_cmd}" distclean >/dev/null 2>&1 || true
./configure \
  --target-os=android \
  --enable-cross-compile \
  --arch=aarch64 \
  --cpu=armv8-a \
  --libdir=android-libs/arm64-v8a \
  --enable-static \
  --disable-shared \
  --disable-doc \
  --disable-programs \
  --disable-everything \
  --disable-avdevice \
  --disable-avformat \
  --disable-swscale \
  --disable-postproc \
  --disable-avfilter \
  --disable-symver \
  --disable-v4l2-m2m \
  --disable-vulkan \
  --enable-swresample \
  --enable-decoder=ac3 \
  --enable-decoder=eac3 \
  --enable-decoder=dca \
  --enable-decoder=mlp \
  --enable-decoder=truehd \
  --cc="${cc}" \
  --cxx="${cxx}" \
  --nm="${toolchain}/llvm-nm${exe_suffix}" \
  --ar="${toolchain}/llvm-ar${exe_suffix}" \
  --ranlib="${toolchain}/llvm-ranlib${exe_suffix}" \
  --strip="${toolchain}/llvm-strip${exe_suffix}"
"${make_cmd}" -j"$(nproc)"
"${make_cmd}" install-libs
popd >/dev/null

sdk_dir="${ANDROID_HOME}"
if command -v cygpath >/dev/null 2>&1; then
  sdk_dir="$(cygpath -w "${ANDROID_HOME}")"
fi
printf 'sdk.dir=%s\n' "${sdk_dir}" > "${media_root}/local.properties"
(
  cd "${media_root}"
  ./gradlew :lib-decoder-ffmpeg:assembleRelease -Pandroid.injected.build.abi=arm64-v8a
)

built_aar="$(find "${media_root}/libraries/decoder_ffmpeg/build/outputs/aar" -name '*.aar' -print -quit)"
if [[ -z "${built_aar}" ]]; then
  echo "Media3 decoder AAR was not produced" >&2
  exit 1
fi
cp "${built_aar}" "${output_aar}"
echo "Built ${output_aar}"
