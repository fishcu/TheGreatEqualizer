#!/usr/bin/env python3
"""
TheGreatEqualizer Logo Generator
=================================
pip install svgwrite

Outputs:
  tge_logo.svg                   — SVG preview (open in browser)
  ic_launcher_foreground.xml     — Android VectorDrawable foreground
  ic_launcher_background.xml     — Android VectorDrawable background
  ic_launcher.xml                — Android Adaptive Icon wrapper

Adjust PARAMS dict and re-run.
"""

import svgwrite, math, os

# ============================================================
# TUNABLE PARAMETERS — edit these and re-run
# ============================================================
PARAMS = {
    # Canvas & dial
    "full_size": 2048,
    "output_size": 1024,
    "dial_radius_frac": 0.37,

    # Colors
    "navy": "#131b2e",
    "warm_white": "#f8f3eb",

    # Line weights (fractions of full_size)
    "dial_w_frac": 0.04,           # dial circle outline
    "line_w_frac": 0.022,           # hour marks
    "hand_w_frac": 0.03,

    # Hour marks
    "mark_margin_frac": 0.022,
    "mark_length_frac": 0.09,
    "double_mark_sep_frac": 0.015,

    # Hand (S-curve Bezier)
    "hand_end_angle_deg": 44,       # clock angle (0=12, 90=3, 45=1:30)
    "hand_end_r_frac": 1.25,         # tip distance as multiple of dial radius
    "hand_bulge_start_frac": 0.35,  # curvature near center (0=straight, 0.5=very curvy)
    "hand_bulge_end_frac": 0.25,    # curvature near tip (0=straight, 0.5=very curvy)
    "hand_cp_dist_frac": 0.4,       # control point distance along hand

    # Hand outline (bright color behind hand for clean separation)
    "hand_outline_w_frac": 0.045,   # outline width (wider than hand)

    # Arrowhead
    "arrow_size_frac": 0.045,
    "arrow_length_mult": 1.8,
    "arrow_width_mult": 0.55,
    "arrow_offset_frac": 0.06,      # how far arrow tip extends past hand endpoint
    "arrow_round_frac": 0.008,      # corner rounding radius (fraction of full_size)

    # Center dot
    "center_dark_r_frac": 0.035,
    "center_bright_r_frac": 0.015,

    # Crop (top-right quadrant)
    "crop_size_frac": 0.53,
    "crop_center_x": 0.16,           # dial center X position in crop (0=left, 1=right)
    "crop_center_y": 0.83,           # dial center Y position in crop (0=top, 1=bottom)

    # Android adaptive icon extra margin (fraction per side, e.g. 0.15 = 15%)
    "android_margin_frac": 0.30,
}


