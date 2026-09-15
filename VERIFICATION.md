# Verification and deployment status

Date: **2026-09-15**. Source branch: **arena/01a0a584-editor**.

## Performed locally

| Check | Result |
|---|---|
| Inspected original repository | Only `README.md` plus Git metadata; no existing app removed |
| Java source parsing | Passed for application sources |
| Application Java type-check | Passed using Eclipse JDT compiler, Java 17 runtime, Android 35 API class archive and generated `R` / `BuildConfig` placeholders |
| JVM engine/animation/format tests | **30 passed**, JUnit 4.13.2 with real JSON-java implementation |
| Release safety unit tests | **6 passed**, gh/git mocked; no release created by tests |
| XML parsing, version validation, offline permission check, signing-variable references, release/Pages wiring | Passed `scripts/validate.py` |
| Workflow YAML parse | CI, release and Pages parse successfully with unique-key validation |
| Website JavaScript syntax | Passed `node --check docs/site.js` |
| Official Gradle wrapper JAR | SHA-256 matches Gradle 8.9 release-published checksum; distribution checksum pinned |
| Repository Pages setting | GitHub reports build type `workflow`, URL `https://gredeye.github.io/editor/` |

The isolated compiler and test tools were downloaded into ignored `.tools/`. They are **not** shipped, committed, used by release CI, or substituted for an Android SDK build. Source type-checking does not compile Android resources, dex bytecode, inspect manifest merges, run Android lint, or install an APK.

## Attempted but blocked

- The environment initially had no JDK, Gradle or Android SDK. Debian package installation and direct downloads from Google/Gradle/Maven failed with network/TLS errors.
- A Java runtime and Eclipse compiler could be obtained via npm packages, and API/JSON source artifacts via GitHub. This enabled the independent checks above.
- `./gradlew --no-daemon testDebugUnitTest assembleDebug assembleRelease` was attempted with that runtime. Wrapper distribution download fails with `SSLHandshakeException` / peer shutdown at `services.gradle.org`.
- Google SDK and Maven dependency download paths are also inaccessible here. Therefore **no Gradle debug/release APK, Android lint result or connected Android test result exists from this workspace**.
- GitHub permission metadata reported no push/admin permissions, but an actual branch push and PR creation succeeded. Protected-branch/Actions administration queries return `403 Resource not accessible by integration`. No secrets were read or printed.
- [PR #1](https://github.com/gredeye/editor/pull/1) is open. The first GitHub-hosted CI run failed during Android SDK setup, before compilation/tests. Action versions and SDK package selection have been updated; subsequent results must be reviewed. Runner log download hosts are also inaccessible from this workspace.

## Not verified / not published

- Actual Android installation, media-provider imports, UI gestures, typography, rendering pixels, audio hardware synchronization, AVC/AAC output, cancellation and lifecycle behavior.
- The AndroidX instrumentation suite contains real import, composition/mask and MP4 decode-back assertions. It must run on an emulator/device; these are **not claimed as passed**.
- Signing-secret presence, password/alias correctness and certificate validity. Only variable wiring has been checked. The release workflow verifies the actual signed APK when authorized to run.
- GitHub CI completion, automatic PR merge, production release with signed APK, and live Pages deployment. Configuration is present; deployed artifacts are not claimed.

## Production acceptance checklist

- [ ] Run both Android CI jobs successfully.
- [ ] Install debug and signed builds on Android 10 and Android 15 physical devices.
- [ ] Round-trip `.vnx` with video/image/audio on a second device; remove/relink assets.
- [ ] Compare preview with export at split boundaries and animated effect/mask keyframes.
- [ ] Validate audio over long projects and inputs with nonzero A/V offsets / VFR.
- [ ] Exercise cancellation during decode/encode/output copy, background timeout, low disk and unexpected process termination.
- [ ] Measure memory and realtime frame budgets; set tested device-dependent resolution/layer limits.
- [ ] Audit app accessibility and landscape layout.
- [ ] Review licensing, signing security and workflow permission/protection settings.
- [ ] Publish a controlled release only after tests pass; install/upgrade using its APK.
- [ ] Confirm Pages deployment and latest APK download in an unauthenticated browser.

See README's known limitations and roadmap. This is a substantial real implementation, **not a completed production certification**.
