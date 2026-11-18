# Changelog

## v1.0.0 — 2026-11-18

First release.

- Air-gapped Android YouTube client: Pure OLED (`#000000`) Compose UI with Subscriptions, Search, Library, and Settings tabs, plus a full player screen.
- Dual-layer HaramBlur engine, 100% on-device (TensorFlow Lite):
  - In-app layer: pre-blurred Coil thumbnails (zero-flash), 12 FPS live-video frame sampling with EMA smoothing and 450 ms hysteresis hold, frosted / pixelate / solid-curtain mask styles, hair-and-shoulders box expansion, gender-target and sensitivity controls, per-card peek override.
  - System-wide shield: MediaProjection consent handshake, click-through overlay window, motion-delta inference skipping, Quick Settings tile.
  - Graceful no-op when model files are absent from `assets/models/`.
- Zero Shorts engine: Shorts stripped from search and feeds; `/shorts/` and `youtu.be` links normalized into the regular player.
- Extraction: NewPipeExtractor primary with silent failover to a configurable Piped API.
- Playback: Media3 MediaSession background service, 1-tap audio-only mode (renderer disable, no re-buffer), mono merge audio processor, loudness normalization, native PiP with Play/Mute/+10 s actions and auto-enter, pocket volume-button long-press skip, Cast via MediaRouter, hold-to-2× and edge-safe double-tap seek.
- Community metadata: SponsorBlock auto-skip via SHA-256 hash-prefix lookup with undo pill, DeArrow neutral titles, Return YouTube Dislike counts.
- Local data: Room subscriptions / watch progress (92% completion) / playlists / blocklist, DataStore preferences for every setting, offline NewPipe JSON and YouTube Takeout CSV import/export.
- Flat settings screen with instant search, four collapsible sections, and an inline (?) explanation on every control.
- 23 JVM unit tests covering URL normalization, Shorts filtering, blur-box math and tracking, SponsorBlock hashing and parsing, mono downmix, import parsing, and completion threshold.
