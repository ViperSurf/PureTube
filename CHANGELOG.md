# Changelog

## v1.4.0 — 2026-11-18

In-video HaramBlur fix, player UX, rich descriptions, comment replies.

### In-video HaramBlur — the headline fix
- Root cause of live-video blur never firing: the sampler captured frames with `PixelCopy.request(SurfaceView, …)` against the player's SurfaceView; on real devices that path routinely failed (or returned black), and the sampler's defensive design swallowed every failure silently — thumbnails blurred, live video never did.
- The player now renders into a **TextureView** (`MediaController.setVideoTextureView`, cleared on release; PlayerView is gone from the full player), and the sampler copies frames with **TextureView.getBitmap()** into the same 320×180 buffer at ~12 FPS on its own thread — no PixelCopy anywhere. Same EMA tracker (alpha 0.65) and 450 ms hold, all failures still silent no-ops in the player.
- **Sampler diagnostics** now exist so this can never fail invisibly again: Settings > HaramBlur status shows frames sampled, last frame mean brightness (0–255), last detection count, and the last sampler error. The same numbers ride along in the player's Copy diagnostics.
- **Face box shrunk to face/head only:** expansion is now ~0.20 width each side, 0.25 top, 0.20 bottom (chin/neck, no body) — down from 0.45 / 0.40 / 0.85 hair-and-shoulders. Applies to live video and thumbnails.
- The HaramBlur status text is **removed from the player**; status lives only in Settings.

### Player UX
- **Drag-down-to-minimize:** a visible drag-handle pill above the title — pull it down past ~120 dp to minimize. (Swipe-down on the video itself is intentionally not used: vertical drags there are brightness/volume.)
- **Swipe controls:** vertical drag on the video's LEFT third adjusts window brightness, RIGHT third adjusts media volume, with a small centre-top % indicator while dragging. Full gesture map (tap = controls, double-tap = seek, hold = 2×, handle = minimize, sides = brightness/volume) is documented in the README.
- **Controls visibility** setting: Auto-hide (3 s while playing) or Always visible.
- **Rotate chip** next to Minimize: "Rotate: Auto" / "Rotate: Locked" — Auto lets the player rotate with the sensor (landscape included), Locked pins portrait; leaving the player restores the system default.
- **PiP chip** now carries the Picture-in-Picture icon + "PiP" text, still immediately right of Sleep.
- **Seek sliders in the mini players:** the dock and the floating mini window both show a live seek slider with position/duration and support scrubbing.
- **Unmute raises volume** (on by default): unmuting while the device media volume is 0 raises it to ~40%; the sound chip shows a "Vol 0" hint in that state.

### Likes + description
- **Likes display** setting: Numbers, Percentage, or Both — the player's like/dislike line uses the Return YouTube Dislike counts with pure percentage math (hidden when counts are unknown).
- **Formatted descriptions:** HTML is parsed into segments — line breaks and paragraphs become newlines, entities are decoded, runs of blank lines collapse to one, **links are tappable** (open in the browser), and **timestamps are tappable** (seek the player). Still collapsed by default and gated by the Description toggle.

### Comments + sticky video
- **Comment replies:** comments with replies get a "View replies (N)" expander that fetches them through the extractor's real replies API (`CommentsInfoItem.getReplies()` continuation Page → `CommentsInfo.getMoreItems(service, url, page)`), with the same comment filters applied; failures say "Replies unavailable for this comment."
- **Sticky video** (on by default): the video + seek + chips stay pinned as a header while the title, actions, description and comments scroll underneath; turn it off for the old single-scroll page.

### Search reset + channel exemptions
- **Double-tap Search resets** (on by default): tapping the Search tab while already on Search clears the query, results, type filter and scroll.
- **HaramBlur channel exemptions:** "Don't blur this channel" in the player's actions and on the channel screen — exempt channels are never blurred in thumbnails (Coil) or live video (sampler). Exemptions are listed with Remove buttons in Settings. Stored as a new blocklist filter type (TEXT column — no database migration, DB stays v2).

### Tests
- 88 JVM unit tests (was 69): DescriptionFormatter (tags, links, entities, timestamps, blank-line collapse), likes percentage + format modes, the new face-box padding, the exemption decision, controls/search-reset policies and swipe-control math.

## v1.3.0 — 2026-11-18

Field-test fix release: search NPE, real HaramBlur, player controls, audio-only.

### Search NPE — fixed
- Searching a channel name under the Videos filter (e.g. "ssundee") or a term under Channels (e.g. "minecraft") failed with "NullPointerException: getOrDefault (.) must not be null". Cause: NewPipe 0.27.1 InfoItem getters are Java platform types annotated @Nonnull that return null at runtime for some typed-search item shapes; the mapping code assigned them straight into non-null Kotlin fields and used `runCatching { … }.getOrDefault(…)` patterns, so one null getter threw and failed the whole search. Now every getter is read into a nullable local first, mapping runs on raw nullable holders with defaults, and each item maps inside its own runCatching — a bad item is skipped, never fatal. Search failures show the exception class + top stack frames with a Copy diagnostics button; a genuinely empty result shows "No results".

