"""Generate normalized R8 grain tiles for the Android GPU pipeline."""

from pathlib import Path

import numpy as np

from grain import GrainParams, generate_grain_tile


_TEXTURE_RANGE = 4.0


def encode_r8(tile: np.ndarray) -> bytes:
    """Encode a normalized grain tile over the shader's fixed value range."""
    assert tile.dtype == np.float32, "grain tile must use float32"
    encoded = np.rint(
        (np.clip(tile, -_TEXTURE_RANGE, _TEXTURE_RANGE) + _TEXTURE_RANGE)
        * np.float32(255.0 / (2.0 * _TEXTURE_RANGE))
    ).astype(np.uint8)
    return encoded.tobytes()


def write_asset(path: Path, tile: np.ndarray) -> None:
    """Write one square R8 grain texture."""
    assert path.parent.is_dir(), f"asset directory does not exist: {path.parent}"
    assert tile.ndim == 2, "grain tile must be two-dimensional"
    assert tile.shape[0] == tile.shape[1], "grain tile must be square"
    path.write_bytes(encode_r8(tile))


def main() -> None:
    repository_root = Path(__file__).resolve().parent.parent
    output_directory = (
        repository_root
        / "android"
        / "app"
        / "src"
        / "main"
        / "assets"
        / "grain"
    )
    params = GrainParams(
        amount=0.0,
        size=1.25,
        roughness=0.35,
        midtone_bias=1.0,
        edge_strength=0.15,
        seed=0,
    )
    tiles = generate_grain_tile(params)
    write_asset(output_directory / "grain_primary_r8.bin", tiles.primary)
    write_asset(output_directory / "grain_secondary_r8.bin", tiles.secondary)


if __name__ == "__main__":
    main()
