#!/usr/bin/env bash
# 下载偶发失败时只重试安装；不重试或跳过应用测试。
set -u

verify_emulator() {
  local emulator="${ANDROID_HOME:?ANDROID_HOME is required}/emulator/emulator"
  test -x "$emulator" && "$emulator" -version
}

prepare_emulator() {
  local attempt status=1
  for attempt in 1 2 3; do
    echo "Install Android Emulator: attempt $attempt/3"
    if sdkmanager --install emulator --channel=0 && verify_emulator; then
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
