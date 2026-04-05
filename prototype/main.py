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


class DebugPlot(QMainWindow):
    """Separate window with a matplotlib figure for debug visualizations."""

    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("Debug")
        self._fig = matplotlib.figure.Figure(figsize=(5, 5), tight_layout=True)
        self._ax = self._fig.add_subplot(111)
        self._canvas = FigureCanvasQTAgg(self._fig)
        self.setCentralWidget(self._canvas)
        self._x = np.linspace(0.0, 1.0, 1024)

    def update_cdf(
        self, t: float, s: float, c: float, g: float, black: float, white: float
    ) -> None:
        y = compute_target_cdf(self._x, t, s, c, g, black, white)
        ax = self._ax
        ax.clear()
        ax.plot(self._x, y, color="black", linewidth=1.2)
        ax.plot([0, 1], [0, 1], color="gray", linewidth=0.5, linestyle="--")
        ax.set_xlim(0, 1)
        ax.set_ylim(0, 1)
        ax.set_aspect("equal")
        ax.set_xlabel("input")
        ax.set_ylabel("output")
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
        self.t = LabeledSlider("t", 0.01, 5.0, 1.0)
        self.s = LabeledSlider("s", 0.01, 5.0, 1.0)
        self.c = LabeledSlider("c", 0.0, 1.0, 0.5)
        self.g = LabeledSlider("g", 0.1, 3.0, 1.0)
        self.black = LabeledSlider("black", -0.2, 0.2, 0.0)
        self.white = LabeledSlider("white", 0.8, 1.2, 1.0)
        for slider in (self.t, self.s, self.c, self.g, self.black, self.white):
            layout.addWidget(slider)
            slider.value_changed.connect(lambda _: self.params_changed.emit())
        layout.addStretch()
        self.setLayout(layout)


# ---------------------------------------------------------------------------
# App
# ---------------------------------------------------------------------------


class App:
    def __init__(self, src: np.ndarray) -> None:
        self._src = src

        screen = QApplication.primaryScreen().availableGeometry()
        default_w = min(src.shape[1], int(screen.width() * 0.55))
        default_h = min(src.shape[0], int(screen.height() * 0.65))

        margin = 12

        self.viewer = ImageViewer("Output", quit_on_close=True)
        self.viewer.resize(default_w, default_h)
        self.viewer.move(screen.x() + margin, screen.y() + margin)

        ctrl_w, ctrl_h = 420, 230
        self.controls = ControlsPanel()
        self.controls.resize(ctrl_w, ctrl_h)
        self.controls.move(
            screen.x() + margin + default_w + margin,
            screen.y() + margin,
        )
        self.controls.params_changed.connect(self._update)

        debug_w, debug_h = 620, 340
        self.debug_plot = DebugPlot()
        self.debug_plot.resize(debug_w, debug_h)
        self.debug_plot.move(
            screen.x() + margin + default_w + margin,
            screen.y() + margin + ctrl_h + margin,
        )

        self.viewer.show()
        self.debug_plot.show()
        self.controls.show()

        self._update()

    def _update(self) -> None:
        self.viewer.show_image(self._src)
        self.debug_plot.update_cdf(
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
