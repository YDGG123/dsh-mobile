#!/bin/bash
# 一条命令完成「类型检查 → 推送 → 出包」。
#
#   ./scripts/ship.sh                    # 检查+推送,并在 Actions 页面等结果
#   ./scripts/ship.sh -m "修复xxx"       # 指定提交信息
#   ./scripts/ship.sh --no-wait          # 推完就返回,不等待构建
#   ./scripts/ship.sh --skip-check       # 跳过本地类型检查(不推荐)
#
# 做三件事:
#   1. 本地 swiftc 类型检查(约 90 秒,能抓语法和 API 可用性错误)
#   2. 提交并推送到 fork 的 ios16-support 分支
#   3. 轮询 GitHub Actions,构建完成后下载 IPA 到 ./build-output/
#
# 需要:gh 已登录(gh auth status 可查)、能访问 GitHub(脚本会自动用本地代理)

set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

BRANCH="ios16-support"
REMOTE="fork"
OUT_DIR="build-output"
WAIT=1
SKIP_CHECK=0
MSG=""

# ---- 本地代理:Clash Verge 默认 7897,按需自动探测 ----
detect_proxy() {
  for p in 7897 7890 1087; do
    if curl -s -m 2 -x "http://127.0.0.1:$p" -o /dev/null https://github.com 2>/dev/null; then
      echo "http://127.0.0.1:$p"; return
    fi
  done
  echo ""
}

while [ $# -gt 0 ]; do
  case "$1" in
    -m|--message) MSG="${2:-}"; shift 2;;
    --no-wait) WAIT=0; shift;;
    --skip-check) SKIP_CHECK=1; shift;;
    -h|--help) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0;;
    *) echo "未知参数: $1"; exit 2;;
  esac
done

PROXY=$(detect_proxy)
if [ -n "$PROXY" ]; then
  export https_proxy="$PROXY" http_proxy="$PROXY" all_proxy="$PROXY"
  git config http.proxy "$PROXY" 2>/dev/null
  git config https.proxy "$PROXY" 2>/dev/null
  echo "→ 使用代理 $PROXY"
else
  echo "→ 未检测到可用代理,直连 GitHub"
fi

# ---- 前置检查 ----
command -v gh >/dev/null || { echo "❌ 找不到 gh,请先安装 GitHub CLI"; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "❌ gh 未登录,请运行: gh auth login"; exit 1; }

REPO=$(git remote get-url "$REMOTE" 2>/dev/null | sed -E 's#.*github\.com[:/]([^/]+/[^/.]+)(\.git)?#\1#')
[ -n "$REPO" ] || { echo "❌ 无法从 remote '$REMOTE' 解析仓库名"; exit 1; }
echo "→ 目标仓库 $REPO 分支 $BRANCH"

if [ "$(git branch --show-current)" != "$BRANCH" ]; then
  echo "❌ 当前不在 $BRANCH 分支(在 $(git branch --show-current))"
  echo "   先执行: git checkout $BRANCH"
  exit 1
fi

# ---- 1. 本地类型检查 ----
if [ "$SKIP_CHECK" -eq 0 ]; then
  echo
  echo "=== 1/3 本地类型检查 ==="
  if ! ./scripts/local-typecheck.sh; then
    echo
    echo "❌ 类型检查未通过,已停止推送。修好后再运行。"
    exit 1
  fi
else
  echo
  echo "=== 1/3 跳过类型检查 ==="
fi

# ---- 2. 提交并推送 ----
echo
echo "=== 2/3 提交并推送 ==="
if [ -z "$(git status --porcelain)" ]; then
  echo "→ 工作区无改动,直接推送已有提交"
else
  git status --short | head -20
  if [ -z "$MSG" ]; then
    MSG="Update $(date '+%Y-%m-%d %H:%M')"
  fi
  git add -A || { echo "❌ git add 失败"; exit 1; }
  git commit -m "$MSG" >/dev/null || { echo "❌ 提交失败"; exit 1; }
  echo "→ 已提交: $MSG"
fi

if ! git push "$REMOTE" "$BRANCH" 2>&1 | tail -3; then
  echo "❌ 推送失败"
  exit 1
fi

HEAD_SHA=$(git rev-parse HEAD)
echo "→ 已推送 $(git rev-parse --short HEAD)"

