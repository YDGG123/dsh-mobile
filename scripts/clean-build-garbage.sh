#!/bin/bash
# 出包后清理构建垃圾（用户 2026-11-02 要求：「以后出了包就清理掉因出包产生的垃圾」）
#
# 为什么需要这个：系统盘只有 123G，一次出包会产生 3–6G 临时物（xcarchive、DerivedData
# 副本、/tmp worktree、typecheck deps）。不清就会一路涨到 98% 卡住所有构建（2026-11-02
# 实测：出包期间系统盘只剩 2.4G，任何构建都可能失败）。
#
# 设计原则（重要）：
#   1. **只删「可重建」的东西**。产物（IPA/APK）、git 仓库、模拟器运行时一律不碰。
#   2. **默认 dry-run**：先打印要删什么、能省多少，加 --yes 才真删。防止手滑。
#   3. **保留正在用的**：正在跑的 xcodebuild/gradle 的 DerivedData、未完成的 worktree 不删。
#   4. 每项删除前后都打印磁盘可用量，便于核对实际释放了多少。
#
# 用法：
#   bash scripts/clean-build-garbage.sh            # 预览（不删）
#   bash scripts/clean-build-garbage.sh --yes      # 真删
#   bash scripts/clean-build-garbage.sh --yes --keep-tmp   # 保留 /tmp 里的 worktree（调试用）
#
# 退出码：0 = 成功（含「没有可清的」）；1 = 有删除失败。

set -uo pipefail

YES=0
KEEP_TMP=0
for arg in "$@"; do
  case "$arg" in
    --yes) YES=1 ;;
    --keep-tmp) KEEP_TMP=1 ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "未知参数: $arg（可用 --yes / --keep-tmp / --help）" >&2; exit 1 ;;
  esac
done

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DERIVED="$HOME/Library/Developer/Xcode/DerivedData"
FAILED=0
FREED_KB=0

say() { printf '%s\n' "$*"; }
avail() { df -k /System/Volumes/Data 2>/dev/null | awk 'NR==2{print $4}'; }
size_of() { du -sk "$1" 2>/dev/null | awk '{print $1}'; }
human() { awk -v k="$1" 'BEGIN{ if(k>=1048576) printf "%.1fG", k/1048576; else if(k>=1024) printf "%.0fM", k/1024; else printf "%dK", k }'; }

# 正在跑的构建：它们正在写的 DerivedData 不能删
build_running() {
  pgrep -f 'xcodebuild|GradleWrapperMain|gradlew' >/dev/null 2>&1
}

# 删一个路径，累计释放量。$1 = 路径，$2 = 说明
purge() {
  local path="$1" label="$2"
  [ -e "$path" ] || return 0
  local kb before after
  kb=$(size_of "$path")
  [ -z "$kb" ] && kb=0
  if [ "$kb" -eq 0 ]; then
    say "  跳过（0 字节）：$label"
    return 0
  fi
  if [ "$YES" -eq 0 ]; then
    say "  [预览] $label  $(human "$kb")  ($path)"
    FREED_KB=$((FREED_KB + kb))
    return 0
  fi
  before=$(avail)
  rm -rf "$path" 2>/dev/null
  if [ -e "$path" ]; then
    say "  ✗ 删除失败：$label（权限或占用中）"
    FAILED=1
    return 1
  fi
  after=$(avail)
  local gained=$(( ${after:-0} - ${before:-0} ))
  [ "$gained" -lt 0 ] && gained=0
  FREED_KB=$((FREED_KB + gained))
  say "  ✓ $label  释放 $(human "$gained")"
}

say "=== 出包垃圾清理（$( [ "$YES" -eq 1 ] && echo 执行 || echo 预览 )）==="
say "系统盘可用（前）：$(human "$(avail)")"
say ""

# ── 1. /tmp 里的构建临时物 ──────────────────────────────────────────────
# xcarchive、build worktree（bNNwt / iosNN / vcNNwt）、typecheck 依赖、pub-check 等。
# **只删「构建用」的命名模式**，不认识的目录一律不动（避免误删别人的东西）。
say "① /tmp 构建临时物"
if [ "$KEEP_TMP" -eq 1 ]; then
  say "  （--keep-tmp：全部保留）"
elif build_running; then
  # **构建进行中时整项跳过**：正在跑的 xcodebuild 就在 /tmp/bNNwt 里编，
  # 删它等于当场掐断构建（2026-11-02 自查发现预览把正在用的 b69wt 也列进去了）。
  say "  （检测到构建正在运行，本项跳过 —— /tmp 里的 worktree 可能正被使用）"
