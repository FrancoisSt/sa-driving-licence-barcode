# demo: the Android demo app

A single-screen Android app that scans a South African driving-licence barcode and shows everything in it,
including the portrait. It is a starting point to copy from, not a library.

- **Scanner**: CameraX at the highest analysis resolution, a letterboxed 4:3 preview, and each frame cropped to the
  guide. ML Kit reads first; zxing-cpp is the fallback, with a straighten-and-blur retry for symbols it finds but
  cannot correct ([FrameReader.kt](src/main/kotlin/io/github/francoisst/sadl/demo/FrameReader.kt)).
- **Feedback**: a scan line sweeps the guide; a box follows the barcode, amber when found but not read, red when
  it is not a licence, green (with a pulse and a flash) when a licence is recognised
  ([GuideOverlayView.kt](src/main/kotlin/io/github/francoisst/sadl/demo/GuideOverlayView.kt)).
- **Decoding**: the [decoder](../decoder) module, then the checks (a licence that fails a blocking check is
  rejected, in red, and scanning goes on), then the portrait with the decoder's pure-Kotlin `WiPortrait` (under 100 ms for the first portrait after launch, 10 to 20 ms after that, on this phone).
- **Privacy**: `FLAG_SECURE`, no storage, no backups, no network permission (the manifest removes the two that ML
  Kit's telemetry library would add), and logs carry outcomes and timings only (tag `SadlDemo`). The result is cleared when the app goes to the background.

Build and install (JDK 17 and the Android SDK; no NDK needed):

```sh
./gradlew :demo:installDebug
adb logcat -s SadlDemo       # outcomes: "decoded card (720 bytes) by ml-kit after 12 frames", timings
./gradlew :demo:connectedDebugAndroidTest   # the portrait decoder on the phone's ART: public-vector and synthetic hashes
```

The device test needs the public vectors (`scripts/fetch-test-vectors.sh`); it compares hashes only.

Tested on a Blackview BV5300 (Android 12, a low-end rugged phone) with real licence cards. The emulator's virtual
camera cannot show a real card; use a phone.

Want only zxing-cpp (no Google component)? Remove the ML Kit dependency and the ML Kit block in `FrameReader.read`;
zxing-cpp alone reads most cards, but struggles more with worn ones.

Files:

| File | What |
|---|---|
| `MainActivity.kt` | Permission, CameraX binding, torch, the result screen |
| `LicenceAnalyzer.kt` | Per frame: crop, read, decode, stop at the first licence |
| `FrameReader.kt` | The two engines, the recovery, corners for the overlay |
| `FrameGeometry.kt` | The guide, and its mapping to the letterboxed preview and the sensor buffer |
| `GuideOverlayView.kt` | The animation and the tracking box |
