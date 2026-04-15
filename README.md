# The Great Equalizer

A photo editor for Android that uses OKLab color space histogram matching.

Inspired by toy camera aesthetics.

## Features

- **OKLab color space processing** — perceptually uniform color manipulation
- **Histogram CDF matching** for lightness and chroma
- **Three editing tabs**: Light, Color, Zoned Tint
- **GPU-accelerated** via GLES 3.1 compute shaders
- **Full-resolution tiled export**
- **Dice button / shake to randomize** parameters

## Build

**Prerequisites:** Android SDK, JDK 17

Clone the repo, open in Android Studio, or build from CLI:

```bash
cd android && ./gradlew assembleRelease
```

> Release builds require a `keystore.properties` file — see `android/app/build.gradle.kts`.

Debug builds need no keystore:

```bash
./gradlew assembleDebug
```

**Min SDK:** 28 (Android 9.0)

## License

[MIT](LICENSE)