else
  while IFS= read -r d; do
    [ -n "$d" ] || continue
    purge "$d" "/tmp/$(basename "$d")"
  done < <(find /private/tmp -maxdepth 1 \( \
        -name 'b[0-9]*wt' -o -name 'ios[0-9]*' -o -name 'vc[0-9]*wt' -o \
        -name 'b[0-9]*' -o -name '*.xcarchive' -o -name 'dsh-typecheck-deps*' -o \
        -name 'dsh-tc-*' -o -name 'pub-check-*' -o -name 'suite-pre*' -o \
        -name 'suite-reh-*' -o -name 'ipa[0-9]*' -o -name 'apk[0-9]*' \
      \) -print 2>/dev/null | sort)
fi
say ""

# ── 2. DerivedData：非活跃项目的副本 ────────────────────────────────────
# Xcode 每次 archive 都会新建一个 DerivedData 目录（按路径哈希），旧的永远用不上。
# 但**正在跑构建时不动**（它可能正写其中某个）。
say "② Xcode DerivedData（保留 ModuleCache 与最新一个）"
if build_running; then
  say "  （检测到构建正在运行，本项跳过）"
else
  newest=$(ls -td "$DERIVED"/DeepSeekHarnessMobile-* 2>/dev/null | head -1)
  if [ -n "$newest" ]; then
    say "  保留最新：$(basename "$newest")"
    while IFS= read -r d; do
      [ -n "$d" ] || continue
      [ "$d" = "$newest" ] && continue
      purge "$d" "DerivedData/$(basename "$d")"
    done < <(ls -d "$DERIVED"/DeepSeekHarnessMobile-* 2>/dev/null)
  fi
  # ModuleCache 也保留：删了会触发全量重编，慢得多（用户没要求清它）
fi
say ""

# ── 3. 仓库内构建产物 ──────────────────────────────────────────────────
# build-output/：历史 IPA 副本（成品已进 releases/，这里是出包过程的中间产物）
# **不删 releases/**（那是交付件，用户要看）。
say "③ 仓库内构建中间物"
# build-output/ 与 debug APK 是**历史产物副本**，构建进行中也安全（当前构建不写它们）。
purge "$REPO_ROOT/build-output" "build-output/（历史 IPA 中间副本）"
purge "$REPO_ROOT/androidApp/build/outputs/apk/debug" "androidApp debug APK 构建产物"
# shared/build 是 KMP 的活动构建目录：**构建进行中删它会掐断编译**，所以单独判。
if build_running; then
  say "  （构建进行中：shared/build 跳过）"
else
  purge "$REPO_ROOT/shared/build" "shared/ 构建目录（KMP 中间物，下次构建重建）"
fi
say ""

# ── 4. 已完成使命的 git worktree ────────────────────────────────────────
# 出包常在 worktree 里做（不污染主树）。出完包 worktree 就没用了，但 git 登记还在。
say "④ 已结束的 git worktree（保留主树与 /tmp 现存目录）"
if [ -d "$REPO_ROOT/.git" ] || [ -f "$REPO_ROOT/.git" ]; then
  while IFS= read -r line; do
    wt=$(printf '%s' "$line" | sed 's/^worktree //')
    [ -n "$wt" ] || continue
    [ "$wt" = "$REPO_ROOT" ] && continue
    if [ -d "$wt" ]; then
      say "  保留（目录仍存在）：$wt"
    else
      say "  登记残留（目录已删，可 prune）：$wt"
      [ "$YES" -eq 1 ] && ( cd "$REPO_ROOT" && git worktree prune ) >/dev/null 2>&1
    fi
  done < <( cd "$REPO_ROOT" && git worktree list --porcelain 2>/dev/null | grep '^worktree ' )
else
  say "  （不是 git 仓库，跳过）"
fi
say ""

# ── 汇总 ────────────────────────────────────────────────────────────────
say "=== 汇总 ==="
if [ "$YES" -eq 1 ]; then
  say "系统盘可用（后）：$(human "$(avail)")"
  say "本轮释放：$(human "$FREED_KB")"
  [ "$FAILED" -eq 1 ] && say "⚠️ 有删除失败项（多为权限/占用），见上方 ✗" && exit 1
  say "✓ 清理完成"
else
  say "预计可释放：$(human "$FREED_KB")"
  say "（这是预览。加 --yes 才真删）"
fi
exit 0
