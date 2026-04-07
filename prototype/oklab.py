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
