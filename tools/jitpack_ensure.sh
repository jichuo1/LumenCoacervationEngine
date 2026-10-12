#!/usr/bin/env bash
# 确保某个 tag 的 JitPack 构建可用：触发构建 → 命中失败记录则删除并重试 → 核对全部模块产物可下载。
#
# 用法：bash tools/jitpack_ensure.sh [tag] [max_attempts]
#   tag 默认取 gradle.properties 的 lumen.version；max_attempts 默认 4（每轮等待上限约 10 分钟）。
# 认证：删除失败记录需要对该 GitHub 仓库有 push 权限的令牌，按顺序取 JITPACK_TOKEN 环境变量、
#       `gh auth token`；不需要删除时无令牌也能跑（例如只做核验）。
# 退出码：0 = 构建成功且各模块 pom 可下载；1 = 重试耗尽、验证失败或无法删除失败记录；2 = 用法错误。
#
# 背景：JitPack 以请求触发为主，失败记录会被缓存（重复请求只回旧的 "Build failed"），官方恢复流程
# 就是删除失败记录后重新请求。构建机解析插件的偶发失败与代码无关（AGP 等版本在 Google Maven
# 一直可用），删除后重试即可。
set -uo pipefail

GROUP="com.github.jichuo1"
ARTIFACT="LumenCoacervationEngine"
BASE="https://jitpack.io"
# JitPack 有两种路径格式，别混用：
#   状态 API：/api/builds/<点分 group>/<仓库名>/<ref>
#   产物与页面：/com/github/<user>/<仓库名>/<ref>/...
API_BASE="$BASE/api/builds/$GROUP/$ARTIFACT"
PROJECT="$BASE/com/github/jichuo1/$ARTIFACT"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

API_TIMEOUT=30    # 单次 API / 产物请求超时（秒）
POLL_SECONDS=20   # 轮询间隔（秒）
POLL_ROUNDS=30    # 每轮轮询次数上限（30 × 20s ≈ 10 分钟）
RETRIGGER_EVERY=6 # 非终态且未在构建时，每 6 次轮询补发一次触发请求（防首次请求丢失）
DEFAULT_ATTEMPTS=4

pin="$(sed -n 's/^lumen\.version=//p' "$ROOT/gradle.properties" | tr -d '\r')"
tag="${1:-$pin}"
max_attempts="${2:-$DEFAULT_ATTEMPTS}"
case "$max_attempts" in
  ''|*[!0-9]*) echo "usage: $0 <tag> [max_attempts]" >&2; exit 2;;
esac
if [ -z "$tag" ] || [ "$max_attempts" -lt 1 ]; then
  echo "usage: $0 <tag> [max_attempts]" >&2
  exit 2
fi
if [ -n "$pin" ] && [ "$pin" != "$tag" ] && [[ "$tag" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "warning: tag $tag 与 gradle.properties 的 lumen.version=$pin 不一致（维护旧版本时可忽略）"
fi

API="$API_BASE/$tag"
PAGE="$PROJECT/$tag/"
LOG="$PROJECT/$tag/build.log"

token="${JITPACK_TOKEN:-}"
if [ -z "$token" ]; then
  token="$(gh auth token 2>/dev/null || true)"
fi
auth_args=()
if [ -n "$token" ]; then
  auth_args=(-u "$token:")
fi

# 输出 ok / Error / none / Building / 空串（读不到时）。
status() {
  curl -sS -m "$API_TIMEOUT" "$API" 2>/dev/null \
    | grep -o '"status"[[:space:]]*:[[:space:]]*"[^"]*"' \
    | head -n 1 \
    | sed 's/.*"\([^"]*\)"$/\1/'
}

# 请求版本页即触发构建；请求超时也往往已入队，因此失败不作为错误。
trigger() {
  curl -sS -m 60 -o /dev/null "$PAGE" 2>/dev/null || true
}

delete_failed_record() {
  if [ "${#auth_args[@]}" -eq 0 ]; then
    echo "错误：需要删除失败记录，但没有可用令牌（设置 JITPACK_TOKEN 或登录 gh）。" >&2
    return 1
  fi
  local code
  code="$(curl -sS -m "$API_TIMEOUT" -o /dev/null -w '%{http_code}' -X DELETE "${auth_args[@]}" "$API")"
  case "$code" in
    200|202|204) echo "  已删除失败记录";;
    *) echo "错误：删除失败记录返回 HTTP $code" >&2; return 1;;
  esac
}

