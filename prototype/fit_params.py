"""Fit parametric target-CDF parameters to observed input CDFs.

Uses Adam with numerical gradients — pure numpy, no external optimiser
dependency, directly portable to Kotlin / any other language.
"""

import numpy as np

# Parameter names and their (min, max) bounds, mirroring the UI sliders.
# t, s, and g are angles in radians; π/4 is the neutral identity.
# Bounds chosen so the exponent range matches the original raw-exponent limits.
# Black/white are excluded — they are independent post-CDF deltas, not fitted.
_PI_4 = float(np.pi / 4)
PARAM_NAMES = ("t", "s", "c", "g")
PARAM_BOUNDS = {
    "t": (0.01, 1.37),
    "s": (0.20, 1.56),
    "c": (0.01, 0.99),
    "g": (0.10, 1.25),
}
PARAM_DEFAULTS = {"t": _PI_4, "s": _PI_4, "c": 0.5, "g": _PI_4}


def _toe_angle_to_exp(angle_rad: float) -> float:
    """Toe angle (radians) → power exponent via tan(θ).

    Higher angle → higher exponent → more shadow compression.
    """
    return float(np.tan(angle_rad))


def _shoulder_angle_to_exp(angle_rad: float) -> float:
    """Shoulder angle (radians) → power exponent via cot(θ) = 1/tan(θ).

    Higher angle → lower exponent → brighter / more open highlights.
    """
    return 1.0 / float(np.tan(angle_rad))


def _exp_to_toe_angle(t_exp: float) -> float:
    """Power exponent → toe angle (radians) via atan."""
    return float(np.arctan(t_exp))


def _exp_to_shoulder_angle(s_exp: float) -> float:
    """Power exponent → shoulder angle (radians) via atan(1/exp)."""
    return float(np.arctan(1.0 / s_exp))


def _gamma_angle_to_exp(angle_rad: float) -> float:
    """Gamma angle (radians) → power exponent via tan(θ).

    Higher angle → higher exponent → more gamma contrast.
    """
    return float(np.tan(angle_rad))


def _exp_to_gamma_angle(g_exp: float) -> float:
    """Power exponent → gamma angle (radians) via atan."""
    return float(np.arctan(g_exp))


def compute_target_cdf(
    x: np.ndarray,
    t: float,
    s: float,
    c: float,
    g: float,
) -> np.ndarray:
    """Parametric target CDF — always maps [0, 1] → [0, 1].

    *t*, *s*, and *g* are angles in radians; π/4 is the identity.
    Internally converted to power exponents via tan (toe, gamma) / cot (shoulder).
    """
    t_exp = _toe_angle_to_exp(t)
    s_exp = _shoulder_angle_to_exp(s)
    g_exp = _gamma_angle_to_exp(g)
    alpha = s_exp * c / (s_exp * c + t_exp * (1.0 - c))
    beta = 1.0 - alpha
    h = np.where(
        x <= c,
        alpha * np.power(x / c, t_exp),
        1.0 - beta * np.power((1.0 - x) / (1.0 - c), s_exp),
    )
    return np.clip(np.power(h, g_exp), 0.0, 1.0)


def _pack(params: dict[str, float]) -> np.ndarray:
    return np.array([params[n] for n in PARAM_NAMES])


def _unpack(vec: np.ndarray) -> dict[str, float]:
    return {n: float(vec[i]) for i, n in enumerate(PARAM_NAMES)}


def _clamp_vec(vec: np.ndarray) -> np.ndarray:
    lo = np.array([PARAM_BOUNDS[n][0] for n in PARAM_NAMES])
    hi = np.array([PARAM_BOUNDS[n][1] for n in PARAM_NAMES])
    return np.clip(vec, lo, hi)


def _mse(x: np.ndarray, target_cdf: np.ndarray, params: np.ndarray) -> float:
    p = _unpack(params)
    pred = compute_target_cdf(x, p["t"], p["s"], p["c"], p["g"])
    return float(np.mean((pred - target_cdf) ** 2))


