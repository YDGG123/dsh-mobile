#!/bin/bash
# 出包：把当前 HEAD 的 App 代码送进 `build/u6-batch2` 并触发唯一一次 workflow_dispatch。
#
# 为什么不是 `git push`：
#   1. 本机令牌没有 `workflow` scope —— 含 `.github/workflows/*` 改动的提交推不上去；
#   2. 本机网络 `github.com:443` 经常不可达（`api.github.com` 通），git 传输直接超时。
# 所以这里走 GitHub Git Data API 建提交（blobs → tree → commit → PATCH ref），
# 再用 `gh workflow run` 触发。**dispatch 只需要 repo scope**，与 workflow scope 无关。
#
# 用法：
#   ./scripts/ship-branch.sh                 # 建提交 + 触发 + 等构建 + 下载 + 报 sha256
#   ./scripts/ship-branch.sh --no-wait       # 只建提交并触发
#   ./scripts/ship-branch.sh --dry-run       # 只打印将要提交的文件，不写远端
#
# 约定：`.github/workflows/` 下的文件一律不写进出包提交（沿用 base 的版本），
# 这样既不触发 scope 限制，也不改变 CI 行为（我们是手动 dispatch）。
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

REPO="${REPO:-YDGG123/dsh-mobile}"
BRANCH="${BRANCH:-build/u6-batch2}"
WORKFLOW="${WORKFLOW:-build-ipa.yml}"
OUT_DIR="${OUT_DIR:-build-output}"
WAIT=1
DRY=0

while [ $# -gt 0 ]; do
  case "$1" in
    --no-wait) WAIT=0; shift;;
    --dry-run) DRY=1; shift;;
    -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0;;
    *) echo "未知参数: $1"; exit 2;;
  esac
done

command -v gh >/dev/null || { echo "❌ 找不到 gh"; exit 1; }
command -v python3 >/dev/null || { echo "❌ 找不到 python3"; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "❌ gh 未登录"; exit 1; }

LOCAL_SHA=$(git rev-parse HEAD)
LOCAL_TREE=$(git rev-parse HEAD^{tree})
echo "→ 本地 HEAD ${LOCAL_SHA:0:7}  tree ${LOCAL_TREE:0:7}"

echo "→ 读取远端 ${BRANCH} 的 base"
BASE_SHA=$(gh api "repos/$REPO/git/ref/heads/$BRANCH" --jq '.object.sha') || { echo "❌ 读不到分支 $BRANCH"; exit 1; }
BASE_TREE=$(gh api "repos/$REPO/git/commits/$BASE_SHA" --jq '.tree.sha') || { echo "❌ 读不到 base commit"; exit 1; }
echo "  base ${BASE_SHA:0:7}  tree ${BASE_TREE:0:7}"

# base 提交是经 API 建的，本地对象库里没有它（本机 github.com 不通，fetch 不了）。
# 旧逻辑要求「本地存在 tree 完全相同的提交」，但出包提交按约定不回写 workflow，
# tree 天然永远不同 → 必然失败。改为**逐文件**与远端 base 比对。
CHANGED_FILE=$(mktemp)
DELETED_FILE=$(mktemp)
trap 'rm -f "$CHANGED_FILE" "$DELETED_FILE"' EXIT
REPO="$REPO" BASE_TREE="$BASE_TREE" python3 - "$CHANGED_FILE" "$DELETED_FILE" <<'PYFILES'
import json, os, subprocess, sys
repo, base_tree = os.environ['REPO'], os.environ['BASE_TREE']
out_changed, out_deleted = sys.argv[1], sys.argv[2]

remote = subprocess.run(['gh', 'api', f'repos/{repo}/git/trees/{base_tree}?recursive=1'],
                        text=True, capture_output=True)
if remote.returncode != 0:
    sys.exit('读取 base tree 失败: ' + remote.stderr.strip())
base = {}
for item in json.loads(remote.stdout).get('tree', []):
    if item.get('type') == 'blob' and not item['path'].startswith('.github/workflows/'):
        base[item['path']] = item['sha']

local = {}
listing = subprocess.run(['git', 'ls-tree', '-r', 'HEAD'], text=True, capture_output=True)
for line in listing.stdout.splitlines():
    meta, path = line.split('\t', 1)
    if path.startswith('.github/workflows/'):
        continue
    local[path] = meta.split()[2]

