"""BO Sat vintage skin: prepare the background from the user's reference image (satv/full.png).

    python make_sat_background.py [dir]    (default satv2) -> <dir>/bg2x.png, <dir>/geom.json
    <dir>/config.json: approx knob centres, lamp centres, chart mapping, button box, device size in units

- measures the six knob caps (circle fit on the cap edge) and their pointer bars
- paints the pointer bars out (they are drawn turning by an overlay)
- replaces the lit S lamp by a copy of the unlit T lamp (the lit lamp is drawn by an overlay)
- fits the lit lamp's colours as a polynomial in the lamp's unit disc, for the overlay
- writes everything at 2x of the device size (102 x 53 grid units = 408 x 212 px -> 816 x 424)
"""
import json
import sys
from pathlib import Path

import cv2
import numpy as np
from scipy import ndimage, optimize

HERE = Path(__file__).resolve().parent
D = HERE / (sys.argv[1] if len(sys.argv) > 1 else "satv2")
CFG = json.loads((D / "config.json").read_text())
OUT_W, OUT_H = CFG["units"][0] * 8, CFG["units"][1] * 8     # 2x of the device size

PORTS = ["curve", "tone", "asym", "mix", "drive", "output"]
APPROX = CFG["knobs"]
LAMPS = CFG["lamps"]
# chart: image px of input / output amplitude a (x) and b (y): x = x0 + (x1 - x0) * (a - a0) / (a1 - a0) etc.
CHART = CFG["chart"]
BUTTONS = CFG["buttons"]


def cap_circle(lum, cx, cy):
    c0 = ndimage.map_coordinates(lum, [[cy + 10], [cx + 10]], order=1)[0]
    thr = c0 * 0.55
    pts = []
    for a in np.radians(np.arange(0, 360, 4)):
        if np.cos(a) < -0.6:                      # the pointer points left
            continue
        for r in np.arange(15, 60, 0.25):
            x, y = cx + r * np.cos(a), cy + r * np.sin(a)
            if ndimage.map_coordinates(lum, [[y], [x]], order=1)[0] < thr:
                pts.append((x, y))
                break
    p = np.array(pts)
    q = optimize.least_squares(lambda q: np.hypot(p[:, 0] - q[0], p[:, 1] - q[1]) - q[2], [cx, cy, 37]).x
    return [float(v) for v in q]


