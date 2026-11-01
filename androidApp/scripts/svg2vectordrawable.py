#!/usr/bin/env python3
"""SVG → Android VectorDrawable 转换器 + 几何判据工具（dsh-mobile 品牌资产）。

把 2026-11-01 wordmark 三连缺陷（丢 <rect> 底板 / 丢内层 group 平移 / 漏 night
变体）固化成断言与自检，缺一不可：
  1. 遍历 SVG **全节点树**按文档序收集可绘制元素 —— 只抓 <path> 会把 <rect>
     （HARNESS 徽章底板）静默丢掉，浅色下白字落透明底直接隐形；
  2. <rect> 转圆角矩形 pathData（VectorDrawable 没有 <rect>）；
  3. **每一层** <g translate> 都要落成嵌套 <group>（丢内层 translate =
     徽章整体右移，逐 path 数值对但空间关系错）；
  4. viewBox 的 minX/minY → 外层 <group> 平移归一；
  5. fill 必须逐元素显式 —— VectorDrawable 的 <group> 不继承 fill；
  6. **硬断言：SVG 可绘制元素总数 == 输出 pathData 数**，不等 exit 1；
  7. --invert 生成深色变体（#000000/#FFFFFF 互换，对齐 iOS colorInvertIfNeeded）。

几何判据 path_bounds()：给 Tester/iOSDev 的比对工具复用 —— 必须吃**相对坐标**
（h/v/a/...）并按 SVG 规范端点→圆心公式算弧（圆心候选角点按 sweep 判），
「找字面量」「无脑 ±r」两种偷懒都会误报（2026-11-01 两次假红根因）。

用法：
  svg2vectordrawable.py <input.svg> <output.xml> [--invert]
  svg2vectordrawable.py --self-check    # 正反用例：正=元素全落位+两层平移+rect 转换+底板包围盒；
                                        # 反=<circle> 必须拒、绝对不该出现的字面量判据必须失效

团队纪律：转换完成后 a) 报提交 hash 并 `git show <hash>:<path>` 自证关键值；
b) 交双工具（形状清单 + 几何比对）对**当前 HEAD**复核，别各自挑提交。
"""

import argparse
import math
import re
import sys
import xml.etree.ElementTree as ET

UNSUPPORTED_DRAWABLES = {
    "circle", "ellipse", "line", "polygon", "polyline", "text", "image",
    "use", "marker", "pattern",
}


class ConversionError(Exception):
    pass


def _local(tag):
    return tag.split("}")[-1] if "}" in tag else tag


def _num(v):
    s = f"{float(v):.3f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


# ---------------------------------------------------------------------------
# pathData 几何：相对坐标展开 + 圆弧端点→圆心（Tester 假红的两个根因的正解）
# ---------------------------------------------------------------------------

_TOKEN = re.compile(r"([MmLlHhVvCcSsQqTtAaZz])|(-?\d*\.?\d+(?:[eE][-+]?\d+)?)")


