#!/usr/bin/env python3
"""改名残留闸门：项目从 `Whaleness` 改名为 `Orcarness` 后，
检查代码与配置里是否还残留**旧品牌**或**原作者入口**。

为什么需要这个闸门：
  改名的死法不是「改错一个字」，而是「漏了一处」——漏在 Info.plist、漏在发布脚本、
  漏在某个 README 徽章里。这类残留不会让构建变红，只会在用户看到产品时露出来。
  所以这里把三类目标逐文件扫一遍，命中即红，并给出精确位置。

## ⚠️ 已知盲区（写在这里，因为「让人以为全查了」比漏查更危险）

本闸门**只扫文本文件**（后缀白名单见 `TEXT_SUFFIXES`），因此以下情况它**查不到**，
必须靠人工复核或别的闸门兜底：

1. **位图内容**——PNG/JPG/APNG 里若印着旧 logo 或旧品牌字，本闸门**永远抓不到**
   （`Docs/images/`、`Assets.xcassets` 里的图片都属于此列）。改名时图片要么换新图，
   要么逐张目视确认。
2. **编译产物与二进制**——`build/`、`.app`、IPA/APK 内部字符串不在扫描范围内。
3. **代码里的「间接旧名」**——例如资源名 `whaleness_loading` 会被抓到，
   但若有人改成 `legacy_loading` 这种不含目标词的名字，闸门就看不见了。改名要成对检查「资源名 + 引用它的代码」。
4. **域名/包名这类「保留但需迁移」的项**——已按豁免处理并显式提示，
   不代表它们是「正确的」，只代表「现在不动它更安全」（见下方豁免第 1、2 条）。

> 闸门绿 = 「文本里没有旧名残留」；**不等于**「产品完全改名干净」。

## 三类目标（性质完全不同，处置也不同）

| 目标 | 含义 | 处置 |
| --- | --- | --- |
| `Whaleness` / `whaleness` | **旧产品名** | 应改名为 `Orcarness` / `orcarness`。**例外**见下方豁免 |
| `Clarklevis1995` / `clarklevis1995` / `clarklevis`（非包名形态） | **原作者 GitHub / 官网入口** | 产品不能挂别人的入口。署名致谢可留在特定文件，但入口链接要撤 |
| `licheofan` | 另一位作者的机器用户名 | 不应出现在仓库任何地方（含文档） |

## 豁免（这些**不是**残留，必须留着，否则会改坏东西）

1. **Kotlin 包名 `com.clarklevis.dsh`** —— 这是 Java/Kotlin 的目录结构与 import 路径，
   改它等于全仓重构 + 断掉 Android 应用 ID 与已发布 APK 的签名连续性。**永远豁免**。
   （真正的产品标识是 `applicationId`，那是另一个议题，不在本闸门范围。）
2. **发布域名 `download.whaleness.cc`** —— 这是**已上线的实际分发域名**，
   换域名需要 DNS + R2 桶 + 已装机用户的 latest.json 全部迁移，属于部署决策，
   不是改名遗漏。在迁移完成前**豁免**，但会以 `⚠️` 列出，提醒「域名仍是旧名」。
3. **上游/第三方署名** —— 如 `Docs/third-party-notices.md` 里对上游项目的致谢、
   `Docs/upstream-delta-*.md` 里「上游 = Clarklevis1995/dsh-mobile」的事实陈述。
   **开源致谢必须保留**（这是法务与道义要求），只要求**产品内不出现他的入口链接**。
4. **`Docs/` 下的历史研究稿** —— 记录的是当时的事实，改它等于篡改历史。
   豁免，但要列进清单（要求这些文件头部有「项目已改名」声明，由人复核）。

用法：
  python3 scripts/verify-rename.py              # 正常检查，有残留 exit 1
  python3 scripts/verify-rename.py --list       # 列出全部命中与豁免，不做判定
  python3 scripts/verify-rename.py --strict     # 连「域名豁免」也不豁免（迁移完成后用）

退出码：0 = 无残留；1 = 有残留；2 = 仓库结构异常（找不到预期文件）
"""

from __future__ import annotations

