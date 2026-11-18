# PureTube

Air-gapped Android YouTube client with the dual-layer, on-device **HaramBlur** protection engine. Pure OLED interface, no Shorts, SponsorBlock, background play, and 1-tap audio-only play.

- Package: `com.puretube.app`
- Version: 1.0.0 (versionCode 1)
- minSdk 26 · target/compileSdk 35
- Kotlin · Jetpack Compose · Media3 (ExoPlayer) · Room · DataStore · Coil 3 · NewPipeExtractor

## Privacy statement

- **100% on-device inference.** HaramBlur runs entirely on the phone with TensorFlow Lite. No frame, thumbnail, or bitmap is ever written to storage or sent over the network for scanning.
- **Air-gapped models.** The vision models live in `assets/models/` on the device and make zero network calls.
- **Privacy-preserving lookups.** SponsorBlock queries send only the first 4 hex characters of the video ID's SHA-256 hash — never the full ID.
- No accounts, no ads, no analytics, no Google Play Services dependency.

## Features

### HaramBlur — dual-layer protection

- **Layer 1 (in-app):** every thumbnail passes through a Coil transformation that detects and blurs *before* the image is ever displayed, so unblurred pixels never flash for a single frame. Live video is sampled at 12 FPS (83 ms) on a dedicated background thread into a 320×180 buffer; detected regions are drawn as a hardware-accelerated Compose Canvas mask with EMA smoothing (alpha 0.65) and a 450 ms hysteresis hold so a head-turn never flickers the blur off. A zero-flash gate keeps an opaque curtain over the surface until the first frame has been scanned, and re-arms on every seek or source change.
- **Layer 2 (system-wide shield):** an opt-in `MediaProjection` + click-through overlay (`FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE`) that protects other apps. It starts only after Android's screen-capture consent returns OK (foreground service type `mediaProjection`, kept separate from playback), skips inference when the screen is static (motion-delta checksum), and has a Quick Settings tile ("HaramBlur Shield").
- Detected face boxes are expanded to cover hair and shoulders (45% width, 40% top, 85% bottom padding). Choose who to blur (females only / males only / all people), what to protect (images / videos / both), and the visual style: Frosted Gaussian, Pixelate Mosaic, or Solid Dim Curtain. At strict sensitivity, an uncertain classification is blurred under the females-only rule rather than let through.
- Every thumbnail has a 1-tap eye **peek** override, per card.

### Focus-first YouTube

- **Zero Shorts engine:** no Shorts tab, Shorts shelves are stripped from search and feeds, and any opened `/shorts/` or `youtu.be` link is converted into the regular full player with seekbar, speed, and PiP.
- **Subscriptions home** with an "Unwatched only" pill (a video counts as watched at 92%), local keyword/channel blocklist filters, and offline import/export (NewPipe JSON and YouTube Takeout CSV, parsed entirely on-device).
- **Library:** history with resume progress bars, local playlists, and subscription import/export.
- **SponsorBlock** auto-skip (sponsor, self-promo, intro, outro, interaction) with a non-blocking "Skipped Sponsor • Undo" pill; **DeArrow** neutral titles/thumbnails; **Return YouTube Dislike** counts.

### Playback

- Background play through a Media3 `MediaSessionService` with a full media notification.
- **1-tap Audio-Only mode:** disables the video renderer and detaches the surface — the media item is never replaced, so audio continues with zero interruption while saving data and battery.
- **Mono merge** audio processor (true PCM16 stereo→mono averaging) for single-earbud listening, and **loudness normalization** (+350 mB target gain).
- **Native PiP** with Play/Pause, Mute, and +10 s remote actions; auto-enter on Home (Android 12+) when enabled.
- **Pocket volume-button skip:** hold Volume Up/Down for 0.6 s to seek ±10 s.
- Player HUD pills: Audio Only, Mute, Speed (tap cycles 1.0×→1.25×→1.5×→2.0×; slider covers 0.25×–4.0× in 0.05× steps), HaramBlur status, PiP, Cast (MediaRouter).
- **Hold-to-2×:** press and hold the video for more than 220 ms to fast-forward, release to snap back. Double-tap seek ignores the outer 24 dp screen edges so the system back gesture never fights it.
- Stream extraction uses NewPipeExtractor first and silently fails over to a configurable Piped API instance if extraction fails.