def _arc_points(x1, y1, rx, ry, phi_deg, fa, fs, x2, y2, samples=64):
    """SVG 规范 F.6 端点→圆心参数化；返回弧上采样点（含轴向极值候选，圆心按 sweep 判）。"""
    if rx == 0 or ry == 0 or (x1 == x2 and y1 == y2):
        return [(x2, y2)]
    phi = math.radians(phi_deg)
    cos_p, sin_p = math.cos(phi), math.sin(phi)
    dx2, dy2 = (x1 - x2) / 2, (y1 - y2) / 2
    x1p = cos_p * dx2 + sin_p * dy2
    y1p = -sin_p * dx2 + cos_p * dy2
    rx, ry = abs(rx), abs(ry)
    lam = x1p ** 2 / rx ** 2 + y1p ** 2 / ry ** 2
    if lam > 1:
        s = math.sqrt(lam)
        rx *= s
        ry *= s
    num = rx ** 2 * ry ** 2 - rx ** 2 * y1p ** 2 - ry ** 2 * x1p ** 2
    den = rx ** 2 * y1p ** 2 + ry ** 2 * x1p ** 2
    c = math.sqrt(max(0.0, num / den)) if den else 0.0
    if fa == fs:
        c = -c  # 圆心二选一由 large-arc 与 sweep 共同决定 —— 不是无脑 ±r
    cxp = c * rx * y1p / ry
    cyp = -c * ry * x1p / rx
    cx = cos_p * cxp - sin_p * cyp + (x1 + x2) / 2
    cy = sin_p * cxp + cos_p * cyp + (y1 + y2) / 2

    def ang(ux, uy, vx, vy):
        dot = ux * vx + uy * vy
        n = math.hypot(ux, uy) * math.hypot(vx, vy)
        a = math.acos(max(-1.0, min(1.0, dot / n))) if n else 0.0
        return -a if ux * vy - uy * vx < 0 else a

    th1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dth = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not fs and dth > 0:
        dth -= 2 * math.pi
    if fs and dth < 0:
        dth += 2 * math.pi

    cand = [th1 + dth * i / samples for i in range(samples + 1)]
    # 轴向极值候选：椭圆参数角 0/π/2/π…按 2π 周期折进弧区间（真包围盒的关键）
    lo, hi = min(th1, th1 + dth), max(th1, th1 + dth)
    for k in range(4):
        base = k * math.pi / 2
        for rep in range(-2, 3):
            a = base + rep * 2 * math.pi
            if lo - 1e-9 <= a <= hi + 1e-9:
                cand.append(a)
    pts = []
    for t in cand:
        px = cos_p * rx * math.cos(t) - sin_p * ry * math.sin(t) + cx
        py = sin_p * rx * math.cos(t) + cos_p * ry * math.sin(t) + cy
        pts.append((px, py))
    return pts


def _bez_points(p0, p1, p2, p3, n=32):
    """三次贝塞尔 on-curve 采样（近似包围盒；验证用途足够，非控制点外包围）。"""
    out = []
    for i in range(n + 1):
        t = i / n
        u = 1 - t
        x = u ** 3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t ** 3 * p3[0]
        y = u ** 3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t ** 3 * p3[1]
        out.append((x, y))
    return out


def _quad_points(p0, p1, p2, n=32):
    out = []
    for i in range(n + 1):
        t = i / n
        u = 1 - t
        x = u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0]
        y = u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1]
        out.append((x, y))
    return out


def path_bounds(d):
    """pathData → (min_x, max_x, min_y, max_y)。支持全命令集，弧走端点→圆心。"""
    toks = [(m.group(1), m.group(2)) for m in _TOKEN.finditer(d)]
    i = 0
    cmd = None
    cx = cy = 0.0
    start = (0.0, 0.0)
    last_ctrl = None  # S/T 平滑控制点
    pts = []
    nums = []

    def take(n):
        nonlocal i, nums
        vals = [float(toks[i + k][1]) for k in range(n)]
        i += n
        return vals

    while i < len(toks):
        letter, val = toks[i]
        if letter:
            cmd = letter
            i += 1
            if cmd in "Zz":
                pts.append(start)
                cx, cy = start
                continue
            nums = []
        if cmd is None:
            raise ConversionError("pathData 以坐标开头，缺初始命令")
        rel = cmd.islower()
        C = cmd.upper()
        if C == "M":
            x, y = take(2)
            if rel:
                x, y = cx + x, cy + y
            pts.append((x, y))
            start = (x, y)
            cx, cy = x, y
            cmd = "m" if rel else "M"  # 后续对视为 L
        elif C == "L":
            x, y = take(2)
            if rel:
                x, y = cx + x, cy + y
            pts.append((x, y))
            cx, cy = x, y
        elif C == "H":
            x, = take(1)
            x = cx + x if rel else x
            pts.append((x, cy))
            cx = x
        elif C == "V":
            y, = take(1)
            y = cy + y if rel else y
            pts.append((cx, y))
            cy = y
        elif C == "C":
            (a, b, c2, d2, e, f2) = take(6)
            if rel:
                a, b, c2, d2, e, f2 = cx + a, cy + b, cx + c2, cy + d2, cx + e, cy + f2
            pts += _bez_points((cx, cy), (a, b), (c2, d2), (e, f2))
            cx, cy = e, f2
        elif C == "S":
            (a, b, e, f2) = take(4)
            if rel:
                a, b, e, f2 = cx + a, cy + b, cx + e, cy + f2
            c2, d2 = (2 * cx - last_ctrl[0], 2 * cy - last_ctrl[1]) if last_ctrl else (cx, cy)
            pts += _bez_points((cx, cy), (c2, d2), (a, b), (e, f2))
            last_ctrl = (a, b)
            cx, cy = e, f2
        elif C == "Q":
            (a, b, e, f2) = take(4)
            if rel:
                a, b, e, f2 = cx + a, cy + b, cx + e, cy + f2
            pts += _quad_points((cx, cy), (a, b), (e, f2))
            last_ctrl = (a, b)
            cx, cy = e, f2
        elif C == "T":
            (e, f2) = take(2)
            if rel:
                e, f2 = cx + e, cy + f2
            a, b = (2 * cx - last_ctrl[0], 2 * cy - last_ctrl[1]) if last_ctrl else (cx, cy)
            pts += _quad_points((cx, cy), (a, b), (e, f2))
            last_ctrl = (a, b)
            cx, cy = e, f2
        elif C == "A":
            (rx, ry, rot, fa, fs, x, y) = take(7)
            if rel:
                x, y = cx + x, cy + y
            pts += _arc_points(cx, cy, rx, ry, rot, fa, fs, x, y)
            cx, cy = x, y
        elif C == "Z":
            pts.append(start)
            cx, cy = start
        if C not in ("S", "T"):
            if C not in ("C", "Q"):
                last_ctrl = None
    if not pts:
        raise ConversionError("pathData 无坐标")
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    return min(xs), max(xs), min(ys), max(ys)


