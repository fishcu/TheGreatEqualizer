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


_CDF_CENTERS = np.array([1.0 / 6.0, 1.0 / 2.0, 5.0 / 6.0], dtype=np.float32)
_CDF_SIGMA = np.float32(0.2)
_CDF_INV_2S2 = np.float32(-0.5 / (_CDF_SIGMA * _CDF_SIGMA))


def zone_weights_cdf(
    L: np.ndarray,
    cdf_bins: np.ndarray,
    cdf_values: np.ndarray,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Zone weights from fixed Gaussians in CDF space.

    Maps each pixel's L through the output CDF to a uniform [0, 1] rank,
    then applies three Gaussians centered at 1/6, 1/2, 5/6 with fixed
    sigma.  Adapts automatically to any L distribution with zero tunable
    parameters.
    """
    u = np.interp(np.clip(L, 0.0, 1.0), cdf_bins,
                  cdf_values).astype(np.float32)
    w_s = np.exp(_CDF_INV_2S2 * (u - _CDF_CENTERS[0]) ** 2)
    w_m = np.exp(_CDF_INV_2S2 * (u - _CDF_CENTERS[1]) ** 2)
    w_h = np.exp(_CDF_INV_2S2 * (u - _CDF_CENTERS[2]) ** 2)
    w_sum = w_s + w_m + w_h + np.float32(1e-12)
    return w_s / w_sum, w_m / w_sum, w_h / w_sum


def build_gamut_lut(n_L: int = 256, n_h: int = 360) -> np.ndarray:
    """Precompute max OKLab chroma for sRGB at each (L, hue) via bisection.

    Returns a float32 array of shape (n_L, n_h) where entry [i, j] is the
    largest chroma C such that (L_i, C·cos h_j, C·sin h_j) maps to linear
    RGB in [0, 1].
    """
    L_vals = np.linspace(0.0, 1.0, n_L, dtype=np.float32)
    h_vals = np.linspace(0.0, 2.0 * np.pi, n_h,
                         endpoint=False, dtype=np.float32)
    L_grid, h_grid = np.meshgrid(L_vals, h_vals, indexing="ij")
    cos_h = np.cos(h_grid)
    sin_h = np.sin(h_grid)

    lo = np.zeros((n_L, n_h), dtype=np.float32)
    hi = np.full((n_L, n_h), 0.5, dtype=np.float32)

    for _ in range(20):
        mid = (lo + hi) * 0.5
        lab = np.stack((L_grid, mid * cos_h, mid * sin_h), axis=-1)
        rgb = (lab @ _M2_INV.T) ** 3 @ _M1_INV.T
        in_gamut = np.all((rgb >= -1e-6) & (rgb <= 1.0 + 1e-6), axis=-1)
        lo = np.where(in_gamut, mid, lo)
        hi = np.where(in_gamut, hi, mid)

    return lo


def _lookup_gamut_max(
    L: np.ndarray,
    h: np.ndarray,
    gamut_lut: np.ndarray,
) -> np.ndarray:
    """Bilinear interpolation of max chroma from the pre-computed gamut LUT.

    *h* is the hue angle in radians (wraps automatically).
    """
    n_L, n_h = gamut_lut.shape
    li_f = np.clip(L, 0.0, 1.0) * (n_L - 1)
    hi_f = h * (n_h / (2.0 * np.pi))

    li0 = np.floor(li_f).astype(np.intp)
    li1 = np.minimum(li0 + 1, n_L - 1)
    fl = (li_f - li0).astype(np.float32)

    hi0 = np.floor(hi_f).astype(np.intp) % n_h
    hi1 = (hi0 + 1) % n_h
    fh = (hi_f - np.floor(hi_f)).astype(np.float32)

    return (
        gamut_lut[li0, hi0] * (1 - fl) * (1 - fh)
        + gamut_lut[li0, hi1] * (1 - fl) * fh
        + gamut_lut[li1, hi0] * fl * (1 - fh)
        + gamut_lut[li1, hi1] * fl * fh
    )


def gamut_clamp_ab(
    L: np.ndarray,
    a: np.ndarray,
    b_ok: np.ndarray,
    gamut_lut: np.ndarray,
) -> tuple[np.ndarray, np.ndarray]:
    """Reduce chroma to stay inside the sRGB gamut while preserving L and hue."""
    C = np.sqrt(a * a + b_ok * b_ok)
    h = np.arctan2(b_ok, a) % (2.0 * np.pi)
    max_C = _lookup_gamut_max(L, h, gamut_lut)
    C_safe = np.maximum(C, 1e-10)
    scale = np.where(C > 1e-10, np.minimum(max_C / C_safe, 1.0), 1.0)
    return a * scale, b_ok * scale


def relative_chroma(
    L: np.ndarray,
    a: np.ndarray,
    b_ok: np.ndarray,
    gamut_lut: np.ndarray,
) -> tuple[np.ndarray, np.ndarray]:
    """Per-pixel chroma as a fraction of the sRGB gamut boundary.

    Returns (C_rel, h) where C_rel = C / C_max(L, h) clipped to [0, 1]
    and h is the hue angle in radians.
    """
    C = np.sqrt(a * a + b_ok * b_ok)
    h = np.arctan2(b_ok, a) % (2.0 * np.pi)
    C_max = _lookup_gamut_max(L, h, gamut_lut)
    C_rel = np.where(C_max > 1e-10, C /
                     np.maximum(C_max, 1e-10), np.float32(0.0))
    return np.clip(C_rel, 0.0, 1.0).astype(np.float32), h.astype(np.float32)


def reconstruct_ab(
    C_rel: np.ndarray,
    h: np.ndarray,
    L: np.ndarray,
    gamut_lut: np.ndarray,
) -> tuple[np.ndarray, np.ndarray]:
    """Reconstruct OKLab (a, b) from relative chroma, hue, and lightness."""
    C = C_rel * _lookup_gamut_max(L, h, gamut_lut)
    return (C * np.cos(h)).astype(np.float32), (C * np.sin(h)).astype(np.float32)


def chroma_offset_ab(
    a: np.ndarray,
    b_ok: np.ndarray,
    w_s: np.ndarray,
    w_m: np.ndarray,
    w_h: np.ndarray,
    theta_shadow_rad: float,
    str_shadow: float,
    theta_mid_rad: float,
    str_mid: float,
    theta_hi_rad: float,
    str_hi: float,
) -> tuple[np.ndarray, np.ndarray]:
    """Add (a,b) offsets = sum_k w_k * str_k * (cos theta_k, sin theta_k)."""
    da_s = np.float32(str_shadow * np.cos(theta_shadow_rad))
    db_s = np.float32(str_shadow * np.sin(theta_shadow_rad))
    da_m = np.float32(str_mid * np.cos(theta_mid_rad))
    db_m = np.float32(str_mid * np.sin(theta_mid_rad))
    da_h = np.float32(str_hi * np.cos(theta_hi_rad))
    db_h = np.float32(str_hi * np.sin(theta_hi_rad))
    ca = w_s * da_s + w_m * da_m + w_h * da_h
    sb = w_s * db_s + w_m * db_m + w_h * db_h
    return a + ca, b_ok + sb