### Interface

- Pure OLED theme: true `#000000` canvas, `#1A1A1A` 1 dp borders, emerald accent, optional night dimmer layer.
- Flat settings screen with instant search and four collapsible sections. **Every** control has an inline (?) badge with a plain-English explanation — no modal popups anywhere.

## Permissions

All permissions are opt-in at the moment you enable the feature that needs them. Nothing is prompted at first launch.

| Permission | Used for |
| --- | --- |
| `INTERNET` | Fetching streams, thumbnails, and community metadata |
| `ACCESS_NETWORK_STATE` | Choosing Wi-Fi vs mobile default quality |
| `WAKE_LOCK` | Keeping background playback alive |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Background audio media service |
| `SYSTEM_ALERT_WINDOW` | System Shield click-through overlay (only when you enable the shield) |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | System Shield capture service (only after you grant screen-capture consent) |

## Model files

The HaramBlur engine expects two TensorFlow Lite models in `app/src/main/assets/models/`:

- `blaze_face_short_range.tflite` — face detection
- `gender_attr_classifier_int8.tflite` — quantized INT8 subject classifier

Model files are **not** bundled in this repository. Drop them into `assets/models/` before building to enable detection. Without them the engine degrades gracefully — it reports no regions and the rest of the app works normally.

## Install

1. Download `PureTube-v1.0.0.apk` from the GitHub Release and open it on your phone (allow "install from this app" for your browser/Files when Android asks).
2. Or download the APK artifact from the latest CI run on `main`.

Builds are signed with the project's stable dev keystore committed in `keystore/`, so new builds install over the top of old ones without a signature-mismatch uninstall.

## Build from source

Requires JDK 17 and Android SDK platform 35 / build-tools 35.0.0.

```bash
./gradlew testDebugUnitTest   # JVM unit tests
./gradlew assembleRelease     # release APK (dev-signed)
```

CI (GitHub Actions) runs the unit tests and then builds the release APK on every push to `main`.

## Build notes

- On-device vision uses `org.tensorflow.lite:tensorflow-lite` + `tensorflow-lite-gpu` (2.14.0) behind the `HaramBlurEngine` interface.
- NewPipeExtractor is resolved from JitPack: `com.github.TeamNewPipe:NewPipeExtractor:v0.24.4`.
- Room is 2.6.1 with KSP; Coil is 3.0.4 (`coil-compose` + `coil-network-okhttp`).

## Architecture

```text
app/src/main/java/com/puretube/app/
├── PureTubeApp.kt / MainActivity.kt     App entry, PiP, deep links, shield consent
├── data/
│   ├── local/                           Room entities, DAOs, AppDatabase, DataStore preferences
│   ├── remote/extractor/                NewPipe downloader, stream repository, Shorts filter
│   ├── remote/sponsorblock/             Hash-prefix skip segments
│   ├── remote/dearrow/ · remote/ryd/    Neutral titles · dislike counts
│   └── backup/                          NewPipe JSON / Takeout CSV import-export
├── haramblur/
│   ├── engine/                          Detection coordinator, box math, tracker, Coil pre-blur
│   ├── video/                           12 FPS frame sampler, Compose mask overlay
│   └── system/                          System Shield service, motion detector, QS tile
├── playback/                            MediaSession service, controller, mono, PiP, pocket skip, cast
└── ui/                                  Pure OLED theme, components, screens
```

## Tests

JVM unit tests cover: Shorts/URL normalization and video-ID extraction, Shorts filtering and blocklist filtering, bounding-box expansion/IoU/NMS, EMA smoothing and the 450 ms hysteresis hold, strict-sensitivity unknown-subject handling, SponsorBlock SHA-256 prefix (known vector) and segment parsing, mono downmix sample math, NewPipe JSON / Takeout CSV import parsing and export round-trip, and the 92% watch-completion threshold.

## License

MIT — see [LICENSE](LICENSE).
