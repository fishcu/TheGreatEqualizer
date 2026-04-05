"""
TheGreatEqualizer – Python prototype.

Usage:
    python main.py <image_path>
"""

import sys
from pathlib import Path

import cv2
import numpy as np
import matplotlib.figure
from matplotlib.backends.backend_qtagg import FigureCanvasQTAgg
from PySide6.QtCore import Qt, Signal, QSize, QEvent
from PySide6.QtGui import QImage, QPixmap
from PySide6.QtWidgets import (
    QApplication,
    QLabel,
    QMainWindow,
    QSlider,
    QVBoxLayout,
    QHBoxLayout,
    QWidget,
)


def bgr_to_qimage(img: np.ndarray) -> QImage:
    """Convert a BGR uint8 numpy array to QImage."""
    rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
    h, w, ch = rgb.shape
    return QImage(rgb.data, w, h, ch * w, QImage.Format.Format_RGB888).copy()


NUM_BINS = 256


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
    black: float,
    white: float,
) -> np.ndarray:
    """Evaluate the parametric target CDF at positions *x* (in [0, 1]).

    The inner curve h(x) is a piecewise power function joined at *c*,
    shaped by *t* (shadow exponent) and *s* (highlight exponent), with
    continuity ensured by the alpha/beta weighting.  The final curve
    is h(x)**g, linearly mapped from [0,1] to [black, white] and clamped.
    """
    alpha = s * c / (s * c + t * (1.0 - c))
    beta = 1.0 - alpha

    h = np.where(
        x <= c,
        alpha * np.power(x / c, t),
        1.0 - beta * np.power((1.0 - x) / (1.0 - c), s),
    )
    f = np.power(h, g)
    return np.clip(black + f * (white - black), 0.0, 1.0)


# ---------------------------------------------------------------------------
# Widgets
# ---------------------------------------------------------------------------


class ImageViewer(QMainWindow):
    """Resizable window that displays an image at correct aspect ratio."""

    def __init__(self, title: str, *, quit_on_close: bool = False) -> None:
        super().__init__()
        self.setWindowTitle(title)
        self._quit_on_close = quit_on_close
        self._label = QLabel(alignment=Qt.AlignmentFlag.AlignCenter)
        self._label.setMinimumSize(QSize(160, 120))
        self.setCentralWidget(self._label)
        self._pixmap = QPixmap()

    def show_image(self, img: np.ndarray) -> None:
        self._pixmap = QPixmap.fromImage(bgr_to_qimage(img))
        self._refresh()

    def closeEvent(self, event) -> None:  # noqa: N802
        if self._quit_on_close:
            QApplication.instance().quit()
        super().closeEvent(event)

    def resizeEvent(self, event) -> None:  # noqa: N802
        super().resizeEvent(event)
        self._refresh()

    def _refresh(self) -> None:
        if self._pixmap.isNull():
            return
        scaled = self._pixmap.scaled(
            self._label.size(),
            Qt.AspectRatioMode.KeepAspectRatio,
            Qt.TransformationMode.SmoothTransformation,
        )
        self._label.setPixmap(scaled)


class HistogramPlot(QMainWindow):
    """Window with raw and capped histograms stacked vertically."""

    COLORS = ["blue", "green", "red"]  # BGR channel order

    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("Histograms")
        self._fig = matplotlib.figure.Figure(figsize=(6, 4), tight_layout=True)
        self._ax_raw, self._ax_cap = self._fig.subplots(2, 1, sharex=True)
        self._canvas = FigureCanvasQTAgg(self._fig)
        self.setCentralWidget(self._canvas)
        self._bins = np.linspace(0.0, 1.0, NUM_BINS)

    def update(
        self,
        raw_hists: list[np.ndarray],
        capped_hists: list[np.ndarray],
    ) -> None:
        for ax, hists, title in (
            (self._ax_raw, raw_hists, "Raw"),
            (self._ax_cap, capped_hists, "Capped"),
        ):
            ax.clear()
            for hist, color in zip(hists, self.COLORS):
                ax.plot(self._bins, hist, color=color, linewidth=0.7, alpha=0.7)
            ax.set_xlim(0, 1)
            ax.set_ylabel("count")
            ax.set_title(title, fontsize=9)
        self._ax_cap.set_xlabel("intensity")
        self._canvas.draw_idle()


