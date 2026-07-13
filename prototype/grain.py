"""Apply deterministic, film-like grain to the OKLab lightness channel.

The grain is synthesized into small, tileable textures using a radially
symmetric frequency response. Two independently sized tiles are combined when
sampling an image so repetition remains difficult to see.

The result is modulated by image lightness so grain is strongest around the
midtones and falls off toward black and white.

Usage:
    python grain.py INPUT
    python grain.py INPUT OUTPUT
    python grain.py INPUT OUTPUT --amount 0.035 --size 1.5 --seed 42

With no output path, the script opens an interactive preview. Supplying an
output path retains the non-interactive batch mode.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from math import sqrt
from pathlib import Path
import time

import cv2
import numpy as np
from PySide6.QtCore import Qt, QTimer, Signal
from PySide6.QtGui import QImage, QPixmap
from PySide6.QtWidgets import (
    QApplication,
    QHBoxLayout,
    QLabel,
    QMainWindow,
    QPushButton,
    QSizePolicy,
    QSlider,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

from oklab import (
    build_gamut_lut,
    gamut_clamp_ab,
    linear_bgr_to_oklab,
    oklab_to_linear_bgr,
)


@dataclass(frozen=True)
class GrainParams:
    """Parameters for L-channel isotropic grain synthesis."""

    amount: float
    size: float
    roughness: float
    midtone_bias: float
    edge_strength: float
    seed: int

    def validate(self) -> None:
        assert 0.0 <= self.amount <= 0.25, "amount must be in [0.0, 0.25]"
        assert 0.25 <= self.size <= 16.0, "size must be in [0.25, 16.0] pixels"
        assert 0.0 <= self.roughness <= 1.0, "roughness must be in [0.0, 1.0]"
        assert 0.0 <= self.midtone_bias <= 4.0, "midtone_bias must be in [0.0, 4.0]"
        assert 0.0 <= self.edge_strength <= 1.0, "edge_strength must be in [0.0, 1.0]"
        assert 0 <= self.seed <= 2**31 - 1, "seed must be a non-negative 31-bit integer"


@dataclass(frozen=True)
class PreparedImage:
    """An image converted to the planes needed for repeated grain rendering."""

    source_bgr: np.ndarray
    lightness: np.ndarray
    a_channel: np.ndarray
    b_channel: np.ndarray


@dataclass(frozen=True)
class GrainTile:
    """Two independent, tileable isotropic grain layers."""

    primary: np.ndarray
    secondary: np.ndarray


def srgb_eotf(encoded: np.ndarray) -> np.ndarray:
    """Convert encoded sRGB values in [0, 1] to linear light."""
    return np.where(
        encoded <= 0.04045,
        encoded / 12.92,
        np.power((encoded + 0.055) / 1.055, 2.4),
    ).astype(np.float32)


def srgb_oetf(linear: np.ndarray) -> np.ndarray:
    """Convert linear-light sRGB values in [0, 1] to encoded sRGB."""
    return np.where(
        linear <= 0.0031308,
        12.92 * linear,
        1.055 * np.power(linear, 1.0 / 2.4) - 0.055,
    ).astype(np.float32)


_PRIMARY_TILE_SIZE = 512
_SECONDARY_TILE_SIZE = 509
SIZE_REFERENCE_PIXELS = 1.25


def _gaussian_filtered_tile(
    tile_size: int,
    grain_size: float,
    rng: np.random.Generator,
) -> np.ndarray:
    """Generate periodic Gaussian noise with a radial frequency response.

    ``grain_size`` is the full width at half maximum of the equivalent
    isotropic Gaussian kernel, measured in output pixels.
    """
    assert tile_size > 1, "tile_size must be greater than one"
    assert grain_size > 0.0, "grain_size must be positive"

    white = rng.standard_normal((tile_size, tile_size), dtype=np.float32)
    frequencies_y = np.fft.fftfreq(tile_size).astype(np.float32)
    frequencies_x = np.fft.rfftfreq(tile_size).astype(np.float32)
    radius_squared = (
        frequencies_y[:, np.newaxis] ** 2
        + frequencies_x[np.newaxis, :] ** 2
    )

    sigma = np.float32(grain_size / 2.354820045)
    response = np.exp(
        np.float32(-2.0 * np.pi**2) * sigma * sigma * radius_squared
    ).astype(np.float32)
    spectrum = np.fft.rfft2(white)
    filtered = np.fft.irfft2(
        spectrum * response,
        s=white.shape,
    ).astype(np.float32)

    filtered -= np.float32(filtered.mean(dtype=np.float64))
    standard_deviation = float(filtered.std(dtype=np.float64))
    assert standard_deviation > 0.0, "filtered grain tile has zero variance"
    filtered /= np.float32(standard_deviation)
    return filtered


def _synthesize_tile(
    tile_size: int,
    params: GrainParams,
    rng: np.random.Generator,
) -> np.ndarray:
    """Create one tile with a roughness-controlled grain-size distribution."""
    primary = _gaussian_filtered_tile(tile_size, params.size, rng)
    if params.roughness == 0.0:
        return primary

    fine = _gaussian_filtered_tile(
        tile_size,
        max(0.25, params.size * 0.6),
        rng,
    )
    coarse = _gaussian_filtered_tile(
        tile_size,
        params.size * 1.8,
        rng,
    )
    varied = (fine + coarse) / np.float32(np.sqrt(2.0))
    roughness = np.float32(params.roughness)
    tile = (np.float32(1.0) - roughness) * primary + roughness * varied

    tile -= np.float32(tile.mean(dtype=np.float64))
    standard_deviation = float(tile.std(dtype=np.float64))
    assert standard_deviation > 0.0, "rough grain tile has zero variance"
    tile /= np.float32(standard_deviation)
    return tile


def generate_grain_tile(params: GrainParams) -> GrainTile:
    """Generate two deterministic isotropic grain tiles."""
    params.validate()
    rng = np.random.default_rng(params.seed)
    return GrainTile(
        primary=_synthesize_tile(_PRIMARY_TILE_SIZE, params, rng),
        secondary=_synthesize_tile(_SECONDARY_TILE_SIZE, params, rng),
    )


def _repeat_tile(tile: np.ndarray, shape: tuple[int, int]) -> np.ndarray:
    """Repeat a tile from the global origin and crop it to ``shape``."""
    assert tile.ndim == 2, "grain tile must be two-dimensional"
    height, width = shape
    repeats_y = (height + tile.shape[0] - 1) // tile.shape[0]
    repeats_x = (width + tile.shape[1] - 1) // tile.shape[1]
    return np.tile(tile, (repeats_y, repeats_x))[:height, :width]


def sample_grain_tile(
    tile: GrainTile,
    shape: tuple[int, int],
    seed: int,
) -> np.ndarray:
    """Sample two incommensurate tile layers into a full grain field."""
    height, width = shape
    assert height > 1 and width > 1, "grain field must be at least 2 by 2 pixels"

    primary = _repeat_tile(tile.primary, shape)
    offset_y = seed % tile.secondary.shape[0]
    offset_x = (seed * 1664525 + 1013904223) % tile.secondary.shape[1]
    shifted_secondary = np.roll(
        tile.secondary,
        shift=(offset_y, offset_x),
        axis=(0, 1),
    )
    secondary = _repeat_tile(shifted_secondary, shape)
    return (
        (primary + secondary) / np.float32(np.sqrt(2.0))
    ).astype(np.float32)


def generate_grain(
    shape: tuple[int, int],
    params: GrainParams,
) -> np.ndarray:
    """Generate a deterministic isotropic grain field for an image."""
    tile = generate_grain_tile(params)
    return sample_grain_tile(tile, shape, params.seed)


def perceived_size_gain(size: float) -> float:
    """Apply inverse-square-root gain relative to the reference grain size."""
    assert size > 0.0, "grain size must be positive"
    return sqrt(SIZE_REFERENCE_PIXELS / size)


def lightness_envelope(lightness: np.ndarray, params: GrainParams) -> np.ndarray:
    """Return tone-dependent grain strength for an OKLab L plane."""
    params.validate()
    assert lightness.ndim == 2, "lightness must be a two-dimensional array"

    if params.midtone_bias == 0.0:
        return np.ones_like(lightness, dtype=np.float32)

    midtone_weight = np.clip(
        np.float32(4.0) * lightness * (np.float32(1.0) - lightness),
        0.0,
        1.0,
    )
    biased_weight = np.power(midtone_weight, params.midtone_bias).astype(np.float32)
    return (
        np.float32(params.edge_strength)
        + np.float32(1.0 - params.edge_strength) * biased_weight
    )


def prepare_image(source_bgr: np.ndarray) -> PreparedImage:
    """Convert an 8-bit BGR image into reusable OKLab planes."""
    assert source_bgr.dtype == np.uint8, "source image must use uint8"
    assert source_bgr.ndim == 3, "source image must have height, width, and channels"
    assert source_bgr.shape[2] == 3, "source image must have three BGR channels"

    encoded_bgr = source_bgr.astype(np.float32) / np.float32(255.0)
    linear_bgr = srgb_eotf(encoded_bgr)
    lightness, a_channel, b_channel = linear_bgr_to_oklab(linear_bgr)
    return PreparedImage(
        source_bgr=source_bgr,
        lightness=lightness,
        a_channel=a_channel,
        b_channel=b_channel,
    )


def render_prepared_with_gain(
    prepared: PreparedImage,
    params: GrainParams,
    grain: np.ndarray,
    gamut_lut: np.ndarray,
    size_gain: float,
) -> np.ndarray:
    """Render a prepared image using an explicit grain-size gain."""
    params.validate()
    assert grain.shape == prepared.lightness.shape, "grain shape must match image"
    assert grain.dtype == np.float32, "grain field must use float32"
    assert size_gain > 0.0, "size_gain must be positive"

    if params.amount == 0.0:
        return prepared.source_bgr.copy()

    envelope = lightness_envelope(prepared.lightness, params)
    grain_delta = (
        np.float32(params.amount)
        * np.float32(size_gain)
        * envelope
        * grain
    )
    grainy_lightness = np.clip(
        prepared.lightness + grain_delta,
        0.0,
        1.0,
    ).astype(np.float32)

    grainy_a, grainy_b = gamut_clamp_ab(
        grainy_lightness,
        prepared.a_channel,
        prepared.b_channel,
        gamut_lut,
    )
    grainy_linear_bgr = oklab_to_linear_bgr(
        grainy_lightness,
        grainy_a,
        grainy_b,
    )
    grainy_linear_bgr = np.clip(grainy_linear_bgr, 0.0, 1.0)
    grainy_encoded_bgr = srgb_oetf(grainy_linear_bgr)
    return np.rint(grainy_encoded_bgr * np.float32(255.0)).astype(np.uint8)


def render_prepared(
    prepared: PreparedImage,
    params: GrainParams,
    grain: np.ndarray,
    gamut_lut: np.ndarray,
) -> np.ndarray:
    """Render a prepared image using the default size compensation."""
    return render_prepared_with_gain(
        prepared,
        params,
        grain,
        gamut_lut,
        perceived_size_gain(params.size),
    )


def apply_grain(source_bgr: np.ndarray, params: GrainParams) -> np.ndarray:
    """Apply grain to an 8-bit BGR image and return an 8-bit BGR image."""
    params.validate()
    prepared = prepare_image(source_bgr)
    if params.amount == 0.0:
        return prepared.source_bgr.copy()

    grain = generate_grain(prepared.lightness.shape, params)
    gamut_lut = build_gamut_lut()
    return render_prepared(prepared, params, grain, gamut_lut)


def load_image(path: Path) -> np.ndarray:
    """Load an image as an 8-bit BGR array."""
    assert path.is_file(), f"input image does not exist: {path}"
    image = cv2.imread(str(path), cv2.IMREAD_COLOR)
    assert image is not None, f"failed to decode input image: {path}"
    return image


def save_image(path: Path, image: np.ndarray) -> None:
    """Write an 8-bit BGR image."""
    assert path.parent.is_dir(), f"output directory does not exist: {path.parent}"
    assert image.dtype == np.uint8, "output image must use uint8"
    assert cv2.imwrite(str(path), image), f"failed to write output image: {path}"


def bgr_to_qimage(image: np.ndarray) -> QImage:
    """Convert an 8-bit BGR image to an owned QImage."""
    assert image.dtype == np.uint8, "display image must use uint8"
    rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
    height, width, channels = rgb.shape
    return QImage(
        rgb.data,
        width,
        height,
        channels * width,
        QImage.Format.Format_RGB888,
    ).copy()


class FitImageLabel(QLabel):
    """Display a complete pixmap scaled to the available viewport."""

    def __init__(self) -> None:
        super().__init__()
        self._source_pixmap = QPixmap()
        self.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.setMinimumSize(320, 240)
        self.setSizePolicy(
            QSizePolicy.Policy.Expanding,
            QSizePolicy.Policy.Expanding,
        )

    def show_pixmap(self, pixmap: QPixmap) -> None:
        assert not pixmap.isNull(), "display pixmap must not be null"
        self._source_pixmap = pixmap
        self._refresh()

    def resizeEvent(self, event) -> None:  # noqa: N802
        super().resizeEvent(event)
        self._refresh()

    def _refresh(self) -> None:
        if self._source_pixmap.isNull():
            return
        scaled = self._source_pixmap.scaled(
            self.size(),
            Qt.AspectRatioMode.KeepAspectRatio,
            Qt.TransformationMode.SmoothTransformation,
        )
        super().setPixmap(scaled)


class FloatSlider(QWidget):
    """A labeled floating-point slider with a live numeric value."""

    value_changed = Signal()

    def __init__(
        self,
        name: str,
        minimum: float,
        maximum: float,
        default: float,
        steps: int = 1000,
    ) -> None:
        super().__init__()
        assert minimum < maximum, "slider minimum must be below maximum"
        assert minimum <= default <= maximum, "slider default must be in range"
        assert steps > 0, "slider steps must be positive"

        self._minimum = minimum
        self._maximum = maximum
        self._default = default
        self._steps = steps

        self._name_label = QLabel(name)
        self._value_label = QLabel()
        self._value_label.setMinimumWidth(52)
        self._value_label.setAlignment(Qt.AlignmentFlag.AlignRight)
        self._slider = QSlider(Qt.Orientation.Horizontal)
        self._slider.setRange(0, steps)
        self._slider.setValue(self._float_to_tick(default))
        self._slider.valueChanged.connect(self._on_value_changed)
        self._update_label()

        layout = QHBoxLayout()
        layout.setContentsMargins(0, 0, 0, 0)
        layout.addWidget(self._name_label)
        layout.addWidget(self._slider, stretch=1)
        layout.addWidget(self._value_label)
        self.setLayout(layout)

    @property
    def value(self) -> float:
        return self._minimum + (
            self._maximum - self._minimum
        ) * self._slider.value() / self._steps

    def reset(self) -> None:
        self._slider.setValue(self._float_to_tick(self._default))

    def _float_to_tick(self, value: float) -> int:
        return round(
            (value - self._minimum)
            / (self._maximum - self._minimum)
            * self._steps
        )

    def _update_label(self) -> None:
        self._value_label.setText(f"{self.value:.3f}")

    def _on_value_changed(self) -> None:
        self._update_label()
        self.value_changed.emit()


class GrainPreviewWindow(QMainWindow):
    """Interactive grain preview with debounced dynamic rendering."""

    _PREVIEW_MAX_EDGE = 1600
    _RENDER_DEBOUNCE_MS = 35

    def __init__(
        self,
        source_path: Path,
        output_path: Path,
        initial_params: GrainParams,
    ) -> None:
        super().__init__()
        initial_params.validate()
        self.setWindowTitle(f"Film Grain Prototype - {source_path.name}")

        self._source_path = source_path
        self._output_path = output_path
        self._full_source = load_image(source_path)
        self._preview_source = self._make_preview(self._full_source)
        self._preview_prepared = prepare_image(self._preview_source)
        self._gamut_lut = build_gamut_lut()
        self._grain_key = (-1.0, -1.0, -1)
        self._grain = np.empty((0, 0), dtype=np.float32)
        self._processed_pixmap = QPixmap()

        self._image_label = FitImageLabel()
        self._image_label.setStyleSheet("background: #111;")

        controls = QWidget()
        controls.setMinimumWidth(360)
        controls_layout = QVBoxLayout()
        controls_layout.addWidget(QLabel("L-channel isotropic grain tile"))

        self._amount = FloatSlider(
            "Amount", 0.0, 0.12, initial_params.amount, steps=1200)
        self._size = FloatSlider(
            "Size (px)", 0.25, 12.0, initial_params.size, steps=1175)
        self._roughness = FloatSlider(
            "Roughness", 0.0, 1.0, initial_params.roughness)
        self._midtone_bias = FloatSlider(
            "Midtone bias", 0.0, 4.0, initial_params.midtone_bias)
        self._edge_strength = FloatSlider(
            "Edge strength", 0.0, 1.0, initial_params.edge_strength)

        for slider in self._all_sliders():
            controls_layout.addWidget(slider)
            slider.value_changed.connect(self._schedule_render)

        seed_row = QHBoxLayout()
        seed_row.addWidget(QLabel("Seed"))
        self._seed = QSpinBox()
        self._seed.setRange(0, 2**31 - 1)
        self._seed.setValue(initial_params.seed)
        self._seed.valueChanged.connect(self._schedule_render)
        seed_row.addWidget(self._seed, stretch=1)
        new_seed_button = QPushButton("New seed")
        new_seed_button.clicked.connect(self._new_seed)
        seed_row.addWidget(new_seed_button)
        controls_layout.addLayout(seed_row)

        compare_button = QPushButton("Hold for original")
        compare_button.pressed.connect(self._show_original)
        compare_button.released.connect(self._show_processed)
        controls_layout.addWidget(compare_button)

        button_row = QHBoxLayout()
        reset_button = QPushButton("Reset")
        reset_button.clicked.connect(self._reset)
        save_button = QPushButton("Save full resolution")
        save_button.clicked.connect(self._save_full_resolution)
        button_row.addWidget(reset_button)
        button_row.addWidget(save_button)
        controls_layout.addLayout(button_row)

        output_label = QLabel(f"Output: {output_path}")
        output_label.setWordWrap(True)
        output_label.setTextInteractionFlags(
            Qt.TextInteractionFlag.TextSelectableByMouse)
        controls_layout.addWidget(output_label)

        self._status_label = QLabel()
        self._status_label.setWordWrap(True)
        controls_layout.addWidget(self._status_label)
        controls_layout.addStretch()
        controls.setLayout(controls_layout)

        central = QWidget()
        central_layout = QHBoxLayout()
        central_layout.addWidget(self._image_label, stretch=1)
        central_layout.addWidget(controls)
        central.setLayout(central_layout)
        self.setCentralWidget(central)

        self._render_timer = QTimer(self)
        self._render_timer.setSingleShot(True)
        self._render_timer.setInterval(self._RENDER_DEBOUNCE_MS)
        self._render_timer.timeout.connect(self._render_preview)

        screen = QApplication.primaryScreen().availableGeometry()
        window_width = min(
            self._preview_source.shape[1] + 420,
            int(screen.width() * 0.9),
        )
        window_height = min(
            self._preview_source.shape[0] + 80,
            int(screen.height() * 0.9),
        )
        self.resize(window_width, window_height)
        self._render_preview()

    def _all_sliders(self) -> tuple[FloatSlider, ...]:
        return (
            self._amount,
            self._size,
            self._roughness,
            self._midtone_bias,
            self._edge_strength,
        )

    @classmethod
    def _make_preview(cls, source: np.ndarray) -> np.ndarray:
        height, width = source.shape[:2]
        scale = min(1.0, cls._PREVIEW_MAX_EDGE / max(height, width))
        if scale == 1.0:
            return source.copy()
        preview_size = (round(width * scale), round(height * scale))
        return cv2.resize(source, preview_size, interpolation=cv2.INTER_AREA)

    def _current_params(self) -> GrainParams:
        params = GrainParams(
            amount=self._amount.value,
            size=self._size.value,
            roughness=self._roughness.value,
            midtone_bias=self._midtone_bias.value,
            edge_strength=self._edge_strength.value,
            seed=self._seed.value(),
        )
        params.validate()
        return params

    @staticmethod
    def _noise_key(params: GrainParams) -> tuple[float, float, int]:
        return (
            params.size,
            params.roughness,
            params.seed,
        )

    def _schedule_render(self) -> None:
        self._render_timer.start()

    def _render_preview(self) -> None:
        started = time.perf_counter()
        params = self._current_params()
        grain_key = self._noise_key(params)

        if params.amount == 0.0:
            output = self._preview_source
        else:
            if grain_key != self._grain_key:
                self._grain = generate_grain(
                    self._preview_prepared.lightness.shape,
                    params,
                )
                self._grain_key = grain_key
            output = render_prepared(
                self._preview_prepared,
                params,
                self._grain,
                self._gamut_lut,
            )

        self._processed_pixmap = QPixmap.fromImage(bgr_to_qimage(output))
        self._show_processed()
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        self._status_label.setText(f"Preview rendered in {elapsed_ms:.0f} ms")

    def _show_original(self) -> None:
        original = QPixmap.fromImage(bgr_to_qimage(self._preview_source))
        self._image_label.show_pixmap(original)

    def _show_processed(self) -> None:
        self._image_label.show_pixmap(self._processed_pixmap)

    def _new_seed(self) -> None:
        seed = int(np.random.default_rng().integers(0, 2**31))
        self._seed.setValue(seed)

    def _reset(self) -> None:
        for slider in self._all_sliders():
            slider.reset()
        self._seed.setValue(0)
        self._schedule_render()

    def _save_full_resolution(self) -> None:
        self._status_label.setText("Rendering full-resolution output...")
        QApplication.processEvents()
        started = time.perf_counter()
        params = self._current_params()
        output = apply_grain(self._full_source, params)
        save_image(self._output_path, output)
        elapsed_seconds = time.perf_counter() - started
        self._status_label.setText(
            f"Saved {self._output_path} in {elapsed_seconds:.2f} s")


def default_output_path(input_path: Path) -> Path:
    """Build the default output path used by the interactive preview."""
    return input_path.with_name(f"{input_path.stem}_grain{input_path.suffix}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Apply deterministic isotropic grain to OKLab lightness.",
    )
    parser.add_argument("input", type=Path, help="input image path")
    parser.add_argument(
        "output",
        type=Path,
        nargs="?",
        help="output image path; omit to open the interactive preview",
    )
    parser.add_argument(
        "--amount",
        type=float,
        default=0.03,
        help="OKLab L standard deviation at midtones (default: 0.03)",
    )
    parser.add_argument(
        "--size",
        type=float,
        default=1.25,
        help="apparent grain diameter in output pixels (default: 1.25)",
    )
    parser.add_argument(
        "--roughness",
        type=float,
        default=0.35,
        help="variation around the typical grain size (default: 0.35)",
    )
    parser.add_argument(
        "--midtone-bias",
        type=float,
        default=1.0,
        help="grain falloff toward black and white; 0 is uniform (default: 1.0)",
    )
    parser.add_argument(
        "--edge-strength",
        type=float,
        default=0.15,
        help="grain retained at pure black and white (default: 0.15)",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=0,
        help="non-negative deterministic grain seed (default: 0)",
    )
    return parser.parse_args()


def params_from_args(args: argparse.Namespace) -> GrainParams:
    """Create validated grain parameters from the command-line boundary."""
    params = GrainParams(
        amount=args.amount,
        size=args.size,
        roughness=args.roughness,
        midtone_bias=args.midtone_bias,
        edge_strength=args.edge_strength,
        seed=args.seed,
    )
    params.validate()
    return params


def main() -> None:
    args = parse_args()
    params = params_from_args(args)

    if args.output is None:
        output_path = default_output_path(args.input)
        qt_app = QApplication([])
        window = GrainPreviewWindow(args.input, output_path, params)
        window.show()
        qt_app.exec()
        return

    source = load_image(args.input)
    output = apply_grain(source, params)
    save_image(args.output, output)
    print(f"Wrote {args.output}")


if __name__ == "__main__":
    main()