### HaramBlur — actually works now
- The official MediaPipe BlazeFace short-range float16 model is bundled (229,746 B, SHA-256 b4578f35940bf5a1a655214a1cce5cab13eba73c1297cd78e1a04c2380b0152f). Its real tensors were verified (input [1,128,128,3]; regressors [1,896,16]; classifiers [1,896,1]) and the anchor decode was rewritten to the standard 896-anchor scheme (the old code used a wrong grid and swapped dx/dy); a host-side LiteRT run on the bundled test portrait lands a box on the face at ~0.84 confidence.
- No gender model is bundled, so ALL detected faces are blurred regardless of Who-to-Blur, and the UI says so: the player pill and a new Settings HaramBlur status block show face-model loaded?, mode (All-faces fallback / Gender model), and the last load error. A Test detection button runs the engine on a bundled public-domain portrait and reports the region count.
- The Coil thumbnail cache key carries a model-revision token so pre-v1.3 cached unblurred thumbnails are never served; BOTH rows and thumbnail cells now use the same protected thumbnail pipeline as full cards.

### Player controls + reopen freeze
- Reopening the video that is already loaded attaches instantly from a playback-session cache — no re-extraction, no setMediaItem — across full → dock → floating → full.
- The PlayerView's own controller is disabled. A single tap toggles PureTube's controls (state chips + seek bar); they auto-hide after ~3 s while playing and always come back on tap. Double-tap seek and hold-to-2× remain.

### Audio-only + toggle redesign
- One row of state chips: Sound Unmuted/Muted (red), Media Video + Audio / Audio Only (red when on), Sleep Timer, PiP immediately right of Sleep, Speed (tap cycles, long-press opens the slider inline — the standalone Speed slider button is gone), ⌄ Minimize on the player.
- Audio Only is single-source state re-applied on every attach: on, the picture is removed everywhere (placeholder + title + "Audio Only" label in the full player and floating window, label in the dock); off, the renderer is re-enabled and the surface re-binds in place.
- New preferences: Launch Videos in Audio-Only (off by default), Comments (on), Description (on). The player gains a collapsed-by-default Description section; comments/description are never fetched or shown when their toggle is off.

### Layout, floating window, PiP, home feed
- The shared display controls (layout incl. Thumbnail-only/Title-only, thumbnail scale, grid columns) now appear on Home/Subscriptions, Library (incl. playlist detail), and Channel screens.
- Floating mini window: S/M/L resize, explicit Expand, and Close now collapses to the dock with playback continuing.
- System PiP hides the bottom navigation, dock, floating window and notices — the PiP window shows only the video.
- Subscriptions All feed shows "Loading your feed…" with a spinner during the first load; "Nothing in the feed yet. Tap Refresh to load each channel's newest videos." appears only after a completed empty load.


## v1.2.0 — 2026-11-18

Field-test fix release: playback failover, search, queue visibility, imports.

### Playback: extraction failover (the v1.1.0 "Failover HTTP 403")
- NewPipeExtractor upgraded v0.24.4 -> v0.27.1 (the old pin predates current YouTube-side changes).
- Extraction is now a logged multi-stage failover: NewPipe primary, NewPipe retry, the user's configured Piped instance, several built-in Piped instances, then several built-in Invidious instances. Any stage that yields a playable progressive/HLS URL plays. Nothing throws silently: the player error and Copy Diagnostics show the resolved title, which stage supplied the streams, and a one-line result per stage (class + message, no more blank causes).

### Saved playlists by link
- Playlist URLs are canonicalised (watch+list links become real playlist URLs) and contents are fetched across all pages and deduplicated. The playlist screen shows the video count, surfaces load warnings/errors in-screen, and Play All / Queue All confirm with the video count.

### Search
- Type filter chips: All / Videos / Playlists / Channels, with per-type results (playlist results open/bookmark the playlist, channel results open the channel page, type badges on mixed results).
- Pagination: results keep loading past the first ~20 — auto-load near the bottom plus a Load more button, deduplicated, with a growing "N results +" count. Channel videos paginate too.
- Recent searches: the last 20 searches appear on the empty search screen (when search history is on), tap to re-run, remove one, or clear all.
- Scale & layout: thumbnail scale (Compact / Normal / Large), layouts List / Grid / Both (thumbnail+title) / Thumbnail only / Title only, and a 2/3/4 grid-columns selector — quick controls on the Search screen, honoured by Search, Subscriptions, Library, Channel, and Playlist screens.
- Search state now lives outside the screen: opening a video and coming back keeps the query, results, filter, and scroll position.

