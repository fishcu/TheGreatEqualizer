"""
TheGreatEqualizer – Python prototype.

Usage:
    python main.py <image_path>
"""

from PySide6.QtWidgets import (
    QApplication,
    QLabel,
    QMainWindow,
    QPushButton,
    QSlider,
    QVBoxLayout,
    QHBoxLayout,
    QWidget,
)
from PySide6.QtGui import QImage, QPixmap
from PySide6.QtCore import QEvent, QRect, QSize, Qt, QTimer, Signal
from matplotlib.backends.backend_qtagg import FigureCanvasQTAgg
import matplotlib.figure
from oklab import (
    chroma_offset_ab,
    linear_bgr_to_oklab,
    oklab_to_linear_bgr,
    zone_weights_okl,
)
from fit_params import fit_initial_params
import numpy as np
import matplotlib
import cv2
from pathlib import Path
import sys
import os

# Matplotlib's Qt backend must match PySide6; otherwise FigureCanvas subclasses
# PyQt6.QtWidgets.QWidget and setCentralWidget rejects it.
os.environ.setdefault("QT_API", "pyside6")


matplotlib.use("qtagg")


def bgr_to_qimage(img: np.ndarray) -> QImage:
    """Convert a BGR uint8 numpy array to QImage."""
    rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
    h, w, ch = rgb.shape
    return QImage(rgb.data, w, h, ch * w, QImage.Format.Format_RGB888).copy()


NUM_BINS = 256


def clamp_window_top_left(available: QRect, x: int, y: int, w: int, h: int) -> tuple[int, int]:
    """Clamp top-left so a w×h window stays inside *available* (e.g. primaryScreen().availableGeometry())."""
    left = available.left()
    top = available.top()
    x_max = left + available.width() - w
    y_max = top + available.height() - h
    return max(left, min(x, x_max)), max(top, min(y, y_max))


def srgb_eotf(v: np.ndarray) -> np.ndarray:
    """sRGB electro-optical transfer function (sRGB → linear)."""
    return np.where(
        v <= 0.04045, v / 12.92, np.power((v + 0.055) / 1.055, 2.4),
    ).astype(v.dtype, copy=False)


def srgb_oetf(v: np.ndarray) -> np.ndarray:
    """sRGB opto-electronic transfer function (linear → sRGB)."""
    return np.where(
        v <= 0.0031308, 12.92 * v, 1.055 * np.power(v, 1.0 / 2.4) - 0.055,
    ).astype(v.dtype, copy=False)


def compute_histogram(channel: np.ndarray, num_bins: int = NUM_BINS) -> np.ndarray:
    """Histogram of a single float [0,1] channel. Returns shape (num_bins,)."""
    return np.histogram(channel, bins=num_bins, range=(0.0, 1.0))[0].astype(np.float64)


def cap_histogram(hist: np.ndarray, cap: float) -> np.ndarray:
    """Cap histogram bins at *cap* and redistribute excess locally.

    Each over-cap bin's excess is spread outward symmetrically, one distance
    layer at a time.  At each layer, the deposit is split proportionally to
    available room on each side, so there is no directional bias.
    """
    if cap <= 0.0 or hist.max() <= cap:
        return hist.copy()

    out = hist.astype(np.float64).copy()
    n = len(out)

    for i in range(n):
        if out[i] <= cap:
            continue
        surplus = out[i] - cap
        out[i] = cap
        lo, hi = i - 1, i + 1
        while surplus > 1e-12 and (lo >= 0 or hi < n):
            room_lo = (cap - out[lo]) if lo >= 0 and out[lo] < cap else 0.0
            room_hi = (cap - out[hi]) if hi < n and out[hi] < cap else 0.0
            total_room = room_lo + room_hi
            if total_room > 0.0:
                to_place = min(surplus, total_room)
                frac_lo = room_lo / total_room
                deposit_lo = to_place * frac_lo
                deposit_hi = to_place - deposit_lo
                if lo >= 0:
                    out[lo] += deposit_lo
                if hi < n:
                    out[hi] += deposit_hi
                surplus -= to_place
            lo -= 1
            hi += 1

    return out


