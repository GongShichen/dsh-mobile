#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="$ROOT/third_party/iroh-ffi"
EXPECTED_REVISION="66a8d619a4d791d458d582f312df6e0ab42bc541"
case "$(uname -s)" in
  Darwin) DEFAULT_SDK="$HOME/Library/Android/sdk"; NDK_HOST=darwin-x86_64 ;;
  Linux) DEFAULT_SDK="$HOME/Android/Sdk"; NDK_HOST=linux-x86_64 ;;
  *) echo "Native Android builds require macOS or Linux" >&2; exit 1 ;;
esac
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$DEFAULT_SDK}}"
NDK_DIR="${ANDROID_NDK_HOME:-$SDK_DIR/ndk/27.1.12297006}"
if [[ -z "${ANDROID_NDK_HOME:-}" && ! -f "$NDK_DIR/toolchains/llvm/prebuilt/$NDK_HOST/sysroot/usr/include/assert.h" ]]; then
  # Use the other validated r27 installation when the default download is incomplete.
  fallback_ndk="$SDK_DIR/ndk/27.0.12077973"
  if [[ -f "$fallback_ndk/toolchains/llvm/prebuilt/$NDK_HOST/sysroot/usr/include/assert.h" ]]; then
    NDK_DIR="$fallback_ndk"
  fi
fi
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$NDK_HOST/bin"
OUT="$ROOT/core/transport/src/androidMain/jniLibs"

if [[ ! -d "$SOURCE/.git" && ! -f "$SOURCE/.git" ]]; then
  git -C "$ROOT" submodule update --init --depth 1 third_party/iroh-ffi
fi
[[ "$(git -C "$SOURCE" rev-parse HEAD)" == "$EXPECTED_REVISION" ]] || { echo "Unexpected iroh-ffi revision" >&2; exit 1; }
[[ -x "$TOOLCHAIN/llvm-ar" ]] || { echo "Android NDK not found: $NDK_DIR" >&2; exit 1; }
[[ -f "$NDK_DIR/toolchains/llvm/prebuilt/$NDK_HOST/sysroot/usr/include/assert.h" ]] || {
  echo "Android NDK has an incomplete sysroot: $NDK_DIR. Set ANDROID_NDK_HOME to a complete installation." >&2
  exit 1
}

export CARGO_TARGET_DIR="$ROOT/.local/iroh-target"
# Android 15+ devices can use 16 KiB pages. r27 requires this explicit link alignment.
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN/aarch64-linux-android26-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$TOOLCHAIN/x86_64-linux-android26-clang"
export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
export CC_x86_64_linux_android="$CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER"
export AR_aarch64_linux_android="$TOOLCHAIN/llvm-ar"
export AR_x86_64_linux_android="$TOOLCHAIN/llvm-ar"

for TARGET in ${IROH_ANDROID_TARGETS:-aarch64-linux-android x86_64-linux-android}; do
  rustup target add "$TARGET"
  cargo build --manifest-path "$SOURCE/Cargo.toml" --locked --release --lib -p iroh-ffi --target "$TARGET"
  case "$TARGET" in
    aarch64-linux-android) ABI=arm64-v8a ;;
    x86_64-linux-android) ABI=x86_64 ;;
    *) echo "Unsupported target: $TARGET" >&2; exit 1 ;;
  esac
  mkdir -p "$OUT/$ABI"
  cp "$CARGO_TARGET_DIR/$TARGET/release/libiroh_ffi.so" "$OUT/$ABI/libiroh_ffi.so"
  "$TOOLCHAIN/llvm-readelf" -l "$OUT/$ABI/libiroh_ffi.so" | awk '/LOAD/ { print }'
done

mkdir -p "$ROOT/core/transport/src/androidMain/kotlin/computer/iroh"
cp "$SOURCE/kotlin/lib/src/main/kotlin/computer/iroh/iroh_ffi.kt" "$ROOT/core/transport/src/androidMain/kotlin/computer/iroh/"
cp "$SOURCE/kotlin/android/src/main/kotlin/computer/iroh/IrohAndroid.kt" "$ROOT/core/transport/src/androidMain/kotlin/computer/iroh/"
echo "Built iroh-ffi 1.2.0 (iroh 1.4.1) Android native libraries"