# ---------------------------------------------------------------------------
# 转换
# ---------------------------------------------------------------------------

def _rect_to_path_data(rect):
    def f(v):
        return _num(v)

    x, y = float(rect.get("x", 0)), float(rect.get("y", 0))
    w, h = float(rect.get("width")), float(rect.get("height"))
    if w <= 0 or h <= 0:
        raise ConversionError(f"<rect> 尺寸非法: w={w} h={h}")
    rx = float(rect.get("rx", 0) or 0)
    ry = float(rect.get("ry", 0) or 0)
    r = max(rx, ry)
    if r > 0:
        r = min(r, w / 2, h / 2)
        return (
            f"M{f(x + r)},{f(y)}h{f(w - 2 * r)}a{f(r)},{f(r)} 0 0 1 {f(r)},{f(r)}"
            f"v{f(h - 2 * r)}a{f(r)},{f(r)} 0 0 1 -{f(r)},{f(r)}h-{f(w - 2 * r)}"
            f"a{f(r)},{f(r)} 0 0 1 -{f(r)},-{f(r)}v-{f(h - 2 * r)}"
            f"a{f(r)},{f(r)} 0 0 1 {f(r)},-{f(r)}z"
        )
    return f"M{f(x)},{f(y)}h{f(w)}v{f(h)}h-{f(w)}z"


