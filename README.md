# PureTube

Air-gapped Android YouTube client with the dual-layer, on-device **HaramBlur** protection engine. Pure OLED interface, no Shorts, SponsorBlock, background play, and 1-tap audio-only play.

- Package: `com.puretube.app`
- Version: 2.0.0 (versionCode 7)
- minSdk 26 · target/compileSdk 35
- Kotlin · Jetpack Compose · Media3 (ExoPlayer) · Room · DataStore · Coil 3 · NewPipeExtractor

## Privacy statement

- **100% on-device inference.** HaramBlur runs entirely on the phone with TensorFlow Lite. No frame, thumbnail, or bitmap is ever written to storage or sent over the network for scanning.
- **Air-gapped models.** The vision models live in `assets/models/` on the device and make zero network calls.
- **Privacy-preserving lookups.** SponsorBlock queries send only the first 4 hex characters of the video ID's SHA-256 hash — never the full ID.
- No ads, no analytics, no Google Play Services dependency. An **optional** YouTube sign-in exists (v2.0): your session cookies are stored only in Keystore-encrypted storage on the device, are sent only to youtube.com, and are never part of settings exports. Everything works fully signed out.

## Features

### HaramBlur — dual-layer protection

- **Layer 1 (in-app):** every thumbnail passes through a Coil transformation that detects and blurs *before* the image is ever displayed, so unblurred pixels never flash for a single frame. Live video is sampled at 12 FPS (83 ms) on a dedicated background thread into a 640×360 buffer (v1.5, raised from 320×180 so small faces survive the downsample); detected regions are drawn as a hardware-accelerated Compose Canvas mask with EMA smoothing (alpha 0.65) and a 450 ms hysteresis hold so a head-turn never flickers the blur off. When a full-frame detection pass finds nothing, a **tiled second pass** retries on 2×2 overlapping quadrants of the frame — small/distant faces occupy far more of the detector's input inside a tile (video: at a reduced cadence, only after consecutive empty full passes; thumbnails: always, plus a 2× upscale retry for thumbnails under 256 px wide). A zero-flash gate keeps an opaque curtain over the surface until the first frame has been scanned, and re-arms on every seek or source change.
- **TextureView capture (v1.4):** the player renders into a `TextureView` (bound via `Player.setVideoTextureView`) and the sampler reads frames back with `TextureView.getBitmap()` — the v1.3 PixelCopy-on-SurfaceView capture silently produced no frames on real devices. Battery note: TextureView costs a little more GPU/composition than SurfaceView; that is the deliberate price of a sampler that can actually read frames, and sampling still only runs while a video plays (and stops entirely in Audio Only mode). Sampler diagnostics (frames sampled, last frame brightness, last detection count, last error) are shown in Settings > HaramBlur status.
- **Real pixel patches + true mapping (v2.0):** a full audit of the video path found the overlay never drew any part of the actual frame — every style was a flat translucent dark box (nearly invisible on dark scenes, and never readable as "blur") — and it mapped regions against the container, assuming the picture filled it. Now the sampler publishes a 128×72 pixel copy of each sampled frame and the overlay draws **real pixelated patches** of the frame itself (mosaic: upscaled with filtering off; frosted: the same patch, smoothed, plus a frost tint; solid curtain unchanged). The TextureView is laid out at exactly the **displayed-video rect** for the active aspect mode, so detections (in video-frame space) map through that rect — letterbox bars are never blurred and cropped ZOOM regions land correctly. Sampler diagnostics now also report: sampler running (yes/no), surface size, video size, last detections, and **regions actually drawn by the overlay**, so a field report can separate "not detecting" from "detecting but not drawing".
- **Layer 2 (system-wide shield):** an opt-in `MediaProjection` + click-through overlay (`FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE`) that protects other apps. It starts only after Android's screen-capture consent returns OK (foreground service type `mediaProjection`, kept separate from playback), skips inference when the screen is static (motion-delta checksum), and has a Quick Settings tile ("HaramBlur Shield").
- Detected face boxes are expanded modestly around the face/head only (v1.4: ~20% width each side, 25% top, 20% bottom — chin/neck, no body; earlier versions used a much larger hair-and-shoulders box). **Blur Scope (v1.5):** `FACE_ONLY` (default) keeps that tight box; `FACE_AND_BODY` widens it into an approximated person region (~1.6× wider, extending ~2.5 face-heights down from the face top) — a geometric estimate from the face box, not a body detection. The scope applies to thumbnails, live video, and the system shield. **Channel exemptions:** mark a channel "Don't blur this channel" (player actions or the channel screen) and its thumbnails and videos are never blurred; exemptions are listed with Remove buttons in Settings. Choose who to blur (females only / males only / all people), what to protect (images / videos / both), and the visual style: Frosted Gaussian, Pixelate Mosaic, or Solid Dim Curtain. At strict sensitivity, an uncertain classification is blurred under the females-only rule rather than let through.
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