def generate(p=PARAMS, svg_path="tge_logo.svg"):
    S = p["full_size"]
    CX, CY = S / 2, S / 2
    R = S * p["dial_radius_frac"]
    NAVY = p["navy"]
    WHITE = p["warm_white"]
    DW = S * p["dial_w_frac"]
    LW = S * p["line_w_frac"]
    HW = S * p["hand_w_frac"]
    HOW = S * p["hand_outline_w_frac"]
    MARGIN = S * p["mark_margin_frac"]
    MLEN = S * p["mark_length_frac"]
    DSEP = S * p["double_mark_sep_frac"]

    # Compute crop region
    cw = int(S * p["crop_size_frac"])
    ch = cw
    cl = max(0, int(CX - cw * p["crop_center_x"]))
    ct = max(0, int(CY - ch * p["crop_center_y"]))
    cr = min(S, cl + cw)
    cb = min(S, ct + ch)
    out_size = p["output_size"]

    # Output SVG is the cropped region
    dwg = svgwrite.Drawing(svg_path, size=(f'{out_size}px', f'{out_size}px'),
                            viewBox=f'{cl} {ct} {cr-cl} {cb-ct}')

    # Background
    dwg.add(dwg.rect(insert=(cl, ct), size=(cr-cl, cb-ct), fill=WHITE))

    # Dial circle
    dwg.add(dwg.circle(center=(CX, CY), r=R, fill='none', stroke=NAVY, stroke_width=DW))

    # Hour marks
    for i in range(12):
        angle = math.radians(i * 30 - 90)
        outer_r = R - DW / 2 - MARGIN
        inner_r = outer_r - MLEN

        if i == 0:
            px, py = -math.sin(angle), math.cos(angle)
            for sign in [-1, 1]:
                ox = CX + outer_r * math.cos(angle) + sign * DSEP * px
                oy = CY + outer_r * math.sin(angle) + sign * DSEP * py
                ix = CX + inner_r * math.cos(angle) + sign * DSEP * px
                iy = CY + inner_r * math.sin(angle) + sign * DSEP * py
                dwg.add(dwg.line(start=(ox, oy), end=(ix, iy),
                               stroke=NAVY, stroke_width=LW, stroke_linecap='butt'))
        else:
            ox = CX + outer_r * math.cos(angle)
            oy = CY + outer_r * math.sin(angle)
            ix = CX + inner_r * math.cos(angle)
            iy = CY + inner_r * math.sin(angle)
            dwg.add(dwg.line(start=(ox, oy), end=(ix, iy),
                           stroke=NAVY, stroke_width=LW, stroke_linecap='butt'))

    # === S-curve hand ===
    ea = math.radians(p["hand_end_angle_deg"] - 90)
    er = R * p["hand_end_r_frac"]
    ex, ey = CX + er * math.cos(ea), CY + er * math.sin(ea)

    dx, dy = ex - CX, ey - CY
    dist = math.sqrt(dx*dx + dy*dy)
    ux, uy = dx / dist, dy / dist
    ppx, ppy = -uy, ux

    bulge_start = dist * p["hand_bulge_start_frac"]
    bulge_end = dist * p["hand_bulge_end_frac"]
    cpd = dist * p["hand_cp_dist_frac"]

    cp1x = CX + cpd * ux + bulge_start * ppx
    cp1y = CY + cpd * uy + bulge_start * ppy
    cp2x = ex - cpd * ux - bulge_end * ppx
    cp2y = ey - cpd * uy - bulge_end * ppy

    bezier_path = f'M {CX},{CY} C {cp1x},{cp1y} {cp2x},{cp2y} {ex},{ey}'

    # Arrowhead — compute tangent at end, then offset tip beyond hand endpoint
    tx, ty = 3 * (ex - cp2x), 3 * (ey - cp2y)
    tl = math.sqrt(tx**2 + ty**2)
    tx, ty = tx / tl, ty / tl
    asz = S * p["arrow_size_frac"]
    apx, apy = -ty, tx
    offset = S * p["arrow_offset_frac"]

    # Arrow tip is offset beyond the hand endpoint
    tip_x = ex + tx * offset
    tip_y = ey + ty * offset
    # Arrow base sits at approximately the hand endpoint
    bx = tip_x - tx * asz * p["arrow_length_mult"]
    by = tip_y - ty * asz * p["arrow_length_mult"]
    aw = asz * p["arrow_width_mult"]

    arrow_d = f'M {tip_x},{tip_y} L {bx + apx*aw},{by + apy*aw} L {bx - apx*aw},{by - apy*aw} Z'

    # Draw order: outline first (white, wider), then hand (navy), then arrowhead
    # 1. Hand outline (bright, separates from dial)
    dwg.add(dwg.path(d=bezier_path, fill='none', stroke=WHITE, stroke_width=HOW,
                     stroke_linecap='round', stroke_linejoin='round'))
    # 2. Arrow outline
    dwg.add(dwg.path(d=arrow_d, fill=WHITE, stroke=WHITE,
                     stroke_width=HOW * 0.5, stroke_linejoin='round'))
    # 3. Hand (navy)
    dwg.add(dwg.path(d=bezier_path, fill='none', stroke=NAVY, stroke_width=HW,
                     stroke_linecap='round', stroke_linejoin='round'))
    # 4. Arrowhead (navy, rounded corners)
    arrow_round = S * p["arrow_round_frac"]
    dwg.add(dwg.path(d=arrow_d, fill=NAVY, stroke=NAVY,
                     stroke_width=arrow_round * 2, stroke_linejoin='round'))

    # Center dot (dark circle + bright dot)
    dwg.add(dwg.circle(center=(CX, CY), r=S * p["center_dark_r_frac"], fill=NAVY))
    dwg.add(dwg.circle(center=(CX, CY), r=S * p["center_bright_r_frac"], fill=WHITE))

    dwg.save()
    print(f"Saved: {svg_path}")
    print(f"Open in browser to preview. Dial center: x={((CX-cl)/(cr-cl)):.2f} y={((CY-ct)/(cb-ct)):.2f}")


