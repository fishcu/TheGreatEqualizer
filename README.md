# The Great Equalizer

A photo editor for Android that is heavily inspired by the Digital Harinezumi and other "toy cameras" and their crunchy, high-contrast look.

## What it does

- Uses **histogram equalization** to redistribute light and color, creating
  contrast across the whole image.
- Starts with a look close to the original photo.
- Adds adjustable grain and vignetting.
- Press the dice button or shake your phone to explore randomized looks.

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