### YouTube account (v2.0, optional)

Sign-in lives in **Settings → Account**. It uses the standard third-party-client pattern: your own YouTube **session cookies** + SAPISIDHASH authorization against YouTube's internal Innertube API (NewPipeExtractor has no write support, so a small raw Innertube client handles the account calls).

- **Sign in (primary): paste your cookies.** On a computer, open youtube.com signed in, open DevTools → Network, click any request to youtube.com, and copy the full **Cookie** request header (it must include a `SAPISID` cookie). Paste it into Settings → Account and tap **Validate & sign in** — PureTube checks it against YouTube and shows your channel name and avatar before keeping anything.
- **Sign in (experimental): in-app WebView.** A WebView opens youtube.com so you can sign in with Google directly (PureTube never sees your password — the page talks to Google). Afterwards, "Use cookies from this page" captures the session. Labelled experimental because Google blocks embedded-browser sign-in for some accounts.
- **Security.** Cookies are session credentials. They are stored **only** in Android Keystore-backed encrypted storage (`EncryptedSharedPreferences`, AES256-GCM), never in Room/DataStore plaintext, **never in settings export/import**, and never logged. **Sign out** wipes them. No Google password is ever collected by the app.
- **What works signed in:** **Like / Dislike** in the player are real (optimistic UI, rolled back on failure; signed-out taps route to sign-in). **Subscribe** in the player and on channel pages performs the real subscribe — YouTube's state wins, the local row follows it. **Comment posting** from the box at the top of comments. **Up Next / Related** prefers the authenticated watch-next results. **Sync now** (Settings → Account) mirrors your account into the local library: playlists (marked "YouTube • synced"), watch history (merged — local resume positions always win), subscriptions (missing ones added), and Liked videos as a local playlist.
- **Honest limits.** This is **unofficial session use**: YouTube can expire or revoke the session at any time (you'll see "Session expired — sign in again" and can just sign in again). Sync is **pull-only** — local changes never push back except the direct like/subscribe/comment actions themselves. Synced playlists keep their local visibility private because the endpoints used don't expose the account-side visibility. Comment posting depends on a token buried in YouTube's watch-page payloads; if YouTube reshuffles them, posting fails with a visible message rather than silently.

### Playback

- Background play through a Media3 `MediaSessionService` with a full media notification.
- **1-tap Audio-Only mode:** disables the video renderer and detaches the surface — the media item is never replaced, so audio continues with zero interruption while saving data and battery.
- **Mono merge** audio processor (true PCM16 stereo→mono averaging) for single-earbud listening, and **loudness normalization** (+350 mB target gain).
- **Native PiP** with Play/Pause, Mute, and +10 s remote actions; auto-enter on Home (Android 12+) when enabled and something is actually playing. v1.5: the Mute action uses **two custom icons** (speaker + sound waves vs speaker + X + slash — the old system pair was indistinguishable at PiP size), and the PiP params are **refreshed live** whenever play/mute state changes while in PiP, so the icons flip in place. While in PiP the activity renders **only the video surface**, whatever tab is underneath — PiP entered from the minimized/dock state shows the video, not the Home tab — and the PiP window animates from the video view's real bounds (sourceRectHint). Rotating the full player to **landscape is fullscreen**: bottom nav, dock and floating window hide until you rotate back.
- **Pocket volume-button skip:** hold Volume Up/Down for 0.6 s to seek ±10 s.
- **Volume-zero rule (v2.0):** when the device media volume reaches 0 while a video plays, playback **pauses**; if the volume stays at 0 for the configured delay (15 s / 30 s default / 1 min / 5 min / Never), playback **stops** completely, with the position saved first. Raising the volume again **never** auto-resumes — restarting is always manual via the big Play button. Both behaviours are Settings toggles ("Pause When Volume Hits Zero", "Stop If Volume Stays Zero"), and the player shows an in-place notice when the rule fires.
- **Aspect modes (v2.0):** the picture is **not stretched by default**. An **Aspect** state chip in the player cycles **Fit** (letterboxed, undistorted — the default) / **Zoom** (centre-crop fills the screen) / **Stretch** (fills by distorting), persisted as a setting; the HaramBlur overlay follows the active mode's displayed-video rect.
- **Immersive fullscreen (v2.0):** landscape fullscreen hides the system status bar and nav bar (transient-by-swipe) and restores them on exit. The fullscreen control stack is laid out in separate bands — title/author top-left, centre play in the middle, chips row above, seek slider pinned to the very bottom edge — so nothing overlaps.
- **Drag moves the whole page (v2.0):** the minimize drag translates the entire player page (video + title + chips + description/comments) as one rigid unit instead of sliding the video over or under the rest.
- Player controls: a **single tap** on the video toggles PureTube's own controls (state chips + seek bar), which auto-hide after ~3 s while playing (or stay always visible — your choice in Settings) and always return on the next tap. One row of **state chips** shows state in words + colour: **Sound** (Unmuted / Muted, red when muted; shows a Vol 0 hint when the device volume is 0 — and unmuting then raises device volume to ~40% by default), **Media** (Video + Audio / Audio Only, red when audio-only), **Sleep Timer**, **PiP** (icon + text, immediately right of Sleep), **Speed** (tap cycles 1.0×→1.25×→1.5×→2.0×; **long-press** opens the 0.25×–4.0× slider inline), **Rotate** (Auto / Locked, next to Minimize), **⌄ Minimize**, and Cast. HaramBlur status lives only in Settings (v1.4).
- **Gesture map (v1.5):** tap = toggle controls · double-tap left/right = seek ∓/± · press-and-hold = 2× fast-forward · **drag DOWN on the video = minimize** (the video follows your finger; release past ~120 dp to minimize, otherwise it springs back) · drag the handle pill above the title down = minimize (secondary) · on the floating window or dock, **drag UP = expand** · tap the floating window = expand. **Brightness/volume swipes are opt-in** (Settings → "Swipe Brightness/Volume Controls", default OFF): when ON, the LEFT third of the video drags brightness and the RIGHT third volume (centre-top % indicator), and minimize drags start from the centre third or the handle. When OFF, vertical drags never change brightness/volume.
- **Floating mini window (v1.5):** just the video — no buttons or overlays on it at all. Drag it to move it, **pinch or drag its corner grip to resize** it dynamically (16:9, clamped), tap or drag up to expand. All its controls live in the dock, which has a **"Mini window" state button** (highlighted while the window is visible) to show/hide it; hiding it keeps playback running in the dock. The dock keeps its live seek slider.
- **Sticky video (v1.4, on by default):** video + seek + chips stay pinned while the title, description and comments scroll; turn it off for one scrolling page. **Descriptions** are formatted: links open the browser, timestamps seek. **Comment replies** load per comment via "View replies (N)". **Likes display** can be Numbers, Percentage, or Both. Tapping the **Search tab while already on Search** clears the search (query, results, filter, scroll) when the Double-Tap Search Resets setting is on.
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

- `model_gender_q.tflite` — a small **gender classifier** (1,356,880 bytes), so Who-to-Blur (Females only by default) is a real classification from v1.5.
  - Source: https://github.com/shubham0204/Age-Gender_Estimation_TF-Android (`app/src/main/assets/model_gender_q.tflite`, MIT License; trained by that project's author on the UTKFace dataset).
  - SHA-256: `7ab000d530a005ccd605a2826485b23d08690d0e4fb23bc4589a5b03861750c0`
  - Verified host-side with LiteRT before bundling: valid TFLite (TFL3 magic), input `[1,128,128,3]` float32 normalized to [0,1], output `[1,2]` float32 probabilities ordered **[male, female]** (per the source project's own code), summing to 1.0. On the bundled test portrait (a man), a tight face crop classifies Male at ~0.88–0.90. The model expects a TIGHT face crop (UTKFace-style): the app classifies the detected face box with only a small margin, because loose, background-heavy crops can mislead it. It is a small model — expect occasional mistakes on unusual angles, heavy occlusion, or animation.

**How detection works now:** the bundled face model is loaded on device; detected faces are expanded per the Blur Scope setting and blurred in Coil thumbnails (pre-blur, before display) and in the live-video sampler mask. Each face is then classified by the bundled gender model and the Who-to-Blur target (females only / males only / all people) is applied for real. If the gender model ever fails to load, HaramBlur falls back to blurring **all** detected faces regardless of the Who-to-Blur setting — the Settings HaramBlur status block says which mode is live ("Gender model" vs "All-faces fallback (no gender model)"), and also shows whether the face model loaded, the last load error if any, and sampler diagnostics (frames sampled, brightness, last detection count, **last detection pass — full/tiled/upscale — and total detections since play start**). Settings has a **Test detection** button that runs the engine on a bundled public-domain portrait (Solly Hofman, Library of Congress via Wikimedia Commons, `assets/test/face_test.jpg`) and reports how many face regions it found, including whether the gender model classified the (male) portrait as male under the Female-only target. Engine failures surface as status, never as crashes.

## Install

1. Download `PureTube-v1.5.0.apk` from the GitHub Release and open it on your phone (allow "install from this app" for your browser/Files when Android asks).
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

JVM unit tests (88) cover: Shorts/URL normalization and video-ID extraction, Shorts filtering and blocklist filtering, bounding-box expansion/IoU/NMS, description formatting (tags/links/entities/timestamps), likes percentage math, HaramBlur channel-exemption decisions, swipe-control math, EMA smoothing and the 450 ms hysteresis hold, strict-sensitivity unknown-subject handling, SponsorBlock SHA-256 prefix (known vector) and segment parsing, mono downmix sample math, NewPipe JSON / Takeout CSV import parsing and export round-trip, and the 92% watch-completion threshold.

## License

MIT — see [LICENSE](LICENSE).