def compute_target_cdf(
    x: np.ndarray,
    t: float,
    s: float,
    c: float,
    g: float,
) -> np.ndarray:
    """Evaluate the parametric target CDF at positions *x* (in [0, 1]).

    The curve h(x) is a piecewise power function joined at *c*,
    shaped by *t* (shadow exponent) and *s* (highlight exponent), with
    continuity ensured by the alpha/beta weighting.  Always maps
    [0, 1] → [0, 1]; black/white deltas are applied separately.
    """
    alpha = s * c / (s * c + t * (1.0 - c))
    beta = 1.0 - alpha

    h = np.where(
        x <= c,
        alpha * np.power(x / c, t),
        1.0 - beta * np.power((1.0 - x) / (1.0 - c), s),
    )
    return np.clip(np.power(h, g), 0.0, 1.0)


def apply_l_channel_cdf(
    L: np.ndarray,
    capped_hist: np.ndarray,
    t: float,
    s: float,
    c: float,
    g: float,
    black: float,
    white: float,
) -> np.ndarray:
    """Histogram specification on scalar OKLab L (input clipped to [0, 1] for LUT).

    The CDF transfer always maps into [0, 1].  Black/white deltas then
    linearly remap the output range to [black, 1 + white].
    """
    bin_centers = np.linspace(0.0, 1.0, NUM_BINS)
    target_x = np.linspace(0.0, 1.0, 4096)
    target_y = compute_target_cdf(target_x, t, s, c, g)
    input_cdf = np.cumsum(capped_hist)
    total = input_cdf[-1]
    if total > 0:
        input_cdf /= total
    transfer = np.interp(input_cdf, target_y, target_x)
    L_in = np.clip(L, 0.0, 1.0)
    L_mapped = np.interp(L_in, bin_centers, transfer)
    L_out = black + L_mapped * (1.0 + white - black)
    return np.clip(L_out, 0.0, 1.0).astype(L.dtype)


# ---------------------------------------------------------------------------
# Widgets
# ---------------------------------------------------------------------------


class ImageViewer(QMainWindow):
    """Resizable window that displays an image at correct aspect ratio.

    Supports an alternate image shown while the mouse button is held down.
    """

    def __init__(self, title: str, *, quit_on_close: bool = False) -> None:
        super().__init__()
        self.setWindowTitle(title)
        self._quit_on_close = quit_on_close
        self._label = QLabel(alignment=Qt.AlignmentFlag.AlignCenter)
        self._label.setMinimumSize(QSize(160, 120))
        self.setCentralWidget(self._label)
        self._pixmap = QPixmap()
        self._alt_pixmap = QPixmap()
        self._showing_alt = False

    def show_image(self, img: np.ndarray) -> None:
        self._pixmap = QPixmap.fromImage(bgr_to_qimage(img))
        if not self._showing_alt:
            self._refresh()

    def set_alt_image(self, img: np.ndarray) -> None:
        self._alt_pixmap = QPixmap.fromImage(bgr_to_qimage(img))

    def mousePressEvent(self, event) -> None:  # noqa: N802
        if not self._alt_pixmap.isNull():
            self._showing_alt = True
            self._refresh()
        super().mousePressEvent(event)

    def mouseReleaseEvent(self, event) -> None:  # noqa: N802
        if self._showing_alt:
            self._showing_alt = False
            self._refresh()
        super().mouseReleaseEvent(event)

    def closeEvent(self, event) -> None:  # noqa: N802
        if self._quit_on_close:
            QApplication.instance().quit()
        super().closeEvent(event)

    def resizeEvent(self, event) -> None:  # noqa: N802
        super().resizeEvent(event)
        self._refresh()

    def _refresh(self) -> None:
        active = self._alt_pixmap if self._showing_alt else self._pixmap
        if active.isNull():
            return
        scaled = active.scaled(
            self._label.size(),
            Qt.AspectRatioMode.KeepAspectRatio,
            Qt.TransformationMode.SmoothTransformation,
        )
        self._label.setPixmap(scaled)