class CdfPlot(QMainWindow):
    """Window with input CDF and target CDF side by side."""

    COLORS = ["blue", "green", "red"]

    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("CDFs")
        self._fig = matplotlib.figure.Figure(figsize=(8, 4), tight_layout=True)
        self._ax_in, self._ax_tgt = self._fig.subplots(1, 2)
        self._canvas = FigureCanvasQTAgg(self._fig)
        self.setCentralWidget(self._canvas)
        self._hist_bins = np.linspace(0.0, 1.0, NUM_BINS)
        self._cdf_x = np.linspace(0.0, 1.0, 1024)

    def update(
        self,
        capped_hists: list[np.ndarray],
        t: float,
        s: float,
        c: float,
        g: float,
        black: float,
        white: float,
    ) -> None:
        ax_in = self._ax_in
        ax_in.clear()
        for hist, color in zip(capped_hists, self.COLORS):
            cdf = np.cumsum(hist)
            total = cdf[-1]
            if total > 0:
                cdf /= total
            ax_in.plot(self._hist_bins, cdf, color=color, linewidth=0.8, alpha=0.7)
        ax_in.plot([0, 1], [0, 1], color="gray", linewidth=0.5, linestyle="--")
        ax_in.set_xlim(0, 1)
        ax_in.set_ylim(0, 1)
        ax_in.set_aspect("equal")
        ax_in.set_xlabel("intensity")
        ax_in.set_ylabel("CDF")
        ax_in.set_title("Input CDF", fontsize=9)

        ax_tgt = self._ax_tgt
        ax_tgt.clear()
        target = compute_target_cdf(self._cdf_x, t, s, c, g, black, white)
        ax_tgt.plot(self._cdf_x, target, color="black", linewidth=1.4)
        ax_tgt.plot([0, 1], [0, 1], color="gray", linewidth=0.5, linestyle="--")
        ax_tgt.set_xlim(0, 1)
        ax_tgt.set_ylim(0, 1)
        ax_tgt.set_aspect("equal")
        ax_tgt.set_xlabel("intensity")
        ax_tgt.set_title("Target CDF", fontsize=9)

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
    """Separate window holding all parameter sliders."""

    params_changed = Signal()

    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("Controls")

        layout = QVBoxLayout()
        self.cap_frac = LabeledSlider("cap", 0.0, 1.0, 1.0)
        self.t = LabeledSlider("t", 0.01, 5.0, 1.0)
        self.s = LabeledSlider("s", 0.01, 5.0, 1.0)
        self.c = LabeledSlider("c", 0.0, 1.0, 0.5)
        self.g = LabeledSlider("g", 0.1, 3.0, 1.0)
        self.black = LabeledSlider("black", -0.2, 0.2, 0.0)
        self.white = LabeledSlider("white", 0.8, 1.2, 1.0)
        for slider in (
            self.cap_frac, self.t, self.s, self.c, self.g, self.black, self.white,
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
        self._src_float = src_bgr.astype(np.float64) / 255.0

        screen = QApplication.primaryScreen().availableGeometry()
        default_w = min(src_bgr.shape[1], int(screen.width() * 0.55))
        default_h = min(src_bgr.shape[0], int(screen.height() * 0.65))

        margin = 12
        right_x = screen.x() + margin + default_w + margin

        self.viewer = ImageViewer("Output", quit_on_close=True)
        self.viewer.resize(default_w, default_h)
        self.viewer.move(screen.x() + margin, screen.y() + margin)

        ctrl_w, ctrl_h = 420, 260
        self.controls = ControlsPanel()
        self.controls.resize(ctrl_w, ctrl_h)
        self.controls.move(right_x, screen.y() + margin)
        self.controls.params_changed.connect(self._update)

        hist_w, hist_h = 500, 350
        self.hist_plot = HistogramPlot()
        self.hist_plot.resize(hist_w, hist_h)
        self.hist_plot.move(right_x, screen.y() + margin + ctrl_h + margin)

        cdf_w, cdf_h = 500, 300
        self.cdf_plot = CdfPlot()
        self.cdf_plot.resize(cdf_w, cdf_h)
        self.cdf_plot.move(right_x, screen.y() + margin + ctrl_h + margin + hist_h + margin)

        self.viewer.show()
        self.hist_plot.show()
        self.cdf_plot.show()
        self.controls.show()

        self._raw_hists = [
            compute_histogram(self._src_float[:, :, ch]) for ch in range(3)
        ]
        self._update()

    def _update(self) -> None:
        cap_frac = self.controls.cap_frac.val
        global_max = max(h.max() for h in self._raw_hists)
        cap = cap_frac * global_max
        capped_hists = [cap_histogram(h, cap) for h in self._raw_hists]

        self.viewer.show_image(self._src_bgr)
        self.hist_plot.update(self._raw_hists, capped_hists)
        self.cdf_plot.update(
            capped_hists,
            self.controls.t.val,
            self.controls.s.val,
            self.controls.c.val,
            self.controls.g.val,
            self.controls.black.val,
            self.controls.white.val,
        )


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