changed = [path for path in local if base.get(path) != local[path]]
deleted = [path for path in base if path not in local]
open(out_changed, 'w').write(''.join(f'{q}\n' for q in changed))
open(out_deleted, 'w').write(''.join(f'{q}\n' for q in deleted))
print(f'  base 逐文件比对：改动/新增 {len(changed)} 个，删除 {len(deleted)} 个')
PYFILES

# 相对 base 的改动，排除 workflow 文件（沿用 base 版本）
CHANGED=$(cat "$CHANGED_FILE")
DELETED=$(cat "$DELETED_FILE")
if [ -z "$CHANGED" ] && [ -z "$DELETED" ]; then
  echo "⚠️  App 代码与 base 相同（只差 workflow 配置），无需新建提交"
else
  echo "→ 将写入 ${BRANCH} 的文件："
  echo "$CHANGED" | sed '/^$/d' | sed 's/^/    /'
  if [ -n "$DELETED" ]; then echo "→ 将删除的文件："; echo "$DELETED" | sed '/^$/d' | sed 's/^/    - /'; fi
fi

if [ "$DRY" -eq 1 ]; then echo "→ dry-run，结束"; exit 0; fi

NEW_SHA=$(BASE_SHA="$BASE_SHA" LOCAL_SHA="$LOCAL_SHA" REPO="$REPO" CHANGED_FILE="$CHANGED_FILE" DELETED_FILE="$DELETED_FILE" python3 - <<'PY'
import base64, json, os, subprocess, sys

repo = os.environ['REPO']
base = os.environ['BASE_SHA']
local = os.environ['LOCAL_SHA']
changed_file = os.environ['CHANGED_FILE']
deleted_file = os.environ['DELETED_FILE']

def gh(args, payload=None):
    cmd = ['gh', 'api'] + (['--input', '-'] if payload is not None else []) + args
    p = subprocess.run(cmd, input=(json.dumps(payload) if payload is not None else None),
                       text=True, capture_output=True)
    if p.returncode != 0:
        sys.exit(f"gh api failed: {' '.join(args)}\n{p.stdout}\n{p.stderr}")
    return json.loads(p.stdout) if p.stdout.strip() else {}

base_tree = gh([f'repos/{repo}/git/commits/{base}'])['tree']['sha']
files = [f for f in open(changed_file).read().split('\n') if f.strip()]
deletions = [f for f in open(deleted_file).read().split('\n') if f.strip()]

entries = []
for path in files:
    # 一律取**已提交**内容（HEAD），不读工作区：否则未提交的改动会让内容校验必然失败。
    blob = subprocess.run(['git', 'show', f'HEAD:{path}'], capture_output=True)
    if blob.returncode != 0:
        sys.exit(f'读取 {path} 的 HEAD 内容失败: {blob.stderr.decode(errors="ignore")}')
    content = blob.stdout
    created = gh([f'repos/{repo}/git/blobs'],
                 {'content': base64.b64encode(content).decode(), 'encoding': 'base64'})
    entries.append({'path': path, 'mode': '100644', 'type': 'blob', 'sha': created['sha']})
# 删除用 sha=None 表达（Git Data API 语义）。
for path in deletions:
    entries.append({'path': path, 'mode': '100644', 'type': 'blob', 'sha': None})

tree = gh([f'repos/{repo}/git/trees'], {'base_tree': base_tree, 'tree': entries}) if entries else {'sha': base_tree}
message = subprocess.run(['git', 'log', '-1', '--pretty=%s'], text=True, capture_output=True).stdout.strip()
commit = gh([f'repos/{repo}/git/commits'], {
    'message': f"chore(build): App 代码 = {local[:7]}（{message}）\n\n由 scripts/ship-branch.sh 经 Git Data API 建立；workflow 文件沿用 base。",
    'tree': tree['sha'],
    'parents': [base],
})
print(commit['sha'])
PY
) || { echo "❌ 建提交失败"; exit 1; }

echo "→ 新提交 ${NEW_SHA:0:7}"