class HistogramPlot(QMainWindow):
    """Window with raw and capped OKLab L histograms stacked vertically."""

    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("Histograms (OKLab L)")
        self._fig = matplotlib.figure.Figure(figsize=(6, 4), tight_layout=True)
        self._ax_raw, self._ax_cap = self._fig.subplots(2, 1, sharex=True)
        self._canvas = FigureCanvasQTAgg(self._fig)
        self.setCentralWidget(self._canvas)
        self._bins = np.linspace(0.0, 1.0, NUM_BINS)

    def update(
        self,
        raw_hist: np.ndarray,
        capped_hist: np.ndarray,
    ) -> None:
        for ax, hist, title in (
            (self._ax_raw, raw_hist, "Raw L"),
            (self._ax_cap, capped_hist, "Capped L"),
        ):
            ax.clear()
            ax.plot(self._bins, hist, color="black", linewidth=0.8, alpha=0.85)
            ax.set_xlim(0, 1)
            ax.set_ylabel("count")
            ax.set_title(title, fontsize=9)
        self._ax_cap.set_xlabel("OKLab L (clipped to [0,1] for bins)")
        self._canvas.draw_idle()


class CdfPlot(QMainWindow):
    """Window with input L CDF and parametric target CDF side by side."""

    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("CDFs (OKLab L)")
        self._fig = matplotlib.figure.Figure(figsize=(8, 4), tight_layout=True)
        self._ax_in, self._ax_tgt = self._fig.subplots(1, 2)
        self._canvas = FigureCanvasQTAgg(self._fig)
        self.setCentralWidget(self._canvas)
        self._hist_bins = np.linspace(0.0, 1.0, NUM_BINS)
        self._cdf_x = np.linspace(0.0, 1.0, 1024)

    def update(
        self,
        capped_hist: np.ndarray,
        t: float,
        s: float,
        c: float,
        g: float,
    ) -> None:
        ax_in = self._ax_in
        ax_in.clear()
        cdf = np.cumsum(capped_hist)
        total = cdf[-1]
        if total > 0:
            cdf /= total
        ax_in.plot(self._hist_bins, cdf, color="black",
                   linewidth=0.9, alpha=0.85)
        ax_in.plot([0, 1], [0, 1], color="gray", linewidth=0.5, linestyle="--")

        above_zero = cdf > 0.0
        below_one = cdf < 1.0
        if above_zero.any() and below_one.any():
            x_lo = self._hist_bins[int(np.argmax(above_zero))]
            x_hi = self._hist_bins[
                len(cdf) - 1 - int(np.argmax(below_one[::-1]))
            ]
            for xv in (x_lo, x_hi):
                ax_in.axvline(xv, color="steelblue", linewidth=0.7,
                              linestyle="--", alpha=0.8)
            ax_in.set_title(
                f"Input CDF (L)  trim [{x_lo:.2f}, {x_hi:.2f}]", fontsize=9,
            )
        else:
            ax_in.set_title("Input CDF (L)", fontsize=9)

        ax_in.set_xlim(0, 1)
        ax_in.set_ylim(0, 1)
        ax_in.set_aspect("equal")
        ax_in.set_xlabel("L")
        ax_in.set_ylabel("CDF")

        ax_tgt = self._ax_tgt
        ax_tgt.clear()
        target = compute_target_cdf(self._cdf_x, t, s, c, g)
        ax_tgt.plot(self._cdf_x, target, color="darkgreen",
                    linewidth=1.1, alpha=0.9)
        ax_tgt.plot([0, 1], [0, 1], color="gray",
                    linewidth=0.5, linestyle="--")
        ax_tgt.set_xlim(0, 1)
        ax_tgt.set_ylim(0, 1)
        ax_tgt.set_aspect("equal")
        ax_tgt.set_xlabel("L")
        ax_tgt.set_title("Target CDF (L)", fontsize=9)

        self._canvas.draw_idle()


