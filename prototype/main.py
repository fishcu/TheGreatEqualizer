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
from PySide6.QtCore import Qt, Signal, QSize
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


def cv_to_qimage(img: np.ndarray) -> QImage:
    """Convert a BGR uint8 numpy array to QImage."""
    rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
    h, w, ch = rgb.shape
    return QImage(rgb.data, w, h, ch * w, QImage.Format.Format_RGB888).copy()


def compute_histograms(img: np.ndarray) -> list[np.ndarray]:
    """Return per-channel histograms (B, G, R) as 1-D arrays."""
    return [
        np.histogram(img[:, :, ch], bins=256, range=(0, 256))[0].astype(np.float64)
        for ch in range(3)
    ]


def process(src: np.ndarray, param_a: float, param_b: float) -> np.ndarray:
    """Placeholder – replace with real processing later."""
    _ = param_a, param_b
    return src.copy()


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
        self._pixmap = QPixmap.fromImage(cv_to_qimage(img))
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
        self._fig = matplotlib.figure.Figure(figsize=(6, 3), tight_layout=True)
        self._ax = self._fig.add_subplot(111)
        self._canvas = FigureCanvasQTAgg(self._fig)
        self.setCentralWidget(self._canvas)
        self._bins = np.arange(256)

    def update_histograms(self, histograms: list[np.ndarray]) -> None:
        ax = self._ax
        ax.clear()
        colors = ["red", "green", "blue"]
        for hist, color in zip(histograms, colors):
            ax.plot(self._bins, hist, color=color, linewidth=0.8, alpha=0.7)
        ax.set_xlim(0, 255)
        ax.set_ylabel("count")
        ax.set_xlabel("intensity")
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

        self._name_label = QLabel(name)
        self._value_label = QLabel()
        self._slider = QSlider(Qt.Orientation.Horizontal)
        self._slider.setRange(0, steps)
        self._slider.setValue(self._float_to_tick(default))
        self._update_value_label(self._slider.value())
        self._slider.valueChanged.connect(self._on_changed)

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
        self.param_a = LabeledSlider("param_a", 0.0, 1.0, 0.5)
        self.param_b = LabeledSlider("param_b", 0.0, 1.0, 0.5)
        layout.addWidget(self.param_a)
        layout.addWidget(self.param_b)
        layout.addStretch()
        self.setLayout(layout)

        self.param_a.value_changed.connect(lambda _: self.params_changed.emit())
        self.param_b.value_changed.connect(lambda _: self.params_changed.emit())


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

        ctrl_w, ctrl_h = 420, 130
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
        out = process(
            self._src,
            self.controls.param_a.val,
            self.controls.param_b.val,
        )
        self.viewer.show_image(out)
        self.debug_plot.update_histograms(compute_histograms(out))


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