import argparse
import hashlib
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 扫描范围：代码 + 配置 + 面向用户的文档。
# 明确**不扫**：构建产物、依赖、.git、以及二进制资源。
SCAN_DIRS = [
    "DeepSeekHarnessMobile",
    "DeepSeekHarnessMobileTests",
    "DeepSeekHarnessMobileUITests",
    "AgentLiveActivityWidget",
    "androidApp",
    "shared",
    "scripts",
    "website",
    "Design",
    "Docs",
]
SCAN_FILES = ["README.md", "README.en.md", "ARCHITECTURE.md"]

TEXT_SUFFIXES = {
    ".md", ".swift", ".kt", ".kts", ".java", ".xml", ".plist", ".json", ".yml", ".yaml",
    ".sh", ".py", ".js", ".mjs", ".ts", ".html", ".css", ".gradle", ".properties", ".pbxproj",
    ".xcstrings", ".strings", ".txt", ".rb", ".toml", ".svg",
}
SKIP_DIR_NAMES = {"build", "node_modules", ".git", "Pods", "DerivedData", ".gradle"}

# —— 目标模式 ——
# 每项 = (代号, 正则, 默认处置说明)
PATTERNS = [
    ("Whaleness", re.compile(r"Whaleness"), "旧产品名（应改 Orcarness）"),
    ("whaleness", re.compile(r"whaleness"), "旧产品名小写形态（域名豁免见下）"),
    ("Clarklevis1995", re.compile(r"Clarklevis1995|clarklevis1995"), "原作者 GitHub 入口"),
    ("licheofan", re.compile(r"licheofan", re.IGNORECASE), "他人机器用户名（不应出现）"),
]

# —— 豁免规则 ——
# 返回 True 表示该行命中属于豁免，不计为残留。
DOMAIN_ALLOW = re.compile(r"(?:download\.)?whaleness\.cc|whaleness\.cc")
# Kotlin/Java 包名形态：com.clarklevis 及其路径写法
PACKAGE_ALLOW = re.compile(r"com\.clarklevis|com/clarklevis|(?:kotlin/com/clarklevis)")
# 只允许「署名/致谢/上游事实」存在的文件（产品入口链接仍会被抓）
ATTRIBUTION_FILES = {
    "Docs/third-party-notices.md",
    "Docs/upstream-delta-ec916e1.md",
    "NOTICE",
    "LICENSE",
}


# —— 字形轮廓：闸门扫不到的那一层 ——
# 教训（2026-10-26，真机反馈）：`orcarness-harness.svg` 的**文件名、注释、aria-label**
# 都改成了 Orcarness，但里面 16 个 `<path>` 画的还是 `whaleness` 这 9 个字母的轮廓。
# 字面量闸门永远抓不到这种「自洽但过时」——所以这里补一层**疑似检测**：
#   SVG 里 `<path>` 数 > 12 且没有 `<text>` → 很可能是字母轮廓而不是图标，
#   输出 ⚠️ 要求人工看一眼（不求自动判定谁对谁错，只求不静默漏过）。
# 已人工确认的实现登记在 GLYPH_REVIEWED（带 sha256）：内容一改就重新要求确认，
# 否则「确认过一次」会变成永久免检。
GLYPH_PATH_THRESHOLD = 12
GLYPH_REVIEWED = {
    "DeepSeekHarnessMobile/Resources/Assets.xcassets/OrcarnessHarnessWordmark.imageset/orcarness-harness.svg": (
        "cd4b26185553257df34cc61f740f12d4ab53b052063a36cac0b3f4db4145a3c2",
        "人工确认：9 个字母 path = Orcarness（首字母大写），另 HARNESS 胶囊 —— Chief 生成、用户确认版式",
    ),
}

# 闸门自身必然包含这些词（它们就是规则定义），必须豁免自己
SELF_PATH = "scripts/verify-rename.py"


def is_historical_doc(rel: str) -> bool:
    """Docs/ 下的历史稿：整篇豁免，但要求人工确认头部有改名声明。"""
    if not rel.startswith("Docs/"):
        return False
    # 协议/权威文档不是「历史稿」，它们必须用新名
    authoritative = {
        "Docs/mobile-contract-v1.md",
        "Docs/chunked-frames-v1.md",
        "Docs/team-feed-resume-v1.md",
        "Docs/troubleshooting-matrix-v1.md",
    }
    return rel not in authoritative