class ZoneDebugWindow(QMainWindow):
    """Debug window: zone weight curves + per-zone masked images."""

    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("Zone Debug (Shadows / Mids / Highlights)")
        self._fig = matplotlib.figure.Figure(figsize=(10, 6), tight_layout=True)
        gs = self._fig.add_gridspec(2, 3, height_ratios=[3, 1])
        self._axes_img = [self._fig.add_subplot(gs[0, i]) for i in range(3)]
        self._ax_curves = self._fig.add_subplot(gs[1, :])
        self._canvas = FigureCanvasQTAgg(self._fig)
        self.setCentralWidget(self._canvas)
        self._curve_x = np.linspace(0.0, 1.0, 256)

    def update(
        self,
        out_bgr_u8: np.ndarray,
        w_s: np.ndarray,
        w_m: np.ndarray,
        w_h: np.ndarray,
        sigma: float = 0.17,
    ) -> None:
        zones = (
            (w_s, "Shadows"),
            (w_m, "Midtones"),
            (w_h, "Highlights"),
        )
        for ax, (w, title) in zip(self._axes_img, zones):
            ax.clear()
            ax.imshow(w, aspect="auto", interpolation="bilinear",
                      cmap="inferno", vmin=0.0, vmax=1.0)
            ax.set_title(title, fontsize=9, fontweight="bold")
            ax.set_xticks([])
            ax.set_yticks([])

        ax_c = self._ax_curves
        ax_c.clear()
        ws_c, wm_c, wh_c = zone_weights_okl(self._curve_x, sigma=sigma)
        ax_c.plot(self._curve_x, ws_c, color="steelblue", linewidth=1.2,
                  label="Shadows")
        ax_c.plot(self._curve_x, wm_c, color="forestgreen", linewidth=1.2,
                  label="Midtones")
        ax_c.plot(self._curve_x, wh_c, color="orangered", linewidth=1.2,
                  label="Highlights")
        ax_c.set_xlim(0, 1)
        ax_c.set_ylim(0, 1.05)
        ax_c.set_xlabel("OKLab L (output)", fontsize=8)
        ax_c.set_ylabel("Weight", fontsize=8)
        ax_c.set_title("Zone weight curves", fontsize=9)
        ax_c.legend(fontsize=8, loc="upper right")
        ax_c.grid(alpha=0.3)

        self._canvas.draw_idle()


class LabeledSlider(QWidget):
    """Horizontal slider with a name label and live value display."""

    value_changed = Signal(float)

    def __init__(
        self,
        name: str,
        min_val: float = 0.0,
        max_val: float = 1.0,
        default: float = 0.5,
        steps: int = 1000,
    ) -> None:
        super().__init__()
        self._min = min_val
        self._max = max_val
        self._steps = steps
        self._default = default

        self._name_label = QLabel(name)
        self._value_label = QLabel()
        self._slider = QSlider(Qt.Orientation.Horizontal)
        self._slider.setRange(0, steps)
        self._slider.setValue(self._float_to_tick(default))
        self._update_value_label(self._slider.value())
        self._slider.valueChanged.connect(self._on_changed)
        self._slider.installEventFilter(self)

        row = QHBoxLayout()
        row.addWidget(self._name_label)
        row.addWidget(self._slider, stretch=1)
        row.addWidget(self._value_label)
        row.setContentsMargins(0, 0, 0, 0)
        self.setLayout(row)

    @property
    def val(self) -> float:
        return self._tick_to_float(self._slider.value())

    def set_val(self, v: float) -> None:
        self._slider.setValue(self._float_to_tick(v))

    def _tick_to_float(self, tick: int) -> float:
        return self._min + (self._max - self._min) * tick / self._steps

    def _float_to_tick(self, v: float) -> int:
        return round((v - self._min) / (self._max - self._min) * self._steps)

    def _update_value_label(self, tick: int) -> None:
        self._value_label.setText(f"{self._tick_to_float(tick):.3f}")

    def eventFilter(self, obj, event) -> bool:  # noqa: N802
        if obj is self._slider and event.type() == QEvent.Type.MouseButtonDblClick:
            self._slider.setValue(self._float_to_tick(self._default))
            return True
        return super().eventFilter(obj, event)

    def _on_changed(self, tick: int) -> None:
        self._update_value_label(tick)
        self.value_changed.emit(self._tick_to_float(tick))