echo "→ 校验内容：按文件逐个比对（跳过 .github/workflows/，它沿用 base 版本）"
REMOTE_TREE=$(gh api "repos/$REPO/git/commits/$NEW_SHA" --jq '.tree.sha')
if ! REPO="$REPO" REMOTE_TREE="$REMOTE_TREE" python3 - <<'PYCHECK'
import json, os, subprocess, sys
repo = os.environ['REPO']; remote_tree = os.environ['REMOTE_TREE']
remote = subprocess.run(['gh', 'api', f'repos/{repo}/git/trees/{remote_tree}?recursive=1'],
                        text=True, capture_output=True)
if remote.returncode != 0:
    sys.exit('读取远端 tree 失败: ' + remote.stderr.strip())
remote_map = {item['path']: item['sha'] for item in json.loads(remote.stdout).get('tree', [])
              if item.get('type') == 'blob' and not item['path'].startswith('.github/workflows/')}
local = subprocess.run(['git', 'ls-tree', '-r', 'HEAD'], text=True, capture_output=True).stdout
local_map = {}
for line in local.splitlines():
    meta, path = line.split('\t', 1)
    local_map[path] = meta.split()[2]
local_map = {p_: s_ for p_, s_ in local_map.items() if not p_.startswith('.github/workflows/')}
missing = [p_ for p_ in local_map if p_ not in remote_map]
differs = [p_ for p_ in local_map if p_ in remote_map and remote_map[p_] != local_map[p_]]
extra = [p_ for p_ in remote_map if p_ not in local_map]
if missing or differs or extra:
    print('❌ 内容不一致：')
    if missing: print('  远端缺:', missing[:5])
    if differs: print('  内容不同:', differs[:5])
    if extra: print('  远端多:', extra[:5])
    sys.exit(1)
print('  ✓ 逐文件一致（%d 个文件；workflow 按约定跳过）' % len(local_map))
PYCHECK
then
  echo "❌ 校验失败，未推送分支指针"
  exit 1
fi

gh api --input - -X PATCH "repos/$REPO/git/refs/heads/$BRANCH" \
  <<< "{\"sha\":\"$NEW_SHA\",\"force\":true}" >/dev/null || { echo "❌ 更新分支失败"; exit 1; }
echo "  ✓ 分支 $BRANCH → ${NEW_SHA:0:7}"

RUN_ID=""
for _ in $(seq 1 10); do
  gh workflow run "$WORKFLOW" --repo "$REPO" --ref "$BRANCH" -f configuration=Release >/dev/null 2>&1 && break
  sleep 5
done
for _ in $(seq 1 20); do
  RUN_ID=$(gh run list --repo "$REPO" --branch "$BRANCH" --limit 5 \
            --json databaseId,headSha --jq ".[] | select(.headSha==\"$NEW_SHA\") | .databaseId" 2>/dev/null | head -1)
  [ -n "$RUN_ID" ] && break
  sleep 6
done
[ -n "$RUN_ID" ] || { echo "⚠️  未找到对应的 run，请手动查看 https://github.com/$REPO/actions"; exit 1; }
echo "→ 构建 run $RUN_ID"

[ "$WAIT" -eq 1 ] || exit 0

for _ in $(seq 1 150); do
  ST=$(gh run view "$RUN_ID" --repo "$REPO" --json status --jq .status 2>/dev/null)
  [ "$ST" = "completed" ] && break
  sleep 20
done
CON=$(gh run view "$RUN_ID" --repo "$REPO" --json conclusion --jq '.conclusion // "-"')
echo "→ 结论 $CON"
[ "$CON" = "success" ] || { echo "❌ 构建失败"; exit 1; }

mkdir -p "$OUT_DIR/run-$RUN_ID"
gh run download "$RUN_ID" --repo "$REPO" -D "$OUT_DIR/run-$RUN_ID" 2>&1 | tail -2
IPA=$(find "$OUT_DIR/run-$RUN_ID" -name '*.ipa' | head -1)
[ -n "$IPA" ] || { echo "❌ 没找到 ipa"; exit 1; }
STAMP=$(date '+%Y%m%d-%H%M')
NAME="$OUT_DIR/DshMobile-iOS16-$STAMP.ipa"
cp "$IPA" "$NAME"
echo
echo "=== 交付 ==="
echo "文件：$NAME"
echo "大小：$(stat -f%z "$NAME") 字节"
echo "sha256：$(shasum -a 256 "$NAME" | cut -d' ' -f1)"
echo "run：$RUN_ID"
echo "run head_sha：$NEW_SHA（本地 HEAD ${LOCAL_SHA:0:7}，tree 一致）"