def _esc(d):
    return d.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def convert(svg_bytes: bytes, invert: bool):
    """返回 (xml_text, 输入可绘制元素数, 输出 pathData 数)。"""
    root = ET.fromstring(svg_bytes)
    if _local(root.tag) != "svg":
        raise ConversionError("根元素不是 <svg>")
    vb = root.get("viewBox")
    if not vb:
        raise ConversionError("缺 viewBox（无法做 minX/minY 归一）")
    vb_x, vb_y, vb_w, vb_h = [float(v) for v in vb.split()]
    if vb_w <= 0 or vb_h <= 0:
        raise ConversionError("viewBox 宽高非法")

    def color(fill, where):
        if not fill:
            raise ConversionError(
                f"{where} 缺 fill —— VectorDrawable <group> 不继承 fill，"
                "group 级颜色会静默丢成默认黑"
            )
        if not invert:
            return fill
        swaps = {"#000000": "#FFFFFF", "#000": "#FFF",
                 "#FFFFFF": "#000000", "#FFF": "#000"}
        return swaps.get(fill.strip().lower(), fill)

    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- 由 svg2vectordrawable.py 生成（dsh-mobile 资产工具；元素数断言见脚本头）。 -->",
        f'<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="{_num(vb_w)}dp"',
        f'    android:height="{_num(vb_h)}dp"',
        f'    android:viewportWidth="{_num(vb_w)}"',
        f'    android:viewportHeight="{_num(vb_h)}">',
    ]
    count = 0

    def walk(el, depth):
        nonlocal count
        ind = "    " * (depth + 1)
        for child in el:
            tag = _local(child.tag)
            if tag == "path":
                d = child.get("d")
                if not d:
                    raise ConversionError("<path> 缺 d")
                count += 1
                lines.append(f'{ind}<path android:fillColor="{color(child.get("fill"), "<path>")}" '
                             f'android:pathData="{_esc(d)}" />')
            elif tag == "rect":
                count += 1
                lines.append(f'{ind}<path android:fillColor="{color(child.get("fill"), "<rect>")}" '
                             f'android:pathData="{_esc(_rect_to_path_data(child))}" />')
            elif tag == "g":
                tf = (child.get("transform") or "").strip()
                if tf:
                    m = re.match(r"translate\(([-\d.]+)(?:[, ]([-\d.]+))?\)$", tf)
                    if not m:
                        raise ConversionError(
                            f"<g> transform 不是纯 translate（{tf}）—— scale/rotate/matrix 请先在源展开"
                        )
                    tx, ty = m.group(1), m.group(2)
                    attrs = f' android:translateX="{tx}"' if tx not in ("0", "") else ""
                    if ty and ty != "0":
                        attrs += f' android:translateY="{ty}"'
                    lines.append(f"{ind}<group{attrs}>")
                    walk(child, depth + 1)
                    lines.append(f"{ind}</group>")
                else:
                    walk(child, depth)  # 无 transform 的 g = 透明容器
            elif tag in UNSUPPORTED_DRAWABLES:
                raise ConversionError(f"不支持的元素 <{tag}>（请先在源 SVG 展开为 path）")
            elif tag in ("defs", "clipPath", "mask", "title", "desc"):
                continue
            else:
                raise ConversionError(f"未知元素 <{tag}>，请人工确认后扩展工具")

    if abs(vb_x) > 1e-9 or abs(vb_y) > 1e-9:
        tx = f' android:translateX="{_num(-vb_x)}"' if abs(vb_x) > 1e-9 else ""
        ty = f' android:translateY="{_num(-vb_y)}"' if abs(vb_y) > 1e-9 else ""
        lines.append(f"    <group{tx}{ty}>")
        walk(root, 1)
        lines.append("    </group>")
    else:
        walk(root, 0)
    lines.append("</vector>")
    xml_text = "\n".join(lines) + "\n"

    # ===== 硬断言（trio 缺陷兜底）：SVG 可绘制元素总数 == 输出 pathData 数 =====
    input_count = sum(1 for e in root.iter() if _local(e.tag) in ("path", "rect"))
    for e in root.iter():
        if _local(e.tag) in UNSUPPORTED_DRAWABLES:
            raise ConversionError(f"不支持的元素 <{_local(e.tag)}>")
    out_root = ET.fromstring(xml_text)
    out_count = sum(1 for e in out_root.iter() if _local(e.tag) == "path")
    if out_count != input_count:
        raise ConversionError(
            f"断言失败：SVG 可绘制元素 {input_count} 个，输出 pathData {out_count} 条 —— 有元素被丢"
        )
    return xml_text, input_count, out_count


# ---------------------------------------------------------------------------
# 自检（正反两用例；反向 = 本次 trio 的两个陷阱必须各自变红）
# ---------------------------------------------------------------------------

SELF_SVG = (b'<svg xmlns="http://www.w3.org/2000/svg" viewBox="25 0 163 24">'
            b'<path d="M33.1445 18.745Q31.97 18.745 30.9695 18.3535L33.1445 8.305Z" fill="#000000"/>'
            b'<path d="M40.138 18.6V10.857H41.8635V18.6Z" fill="#000000"/>'
            b'<rect x="107.39" y="5.5" width="52" height="14" rx="2" fill="#000000"/>'
            b'<g transform="translate(-21.959,0)">'
            b'<path d="M132.848 8.93205H134.08V16.137H132.848Z" fill="#FFFFFF"/>'
            b'</g></svg>')