def iter_files():
    for name in SCAN_FILES:
        p = ROOT / name
        if p.is_file():
            yield p
    for d in SCAN_DIRS:
        base = ROOT / d
        if not base.is_dir():
            continue
        for p in base.rglob("*"):
            if not p.is_file():
                continue
            if any(part in SKIP_DIR_NAMES for part in p.parts):
                continue
            if p.suffix.lower() in TEXT_SUFFIXES:
                yield p


def check_glyph_outlines() -> tuple[list[str], list[str]]:
    """返回 (已复核, 待复核)。只看 .svg：path 数量异常多且没有 <text> 的，疑似字母轮廓。"""
    reviewed: list[str] = []
    pending: list[str] = []
    for d in SCAN_DIRS:
        base = ROOT / d
        if not base.is_dir():
            continue
        for p in base.rglob("*.svg"):
            if any(part in SKIP_DIR_NAMES for part in p.parts):
                continue
            try:
                text = p.read_text(errors="ignore")
            except OSError:
                continue
            if text.count("<path") <= GLYPH_PATH_THRESHOLD or "<text" in text:
                continue
            rel = relpath(p)
            expected = GLYPH_REVIEWED.get(rel)
            digest = hashlib.sha256(text.encode()).hexdigest()
            if expected and expected[0] == digest:
                reviewed.append(rel)
            else:
                reason = "内容已变更，需重新确认" if expected else "未登记"
                pending.append(f"{rel}（{reason}；{text.count('<path')} 个 path）")
    return reviewed, pending


def relpath(p: Path) -> str:
    return str(p.relative_to(ROOT))


