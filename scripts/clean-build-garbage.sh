#!/bin/bash
# 出包后清理构建垃圾
#
# 用户要求（2026-11-02）：
#   ① 「以后出了包就清理掉因出包产生的垃圾」
#   ② 「那些下次出包还会重建的就不要删了，不然出包时间会太长的」
#
# ⇒ **核心原则：只删「一次性」的，保留「下次能复用」的。**
#   删缓存能省几十 G，但下次出包要多花几十分钟重编 —— 用户明确不接受这个代价。
#
# ┌─────────────────────────┬──────────┬────────────────────────────────────┐
# │ 项目                     │ 处理     │ 理由                                │
# ├─────────────────────────┼──────────┼────────────────────────────────────┤
# │ /tmp/*.xcarchive         │ **删**   │ 归档中间物；成品已进 releases/      │
# │ /tmp/bNNwt、iosNN（wt）   │ **删**   │ 出包用的 worktree，包出完即无用      │
# │ /tmp/*.log（bNN-*）       │ **删**   │ 构建日志，留档价值在 runbook 里      │
# │ /tmp/dsh-typecheck-deps* │ **删**   │ 闸门依赖拷贝，下次重新拷（不影响速度）│
# │ /tmp/pub-check-*         │ **删**   │ 发布校验临时物                      │
# │ /tmp/ipa*、apk*（解包件） │ **删**   │ 判包时解开的副本                    │
# │ build-output/            │ **删**   │ 历史 IPA 中间副本（成品在 releases/）│
# │ androidApp debug APK     │ **删**   │ 中间产物                            │
# │ 已删目录的 worktree 登记  │ **prune**│ git 元数据清理，零成本              │
# ├─────────────────────────┼──────────┼────────────────────────────────────┤
# │ DerivedData              │ **保留** │ 删了下次全量重编（几十分钟）         │
# │ shared/build（KMP）      │ **保留** │ 删了下次要重链 KMP（约 6 分钟）      │
# │ ModuleCache.noindex      │ **保留** │ 删了所有 Swift 文件重编              │
# │ releases/、IPA/APK 成品   │ **保留** │ 交付件                              │
# │ 模拟器运行时 / 设备       │ **保留** │ iOS 17+ 验证依赖                    │
# └─────────────────────────┴──────────┴────────────────────────────────────┘
#
# 用法：
#   bash scripts/clean-build-garbage.sh            # 预览（默认，不删）
#   bash scripts/clean-build-garbage.sh --yes      # 真删
#
# 退出码：0 = 成功；1 = 有删除失败。

set -uo pipefail

YES=0
for arg in "$@"; do
  case "$arg" in
    --yes) YES=1 ;;
    -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
    *) echo "未知参数: $arg（可用 --yes / --help）" >&2; exit 1 ;;
  esac
done

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FAILED=0
FREED_KB=0

say() { printf '%s\n' "$*"; }
avail() { df -k /System/Volumes/Data 2>/dev/null | awk 'NR==2{print $4}'; }
size_of() { du -sk "$1" 2>/dev/null | awk '{print $1}'; }
human() { awk -v k="$1" 'BEGIN{ if(k>=1048576) printf "%.1fG", k/1048576; else if(k>=1024) printf "%.0fM", k/1024; else printf "%dK", k }'; }

# 正在跑的构建：它用的 /tmp worktree 不能删（删了等于掐断构建）
build_running() { pgrep -f 'xcodebuild|GradleWrapperMain|gradlew' >/dev/null 2>&1; }

purge() {
  local path="$1" label="$2"
  [ -e "$path" ] || return 0
  local kb before after
  kb=$(size_of "$path"); [ -z "$kb" ] && kb=0
  [ "$kb" -eq 0 ] && return 0
  if [ "$YES" -eq 0 ]; then
    say "  [预览] $label  $(human "$kb")"
    FREED_KB=$((FREED_KB + kb))
    return 0
  fi
  before=$(avail)
  rm -rf "$path" 2>/dev/null
  if [ -e "$path" ]; then
    say "  ✗ 删除失败：$label（权限或占用中）"; FAILED=1; return 1
  fi
  after=$(avail)
  local gained=$(( ${after:-0} - ${before:-0} ))
  [ "$gained" -lt 0 ] && gained=0
  FREED_KB=$((FREED_KB + gained))
  say "  ✓ $label  释放 $(human "$gained")"
}

