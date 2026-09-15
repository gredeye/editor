# Verification and deployment status

Date: **2026-09-15**. Source branch: **arena/01a0a584-editor**.

## Verified

**Passing GitHub Android CI:** [run 34987810027](https://github.com/gredeye/editor/actions/runs/34987810027), commit `b2a4f33`. Both the build and emulator jobs completed successfully. Consult [PR #1](https://github.com/gredeye/editor/pull/1) for checks on subsequent commits.

| Check | Result |
|---|---|
| Original repository inspection | Only README and Git metadata; no existing app removed |
| Gradle debug APK build | **Passed on GitHub-hosted runner**; debug APK uploaded as `Vynox-debug` artifact |
| Gradle unsigned release APK build | **Passed on GitHub-hosted runner** |
| Android lint | **Passed**; findings fixed, not globally suppressed |
| JVM animation/engine/format tests | **30 passed**, also independently run locally with JUnit 4.13.2 and JSON-java |
| Android 35 x86_64 emulator instrumentation | **Six tests passed**: composition/mask pixels, real AVC MP4 export with decode-back pixel assertions, image import/copy, export cancellation, PCM volume/mute/placement, activity launch/open-project |
| Release safety tests | **Six passed**; gh/git mocked, no release mutation by tests |
| Website state tests | **Four passed**: no release, published metadata, rate-limit fallback, missing APK asset |
| Website Chromium check | Desktop 1440px and mobile 390px exercised; no JS errors or mobile horizontal overflow; release API mocked for absent/present states; release-note markup remained literal text |
| XML, versions, offline manifest, signing references, workflow wiring | Passed `scripts/validate.py` |
| Workflow YAML / JavaScript syntax | Passed |
| Official Gradle 8.9 wrapper | JAR matches upstream published SHA-256; distribution checksum pinned |
| GitHub Actions supply chain | All third-party actions pinned to full upstream commit SHAs, with Dependabot updates |
| Branch push and PR | Source pushed successfully; [PR #1](https://github.com/gredeye/editor/pull/1) created |
| Pages configuration | Repository reports `workflow` build type and URL `https://gredeye.github.io/editor/` |

The preview/export test verifies an actual encoded composition—not a UI recording. The audio test checks decoded PCM mixing. It is not a comprehensive long-duration A/V sync or AAC priming certification.

## Environment obstacles and fixes

The workspace had no JDK, Gradle or Android SDK. Direct Google/Gradle/Maven/Debian downloads failed with network/TLS errors. A Java runtime, Eclipse compiler, Android API class archive and JSON source could be obtained through available npm/GitHub endpoints, enabling independent source type-checking and JVM tests in ignored `.tools/`. None of those scratch tools are shipped or used by release CI.

The local Gradle wrapper still cannot download its distribution. **The successful APK builds and device tests ran on GitHub-hosted runners, not in this sandbox.** Runner log-download hosts are also inaccessible here; job/check APIs provide statuses and annotations.

Initial GitHub permission metadata misleadingly reported no push permissions. Actual branch push and PR creation succeeded. No authentication reconnection is needed for those operations. Protected-branch/Actions administration endpoints remain inaccessible; protections were not changed.

CI exposed and resolved:

1. Obsolete Android SDK setup defaults / action versions → current SHA-pinned actions and `platform-tools` package selection.
2. Test used a JSON-java-only `similar()` method → Android-compatible round-trip assertion.
3. Extractor sample flags passed directly to a muxer buffer → explicit MediaCodec flag translation and encrypted-sample rejection.
4. MIME import filter lacked explicit local URI schemes → `content`/`file` schemes, no web/deep-link claims.

## Release/deployment boundary

The secure workflow is ready to execute after the final reviewed source passes CI and is merged. The release workflow itself tests, signs, runs `apksigner verify`, publishes the APK/checksum, and explicitly invokes Pages deployment.

At the time this record was written, **a signed GitHub Release and a live Pages deployment had not yet been verified**. Consult [Releases](https://github.com/gredeye/editor/releases), [release workflow runs](https://github.com/gredeye/editor/actions/workflows/release.yml), and [Pages workflow runs](https://github.com/gredeye/editor/actions/workflows/pages.yml) for authoritative deployment outcomes. Secret values, alias validity and signing certificate are never inferred from variable names. No secrets were read, printed or committed.

## Remaining production acceptance work

- [x] Run Android CI (build, lint, JVM and emulator) successfully.
- [x] Decode an exported MP4 and assert actual composition pixels.
- [x] Exercise application startup and opening a saved local project on emulator.
- [ ] Install signed builds on Android 10–15 physical devices from multiple codec vendors.
- [ ] Round-trip `.vnx` containing video/image/audio on a second physical device; remove/relink assets.
- [ ] Compare preview and export at video split boundaries and animated effect/mask keyframes.
- [ ] Validate long-media audio/video sync, nonzero stream offsets, VFR and AAC priming.
- [ ] Exercise low disk, process death, foreground timeout and cancellation during destination copy.
- [ ] Measure RAM / realtime frame budgets and establish tested resolution/layer limits.
- [ ] Audit accessibility, landscape/tablet layout, complex typography and licensing.
- [ ] Install and upgrade with the published signed APK; verify latest download unauthenticated.

See README for explicit missing/limited features. This is a real, buildable and emulator-tested implementation, **not full production certification of every requested feature**.