# ---- 3. 等待构建并下载 IPA ----
echo
echo "=== 3/3 等待云端构建 ==="
RUN_ID=""
for i in $(seq 1 20); do
  RUN_ID=$(gh run list --repo "$REPO" --branch "$BRANCH" --limit 5 \
            --json databaseId,headSha --jq ".[] | select(.headSha==\"$HEAD_SHA\") | .databaseId" 2>/dev/null | head -1)
  [ -n "$RUN_ID" ] && break
  sleep 6
done

if [ -z "$RUN_ID" ]; then
  echo "⚠️  没找到对应的构建任务,请手动查看:"
  echo "   https://github.com/$REPO/actions"
  exit 1
fi

echo "→ 构建任务 #$RUN_ID"
echo "   https://github.com/$REPO/actions/runs/$RUN_ID"

if [ "$WAIT" -eq 0 ]; then
  echo "→ 已按要求跳过等待"
  exit 0
fi

# 公开仓库的 macOS runner 在高峰时段可能长时间分配不到机器,任务会在
# 约 15 分钟后被系统取消(表现为 conclusion=cancelled 且没有任何步骤)。
# 这里检测到「排队超时取消」就自动重新触发,最多 RETRIES 次。
RETRIES="${RETRIES:-3}"
attempt=1
while :; do
  echo "→ 等待构建(第 $attempt 轮,通常 10-20 分钟)..."
  build_failed=0
  for i in $(seq 1 120); do
    st=$(gh api "repos/$REPO/actions/runs/$RUN_ID" --jq '.status' 2>/dev/null)
    con=$(gh api "repos/$REPO/actions/runs/$RUN_ID" --jq '.conclusion // "-"' 2>/dev/null)
    steps=$(gh api "repos/$REPO/actions/runs/$RUN_ID/jobs" --jq '.jobs[0].steps | length' 2>/dev/null)
    if [ "$st" = "completed" ]; then
      if [ "$con" = "success" ]; then
        break
      fi
      # 被取消且一步都没跑:runner 排队失败,可以重试
      if [ "$con" = "cancelled" ] && [ "$steps" = "0" ] && [ "$attempt" -lt "$RETRIES" ]; then
        echo "⚠️  第 $attempt 轮未拿到 macOS runner 被取消,重新触发"
        gh workflow run build-ipa.yml --repo "$REPO" --ref "$BRANCH" -f configuration=Release >/dev/null 2>&1
        sleep 25
        RUN_ID=""
        for j in $(seq 1 10); do
          RUN_ID=$(gh run list --repo "$REPO" --branch "$BRANCH" --limit 3 \
                    --json databaseId,headSha \
                    --jq ".[] | select(.headSha==\"$HEAD_SHA\") | .databaseId" 2>/dev/null | head -1)
          [ -n "$RUN_ID" ] && break
          sleep 6
        done
        if [ -n "$RUN_ID" ]; then
          echo "→ 新构建任务 #$RUN_ID"
          attempt=$((attempt+1))
          build_failed=1
        fi
        break
      fi
      echo "❌ 构建失败 ($con),日志:"
      gh run view "$RUN_ID" --repo "$REPO" --log-failed 2>/dev/null | tail -30
      exit 1
    fi
    [ $((i % 10)) -eq 0 ] && echo "[$(date '+%H:%M:%S')] status=$st conclusion=$con"
    sleep 30
  done

  if [ "$build_failed" -eq 0 ]; then
    # 确认真的成功了
    con=$(gh api "repos/$REPO/actions/runs/$RUN_ID" --jq '.conclusion // "-"' 2>/dev/null)
    [ "$con" = "success" ] && break
    echo "⏱️  等待超时,请手动查看: https://github.com/$REPO/actions/runs/$RUN_ID"
    exit 1
  fi
done

echo "→ 构建成功,下载产物..."
mkdir -p "$OUT_DIR"
rm -rf "$OUT_DIR/tmp-download"
gh run download "$RUN_ID" --repo "$REPO" --dir "$OUT_DIR/tmp-download" >/dev/null 2>&1

IPA=$(find "$OUT_DIR/tmp-download" -name "*.ipa" | head -1)
if [ -z "$IPA" ]; then
  echo "⚠️  产物里没有 IPA,请到 Actions 页面查看"
  exit 1
fi

FINAL="$OUT_DIR/DshMobile-iOS16-$(date '+%Y%m%d-%H%M').ipa"
cp "$IPA" "$FINAL"
rm -rf "$OUT_DIR/tmp-download"

echo
echo "✅ 完成"
echo "   IPA: $FINAL"
echo "   大小: $(du -h "$FINAL" | cut -f1)"
echo
echo "   下一步: 用 Sideloadly 安装到手机"
