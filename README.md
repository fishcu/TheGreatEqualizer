# The Great Equalizer

A playful Android photo editor for punchy, unpredictable toy-camera looks.

## What it does

- Uses **histogram equalization** to spread light and color across their
  ranges, creating contrast across the whole image.
- Starts with a look close to the original photo.
- The dice button, or a shake, quickly brings up randomized looks to explore.

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
