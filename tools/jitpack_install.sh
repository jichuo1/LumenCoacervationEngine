#!/usr/bin/env bash
# JitPack 容器内的安装命令（由 jitpack.yml 调用）：把全部模块发布到 JitPack 的本地 Maven 仓。
# 构建机偶发连不上 Google Maven、解析不到 AGP 插件而整轮失败；这里在同一轮内重试三次，
# 覆盖短抖动（实测该失败模式为 10～25 秒内快速失败，不会逼近 JitPack 的构建时限）。
# 三次都失败则如实退出，由发布侧 tools/jitpack_ensure.sh 删除失败记录并重新触发。
#
# 容器约定：JitPack 在仓库根执行 install（本脚本再 cd 一次双保险）；gradlew 在 git 中带可执行位；
# bash 由 JitPack 镜像（Ubuntu）提供。JitPack 读取的是 tag 自身提交里的 jitpack.yml 与本脚本。
set -u
cd "$(dirname "${BASH_SOURCE[0]}")/.." || { echo "cannot enter repository root" >&2; exit 1; }

MAX_ATTEMPTS=3
RETRY_DELAY=30

for attempt in $(seq 1 "$MAX_ATTEMPTS"); do
  echo "JitPack install: attempt $attempt/$MAX_ATTEMPTS"
  if ./gradlew --no-daemon -Plumen.group=com.github.jichuo1.LumenCoacervationEngine publishToMavenLocal; then
    exit 0
  fi
  if [ "$attempt" -lt "$MAX_ATTEMPTS" ]; then
    echo "install attempt $attempt failed; retrying in ${RETRY_DELAY}s"
    sleep "$RETRY_DELAY"
  fi
done
exit 1