def main():
    img = cv2.imread(str(D / "full.png"), cv2.IMREAD_COLOR)
    H, W = img.shape[:2]
    lum = img.astype(np.float32).mean(2)
    yy, xx = np.mgrid[0:H, 0:W]
    work = img.copy()
    geom = {"src_size": [W, H], "knobs": {}, "chart": CHART, "buttons": BUTTONS}
    mask_all = np.zeros((H, W), np.uint8)
    bar_cols = []
    for port, (ax, ay) in zip(PORTS, APPROX):
        cx, cy, r = cap_circle(lum, ax, ay)
        inside = np.hypot(xx - cx, yy - cy) < r - 0.5
        cap_lum = np.median(lum[inside & (xx > cx + 5)])
        bar = inside & (lum < cap_lum * 0.55) & (xx < cx + 4) & (np.abs(yy - cy) < 7)
        lab, n = ndimage.label(bar)
        big = np.argmax(ndimage.sum(bar, lab, range(1, n + 1))) + 1
        bar = lab == big
        ys, xs = np.nonzero(bar)
        bar_cols.append(img[bar].mean(0)[::-1])
        geom["knobs"][port] = {"c": [cx, cy], "r": r,
                               "bar_y": [float(ys.min()), float(ys.max() + 1)],
                               "bar_x": [float(xs.min()), float(xs.max() + 1)]}
        m = cv2.dilate(bar.astype(np.uint8), np.ones((5, 5), np.uint8))
        m &= (np.hypot(xx - cx, yy - cy) < r + 1).astype(np.uint8)
        mask_all |= m
        print(f"{port:7s} c=({cx:.1f},{cy:.1f}) r={r:.2f} bar x {xs.min()}..{xs.max()} y {ys.min()}..{ys.max()}")
    work = cv2.inpaint(work, mask_all * 255, 4, cv2.INPAINT_TELEA)
    # away from the centre the cap is close to rotationally symmetric: fill the bar's band by
    # interpolating along the circle between the pixels just above and just below it
    src = work.astype(np.float32)          # bar already gone; the band edges are untouched pixels
    for port in PORTS:
        k = geom["knobs"][port]
        cx, cy = k["c"]
        r = k["r"]
        by = (k["bar_y"][0] + k["bar_y"][1]) / 2
        hw = (k["bar_y"][1] - k["bar_y"][0]) / 2 + 3.0
        rr = np.hypot(xx - cx, yy - cy)
        band = (np.abs(yy - by) < hw) & (xx > cx - r - 4) & (xx < cx) & (rr > 7)
        y1, y2 = by - hw - 0.5, by + hw + 0.5
        ys, xs = np.nonzero(band)
        for y, x in zip(ys, xs):
            rad = rr[y, x]
            if rad <= max(abs(y1 - cy), abs(y2 - cy)) + 0.5:
                continue
            cols = []
            for yb in (y1, y2):
                xb = cx - np.sqrt(rad * rad - (yb - cy) ** 2)
                cols.append(np.array([ndimage.map_coordinates(src[..., c], [[yb], [xb]], order=1)[0] for c in range(3)]))
            u = (y - y1) / (y2 - y1)
            w = 1.0 if rad > 11 else (rad - 7) / 4
            work[y, x] = np.clip(w * ((1 - u) * cols[0] + u * cols[1]) + (1 - w) * src[y, x], 0, 255).round().astype(np.uint8)
    bc = np.array(bar_cols)
    geom["bar_rgb"] = [float(v) / 255 for v in np.median(bc[:4], 0)]     # cream knobs: dark grey bar
    hw = np.median([(k["bar_y"][1] - k["bar_y"][0]) / 2 for k in geom["knobs"].values()])
    geom["bar_half_width"] = float(hw)

    # lit lamp model: polynomial (degree 6) per channel on the unit disc
    (sx, sy), (tx, ty) = LAMPS[0], LAMPS[1]
    R = 9.6
    sel = np.hypot(xx - sx, yy - sy) < R
    u = (xx[sel] - sx) / R
    v = (yy[sel] - sy) / R
    terms = [(i, j) for i in range(7) for j in range(7 - i)]
    A = np.stack([u ** i * v ** j for i, j in terms], 1)
    rgb = img[sel][:, ::-1].astype(np.float64) / 255
    coef = np.linalg.lstsq(A, rgb, rcond=None)[0]
    err = np.abs(A @ coef - rgb).mean()
    print(f"lamp fit: {len(terms)} terms, mean abs error {err:.4f}")
    geom["lamp"] = {"centres": [list(c) for c in LAMPS], "r": R, "terms": terms, "coef": coef.T.tolist()}

    # unlit lamp copied over the lit one (feathered)
    pr = 13
    patch = work[int(ty) - pr - 2:int(ty) + pr + 3, int(tx) - pr - 2:int(tx) + pr + 3].astype(np.float32)
    M = np.float32([[1, 0, sx - tx], [0, 1, sy - ty]])
    moved = cv2.warpAffine(work.astype(np.float32), M, (W, H), flags=cv2.INTER_LINEAR)
    d = np.hypot(xx - sx, yy - sy)
    a = np.clip((pr - d) / 3.0, 0, 1)[..., None]
    work = (work.astype(np.float32) * (1 - a) + moved * a).round().clip(0, 255).astype(np.uint8)
    del patch

    # second state of the switch: the changed part of the second image, aligned, with a soft alpha edge
    sw_patch = sw_box = None
    if "switch" in CFG:
        sw = CFG["switch"]
        other = cv2.imread(str(D / sw["image"]), cv2.IMREAD_COLOR)
        M = np.float32([[1, 0, sw["shift"][0]], [0, 1, sw["shift"][1]]])
        al = cv2.warpAffine(other, M, (W, H), flags=cv2.WARP_INVERSE_MAP | cv2.INTER_LINEAR,
                            borderMode=cv2.BORDER_REPLICATE)
        x0, y0, x1, y1 = sw["box"]
        diff = cv2.GaussianBlur(np.abs(img.astype(np.float32) - al.astype(np.float32)).mean(2), (0, 0), 2)
        m = np.zeros((H, W), np.float32)
        m[y0:y1, x0:x1] = (diff[y0:y1, x0:x1] > 6).astype(np.float32)
        m = cv2.dilate(m, np.ones((7, 7), np.uint8))
        m = cv2.GaussianBlur(m, (0, 0), 1.5)
        m[:y0, :] = 0; m[y1:, :] = 0; m[:, :x0] = 0; m[:, x1:] = 0
        sx2, sy2 = OUT_W / W, OUT_H / H
        # patch at 2x on a whole-unit grid (8 px at 2x): find the unit box around the source box
        ux0, uy0 = int(np.floor(x0 * sx2 / 8)), int(np.floor(y0 * sy2 / 8))
        ux1, uy1 = int(np.ceil(x1 * sx2 / 8)), int(np.ceil(y1 * sy2 / 8))
        rgba = np.dstack([al[..., ::-1], (m * 255).round().astype(np.uint8)])
        big = cv2.resize(rgba, (OUT_W, OUT_H), interpolation=cv2.INTER_AREA)
        patch = big[uy0 * 8:uy1 * 8, ux0 * 8:ux1 * 8]
        from PIL import Image as _I
        _I.fromarray(patch, "RGBA").save(D / "switch_on2x.png")
        sw_patch, sw_box = patch, (ux0, uy0, ux1, uy1)
        geom["switch"] = {"units": [ux0, uy0, ux1 - ux0, uy1 - uy0], "box": [x0, y0, x1, y1]}
        print("switch patch units", geom["switch"]["units"])
    geom["sweep"] = CFG.get("sweep", 180)
    out = cv2.resize(work, (OUT_W, OUT_H), interpolation=cv2.INTER_AREA)
    if "switch" in CFG and CFG["switch"].get("on") == "base":
        # the reference image (full.png) shows the switch ON: the background gets the other image's
        # switch (OFF), and the ON patch is the reference's own switch area, opaque
        ux0, uy0, ux1, uy1 = sw_box
        region = out[uy0 * 8:uy1 * 8, ux0 * 8:ux1 * 8].astype(np.float32)          # BGR
        a = sw_patch[..., 3:4].astype(np.float32) / 255
        off = sw_patch[..., 2::-1].astype(np.float32)                              # RGB -> BGR
        on = np.dstack([region[..., ::-1], np.full(region.shape[:2], 255, np.float32)]).round().astype(np.uint8)
        from PIL import Image as _I
        _I.fromarray(on, "RGBA").save(D / "switch_on2x.png")
        out[uy0 * 8:uy1 * 8, ux0 * 8:ux1 * 8] = (region * (1 - a) + off * a).round().clip(0, 255).astype(np.uint8)
        print("switch: background OFF (other image), patch ON (reference image)")
    cv2.imwrite(str(D / "bg2x.png"), out)
    cv2.imwrite(str(D / "bg_full_clean.png"), work)
    (D / "geom.json").write_text(json.dumps(geom, indent=1))
    print("written", D / "bg2x.png")


if __name__ == "__main__":
    sys.exit(main())
