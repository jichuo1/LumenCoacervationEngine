#!/usr/bin/env bash
# 在已启动的模拟器上跑演示包冒烟测试；无论成败都把逐条测试结果打进日志，失败时附上崩溃相关的 logcat。
set -u

adb logcat -c || true
adb logcat -v threadtime > logcat.txt 2>&1 &
logcat_pid=$!

./gradlew :sample:connectedReleaseAndroidTest --console=plain --no-daemon
status=$?

kill "$logcat_pid" 2>/dev/null || true

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
  echo "::group::logcat 最后 200 行"
  tail -n 200 logcat.txt || true
  echo "::endgroup::"
fi
exit "$status"
