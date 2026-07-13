"""Calibrate grain-size compensation with side-by-side 100% crops.

Usage:
    python grain_calibrate.py INPUT

The left crop uses the reference grain size at gain 1.0. The right crop uses
the selected target size and compensation exponent. Adjust the exponent until
both sides have the same perceived grain strength, then repeat with several
target sizes.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import time

import numpy as np
from PySide6.QtCore import Qt, QTimer
from PySide6.QtGui import QPixmap
from PySide6.QtWidgets import (
    QApplication,
    QHBoxLayout,
    QLabel,
    QMainWindow,
    QPushButton,
    QScrollArea,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

from grain import (
    FloatSlider,
    GrainParams,
    SIZE_REFERENCE_PIXELS,
    bgr_to_qimage,
    generate_grain,
    load_image,
    prepare_image,
    render_prepared_with_gain,
)
from oklab import build_gamut_lut


_CROP_SIZE = 448
_DEFAULT_AMOUNT = 0.03
_DEFAULT_TARGET_SIZE = 5.0
_DEFAULT_ROUGHNESS = 0.35
_DEFAULT_EXPONENT = 0.35
_MIDTONE_BIAS = 1.0
_EDGE_STRENGTH = 0.15


def center_crop(image: np.ndarray, maximum_size: int) -> np.ndarray:
    """Return a centered square crop without resampling."""
    assert image.ndim == 3, "image must have height, width, and channels"
    assert maximum_size > 0, "maximum_size must be positive"
    height, width = image.shape[:2]
    crop_size = min(height, width, maximum_size)
    top = (height - crop_size) // 2
    left = (width - crop_size) // 2
    return np.ascontiguousarray(
        image[top:top + crop_size, left:left + crop_size]
    )


class CalibrationWindow(QMainWindow):
    """Compare reference and compensated grain at native pixel scale."""

    _RENDER_DEBOUNCE_MS = 35

    def __init__(self, source_path: Path) -> None:
        super().__init__()
        self.setWindowTitle(f"Grain Size Calibration - {source_path.name}")

        source = load_image(source_path)
        self._crop = center_crop(source, _CROP_SIZE)
        self._prepared = prepare_image(self._crop)
        self._gamut_lut = build_gamut_lut()
        self._reference_key = (-1.0, -1.0, -1)
        self._target_key = (-1.0, -1.0, -1)
        self._reference_grain = np.empty((0, 0), dtype=np.float32)
        self._target_grain = np.empty((0, 0), dtype=np.float32)

        comparison = QWidget()
        comparison_layout = QHBoxLayout()
        comparison_layout.setContentsMargins(0, 0, 0, 0)

        reference_column = QVBoxLayout()
        self._reference_title = QLabel()
        self._reference_title.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self._reference_image = QLabel()
        self._reference_image.setFixedSize(
            self._crop.shape[1],
            self._crop.shape[0],
        )
        self._reference_image.setAlignment(Qt.AlignmentFlag.AlignCenter)
        reference_column.addWidget(self._reference_title)
        reference_column.addWidget(self._reference_image)

        target_column = QVBoxLayout()
        self._target_title = QLabel()
        self._target_title.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self._target_image = QLabel()
        self._target_image.setFixedSize(
            self._crop.shape[1],
            self._crop.shape[0],
        )
        self._target_image.setAlignment(Qt.AlignmentFlag.AlignCenter)
        target_column.addWidget(self._target_title)
        target_column.addWidget(self._target_image)

        comparison_layout.addLayout(reference_column)
        comparison_layout.addLayout(target_column)
        comparison.setLayout(comparison_layout)

        comparison_scroll = QScrollArea()
        comparison_scroll.setWidget(comparison)
        comparison_scroll.setWidgetResizable(False)
        comparison_scroll.setAlignment(Qt.AlignmentFlag.AlignCenter)

        controls = QWidget()
        controls_layout = QVBoxLayout()
        instructions = QLabel(
            "Both crops are shown at 100%. Choose a target size, then adjust "
            "Compensation exponent until its grain looks as strong as the "
            "1.25 px reference."
        )
        instructions.setWordWrap(True)
        controls_layout.addWidget(instructions)

        self._amount = FloatSlider(
            "Amount", 0.0, 0.12, _DEFAULT_AMOUNT, steps=1200)
        self._target_size = FloatSlider(
            "Target size (px)", 0.25, 12.0, _DEFAULT_TARGET_SIZE, steps=1175)
        self._roughness = FloatSlider(
            "Roughness", 0.0, 1.0, _DEFAULT_ROUGHNESS)
        self._exponent = FloatSlider(
            "Compensation exponent", 0.0, 1.0, _DEFAULT_EXPONENT)

        for slider in self._all_sliders():
            controls_layout.addWidget(slider)
            slider.value_changed.connect(self._schedule_render)

        seed_row = QHBoxLayout()
        seed_row.addWidget(QLabel("Seed"))
        self._seed = QSpinBox()
        self._seed.setRange(0, 2**31 - 1)
        self._seed.valueChanged.connect(self._schedule_render)
        seed_row.addWidget(self._seed, stretch=1)
        new_seed_button = QPushButton("New seed")
        new_seed_button.clicked.connect(self._new_seed)
        seed_row.addWidget(new_seed_button)
        controls_layout.addLayout(seed_row)

        reset_button = QPushButton("Reset")
        reset_button.clicked.connect(self._reset)
        controls_layout.addWidget(reset_button)

        self._result_label = QLabel()
        self._result_label.setTextInteractionFlags(
            Qt.TextInteractionFlag.TextSelectableByMouse)
        controls_layout.addWidget(self._result_label)
        self._status_label = QLabel()
        controls_layout.addWidget(self._status_label)
        controls.setLayout(controls_layout)

        central = QWidget()
        central_layout = QVBoxLayout()
        central_layout.addWidget(comparison_scroll, stretch=1)
        central_layout.addWidget(controls)
        central.setLayout(central_layout)
        self.setCentralWidget(central)

        self._render_timer = QTimer(self)
        self._render_timer.setSingleShot(True)
        self._render_timer.setInterval(self._RENDER_DEBOUNCE_MS)
        self._render_timer.timeout.connect(self._render)

        screen = QApplication.primaryScreen().availableGeometry()
        desired_width = self._crop.shape[1] * 2 + 80
        desired_height = self._crop.shape[0] + 360
        self.resize(
            min(desired_width, int(screen.width() * 0.95)),
            min(desired_height, int(screen.height() * 0.95)),
        )
        self._render()

    def _all_sliders(self) -> tuple[FloatSlider, ...]:
        return (
            self._amount,
            self._target_size,
            self._roughness,
            self._exponent,
        )

    def _params(self, size: float) -> GrainParams:
        params = GrainParams(
            amount=self._amount.value,
            size=size,
            roughness=self._roughness.value,
            midtone_bias=_MIDTONE_BIAS,
            edge_strength=_EDGE_STRENGTH,
            seed=self._seed.value(),
        )
        params.validate()
        return params

    @staticmethod
    def _grain_key(params: GrainParams) -> tuple[float, float, int]:
        return params.size, params.roughness, params.seed

    def _target_gain(self) -> float:
        size_ratio = self._target_size.value / SIZE_REFERENCE_PIXELS
        return float(size_ratio ** -self._exponent.value)

    def _schedule_render(self) -> None:
        self._render_timer.start()

    def _render(self) -> None:
        started = time.perf_counter()
        reference_params = self._params(SIZE_REFERENCE_PIXELS)
        target_params = self._params(self._target_size.value)

        reference_key = self._grain_key(reference_params)
        if reference_key != self._reference_key:
            self._reference_grain = generate_grain(
                self._prepared.lightness.shape,
                reference_params,
            )
            self._reference_key = reference_key

        target_key = self._grain_key(target_params)
        if target_key != self._target_key:
            self._target_grain = generate_grain(
                self._prepared.lightness.shape,
                target_params,
            )
            self._target_key = target_key

        target_gain = self._target_gain()
        reference_output = render_prepared_with_gain(
            self._prepared,
            reference_params,
            self._reference_grain,
            self._gamut_lut,
            1.0,
        )
        target_output = render_prepared_with_gain(
            self._prepared,
            target_params,
            self._target_grain,
            self._gamut_lut,
            target_gain,
        )

        self._reference_image.setPixmap(
            QPixmap.fromImage(bgr_to_qimage(reference_output)))
        self._target_image.setPixmap(
            QPixmap.fromImage(bgr_to_qimage(target_output)))
        self._reference_title.setText(
            f"Reference: {SIZE_REFERENCE_PIXELS:.2f} px, gain 1.000")
        self._target_title.setText(
            f"Target: {target_params.size:.2f} px, gain {target_gain:.3f}")
        self._result_label.setText(
            f"Exponent {self._exponent.value:.3f} gives gain "
            f"{target_gain:.3f} at {target_params.size:.2f} px"
        )
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        self._status_label.setText(f"Rendered both crops in {elapsed_ms:.0f} ms")

    def _new_seed(self) -> None:
        seed = int(np.random.default_rng().integers(0, 2**31))
        self._seed.setValue(seed)

    def _reset(self) -> None:
        for slider in self._all_sliders():
            slider.reset()
        self._seed.setValue(0)
        self._schedule_render()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Calibrate perceived grain strength across grain sizes.",
    )
    parser.add_argument("input", type=Path, help="input image path")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    qt_app = QApplication([])
    window = CalibrationWindow(args.input)
    window.show()
    qt_app.exec()


if __name__ == "__main__":
    main()