PLATE_D = ("M109.39,5.5h48a2,2 0 0 1 2,2v10a2,2 0 0 1 -2,2h-48"
           "a2,2 0 0 1 -2,-2v-10a2,2 0 0 1 2,-2z")
BADGE_D = "M132.848 8.93205H134.08V16.137H132.848Z"


def self_check():
    failures = []

    def check(name, cond, detail=""):
        print(f"[{'PASS' if cond else 'FAIL'}] {name}" + (f" —— {detail}" if detail else ""))
        if not cond:
            failures.append(name)

    # 正向 1：转换全落位 + 两层平移 + rect 转换
    xml_text, n_in, n_out = convert(SELF_SVG, invert=False)
    check("元素数断言", n_in == 4 and n_out == 4, f"in={n_in} out={n_out}")
    check("viewBox minX 归一", 'android:translateX="-25"' in xml_text)
    check("内层 group 平移", 'android:translateX="-21.959"' in xml_text)
    check("rect→圆角 path", 'M109.39,5.5h48a2,2 0 0 1 2,2' in xml_text)
    check("--invert 换色", convert(SELF_SVG, invert=True)[0].count('fillColor="#FFFFFF"') == 4,
          "3 黑→白 + 1 白保持 = 4")

    # 正向 2：几何判据（相对坐标 + 圆弧圆心）—— Tester 两次假红的场景
    bx = path_bounds(PLATE_D)
    check("底板包围盒（相对坐标+h/v/a）",
          abs(bx[0] - 107.39) < 1e-6 and abs(bx[1] - 159.39) < 1e-6 and
          abs(bx[2] - 5.5) < 1e-6 and abs(bx[3] - 19.5) < 1e-6,
          f"实测 {tuple(round(v, 4) for v in bx)}")
    bx2 = path_bounds(BADGE_D)
    check("负样本（徽章字母 ≠ 底板）",
          abs(bx2[0] - 132.848) < 1e-3 and abs(bx2[1] - 134.08) < 1e-3)

    # 反向 1：不支持的 <circle> 必须拒
    bad = SELF_SVG.replace(b'<rect x="107.39" y="5.5" width="52" height="14" rx="2" fill="#000000"/>',
                           b'<circle cx="130" cy="12" r="7" fill="#000000"/>')
    try:
        convert(bad, invert=False)
        check("反向：<circle> 被拒", False, "被静默放过")
    except ConversionError as e:
        check("反向：<circle> 被拒", "circle" in str(e), str(e))

    # 反向 2：字面量找坐标的判据方式必须失效（教育性断言：底板 path 里不存在 '107.39'）
    check("反向：字面量判据对相对坐标失效（'107.39' 不在 pathData 里）",
          "107.39" not in PLATE_D)

    print("self-check:", "全部通过" if not failures else f"失败 {failures}")
    return 0 if not failures else 1


def main():
    ap = argparse.ArgumentParser(description="SVG → Android VectorDrawable（元素数断言 + 几何判据）")
    ap.add_argument("svg", nargs="?")
    ap.add_argument("out", nargs="?")
    ap.add_argument("--invert", action="store_true",
                    help="#000000/#FFFFFF 互换（深色变体，对齐 iOS colorInvertIfNeeded）")
    ap.add_argument("--self-check", action="store_true")
    ap.add_argument("--bounds", metavar="PATHDATA",
                    help="打印一段 pathData 的包围盒（min_x,max_x,min_y,max_y）——比对工具复用")
    args = ap.parse_args()
    if args.self_check:
        sys.exit(self_check())
    if args.bounds:
        try:
            b = path_bounds(args.bounds)
        except ConversionError as e:
            print(f"FAIL: {e}", file=sys.stderr)
            sys.exit(1)
        print(",".join(_num(v) for v in b))
        return
    if not args.svg or not args.out:
        ap.error("需要 <input.svg> <output.xml>，或 --self-check / --bounds")
    try:
        xml_text, n_in, n_out = convert(open(args.svg, "rb").read(), args.invert)
    except ConversionError as e:
        print(f"FAIL: {e}", file=sys.stderr)
        sys.exit(1)
    open(args.out, "w").write(xml_text)
    print(f"OK: 可绘制元素 {n_in} → pathData {n_out}（断言过）→ {args.out}")


if __name__ == "__main__":
    main()
