#!/usr/bin/env bash
# 下载偶发失败时只重试安装；不重试或跳过应用测试。
set -u

verify_emulator() {
  local sdk_home="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  local emulator="$sdk_home/emulator/emulator"
  test -x "$emulator" && "$emulator" -version
}

prepare_emulator() {
  local attempt status=1 sdkmanager_bin sdk_home
  sdkmanager_bin="$(command -v sdkmanager || true)"
  if [ -z "$sdkmanager_bin" ]; then
    # 模拟器 Action 尚未运行，不能依赖它替我们把 SDK CLI 加入 PATH。
    sdk_home="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    sdkmanager_bin="$sdk_home/cmdline-tools/latest/bin/sdkmanager"
    if [ -z "$sdk_home" ] || [ ! -x "$sdkmanager_bin" ]; then
      echo "::error::Cannot locate sdkmanager in PATH or the Android SDK command-line tools."
      return 127
    fi
  fi
  for attempt in 1 2 3; do
    echo "Install Android Emulator: attempt $attempt/3"
    if "$sdkmanager_bin" --install emulator --channel=0 && verify_emulator; then
      return 0
    else
      status=$?
    fi
    if [ "$attempt" -lt 3 ]; then
      echo "::warning::Android Emulator installation failed (exit $status); retrying."
      sleep "$((attempt * 5))"
    fi
  done
  echo "::error::Android Emulator installation failed after 3 attempts (exit $status)."
  return "$status"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  prepare_emulator
fi
