# Vynox

**Motion, on your terms.** An offline-first native Android video and motion editor, with portable **`.vnx`** projects and a GitHub-native signed APK release pipeline.

[Download website](https://gredeye.github.io/editor/) · [Releases](https://github.com/gredeye/editor/releases) · [Verification status](VERIFICATION.md) · [Project format](docs/VNX_FORMAT.md)

> **Status: initial implementation, not yet production-certified.** Real editing, composition rendering and platform encoding paths are implemented—not a web mockup or screen recorder. The Android application sources have been independently type-checked and 30 JVM tests pass, and the Android 35 emulator suite now passes (including activity startup and real MP4 export). Full lint/build CI is still being resolved; see verification status. Read [VERIFICATION.md](VERIFICATION.md) before treating this as release-ready. A download is available only after a signed release succeeds.

## Features implemented

- Local projects, local media import via Android's Storage Access Framework, app-private asset copies, project listing and local atomic autosave.
- Video, image, text, vector shape, audio and null transform-parent layers; back-to-front compositing, selection, reordering, visibility, lock and mute.
- Frame-quantized timeline scrubbing, zoom, clip movement/snapping, split, trim, source in-point and duration editing. Cut a segment by splitting at both boundaries and deleting it.
- Data-driven animatable properties: position, scale, rotation, anchor, opacity, dimensions, text metrics, stroke, volume, mask properties and effect amounts.
- Add, move, delete, copy/paste keyframes; linear/ease-in/ease-out/ease-in-out and draggable custom cubic Bezier easing. Graph changes affect actual interpolation.
- Editable text with platform font families, bold/italic, alignment, letter/line spacing, size and ARGB color. Rectangle, rounded rectangle, ellipse, line and polygon shapes with fill/stroke.
- Rectangle/ellipse masks, local positioning, dimensions, box-filter feathering, inversion and opacity.
- Modular CPU effects: brightness, contrast, saturation, hue, exposure, opacity, blur, sharpen and glow.
- Normal, multiply, screen, overlay, additive, lighten and darken blending.
- Local audio decoding to disk-backed PCM; stereo 48 kHz mixing, volume, mute, trimming and placement. Preview uses the audio hardware clock; export encodes AAC.
- Actual composition preview and draggable selected-layer position; fit/zoom. Local MP4 export via Android AVC/AAC codecs, resolution/FPS/bitrate settings, foreground progress, cancellation and SAF output.
- Undo/redo snapshots for editor transactions, including effects/masks/keyframes; failed edits leave the previous project intact.
- Portable ZIP-based `.vnx` files containing the manifest and available media. Missing assets remain identified and can be relinked without changing their asset IDs.

## Quick start on device

1. Install a signed APK from **GitHub Releases**, when one is available. Android 10 / API 29 or later is required.
2. Create a project and choose its dimensions, FPS and duration.
3. **＋ Layer** imports local media or adds text, shapes or a null parent.
4. Select a timeline layer. **Inspector** opens transform/style/timing/effect/mask controls; **◇ Animate** opens keyframes. Effect and mask animation are available in their own inspectors.
5. Drag clips to move them; drag the ruler to scrub. Drag empty timeline space horizontally to scroll, and the track-label area vertically to reach additional tracks. Timeline +/− controls zoom.
6. **Save / share** writes a portable `.vnx` document. Share that file from Android's Files app. The recipient uses **Open .vnx**; relinking is offered if media is absent.
7. **Export** selects MP4 settings and a local output document. Do not force-stop Vynox during export. Android may impose time limits on long background jobs.

## Offline-first architecture

The Android manifest has **no INTERNET permission**, no analytics and no account requirement. The core never calls an editing API. The website's optional release metadata request is separate from the Android application.

```text
UI / Inspector / TimelineView / CurveView
                    │ undoable transactions
                 EditorState
                    │
          Project / Layer / Animation
            ┌───────┴───────────┐
     CompositionRenderer     AudioMixer
       │           │         │       │
    Preview     VideoExporter / AVC + AAC
                    │
                  local MP4

ProjectStore (AtomicFile) ← ProjectJson → VnxArchive
                              │
                     AssetManager / copied media
```

| Directory | Responsibility |
|---|---|
| `app/.../model` | Composition types, animatable properties, Bezier curves |
| `app/.../engine` | Timeline operations, undo/redo, editor state |
| `app/.../format` | Explicit versioned JSON schema, portable ZIP import/export |
| `app/.../media` | SAF asset copies, missing assets, PCM decoding/mixing, audio clock |
| `app/.../render` | Shared Canvas compositor, effect registry, MediaCodec export, foreground service |
| `app/.../storage` | Local project directories and atomic autosave |
| `app/.../ui` | Native dark workspace, project screens, timeline, property and curve editors |
| `app/src/test` | Pure JVM engine, animation and format tests |
| `app/src/androidTest` | Real Android media import/compositor/MP4 tests |
| `.github/workflows` | CI, emulator tests, signed release, Pages deployment |
| `scripts` | Repository validation and non-destructive release publishing |
| `docs` | Static download site and format documentation |

All times are integer microseconds. Timeline frame boundaries use rational integer arithmetic rather than accumulating rounded frame durations. Keyframe times are layer-local. Splitting/trimming shifts key times, including internal negative keys, preserving the original Bezier segment exactly. See [VNX_FORMAT.md](docs/VNX_FORMAT.md).

## Development setup

Required: **JDK 17**, Android SDK platform **35**, build tools **35.0.0**, Android SDK command-line tools, Python 3. Android Studio is recommended. Gradle **8.9** is pinned by the official wrapper, with a distribution SHA-256 checksum. Android Gradle Plugin is **8.7.3**.

```bash
# Set JAVA_HOME to JDK 17 and ANDROID_HOME to your Android SDK.
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
sdkmanager --licenses
./gradlew --no-daemon assembleDebug
./gradlew --no-daemon testDebugUnitTest lintDebug
python3 scripts/validate.py
python3 -m unittest discover -s scripts -p 'test_*.py'
node --test scripts/site.test.cjs
```

Windows: use `gradlew.bat`. Alternatively open the repository in Android Studio and let it install the declared SDK. Never add `local.properties` or local tool installations to Git.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

```bash
# An emulator or USB-connected Android device must be running.
./gradlew connectedDebugAndroidTest
# No signing environment → deliberately unsigned release APK.
./gradlew assembleRelease
```

The only application runtime dependencies are Android platform APIs; AndroidX/JUnit are used for instrumentation tests, not the editor runtime. First-time dependency downloads require internet; installed core editing does not.

## Secure signing

The release workflow uses these existing GitHub Actions secret **names**, never values:

- `VYNOX_KEYSTORE_BASE64`
- `VYNOX_KEYSTORE_PASSWORD`
- `VYNOX_KEY_ALIAS`
- `VYNOX_KEY_PASSWORD`

The workflow decodes the keystore into `$RUNNER_TEMP` with restrictive permissions, passes the path and signing credentials through environment variables to Gradle, verifies the APK signature using `apksigner`, and deletes the temporary file via both a shell trap and an always-run cleanup step. Signing uses a non-daemon Gradle invocation. Test/report artifacts contain only explicitly selected report paths; no keystore is uploaded.

For an authorized local signed build, configure `VYNOX_KEYSTORE_PATH` to a keystore **outside the repository** and provide the password/alias variables in your local secure environment, then run `./gradlew --no-daemon assembleRelease`. Never put values into a command committed to Git, Gradle properties, README, or chat. Without a path, the release build is unsigned rather than debug-signed.

## Versioning and release

`version.properties` is the single version source: `versionName=0.1.0`, `versionCode=1`. Increment the integer code for **every distributable update** and the semantic version for each release. Keep the signing key consistent to allow Android upgrades.

1. Merge reviewed source into the default branch **after CI and device tests pass**.
2. Update both version fields through normal review.
3. Run **Signed production release → Run workflow** on the default branch. Alternatively, an authorized maintainer may push the matching `vX.Y.Z` tag on a commit already merged into the default branch.
4. The workflow repeats validation, unit tests, lint and emulator tests **before** exposing signing secrets to the build step.
5. It signs/verifies the APK, creates the matching tag and draft GitHub Release if absent, attaches stable-name `Vynox.apk` and `Vynox.apk.sha256`, generates notes, then publishes.
6. It explicitly calls the reusable Pages workflow. This avoids GitHub's restriction that events created using `GITHUB_TOKEN` generally do not trigger another workflow.

Third-party Actions are pinned to full commit SHAs (with version comments) and Dependabot tracks updates. The `production` environment can be configured with required reviewers and protected-branch restrictions. Do not disable protection to run a release. Release concurrency is serialized. Published releases are immutable in the publishing script; tag conflicts, downgrades or mismatched draft assets fail safely. A failed draft can be resumed only when existing bytes agree; nondeterministic rebuild bytes may require manual draft review. A release already published from the same tag is left untouched. A failed Pages deployment can be rerun separately without rebuilding or resigning the APK.

## GitHub Pages

Site: **https://gredeye.github.io/editor/**. The repository currently reports Pages build type `workflow`; deployment still requires a successful Actions run and appropriate environment permissions.

For a new repository, an administrator selects **Settings → Pages → Source: GitHub Actions** and grants workflow permissions as needed. The `github-pages` environment must allow the trusted branches/tags used for deployment. The repo should be public for unauthenticated visitors to download release assets.

The primary URL is always:

```text
https://github.com/gredeye/editor/releases/latest/download/Vynox.apk
```

No version-specific URL is hard-coded. The site fetches public release metadata for version, notes and size, uses `textContent` (not unsafe HTML), disables the button when GitHub confirms no release exists, and retains the stable link if the API is temporarily unavailable. To preview just the website: `python3 -m http.server 8080 --bind 0.0.0.0 --directory docs`.

## Known limitations

This implementation does **not** satisfy every aspect of a mature Alight Motion-class production editor yet:

- **Emulator-verified, not physical-device certified.** Android 35 instrumentation passes for startup, import, composition, MP4 export, audio and cancellation. This is not evidence of realtime performance or broad vendor/device compatibility. Complete CI must pass before release.
- CPU/Canvas rendering and per-frame `MediaMetadataRetriever` video decoding prioritize a simple shared composition path over realtime performance. Preview is capped at 640 pixels wide and may drop visual frames; audio is the playback clock. Many/high-resolution layers or effects can use significant RAM and render slowly. 4K settings are not a performance guarantee.
- Preview transport initially decodes source audio to disk. Audio uses PCM16, linear sample-rate conversion and hard-clamped stereo mixing, not a mastering-grade resampler/limiter. No waveform, pitch/time-stretch, reverse playback, or audio fades UI (volume keyframes can create fades). Nonzero stream offsets, encoder priming, variable-frame-rate edge cases and long-media A/V sync need device validation.
- Export depends on a device AVC encoder accepting flexible YUV input and an AAC encoder. Unsupported devices produce an explicit error; no GPU/surface fallback exists yet. No HDR, alpha-video, HEVC, GIF or color-managed wide-gamut export.
- Rectangle/ellipse masks only; no arbitrary path editor or Gaussian feather. Blur is a separable box filter. Image EXIF orientation, animation in GIF/WebP, custom font import, complex-script letter-spacing and sophisticated text wrapping are not implemented.
- Nulls provide transform parenting, not isolated nested-group compositing. Some text attributes (content, font family, color/style) and blend modes are static; numeric text metrics animate. The graph edits easing progress, not a multi-property value/speed graph.
- Canvas dragging edits selected-layer position on release. Scale/rotation/anchor are edited in numeric inspectors; there are no gesture handles for those. No landscape/tablet polish, full accessibility audit, localization, thumbnail strip, magnetic trim handles, or custom splash animation beyond Android's launch window.
- SAF project export can leave a partial document if the provider fails; retry to a new file. Assets are copied (up to 2 GB each); project archives are capped at 4 GB uncompressed, 8 MB manifest, 500 assets and 200 layers. Undo-retained/unused asset files are not garbage-collected; deleting app data removes local projects. Back up `.vnx` files.
- Autosave is queued after transactions and uses `AtomicFile`; a sudden kill before a queued write finishes can lose that transaction. The last completed save remains available in Projects. No separate recovery-history browser. Force-stopped/background-time-limited exports are not resumable.
- No APK update checker, cloud, accounts, online resources, or Play Store publication. No license grant beyond repository visibility has been selected by the owner; agree on licensing before redistribution.

## Roadmap / production gate

1. Run CI, emulator and physical-device tests across API 29–35 and multiple AVC/AAC vendors; fix findings before publishing.
2. GPU-backed composition and video decoder scheduling with measured frame budgets; audio stream-offset/priming tests.
3. Gesture transform/trim handles, waveform/thumbnail generation, accessible property controls, tablet workspace and usability review.
4. Arbitrary path masks, font embedding, color keyframes, better text layout and custom effect plug-ins.
5. Streaming archive progress/cancellation, storage quotas/garbage collection, recovery history and longer export lifecycle tests.
6. Security/release review, performance benchmarks and an explicit distribution license.
