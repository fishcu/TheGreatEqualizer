"""OKLab: linear-light BGR ↔ OKLab conversions, zone-weighted chroma offsets.

Coefficients from Björn Ottosson's OKLab derivation (same as CSS Color 4).
Input/output images use OpenCV BGR channel order.  All arrays are float32.
"""

from __future__ import annotations

import numpy as np

# M1: linear sRGB (R,G,B) → pre-nonlinearity LMS
_M1 = np.array([
    [0.4122214708, 0.5363325363, 0.0514459929],
    [0.2119034982, 0.6806995451, 0.1073969566],
    [0.0883024619, 0.2817188376, 0.6299787005],
], dtype=np.float32)

# M2: cube-root LMS → OKLab (L, a, b)
_M2 = np.array([
    [0.2104542553, 0.7936177850, -0.0040720468],
    [1.9779984951, -2.4285922050, 0.4505937099],
    [0.0259040371, 0.7827717662, -0.8086757660],
], dtype=np.float32)

_M1_INV = np.array([
    [+4.0767416621, -3.3077115913, +0.2309699292],
    [-1.2684380046, +2.6097574011, -0.3413193965],
    [-0.0041960863, -0.7034186147, +1.7076147010],
], dtype=np.float32)

_M2_INV = np.array([
    [1.0, +0.3963377774, +0.2158037573],
    [1.0, -0.1055613458, -0.0638541728],
    [1.0, -0.0894841775, -1.2914855480],
], dtype=np.float32)


def linear_bgr_to_oklab(bgr: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Linear-light BGR float32 image → OKLab (L, a, b) as contiguous planes."""
    rgb = bgr[..., ::-1]
    lms = rgb @ _M1.T
    lms_g = np.cbrt(lms)
    lab = lms_g @ _M2.T
    return lab[..., 0].copy(), lab[..., 1].copy(), lab[..., 2].copy()


def oklab_to_linear_bgr(L: np.ndarray, a: np.ndarray, b_ok: np.ndarray) -> np.ndarray:
    """OKLab (L, a, b) planes → linear-light BGR float32 image."""
    lab = np.stack((L, a, b_ok), axis=-1)
    lms_g = lab @ _M2_INV.T
    lms = lms_g ** 3
    rgb = lms @ _M1_INV.T
    return np.ascontiguousarray(rgb[..., ::-1])


def zone_weights_okl(
    L: np.ndarray,
    center_shadow: float = 0.22,
    center_mid: float = 0.50,
    center_highlight: float = 0.78,
    sigma: float = 0.17,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Smooth Gaussian bumps on OKLab L, normalized to sum to 1 per pixel."""
    inv_2s2 = np.float32(-0.5 / (sigma * sigma))
    cs = np.float32(center_shadow)
    cm = np.float32(center_mid)
    ch = np.float32(center_highlight)
    w_s = np.exp(inv_2s2 * (L - cs) ** 2)
    w_m = np.exp(inv_2s2 * (L - cm) ** 2)
    w_h = np.exp(inv_2s2 * (L - ch) ** 2)
    w_sum = w_s + w_m + w_h + np.float32(1e-12)
    return w_s / w_sum, w_m / w_sum, w_h / w_sum


def chroma_offset_ab(
    L: np.ndarray,
    a: np.ndarray,
    b_ok: np.ndarray,
    theta_shadow_rad: float,
    str_shadow: float,
    theta_mid_rad: float,
    str_mid: float,
    theta_hi_rad: float,
    str_hi: float,
    center_shadow: float = 0.22,
    center_mid: float = 0.50,
    center_highlight: float = 0.78,
    sigma: float = 0.17,
) -> tuple[np.ndarray, np.ndarray]:
    """Add (a,b) offsets = sum_k w_k(L) * str_k * (cos theta_k, sin theta_k).

    *L* should be the lightness used for weighting (output L after tone map).
    """
    w_s, w_m, w_h = zone_weights_okl(
        L, center_shadow, center_mid, center_highlight, sigma,
    )
    da_s = np.float32(str_shadow * np.cos(theta_shadow_rad))
    db_s = np.float32(str_shadow * np.sin(theta_shadow_rad))
    da_m = np.float32(str_mid * np.cos(theta_mid_rad))
    db_m = np.float32(str_mid * np.sin(theta_mid_rad))
    da_h = np.float32(str_hi * np.cos(theta_hi_rad))
    db_h = np.float32(str_hi * np.sin(theta_hi_rad))
    ca = w_s * da_s + w_m * da_m + w_h * da_h
    sb = w_s * db_s + w_m * db_m + w_h * db_h
    return a + ca, b_ok + sb
