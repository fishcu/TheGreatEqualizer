"""Fit parametric target-CDF parameters to observed input CDFs.

Uses Adam with numerical gradients — pure numpy, no external optimiser
dependency, directly portable to Kotlin / any other language.
"""

import numpy as np

# Parameter names and their (min, max) bounds, mirroring the UI sliders.
# Black/white are excluded — they are independent post-CDF deltas, not fitted.
PARAM_NAMES = ("t", "s", "c", "g")
PARAM_BOUNDS = {
    "t": (0.01, 5.0),
    "s": (0.01, 5.0),
    "c": (0.01, 0.99),
    "g": (0.1, 3.0),
}
PARAM_DEFAULTS = {"t": 1.0, "s": 1.0, "c": 0.5, "g": 1.0}


def compute_target_cdf(
    x: np.ndarray,
    t: float,
    s: float,
    c: float,
    g: float,
) -> np.ndarray:
    """Parametric target CDF — always maps [0, 1] → [0, 1]."""
    alpha = s * c / (s * c + t * (1.0 - c))
    beta = 1.0 - alpha
    h = np.where(
        x <= c,
        alpha * np.power(x / c, t),
        1.0 - beta * np.power((1.0 - x) / (1.0 - c), s),
    )
    return np.clip(np.power(h, g), 0.0, 1.0)


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


def _trim_cdf(cdf: np.ndarray) -> np.ndarray:
    """Extract the rising portion of a [0, 1]-normalized CDF.

    Removes leading bins stuck at 0 and trailing bins stuck at 1,
    then rescales the remaining range to [0, 1].  This lets the
    shape fitter ignore dead zones at the histogram edges.
    """
    above_zero = cdf > 0.0
    if not above_zero.any():
        return cdf
    first = int(np.argmax(above_zero))

    below_one = cdf < 1.0
    if not below_one.any():
        return cdf
    last = len(cdf) - 1 - int(np.argmax(below_one[::-1]))

    trimmed = cdf[first : last + 1]
    lo, hi = trimmed[0], trimmed[-1]
    if hi > lo:
        trimmed = (trimmed - lo) / (hi - lo)
    return trimmed


def fit_initial_params(
    capped_hist: np.ndarray,
    steps: int = 1000,
    lr: float = 0.01,
) -> dict[str, float]:
    """Fit target-CDF shape parameters to one channel's capped histogram.

    *capped_hist* is a 1D histogram array (e.g. 256 bins on [0, 1]).
    The CDF is trimmed to its rising portion and normalized to [0, 1]
    so the fitter only sees the actual tonal shape.
    Returns a dict mapping param name -> fitted value (t, s, c, g only).
    """
    cdf = np.cumsum(capped_hist)
    total = cdf[-1]
    if total > 0:
        cdf = cdf / total
    cdf = _trim_cdf(cdf)
    return _fit_single_channel(cdf, len(cdf), steps=steps, lr=lr)
