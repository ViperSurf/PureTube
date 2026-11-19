# PureTube

Air-gapped Android YouTube client with the dual-layer, on-device **HaramBlur** protection engine. Pure OLED interface, no Shorts, SponsorBlock, background play, and 1-tap audio-only play.

- Package: `com.puretube.app`
- Version: 1.3.0 (versionCode 4)
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

### What's new in v1.1

- **Crash-hardened player:** the player opens even when extraction fails, the media service connects late, models are absent, or a stream URL errors — failures become in-player messages with an automatic fallback chain (adaptive -> progressive -> audio-only) and a Copy diagnostics button. Uncaught crashes are recorded on-device and viewable under Settings > Diagnostics.
- **Queue & autoplay:** every video card and the player have a configure menu (Watch Later, Add to Queue, Mark Watched/Unwatched, Add to Playlist, Subscribe/Unsubscribe, Block channel by name or ID). When a video ends, the queue plays next; with an empty queue, autoplay can pick a same-channel video that fits the short-only / max-minutes rules.
- **Sleep timer:** Off, 5, 10, 15, 30, 45, 60 minutes from the player, with a preselectable default; expiry pauses playback and shows "Sleep timer ended".
- **Channel pages:** tap a channel name or avatar anywhere to open its page (Subscribe toggle + its videos).
- **Subscriptions feed:** the All chip aggregates all channels newest-first with a per-channel refresh threshold (Refresh bypasses it), per-channel chips filter, and channels can be added manually by URL, @handle, or ID.
- **Library:** bookmarked playlists, playlists with Public/Unlisted/Private visibility (detail screen with play, rename, visibility, delete, export, and share for non-private lists), Watch Later, and history grouped Today / Yesterday / This week / This month / Older with a visible-days window.
- **File import/export first:** subscriptions (JSON/CSV), playlists (JSON/CSV/TXT of links), and settings (JSON) move through the system file picker; paste/clipboard remains as a secondary route.
- **Filters:** comment blocking by keyword, username, and channel ID; like-count ranges for videos and comments; four feed layouts (List, Grid, Thumbnail only, Title only) shared by Search, Subscriptions, Library, Channel, and Playlist screens.
- **SponsorBlock refinements:** the skip pill can be silenced, and rewinding into an already-skipped segment stops it being skipped again for that video.
- **Playback defaults:** audio focus is required by default (other apps pause PureTube and vice versa), and Android Auto is disabled by default — PureTube refuses the car-projection controller. Both are changeable in Settings.
- **Diagnostics:** Settings shows the last recorded crash (if any) with Copy and Clear, plus import/export and clear-data actions.

### Roadmap

- Account sign-in is **not** included yet — PureTube still works fully without any account, and no sign-in or token code ships in this release. If it arrives later it will be optional and off by default.

### Playback

- Background play through a Media3 `MediaSessionService` with a full media notification.
- **1-tap Audio-Only mode:** disables the video renderer and detaches the surface — the media item is never replaced, so audio continues with zero interruption while saving data and battery.
- **Mono merge** audio processor (true PCM16 stereo→mono averaging) for single-earbud listening, and **loudness normalization** (+350 mB target gain).
- **Native PiP** with Play/Pause, Mute, and +10 s remote actions; auto-enter on Home (Android 12+) when enabled.
- **Pocket volume-button skip:** hold Volume Up/Down for 0.6 s to seek ±10 s.
- Player controls (v1.3): the PlayerView's own controller is disabled; a **single tap** on the video toggles PureTube's own controls (state chips + seek bar), which auto-hide after ~3 s while playing and always return on the next tap. One row of **state chips** shows state in words + colour: **Sound** (Unmuted / Muted, red when muted), **Media** (Video + Audio / Audio Only, red when audio-only), **Sleep Timer**, **PiP** (immediately right of Sleep), **Speed** (tap cycles 1.0×→1.25×→1.5×→2.0×; **long-press** opens the 0.25×–4.0× slider inline), **⌄ Minimize**, the real HaramBlur mode, and Cast.
- **Audio Only** is a single state owned by the player controller and re-applied on every surface: on, the picture is removed everywhere (full player and floating window show a thumbnail + title + "Audio Only" placeholder, the dock shows an Audio Only label); off, the renderer is re-enabled first and the surface re-binds in place — no minimize/reopen dance, audio never interrupts.
- **Launch in Audio-Only** (Settings, off by default): new videos open audio-only until you tap Video + Audio.
- **Comments / Description toggles** (Settings, on by default): when off, that section is never fetched or shown. The description section is collapsed by default.
- **Playback-session cache:** reopening the video that is already loaded (full → dock → floating → full) attaches instantly without re-extracting or re-preparing; only a different video extracts.
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

HaramBlur ships with a real face model bundled in `app/src/main/assets/models/`:

- `blaze_face_short_range.tflite` — the official MediaPipe **BlazeFace short-range** float16 face detector (229,746 bytes).
  - Source: https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite
  - SHA-256: `b4578f35940bf5a1a655214a1cce5cab13eba73c1297cd78e1a04c2380b0152f`
  - Tensors (verified from the file and by host-side LiteRT inference): input `[1,128,128,3]` float normalized to [-1,1]; outputs regressors `[1,896,16]` and classifiers `[1,896,1]`. Anchors are the standard MediaPipe short-range scheme (stride 8 × 2 + stride 16 × 6 per cell, cell-major, 896 total) and decoding is linear in 128-px units — see `BlazeFaceAnchors`. A host-side run on the bundled test portrait produces a face box over the face at ~0.84 confidence.

**How detection works now (v1.3):** the bundled model is loaded on device; detected faces are expanded to cover hair and shoulders and blurred in Coil thumbnails (pre-blur, before display) and in the live-video sampler mask. **No gender model is bundled**, so until a `gender_attr_classifier_int8.tflite` is dropped into `assets/models/`, HaramBlur blurs **all** detected faces regardless of the Who-to-Blur setting — the player pill and the Settings HaramBlur status block say so plainly ("All-faces fallback (no gender model)"), and also show whether the face model loaded and the last load error, if any. Settings has a **Test detection** button that runs the engine on a bundled public-domain portrait (Solly Hofman, Library of Congress via Wikimedia Commons, `assets/test/face_test.jpg`) and reports how many face regions it found. Engine failures surface as status, never as crashes.

## Install

1. Download `PureTube-v1.3.0.apk` from the GitHub Release and open it on your phone (allow "install from this app" for your browser/Files when Android asks).
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

- On-device vision uses `org.tensorflow:tensorflow-lite` + `tensorflow-lite-gpu` (2.17.0) behind the `HaramBlurEngine` interface.
- NewPipeExtractor is resolved from JitPack: `com.github.TeamNewPipe:NewPipeExtractor:v0.27.1`.
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