def generate_adaptive_icon(p=PARAMS, out_dir="."):
    S = p["full_size"]
    CX, CY = S / 2, S / 2
    R = S * p["dial_radius_frac"]
    NAVY = p["navy"]
    WHITE = p["warm_white"]
    DW = S * p["dial_w_frac"]
    LW = S * p["line_w_frac"]
    HW = S * p["hand_w_frac"]
    HOW = S * p["hand_outline_w_frac"]
    MARGIN = S * p["mark_margin_frac"]
    MLEN = S * p["mark_length_frac"]
    DSEP = S * p["double_mark_sep_frac"]

    margin = p.get("android_margin_frac", 0.25)
    cw_base = int(S * p["crop_size_frac"])
    # Find the center of the SVG crop viewport (must match generate() exactly)
    cl_base = int(CX - cw_base * p["crop_center_x"])
    ct_base = int(CY - cw_base * p["crop_center_y"])
    svg_center_x = cl_base + cw_base / 2
    svg_center_y = ct_base + cw_base / 2
    # Enlarge crop window and re-center on the same point
    cw = int(cw_base * (1 + 2 * margin))
    cl = int(svg_center_x - cw / 2)
    ct = int(svg_center_y - cw / 2)

    VP = 108.0
    sc = VP / cw

    def vx(x):
        return f"{(x - cl) * sc:.2f}"

    def vy(y):
        return f"{(y - ct) * sc:.2f}"

    def vs(v):
        return f"{v * sc:.2f}"

    paths: list[str] = []

    # Dial circle
    cx_v = (CX - cl) * sc
    cy_v = (CY - ct) * sc
    r_v = R * sc
    paths.append(
        f'  <path\n'
        f'      android:pathData="M {cx_v:.2f},{cy_v - r_v:.2f}'
        f' A {r_v:.2f},{r_v:.2f},0,0,1,{cx_v:.2f},{cy_v + r_v:.2f}'
        f' A {r_v:.2f},{r_v:.2f},0,0,1,{cx_v:.2f},{cy_v - r_v:.2f} Z"\n'
        f'      android:fillColor="#00000000"\n'
        f'      android:strokeColor="{NAVY}"\n'
        f'      android:strokeWidth="{vs(DW)}"/>'
    )

    # Hour marks
    for i in range(12):
        angle = math.radians(i * 30 - 90)
        outer_r = R - DW / 2 - MARGIN
        inner_r = outer_r - MLEN

        if i == 0:
            px, py = -math.sin(angle), math.cos(angle)
            for sign in [-1, 1]:
                ox = CX + outer_r * math.cos(angle) + sign * DSEP * px
                oy = CY + outer_r * math.sin(angle) + sign * DSEP * py
                ix = CX + inner_r * math.cos(angle) + sign * DSEP * px
                iy = CY + inner_r * math.sin(angle) + sign * DSEP * py
                paths.append(
                    f'  <path\n'
                    f'      android:pathData="M {vx(ox)},{vy(oy)} L {vx(ix)},{vy(iy)}"\n'
                    f'      android:fillColor="#00000000"\n'
                    f'      android:strokeColor="{NAVY}"\n'
                    f'      android:strokeWidth="{vs(LW)}"\n'
                    f'      android:strokeLineCap="butt"/>'
                )
        else:
            ox = CX + outer_r * math.cos(angle)
            oy = CY + outer_r * math.sin(angle)
            ix = CX + inner_r * math.cos(angle)
            iy = CY + inner_r * math.sin(angle)
            paths.append(
                f'  <path\n'
                f'      android:pathData="M {vx(ox)},{vy(oy)} L {vx(ix)},{vy(iy)}"\n'
                f'      android:fillColor="#00000000"\n'
                f'      android:strokeColor="{NAVY}"\n'
                f'      android:strokeWidth="{vs(LW)}"\n'
                f'      android:strokeLineCap="butt"/>'
            )

    # S-curve hand
    ea = math.radians(p["hand_end_angle_deg"] - 90)
    er = R * p["hand_end_r_frac"]
    ex, ey = CX + er * math.cos(ea), CY + er * math.sin(ea)

    dx, dy = ex - CX, ey - CY
    dist = math.sqrt(dx * dx + dy * dy)
    ux, uy = dx / dist, dy / dist
    ppx, ppy = -uy, ux

    bulge_start = dist * p["hand_bulge_start_frac"]
    bulge_end = dist * p["hand_bulge_end_frac"]
    cpd = dist * p["hand_cp_dist_frac"]

    cp1x = CX + cpd * ux + bulge_start * ppx
    cp1y = CY + cpd * uy + bulge_start * ppy
    cp2x = ex - cpd * ux - bulge_end * ppx
    cp2y = ey - cpd * uy - bulge_end * ppy

    bezier_d = (
        f"M {vx(CX)},{vy(CY)} C {vx(cp1x)},{vy(cp1y)} "
        f"{vx(cp2x)},{vy(cp2y)} {vx(ex)},{vy(ey)}"
    )

    # Arrowhead
    atx, aty = 3 * (ex - cp2x), 3 * (ey - cp2y)
    atl = math.sqrt(atx ** 2 + aty ** 2)
    atx, aty = atx / atl, aty / atl
    asz = S * p["arrow_size_frac"]
    apx, apy = -aty, atx
    a_offset = S * p["arrow_offset_frac"]

    tip_x = ex + atx * a_offset
    tip_y = ey + aty * a_offset
    bx = tip_x - atx * asz * p["arrow_length_mult"]
    by = tip_y - aty * asz * p["arrow_length_mult"]
    aw = asz * p["arrow_width_mult"]

    arrow_d = (
        f"M {vx(tip_x)},{vy(tip_y)} "
        f"L {vx(bx + apx * aw)},{vy(by + apy * aw)} "
        f"L {vx(bx - apx * aw)},{vy(by - apy * aw)} Z"
    )

    arrow_round = S * p["arrow_round_frac"]

    # 1. Hand outline (white, wide)
    paths.append(
        f'  <path\n'
        f'      android:pathData="{bezier_d}"\n'
        f'      android:fillColor="#00000000"\n'
        f'      android:strokeColor="{WHITE}"\n'
        f'      android:strokeWidth="{vs(HOW)}"\n'
        f'      android:strokeLineCap="round"\n'
        f'      android:strokeLineJoin="round"/>'
    )
    # 2. Arrow outline (white)
    paths.append(
        f'  <path\n'
        f'      android:pathData="{arrow_d}"\n'
        f'      android:fillColor="{WHITE}"\n'
        f'      android:strokeColor="{WHITE}"\n'
        f'      android:strokeWidth="{vs(HOW * 0.5)}"\n'
        f'      android:strokeLineJoin="round"/>'
    )
    # 3. Hand (navy)
    paths.append(
        f'  <path\n'
        f'      android:pathData="{bezier_d}"\n'
        f'      android:fillColor="#00000000"\n'
        f'      android:strokeColor="{NAVY}"\n'
        f'      android:strokeWidth="{vs(HW)}"\n'
        f'      android:strokeLineCap="round"\n'
        f'      android:strokeLineJoin="round"/>'
    )
    # 4. Arrowhead (navy, rounded)
    paths.append(
        f'  <path\n'
        f'      android:pathData="{arrow_d}"\n'
        f'      android:fillColor="{NAVY}"\n'
        f'      android:strokeColor="{NAVY}"\n'
        f'      android:strokeWidth="{vs(arrow_round * 2)}"\n'
        f'      android:strokeLineJoin="round"/>'
    )

    # Center dot
    cdr = S * p["center_dark_r_frac"] * sc
    cbr = S * p["center_bright_r_frac"] * sc
    dark_d = (
        f"M {cx_v:.2f},{cy_v - cdr:.2f} "
        f"A {cdr:.2f},{cdr:.2f},0,0,1,{cx_v:.2f},{cy_v + cdr:.2f} "
        f"A {cdr:.2f},{cdr:.2f},0,0,1,{cx_v:.2f},{cy_v - cdr:.2f} Z"
    )
    bright_d = (
        f"M {cx_v:.2f},{cy_v - cbr:.2f} "
        f"A {cbr:.2f},{cbr:.2f},0,0,1,{cx_v:.2f},{cy_v + cbr:.2f} "
        f"A {cbr:.2f},{cbr:.2f},0,0,1,{cx_v:.2f},{cy_v - cbr:.2f} Z"
    )
    paths.append(f'  <path\n'
                 f'      android:pathData="{dark_d}"\n'
                 f'      android:fillColor="{NAVY}"/>')
    paths.append(f'  <path\n'
                 f'      android:pathData="{bright_d}"\n'
                 f'      android:fillColor="{WHITE}"/>')

    # Foreground vector drawable
    fg_xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="108"\n'
        '    android:viewportHeight="108">\n'
        + '\n'.join(paths) + '\n'
        '</vector>\n'
    )

    # Background vector drawable (solid fill)
    bg_xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="108"\n'
        '    android:viewportHeight="108">\n'
        f'  <path\n'
        f'      android:pathData="M0,0h108v108h-108z"\n'
        f'      android:fillColor="{WHITE}"/>\n'
        '</vector>\n'
    )

    # Adaptive icon wrapper
    launcher_xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@drawable/ic_launcher_background"/>\n'
        '    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n'
        '</adaptive-icon>\n'
    )

    for name, content in [("ic_launcher_foreground.xml", fg_xml),
                          ("ic_launcher_background.xml", bg_xml),
                          ("ic_launcher.xml", launcher_xml)]:
        path = os.path.join(out_dir, name)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(content)
        print(f"Saved: {path}")


if __name__ == "__main__":
    generate()
    generate_adaptive_icon()