class ControlsPanel(QWidget):
    """Separate window holding luminance CDF sliders and zone chroma offsets."""

    params_changed = Signal()
    fit_requested = Signal()

    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("Controls")

        layout = QVBoxLayout()

        self.fit_btn = QPushButton("Fit to input L CDF")
        layout.addWidget(self.fit_btn)
        self.fit_btn.clicked.connect(self.fit_requested.emit)

        self.cap_frac = LabeledSlider("cap", 0.0, 1.0, 1.0)
        layout.addWidget(self.cap_frac)
        self.cap_frac.value_changed.connect(
            lambda _: self.params_changed.emit())

        lum_label = QLabel("Luminance (target CDF on OKLab L)")
        layout.addWidget(lum_label)

        self.t = LabeledSlider("t", 0.01, 5.0, 1.0)
        self.s = LabeledSlider("s", 0.01, 5.0, 1.0)
        self.c = LabeledSlider("c", 0.01, 0.99, 0.5)
        self.g = LabeledSlider("g", 0.1, 3.0, 1.0)
        self.black = LabeledSlider("black", -0.2, 0.2, 0.0)
        self.white = LabeledSlider("white", -0.2, 0.2, 0.0)

        for slider in (self.t, self.s, self.c, self.g, self.black, self.white):
            layout.addWidget(slider)
            slider.value_changed.connect(lambda _: self.params_changed.emit())

        chrom_label = QLabel(
            "Chroma (OKLab a,b): angle deg + strength; weights use output L"
        )
        layout.addWidget(chrom_label)

        self.sh_angle = LabeledSlider(
            "shadow angle", 0.0, 360.0, 0.0, steps=3600)
        self.sh_str = LabeledSlider("shadow str", 0.0, 0.25, 0.0)
        self.mid_angle = LabeledSlider(
            "mid angle", 0.0, 360.0, 0.0, steps=3600)
        self.mid_str = LabeledSlider("mid str", 0.0, 0.25, 0.0)
        self.hi_angle = LabeledSlider(
            "highlight angle", 0.0, 360.0, 0.0, steps=3600)
        self.hi_str = LabeledSlider("highlight str", 0.0, 0.25, 0.0)
        self.zone_sigma = LabeledSlider("zone sigma", 0.05, 0.50, 0.17)

        for slider in (
            self.sh_angle, self.sh_str,
            self.mid_angle, self.mid_str,
            self.hi_angle, self.hi_str,
            self.zone_sigma,
        ):
            layout.addWidget(slider)
            slider.value_changed.connect(lambda _: self.params_changed.emit())

        layout.addStretch()
        self.setLayout(layout)


# ---------------------------------------------------------------------------
# App
# ---------------------------------------------------------------------------