# 轮询到 ok/Error：成功返回 0，构建失败返回 1，等待超时返回 2。
wait_for_terminal() {
  local i st
  for i in $(seq 1 "$POLL_ROUNDS"); do
    sleep "$POLL_SECONDS"
    st="$(status)"
    case "$st" in
      ok) echo "  构建成功"; return 0;;
      Error) echo "  构建失败"; return 1;;
      *)
        # 长时间停留在非终态时补一次触发请求；已在排队/运行时不打扰。
        if [ "$st" != "Building" ] && [ "$i" -ge 3 ] && [ $((i % RETRIGGER_EVERY)) -eq 0 ]; then
          trigger
        fi
        printf '  等待构建（status=%s，已轮询 %ss）\n' "${st:-none}" "$((i * POLL_SECONDS))"
        ;;
    esac
  done
  return 2
}

# 逐个模块核对 pom 可下载。模块清单优先取状态记录的 modules；读不到时按该 ref 自己的
# settings.gradle.kts 兜底（tag 存在本地就取 tag 的版本，否则取工作区）；两者都拿不到才退化为
# 两个核心模块并显式告警，避免静默少验。
verify_artifacts() {
  local modules from_settings m code resolved record missing=0 total=0
  record="$(curl -sS -m "$API_TIMEOUT" "$API" 2>/dev/null)"
  # 分支快照与短 hash 会被 JitPack 解析成规范版本名（例：main-1.4.1-g4c0fd4d-1），产物目录用规范名。
  resolved="$(printf '%s' "$record" | grep -o '"version"[[:space:]]*:[[:space:]]*"[^"]*"' | head -n 1 | sed 's/.*"\([^"]*\)"$/\1/')"
  [ -n "$resolved" ] || resolved="$tag"
  modules="$(printf '%s' "$record" | grep -o '"lumen-[a-z0-9-]*"' | tr -d '"' | sort -u)"
  if [ -z "$modules" ]; then
    from_settings=""
    if git -C "$ROOT" rev-parse -q --verify "refs/tags/$tag" >/dev/null 2>&1; then
      from_settings="$(git -C "$ROOT" show "$tag:settings.gradle.kts" 2>/dev/null \
        | grep -o 'include(":lumen-[a-z0-9-]*")' | sed 's/include("://;s/")//' | sort -u)"
    fi
    if [ -z "$from_settings" ]; then
      from_settings="$(grep -o 'include(":lumen-[a-z0-9-]*")' "$ROOT/settings.gradle.kts" 2>/dev/null \
        | sed 's/include("://;s/")//' | sort -u)"
    fi
    if [ -n "$from_settings" ]; then
      echo "  状态记录里没有模块清单，按 settings.gradle.kts 核对"
      modules="$from_settings"
    else
      echo "  警告：无法读取模块清单，仅核对 lumen-engine / lumen-motion"
      modules="lumen-engine lumen-motion"
    fi
  fi
  for m in $modules; do
    total=$((total + 1))
    code="$(curl -sS -L -m "$API_TIMEOUT" -o /dev/null -w '%{http_code}' "$PROJECT/$m/$resolved/$m-$resolved.pom")"
    if [ "$code" = "200" ]; then
      echo "  ok  $m"
    else
      echo "  缺失 $m（HTTP $code）"
      missing=$((missing + 1))
    fi
  done
  [ "$missing" -eq 0 ] && [ "$total" -gt 0 ]
}

echo "== 确保 JitPack 构建：$tag =="
for attempt in $(seq 1 "$max_attempts"); do
  st="$(status)"
  case "$st" in
    ok)
      echo "现有构建状态 ok"
      break
      ;;
    Error)
      echo "[$attempt/$max_attempts] 命中失败记录，删除后重新触发"
      delete_failed_record || exit 1
      trigger
      ;;
    *)
      echo "[$attempt/$max_attempts] 触发构建（status=${st:-none}）"
      trigger
      ;;
  esac

  wait_for_terminal
  result=$?
  if [ "$result" -eq 0 ]; then
    break
  fi
  if [ "$result" -eq 1 ] && [ "$attempt" -lt "$max_attempts" ]; then
    echo "  将在下一轮删除失败记录并重新触发"
  fi
  if [ "$result" -eq 2 ]; then
    echo "  本轮等待超时，继续下一轮"
  fi
  if [ "$attempt" -eq "$max_attempts" ]; then
    echo "错误：$max_attempts 轮后仍未成功。构建日志：$LOG" >&2
    exit 1
  fi
done

echo "== 核对产物可下载 =="
if verify_artifacts; then
  echo "JitPack 构建验证通过：$tag"
  exit 0
fi
echo "错误：构建状态为 ok，但部分模块产物不可下载。构建日志：$LOG" >&2
exit 1