def _numerical_grad(
    x: np.ndarray,
    target_cdf: np.ndarray,
    params: np.ndarray,
    eps: float = 1e-5,
) -> np.ndarray:
    grad = np.zeros_like(params)
    for i in range(len(params)):
        p_plus = params.copy()
        p_minus = params.copy()
        p_plus[i] += eps
        p_minus[i] -= eps
        p_plus = _clamp_vec(p_plus)
        p_minus = _clamp_vec(p_minus)
        grad[i] = (_mse(x, target_cdf, p_plus) - _mse(x, target_cdf,
                   p_minus)) / (p_plus[i] - p_minus[i] + 1e-30)
    return grad


def _fit_single_channel(
    input_cdf: np.ndarray,
    num_bins: int,
    steps: int = 1000,
    lr: float = 0.01,
    beta1: float = 0.9,
    beta2: float = 0.999,
    adam_eps: float = 1e-8,
) -> dict[str, float]:
    """Fit params for one channel's input CDF using Adam."""
    x = np.linspace(0.0, 1.0, num_bins)
    params = _pack(PARAM_DEFAULTS)
    m = np.zeros_like(params)
    v = np.zeros_like(params)

    for step in range(1, steps + 1):
        g = _numerical_grad(x, input_cdf, params)
        m = beta1 * m + (1.0 - beta1) * g
        v = beta2 * v + (1.0 - beta2) * g * g
        m_hat = m / (1.0 - beta1 ** step)
        v_hat = v / (1.0 - beta2 ** step)
        params -= lr * m_hat / (np.sqrt(v_hat) + adam_eps)
        params = _clamp_vec(params)

    return _unpack(params)


_TRIM_EPS = 5e-3


def _trim_cdf(
    cdf: np.ndarray,
    num_bins: int,
    eps: float = _TRIM_EPS,
) -> tuple[np.ndarray, float, float]:
    """Extract the rising portion of a [0, 1]-normalized CDF.

    Removes leading bins where CDF < *eps* and trailing bins where
    CDF > 1 - *eps*, then rescales the remaining range to [0, 1].
    This lets the shape fitter ignore sparse outlier pixels at the
    histogram edges that would otherwise prevent effective trimming.

    Returns (trimmed_cdf, x_lo, x_hi) where x_lo and x_hi are the
    positions in [0, 1] of the trim boundaries.
    """
    bins = np.linspace(0.0, 1.0, num_bins)

    above_lo = cdf > eps
    if not above_lo.any():
        return cdf, 0.0, 1.0
    first = int(np.argmax(above_lo))

    below_hi = cdf < 1.0 - eps
    if not below_hi.any():
        return cdf, 0.0, 1.0
    last = len(cdf) - 1 - int(np.argmax(below_hi[::-1]))

    x_lo = float(bins[first])
    x_hi = float(bins[last])

    trimmed = cdf[first: last + 1]
    lo, hi = trimmed[0], trimmed[-1]
    if hi > lo:
        trimmed = (trimmed - lo) / (hi - lo)
    return trimmed, x_lo, x_hi


def fit_initial_params(
    capped_hist: np.ndarray,
    steps: int = 1000,
    lr: float = 0.01,
) -> dict[str, float]:
    """Fit target-CDF shape parameters to one channel's capped histogram.

    *capped_hist* is a 1D histogram array (e.g. 256 bins on [0, 1]).
    The CDF is trimmed to its rising portion and normalized to [0, 1]
    so the fitter only sees the actual tonal shape.

    Returns a dict with fitted shape values (t, s, c, g) and the trim
    boundaries (x_lo, x_hi) used to remove flat CDF tails.
    """
    cdf = np.cumsum(capped_hist)
    total = cdf[-1]
    if total > 0:
        cdf = cdf / total
    trimmed, x_lo, x_hi = _trim_cdf(cdf, len(cdf))
    result = _fit_single_channel(trimmed, len(trimmed), steps=steps, lr=lr)
    result["x_lo"] = x_lo
    result["x_hi"] = x_hi
    return result