say "=== 出包垃圾清理（$( [ "$YES" -eq 1 ] && echo 执行 || echo 预览 )）==="
say "原则：只删一次性的；缓存一律保留（删了下次出包会变慢）"
say "系统盘可用（前）：$(human "$(avail)")"
say ""

# ── 1. /tmp 一次性构建物 ────────────────────────────────────────────────
say "① /tmp 一次性构建物"
if build_running; then
  say "  （检测到构建正在运行，本项跳过 —— worktree 可能正被使用）"
else
  while IFS= read -r d; do
    [ -n "$d" ] || continue
    purge "$d" "/tmp/$(basename "$d")"
  done < <(find /private/tmp -maxdepth 1 \( \
        -name 'b[0-9]*wt' -o -name 'ios[0-9]*' -o -name 'vc[0-9]*wt' -o \
        -name 'b[0-9]*' -o -name '*.xcarchive' -o -name 'dsh-typecheck-deps*' -o \
        -name 'dsh-tc-*' -o -name 'pub-check-*' -o -name 'suite-pre*' -o \
        -name 'suite-reh-*' -o -name 'ipa[0-9]*' -o -name 'apk[0-9]*' -o \
        -name 'b[0-9]*-*.log' -o -name 'ios[0-9]*-*.log' \
      \) -print 2>/dev/null | sort)
fi
say ""

# ── 2. 仓库内一次性中间物 ──────────────────────────────────────────────
# **注意**：`shared/build` 是 KMP 的活动构建目录，删了下次要重链（6 分钟）⇒ **保留**。
say "② 仓库内一次性中间物"
purge "$REPO_ROOT/build-output" "build-output/（历史 IPA 中间副本）"
purge "$REPO_ROOT/androidApp/build/outputs/apk/debug" "androidApp debug APK（中间产物）"
say ""

# ── 3. git worktree 登记残留（零成本，不动任何文件）───────────────────
say "③ git worktree 登记残留"
if [ -d "$REPO_ROOT/.git" ] || [ -f "$REPO_ROOT/.git" ]; then
  stale=0
  while IFS= read -r line; do
    wt=$(printf '%s' "$line" | sed 's/^worktree //')
    [ -n "$wt" ] || continue
    [ "$wt" = "$REPO_ROOT" ] && continue
    [ -d "$wt" ] && continue
    say "  登记残留（目录已删）：$wt"
    stale=$((stale + 1))
  done < <( cd "$REPO_ROOT" && git worktree list --porcelain 2>/dev/null | grep '^worktree ' )
  if [ "$stale" -eq 0 ]; then
    say "  无残留"
  elif [ "$YES" -eq 1 ]; then
    ( cd "$REPO_ROOT" && git worktree prune ) >/dev/null 2>&1 && say "  ✓ 已 prune $stale 条"
  else
    say "  [预览] 将 prune $stale 条"
  fi
else
  say "  （不是 git 仓库，跳过）"
fi
say ""

# ── 4. 明确保留项（打印出来，防有人手工乱删）──────────────────────────
say "④ 明确保留（删了下次出包会变慢，用户 2026-11-02 明确要求不删）"
say "  DerivedData（$(human "$(size_of "$HOME/Library/Developer/Xcode/DerivedData")")）  ← 删了全量重编"
say "  shared/build（$(human "$(size_of "$REPO_ROOT/shared/build")")）  ← 删了 KMP 重链约 6 分钟"
say "  ModuleCache.noindex  ← 删了所有 Swift 重编"
say "  模拟器运行时 / 设备  ← iOS 17+ 验证依赖"
say ""

# ── 汇总 ────────────────────────────────────────────────────────────────
say "=== 汇总 ==="
if [ "$YES" -eq 1 ]; then
  say "系统盘可用（后）：$(human "$(avail)")"
  say "本轮释放：$(human "$FREED_KB")"
  [ "$FAILED" -eq 1 ] && { say "⚠️ 有删除失败项，见上方 ✗"; exit 1; }
  say "✓ 清理完成（缓存未动，下次出包速度不受影响）"
else
  say "预计可释放：$(human "$FREED_KB")"
  say "（预览模式。加 --yes 才真删。缓存不在清理范围，出包速度不受影响）"
fi
exit 0