class App:
    def __init__(self, src_bgr: np.ndarray) -> None:
        self._src_bgr = src_bgr
        src_linear = srgb_eotf(src_bgr.astype(np.float32) / 255.0)
        self._L, self._a, self._b_ok = linear_bgr_to_oklab(src_linear)
        self._raw_hist_L = compute_histogram(np.clip(self._L, 0.0, 1.0))

        screen = QApplication.primaryScreen().availableGeometry()
        default_w = min(src_bgr.shape[1], int(screen.width() * 0.55))
        default_h = min(src_bgr.shape[0], int(screen.height() * 0.65))

        margin = 12
        right_x = screen.x() + margin + default_w + margin

        self.viewer = ImageViewer("Output", quit_on_close=True)
        self.viewer.resize(default_w, default_h)
        self.viewer.move(
            *clamp_window_top_left(
                screen, screen.x() + margin, screen.y() + margin, default_w, default_h,
            ),
        )

        ctrl_w, ctrl_h = 440, 520
        self.controls = ControlsPanel()
        self.controls.resize(ctrl_w, ctrl_h)
        self.controls.move(
            *clamp_window_top_left(screen, right_x,
                                   screen.y() + margin, ctrl_w, ctrl_h),
        )
        self.controls.params_changed.connect(self._update)
        self.controls.fit_requested.connect(self._on_fit_requested)

        self._plot_timer = QTimer(self.controls)
        self._plot_timer.setSingleShot(True)
        self._plot_timer.timeout.connect(self._flush_plots)
        self._plot_delay_ms = 60
        self._plot_payload: tuple[
            np.ndarray, np.ndarray, float, float, float, float,
        ] | None = None
        self._zone_payload: tuple[
            np.ndarray, np.ndarray, np.ndarray, np.ndarray, float,
        ] | None = None

        hist_w, hist_h = 500, 350
        self.hist_plot = HistogramPlot()
        self.hist_plot.resize(hist_w, hist_h)
        y_plots = screen.y() + margin + ctrl_h + margin
        self.hist_plot.move(
            *clamp_window_top_left(screen, right_x, y_plots, hist_w, hist_h),
        )

        cdf_w, cdf_h = 500, 300
        self.cdf_plot = CdfPlot()
        self.cdf_plot.resize(cdf_w, cdf_h)
        # Stacking hist + cdf vertically often exceeds available height; place cdf beside hist when it fits.
        cdf_x_side = right_x + hist_w + margin
        fits_beside_hist = cdf_x_side + cdf_w <= screen.x() + screen.width() - margin
        if fits_beside_hist:
            cdf_x, cdf_y = cdf_x_side, y_plots
        else:
            cdf_x, cdf_y = right_x, y_plots + hist_h + margin
        self.cdf_plot.move(
            *clamp_window_top_left(screen, cdf_x, cdf_y, cdf_w, cdf_h),
        )

        zone_w, zone_h = 700, 450
        self.zone_debug = ZoneDebugWindow()
        self.zone_debug.resize(zone_w, zone_h)
        zone_y = screen.y() + margin + default_h + margin
        self.zone_debug.move(
            *clamp_window_top_left(
                screen, screen.x() + margin, zone_y, zone_w, zone_h,
            ),
        )

        self.viewer.show()
        self.hist_plot.show()
        self.cdf_plot.show()
        self.zone_debug.show()
        self.controls.show()

        self._fit_and_apply_params()
        self.viewer.set_alt_image(self._src_bgr)
        self._update(sync_plots=True)

    def _on_fit_requested(self) -> None:
        self._fit_and_apply_params()
        self._update(sync_plots=True)

    def _fit_and_apply_params(self) -> None:
        """Run the optimizer on OKLab L and push fitted values into shape sliders."""
        cap_frac = self.controls.cap_frac.val
        global_max = self._raw_hist_L.max()
        cap = cap_frac * global_max
        capped_L = cap_histogram(self._raw_hist_L, cap)

        fitted = fit_initial_params(capped_L)

        ctrl = self.controls
        ctrl.blockSignals(True)
        ctrl.t.set_val(fitted["t"])
        ctrl.s.set_val(fitted["s"])
        ctrl.c.set_val(fitted["c"])
        ctrl.g.set_val(fitted["g"])
        ctrl.blockSignals(False)

    def _flush_plots(self) -> None:
        if self._plot_payload is not None:
            rh, cl, t, s, c, g = self._plot_payload
            self.hist_plot.update(rh, cl)
            self.cdf_plot.update(cl, t, s, c, g)
        if self._zone_payload is not None:
            self.zone_debug.update(*self._zone_payload)

    def _update(self, *, sync_plots: bool = False) -> None:
        cap_frac = self.controls.cap_frac.val
        global_max = self._raw_hist_L.max()
        cap = cap_frac * global_max
        capped_L = cap_histogram(self._raw_hist_L, cap)

        ctrl = self.controls
        t = ctrl.t.val
        s = ctrl.s.val
        c = ctrl.c.val
        g = ctrl.g.val
        black = ctrl.black.val
        white = ctrl.white.val

        L_out = apply_l_channel_cdf(
            self._L, capped_L, t, s, c, g, black, white)

        sigma = ctrl.zone_sigma.val
        w_s, w_m, w_h = zone_weights_okl(L_out, sigma=sigma)

        deg2rad = np.pi / 180.0
        a2, b2 = chroma_offset_ab(
            L_out,
            self._a,
            self._b_ok,
            ctrl.sh_angle.val * deg2rad,
            ctrl.sh_str.val,
            ctrl.mid_angle.val * deg2rad,
            ctrl.mid_str.val,
            ctrl.hi_angle.val * deg2rad,
            ctrl.hi_str.val,
            sigma=sigma,
        )

        out_bgr = oklab_to_linear_bgr(L_out, a2, b2)
        out_bgr = np.clip(out_bgr, 0.0, 1.0)
        out_enc = srgb_oetf(out_bgr)
        out_u8 = (out_enc * 255.0).astype(np.uint8)

        self.viewer.show_image(out_u8)
        self._plot_payload = (
            self._raw_hist_L, capped_L, t, s, c, g,
        )
        self._zone_payload = (out_u8, w_s, w_m, w_h, sigma)
        if sync_plots:
            self._plot_timer.stop()
            self._flush_plots()
        else:
            self._plot_timer.start(self._plot_delay_ms)


def main() -> None:
    if len(sys.argv) < 2:
        print(__doc__.strip(), file=sys.stderr)
        sys.exit(1)

    path = Path(sys.argv[1])
    src = cv2.imread(str(path), cv2.IMREAD_COLOR)
    assert src is not None, f"Failed to load image: {path}"

    qt_app = QApplication(sys.argv[:1])
    app = App(src)
    _ = app
    sys.exit(qt_app.exec())


if __name__ == "__main__":
    main()