### Player & queue
- Mute button turns RED while muted (player and mini dock).
- The queue is visible: a Queue screen (order, now-playing highlight, tap-to-play, remove, up/down reorder, clear all, empty-state help) reachable from the player ("Up next: …" + Queue (N)), the mini dock badge, and the Library. Adding to the queue shows an in-app "Added to queue (N)" notice.
- Minimize: the player's Minimize button (and the dock's float button) opens a draggable floating mini window (16:9, ~1/3 screen width) over any screen — same player, surface moved, not duplicated — with play/pause, close, and tap-to-expand. Back from the player keeps playback going in the mini dock, which now shows live progress and a red mute toggle.

### Settings
- All sections start closed; sections render in their numbered order 1-7 with Diagnostics last (it previously rendered at the top).
- "Reset all settings" (with confirmation) plus a per-section reset button on every section.
- New preferences: thumbnail scale, grid columns, history display mode.

### Library
- History display toggle: grouped per day (Today, Yesterday, then one section per day) or one continuous list; the visible-days window works in both modes.

### Imports & channel add
- Subscription import is structure-tolerant and content-sniffed: NewPipe JSON, Google Takeout (CSV, JSON, OPML), SkyTube JSON, Morphe-style JSON, generic OPML, generic CSV (synonym headers, any order), and plain-text URL/handle lists. It reports "imported N (format), skipped M unrecognised rows" instead of failing the file.
- Add Channel is fuzzy: a bare name (e.g. "ssundee") runs a channel search — one match is added with a status naming it, several matches show a tap-to-add picker. Channel IDs, @handles, and URLs still resolve directly, and errors show the full cause instead of a blank message.


## v1.1.0 — 2026-11-18

Crash fix release plus a full feature pass.

### Crash fixes (from the v1.0.0 field report: search worked, tapping a video crashed)

- The player host activity is now a `FragmentActivity`, which the cast `MediaRouteButton` requires; building the cast button is also guarded so any failure hides it instead of crashing.
- The live-video frame sampler was rewritten defensively: cancellable PixelCopy requests, the whole loop guarded, sampling only once playback is running, clean shutdown (job cancelled before its thread quits), and a settings kill switch.
- Streams now carry explicit DASH/HLS MIME types, player errors are recorded and shown in-player, and a failing stream falls back in-player (adaptive -> progressive -> audio-only) before any error message. A late media-controller connection shows a connecting state with retries instead of a silent dead end.
- Uncaught exceptions are recorded to an on-device crash file and shown under Settings > Diagnostics with Copy and Clear; the original crash behaviour is preserved.

### New

- Play queue with autoplay: queue from any card menu or the player, queued videos play next, then autoplay can pick a short-enough video from the same channel (short-only and max-minutes rules in Settings).
- Sleep timer (Off, 5, 10, 15, 30, 45, 60 minutes; default preselectable) that pauses playback and notes "Sleep timer ended".
- Video configure menu on every card and in the player: Watch Later, Add to Queue, Mark Watched/Unwatched, Add to Playlist (create with Public/Unlisted/Private visibility), Subscribe/Unsubscribe, Block channel by name or ID.
- Channel pages: tap a channel name or avatar to open its page with a Subscribe toggle and its videos.
- Subscriptions: All chip aggregates every channel newest-first (per-channel refresh threshold, Refresh bypasses it), per-channel chips, and manual channel add by URL, @handle, or ID.
- Library rework: bookmarked playlists, my playlists with visibility badges and a detail screen (play, remove, rename, visibility, delete, export, share for Public/Unlisted), Watch Later, history grouped Today / Yesterday / This week / This month / Older with a visible-days window, and clear actions for watched history, all history, search history, playback positions, and subscriptions.
- File import/export as the headline route: subscriptions (JSON/CSV), playlists (JSON/CSV/TXT of links), and settings (JSON) via the system file picker; paste/clipboard kept as a secondary route.
- Comments under the player, collapsed by default, filtered by blocked comment keywords, usernames, and channel IDs plus like-count ranges; video like-count ranges filter search and feeds when counts are known.
- Feed layouts everywhere: List, Grid, Thumbnail only, Title only.
- SponsorBlock: optional skip notification pill, and rewinding back into an already-skipped segment stops it being skipped again for that video.
- Many new preferences, all with (?) explanations: watch/search history toggles, search suggestions (off by default — the empty search screen stays clean), autoplay, resume rules, sleep default, feed refresh interval, app language (applies on restart), content country, preferred audio language, audio focus requirement, and Android Auto disabled by default.
- Data layer v2 with a real Room migration (no data loss): playlist visibility, bookmarked playlists, search history, and comment/channel-name block filters.
- Account sign-in is NOT included yet (see the roadmap in the README).


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
