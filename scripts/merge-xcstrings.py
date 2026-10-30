#!/usr/bin/env python3
"""xcstrings 语义合并（dsh-mobile 上游 1.7.12 合并用）

规则（作战图第四节定稿）：
  1. key 并集：ours-only 全留；theirs-only 全留（品牌排除表除外）
  2. 双方都有：条目以 ours 为准；但 ours 缺的语言值从 theirs 补（语言级并集）
  3. 品牌排除：theirs-only 且 key 在排除表 → 丢弃（Whaleness / Whaleness Harness）
  4. 输出为 Xcode 规范格式（2 空格缩进、' : ' 分隔、key 排序），避免下次合并整文件再撞
退出码：0 = 成功；2 = 输入不可用（文件缺失 / JSON 解析失败 / 顶层结构不对）
"""
import json, sys, argparse

EXCLUDE_DEFAULT = ["Whaleness", "Whaleness Harness"]

def fail(msg):
    print(f"❌ {msg}", file=sys.stderr)
    sys.exit(2)

def load(path, label):
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except FileNotFoundError:
        fail(f"{label}: 文件不存在 {path}")
    except json.JSONDecodeError as e:
        fail(f"{label}: JSON 解析失败 {path}: {e}")
    if not isinstance(data, dict) or "strings" not in data or not isinstance(data["strings"], dict):
        fail(f"{label}: 顶层结构不对（缺 strings dict）: {path}")
    return data

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ours")
    ap.add_argument("theirs")
    ap.add_argument("out")
    ap.add_argument("--exclude", nargs="*", default=EXCLUDE_DEFAULT)
    args = ap.parse_args()

    ours = load(args.ours, "ours")
    theirs = load(args.theirs, "theirs")
    o, t = ours["strings"], theirs["strings"]

    only_ours = sorted(k for k in o if k not in t)
    only_theirs = sorted(k for k in t if k not in o)
    excluded = sorted(k for k in only_theirs if k in args.exclude)
    kept_theirs_only = [k for k in only_theirs if k not in args.exclude]
    both = sorted(k for k in o if k in t)

    value_conflicts = []
    lang_fills = {}
    merged = dict(o)
    for k in kept_theirs_only:
        merged[k] = t[k]
    for k in both:
        entry_o, entry_t = o[k], t[k]
        langs_o = entry_o.get("localizations") or {}
        langs_t = entry_t.get("localizations") or {}
        if {lang: (u.get("stringUnit") or {}).get("value") for lang, u in langs_o.items()} != \
           {lang: (u.get("stringUnit") or {}).get("value") for lang, u in langs_t.items()}:
            value_conflicts.append(k)
        new_langs = dict(langs_o)
        for lang, unit in langs_t.items():
            if lang not in new_langs:                      # ours 缺这门语言 → 从 theirs 补
                new_langs[lang] = unit
                lang_fills.setdefault(k, []).append(lang)
        entry = dict(entry_o)                              # ours 条目为基底（extractionState 等以 ours 为准）
        if new_langs:
            entry["localizations"] = new_langs
        elif "localizations" in entry:
            entry["localizations"] = langs_o
        merged[k] = entry

    out = {"sourceLanguage": ours.get("sourceLanguage", "zh-Hans"), "strings": merged}
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2, separators=(",", " : "), sort_keys=True)
        f.write("\n")

    blob = json.dumps(merged, ensure_ascii=False)
    print("=== xcstrings 合并读数 ===")
    print(f"1 ours-only 保留        : {len(only_ours)}")
    print(f"2 theirs-only 保留/剔除 : {len(kept_theirs_only)} / {len(excluded)} {excluded}")
    print(f"3 双方值冲突(以我们为准) : {len(value_conflicts)}")
    for k in value_conflicts[:8]:
        print(f"   - {k!r}")
    if len(value_conflicts) > 8: print(f"   … 共 {len(value_conflicts)} 条")
    print(f"4 语言级补缺(从 theirs)  : {sum(len(v) for v in lang_fills.values())} 条语言值 / {len(lang_fills)} 个 key")
    for k in list(lang_fills)[:6]:
        print(f"   - {k!r} +{lang_fills[k]}")
    print(f"总 key 数: {len(merged)}（ours {len(o)} + theirs 新增 {len(kept_theirs_only)}）")
    bad = blob.count("Whaleness")
    print(f"品牌扫描: 输出中 'Whaleness' 出现 {bad} 次{'（应为 0，需人工检查！）' if bad else ''}")

if __name__ == "__main__":
    main()
