#!/usr/bin/env bash
# local-android-gate.sh —— Android 侧总闸门（vc63 还债批新增，2026-11-04）。
#
# 背景（与 iOS 第五/六路同根因）：androidApp 的 **androidTest 源集自 11-02 起整体编不过**，
# 没有任何闸门编它 ⇒ 烂了没人发现（AdaptiveLayout/AppearanceSettings/OfflineHome 三份
# 引旧版 SettingsScreen/WorkspaceScreen 签名，11-02 改签名后从未再编过测试源集）。
#
# 本闸门（全绿才 exit 0）：
#   ① :androidApp:assembleDebug（主源集能编、能出包）
#   ② :androidApp:compileDebugAndroidTestKotlin（**测试源集能编**——本次还债的核心）
#   ③ :androidApp:testDebugUnitTest + :shared:jvmTest（JVM 用例）
# 反向自证（--self-test）：临时往 androidTest 注入一个必错文件 → ② 必须红；跑完还原。
# 用法：scripts/local-android-gate.sh [--self-test]
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

SELF_TEST=0
[ "${1:-}" = "--self-test" ] && SELF_TEST=1

SENTINEL=""
cleanup() {
  if [ -n "$SENTINEL" ] && [ -f "$SENTINEL" ]; then
    rm -f "$SENTINEL"
    echo "（反向自证哨兵已清理）"
  fi
}
trap cleanup EXIT

if [ "$SELF_TEST" -eq 1 ]; then
  SENTINEL="androidApp/src/androidTest/kotlin/com/clarklevis/dsh/android/GateSelfTestSentinel.kt"
  cat > "$SENTINEL" <<'EOF'
package com.clarklevis.dsh.android
// 反向自证哨兵：这行引用一个不存在的符号，androidTest 编译必须失败。
private val gateSelfTestMustFail: Int = definitelyNotASymbol12345
EOF
  echo "【反向自证】注入哨兵 $SENTINEL"
fi

# vc63 修正（Chief 2026-11-04）：构建环境**不设默认值**——缺失就红（显式失败 > 静默降级）。
# 原先的 `${VAR:-/Volumes/应用/...}` 是另一套缓存（团队约定 = $HOME/.gradle-dsh / $HOME/.konan），
# 且外接盘未挂载时 gradle 会自建空缓存静默跑绿——"闸门绿"与"出包绿"就可能是两个环境的结果。
JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/$(ls "$HOME/.jdks" 2>/dev/null | head -1)}"
if [ -z "${ANDROID_HOME:-}" ] || [ -z "${GRADLE_USER_HOME:-}" ] || [ -z "${KONAN_DATA_DIR:-}" ]; then
  echo "❌ 闸门需要 ANDROID_HOME / GRADLE_USER_HOME / KONAN_DATA_DIR（团队约定见 instructions："
  echo "   ANDROID_HOME=/Volumes/应用/android-toolchain/sdk、GRADLE_USER_HOME=\$HOME/.gradle-dsh、"
  echo "   KONAN_DATA_DIR=\$HOME/.konan）。不设默认值是防『环境缺失被静默换成别的缓存跑绿』。"
  exit 1
fi
[ -d "$GRADLE_USER_HOME" ] || { echo "❌ GRADLE_USER_HOME 不存在：$GRADLE_USER_HOME（盘未挂载？）"; exit 1; }
[ -d "$KONAN_DATA_DIR" ] || { echo "❌ KONAN_DATA_DIR 不存在：$KONAN_DATA_DIR（盘未挂载？）"; exit 1; }
export JAVA_HOME ANDROID_HOME GRADLE_USER_HOME KONAN_DATA_DIR

FAILED=0
step() {
  local name="$1"; shift
  echo "→ $name ..."
  if "$@" > /tmp/android-gate-last.log 2>&1; then
    echo "   ✅ $name 通过（$(tail -1 /tmp/android-gate-last.log | head -c 80)）"
  else
    echo "   ❌ $name 失败："
    grep -E "^e: |FAILED|AssertionError" /tmp/android-gate-last.log | head -12 || tail -8 /tmp/android-gate-last.log
    FAILED=1
  fi
}

if [ "$SELF_TEST" -eq 1 ]; then
  # 自证 0：环境守卫——缺失任一构建环境变量必须红，不得静默换缓存跑绿（Chief 2026-11-04 补）。
  if [ -z "${GRADLE_USER_HOME:-}" ]; then
    echo "✅ 反向自证 0 成立：GRADLE_USER_HOME 缺失 ⇒ 闸门红（不静默降级）"
    exit 0
  fi
  # 只跑 ②：哨兵只影响 androidTest 编译这一路，其余两路在自证里没有信息量。
  step "androidTest 编译（应红）" ./gradlew :androidApp:compileDebugAndroidTestKotlin --console=plain
  if [ "$FAILED" -eq 1 ]; then
    echo "✅ 反向自证成立：androidTest 编译错误确实会让本闸门红（哨兵已清）"
    exit 0
  fi
  echo "❌ 反向自证失败：注入必错文件后 androidTest 编译竟然绿了——闸门没咬合，不许用"
  exit 1
fi

step "① androidApp:assembleDebug" ./gradlew :androidApp:assembleDebug --console=plain
step "② androidApp:compileDebugAndroidTestKotlin（测试源集硬闸门）" ./gradlew :androidApp:compileDebugAndroidTestKotlin --console=plain
step "③a androidApp:testDebugUnitTest" ./gradlew :androidApp:testDebugUnitTest --console=plain
step "③b shared:jvmTest" ./gradlew :shared:jvmTest --console=plain

if [ "$FAILED" -eq 0 ]; then
  echo "✅ Android 闸门通过：主源集 + androidTest 测试源集 + JVM 用例全绿"
  exit 0
fi
echo "❌ Android 闸门失败（见上）；完整日志 /tmp/android-gate-last.log"
exit 1
