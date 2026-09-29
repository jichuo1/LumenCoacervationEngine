#!/usr/bin/env bash
# 在已启动的模拟器上跑演示包冒烟测试；无论成败都把逐条测试结果打进日志，失败时附上崩溃相关的 logcat。
set -u

# 可选：指定 HWUI 渲染后端（skiagl / skiavk），用于区分模拟器图形栈问题与引擎问题。
if [ -n "${HWUI_RENDERER:-}" ]; then
  adb shell setprop debug.hwui.renderer "$HWUI_RENDERER" || true
  echo "debug.hwui.renderer=$(adb shell getprop debug.hwui.renderer)"
fi

# 构建机资源采样：每 15 秒记录主机空闲内存与模拟器进程的常驻内存，定位模拟器被杀的原因。
( while true; do
    echo "$(date +%T) free=$(free -m | awk '/Mem:/ {print $7}')MB emulator_rss=$(ps -C qemu-system-x86_64 -o rss= 2>/dev/null | awk '{s+=$1} END {print int(s/1024)}')MB java_rss=$(ps -C java -o rss= 2>/dev/null | awk '{s+=$1} END {print int(s/1024)}')MB"
    sleep 15
  done ) > host-usage.txt 2>&1 &
sampler_pid=$!

adb logcat -c || true
adb logcat -v threadtime > logcat.txt 2>&1 &
logcat_pid=$!

./gradlew :sample:connectedReleaseAndroidTest --console=plain --no-daemon
status=$?

kill "$logcat_pid" "$sampler_pid" 2>/dev/null || true

echo "::group::主机资源采样"
cat host-usage.txt || true
echo "::endgroup::"

echo "::group::测试结果"
python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
files = glob.glob("sample/build/outputs/androidTest-results/connected/**/*.xml", recursive=True)
if not files:
    print("没有找到测试结果 XML（测试未能开始，或设备在运行中断开）")
total = failed = 0
for path in files:
    for case in ET.parse(path).getroot().iter("testcase"):
        total += 1
        problem = case.find("failure")
        if problem is None:
            problem = case.find("error")
        seconds = case.get("time", "?")
        if problem is None:
            print(f"PASS  {case.get('name')}  ({seconds}s)")
        else:
            failed += 1
            print(f"FAIL  {case.get('name')}  ({seconds}s)")
            print("      " + (problem.text or problem.get("message") or "").strip().replace("\n", "\n      ")[:4000])
print(f"合计 {total} 条，失败 {failed} 条")
PY
echo "::endgroup::"

if [ "$status" -ne 0 ]; then
  echo "::group::logcat：崩溃、ANR 与测试进度"
  grep -E "FATAL|AndroidRuntime|Fatal signal|am_crash|am_anr|ANR in|TestRunner|DemoSmokeTest|Lumen|lowmemorykiller|Out of memory" logcat.txt | tail -n 500 || true
  echo "::endgroup::"
  echo "::group::主机内核日志（OOM killer 等）"
  sudo dmesg -T 2>/dev/null | grep -iE "oom|killed process|out of memory|qemu|emulator|segfault" | tail -n 60 || true
  echo "::endgroup::"
  echo "::group::logcat 最后 200 行"
  tail -n 200 logcat.txt || true
  echo "::endgroup::"
fi
exit "$status"