def classify(rel: str, line: str, code: str) -> str:
    """返回 'residue' | 'allow' 之一。"""
    # 0) 闸门脚本自身豁免（含规则定义）
    if rel == SELF_PATH:
        return "allow"
    # 1) 包名形态永远豁免
    if code in ("Clarklevis1995",) and PACKAGE_ALLOW.search(line):
        return "allow"
    # 2) 发布域名豁免（旧名但属部署决策）
    if DOMAIN_ALLOW.search(line):
        # 但同一行若含真实产品名 Whaleness（非域名），仍算残留
        stripped = DOMAIN_ALLOW.sub("", line)
        if not re.search(r"[Ww]haleness", stripped):
            return "allow"
    # 3) 致谢文件的署名豁免（仅当这一行是署名/致谢语气，**且不是可点击入口**）
    #    「感谢原作者」必须保留；但「官网/下载/TestFlight 链接」是用户入口，产品不能挂别人的。
    if rel in ATTRIBUTION_FILES:
        if re.search(r"(https?://|github\.com/\w)", line):
            return "residue"   # 入口链接一律不算署名豁免
        if re.search(r"(上游|upstream|致谢|credit|acknowledg|作者|author|原项目|fork)", line, re.IGNORECASE):
            return "allow"
    # 4) Docs 历史稿整篇豁免
    if is_historical_doc(rel):
        return "allow"
    return "residue"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--list", action="store_true", help="列出全部命中与豁免，不做判定")
    parser.add_argument("--strict", action="store_true", help="连发布域名豁免也一并判红（迁移完成后用）")
    options = parser.parse_args()

    if not (ROOT / "README.md").is_file():
        print(f"✗ 仓库结构异常：{ROOT/'README.md'} 不存在", file=sys.stderr)
        return 2

    residues: list[tuple[str, int, str, str]] = []
    allowed: list[tuple[str, int, str, str]] = []
    historical: set[str] = set()

    for p in iter_files():
        rel = relpath(p)
        if is_historical_doc(rel):
            historical.add(rel)
        try:
            text = p.read_text(errors="ignore")
        except OSError:
            continue
        for lineno, line in enumerate(text.splitlines(), 1):
            for code, pattern, _desc in PATTERNS:
                if not pattern.search(line):
                    continue
                verdict = classify(rel, line, code)
                if verdict == "allow" and options.strict:
                    verdict = "residue"
                (allowed if verdict == "allow" else residues).append((rel, lineno, code, line.strip()))

    if options.list:
        print(f"=== 残留 {len(residues)} 处 ===")
        for rel, lineno, code, line in residues:
            print(f"  {rel}:{lineno}  [{code}]  {line[:110]}")
        print(f"\n=== 豁免 {len(allowed)} 处 ===")
        for rel, lineno, code, line in allowed:
            print(f"  {rel}:{lineno}  [{code}]  {line[:110]}")
        print(f"\n=== Docs 历史稿豁免清单（{len(historical)} 个文件）===")
        for rel in sorted(historical):
            print(f"  {rel}")
        reviewed_svg, pending_svg = check_glyph_outlines()
        print(f"\n=== 字形轮廓：已复核 {len(reviewed_svg)} 个 / 待复核 {len(pending_svg)} 个 ===")
        for rel in reviewed_svg:
            print(f"  ✅ {rel}")
        for item in pending_svg:
            print(f"  ⚠️ {item}")
        return 0

    if residues:
        # 按代号分组，报错更可读
        by_code: dict[str, list[tuple[str, int, str, str]]] = {}
        for item in residues:
            by_code.setdefault(item[2], []).append(item)
        for code, items in by_code.items():
            desc = next(d for c, _p, d in PATTERNS if c == code)
            print(f"\n✗ [{code}] {desc} —— 残留 {len(items)} 处：", file=sys.stderr)
            for rel, lineno, _c, line in items:
                print(f"    {rel}:{lineno}  {line[:100]}", file=sys.stderr)
        print(f"\n改名闸门：{len(residues)} 处残留（豁免 {len(allowed)} 处，历史稿 {len(historical)} 个文件）", file=sys.stderr)
        print("  处置见脚本头部「三类目标 + 豁免」表；确属误报请改脚本豁免规则，不要就地改文档。", file=sys.stderr)
        return 1

    reviewed_svg, pending_svg = check_glyph_outlines()
    if pending_svg:
        print(f"\n⚠️ 字形轮廓待人工确认 —— {len(pending_svg)} 个 SVG 疑似用 `<path>` 画字，"
              f"而闸门扫不到 path 里的字形：", file=sys.stderr)
        for item in pending_svg:
            print(f"    {item}", file=sys.stderr)
        print("    处置：打开文件/渲染看一眼字形是否正确；确认后把 路径→(sha256, 说明) 加进 "
              "scripts/verify-rename.py 的 GLYPH_REVIEWED。\n"
              "    为什么要拦：文件名/注释/aria-label 全改对、字形仍是旧名字的形态，字面量闸门永远抓不到"
              "（2026-10-26 真机就是这个）。", file=sys.stderr)
        return 1
    if reviewed_svg:
        print(f"  ℹ️ 字形轮廓已复核 {len(reviewed_svg)} 个（sha256 绑定，内容一改就重新要求确认）")

    print(f"改名闸门通过：0 处残留（豁免 {len(allowed)} 处，历史稿 {len(historical)} 个文件）")
    if allowed:
        # 提醒域名仍是旧名——不是错误，但迁移完成前要记着
        domain_hits = [a for a in allowed if "whaleness.cc" in a[3]]
        if domain_hits:
            print(f"  ⚠️ 发布域名仍是旧名 `download.whaleness.cc`（{len(domain_hits)} 处）——这是**待迁移的部署负债**，不是可忽略的豁免。")
            print("     换域名**不是改一个字符串**：`R2UpdateManifest.kt` 把该域硬编码进 APK 更新的安全白名单，")
            print("     直接切域会让已装机老 APK 拒装新域名的包。正确顺序（少一步就断用户）：")
            print("       ① 先发一版只把白名单扩成「新旧域都收」的 APK")
            print("       ② 等用户都升到这一版")
            print("       ③ 切 DNS/证书 + R2 桶 `whaleness-apks` 的 publicBaseUrl + 发布脚本 PUBLIC_BASE")
            print("       ④ 再发一版收紧白名单，删掉旧域")
            print("     迁移完成后用 --strict 复查（届时这些处会按残留判红）。")
        pkg_hits = [a for a in allowed if PACKAGE_ALLOW.search(a[3])]
        if pkg_hits:
            print(f"  ℹ️ Kotlin 包名 `com.clarklevis.dsh` 豁免 {len(pkg_hits)} 处（改包名=全仓重构+断签名连续性，不在本闸门范围）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
