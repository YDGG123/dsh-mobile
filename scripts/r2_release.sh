#!/bin/sh
set -eu


# ── 本机构建环境头（ReleaseBot 2026-11-06 补：合并树起每次 iOS 构建都要过它）────────
# ① xcrun -f ld 在本机必崩（dyld 坏页）；DEVELOPER_DIR 指向 shim 绕过（11-03 README 方案）。
# ② Tsnetgo 阶段需要 Go/gomobile（tools/go/bin + tools/gopath/bin）。
if [ -z "${DEVELOPER_DIR:-}" ]; then
  if [ -d /tmp/FakeXcode.app/Contents/Developer ]; then
    export DEVELOPER_DIR=/tmp/FakeXcode.app/Contents/Developer
  else
    echo "ERROR: /tmp/FakeXcode.app 不存在——本机 xcrun -f ld 必崩，需按 11-03 README 重建：" >&2
    echo "       /Volumes/应用/dsh-workspace/tools/xcode-shim/rebuild-xcode-shim.sh" >&2
    exit 69
  fi
fi
for _d in /Volumes/应用/dsh-workspace/tools/go/bin /Volumes/应用/dsh-workspace/tools/gopath/bin; do
  [ -d "$_d" ] && case ":${PATH:-}:" in *_d:*) ;; *) export PATH="$_d:$PATH" ;; esac
done
export GOPATH="${GOPATH:-/Volumes/应用/dsh-workspace/tools/gopath}"
project_root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
if [ "${1:-}" = "--prepare" ]; then
  exec python3 "$project_root/scripts/publish_r2_release.py" "$@"
fi
runtime="$project_root/build/r2-tools/bin/python"
if [ ! -x "$runtime" ]; then
  python3 -m venv "$project_root/build/r2-tools"
fi
if ! "$runtime" -c 'import boto3' >/dev/null 2>&1; then
  "$runtime" -m pip install -r "$project_root/scripts/r2-requirements.txt"
fi
exec "$runtime" "$project_root/scripts/publish_r2_release.py" "$@"
