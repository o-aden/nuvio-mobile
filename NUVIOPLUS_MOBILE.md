# Nuvio+ Mobile

Android phone integration based on official NuvioMobile `0.6.5-beta`, commit
`af58a32b05741963beec861255f5b18f7ede69cf`. Original history, license and notices remain intact.
The application ID is `com.adenstack.nuvioplus.mobile`; the Android name is `Nuvio+ Mobile`.
Versioning starts at `0.1.0-beta.1` (code 1), independently of TV and upstream.

## Architecture and ownership

- Auth/storage: existing Supabase authentication and Nuvio device-link flow, plus trusted managed username
  resolution and a separate Manager enrollment protocol. Android Keystore AES/GCM protects Supabase sessions
  and Manager device credentials. Session storage is scoped by backend; enrollment credentials are scoped by account.
- Manager runtime: strict DTO validation, HTTPS-only control requests, no redirects or automatic mutation retries,
  generation checks on asynchronous responses, and an opaque account/device/runtime identity.
- Playback: ephemeral Manager AIOStreams sources use the existing Stremio parser and official player. Managed
  discovery does not query ordinary stream addons, plugins, embedded streams or direct debrid. Manager ranking
  is preserved; the phone removes identifiable unsupported AV1/HEVC streams. Hardware AV1 is required for AV1.
- Sync/metadata: the existing shared Supabase profile, addon, watched and watch-progress RPCs remain the sole
  data model. AIOMetadata comes from the account's provisioned, synchronized addons; the device-runtime contract
  supplies AIOStreams only. No second history store or new backend endpoints are introduced.
- Validation: isolated Manager transport/storage fixtures, runtime and username policies, managed launch/source
  isolation, codec/fallback behavior, language preference behavior, and existing player/progress regression tests.

Only the Mobile checkout is modified. TV is read-only; Manager production/configuration/database/device records
are not changed. Worker ownership covers independent auth/storage and runtime modules; the coordinator owns
stream/player integration, branding, build setup and final review.

## Build locally

Use Android Studio's JDK (17 or later), the Android SDK, and PowerShell:

```powershell
.\nuvioplus\Configure-PublicBackend.ps1
.\gradlew.bat :composeApp:testAndroidHostTest :androidApp:assembleFullDebug '-Pnuvio.android.distribution=full' --console=plain
```

The configuration script retrieves the **public publishable client key** through the existing unauthenticated
`https://nuvio.adenstack.com/.well-known/nuvio` contract. It validates the endpoint/document and stores only
client configuration in ignored `local.properties`. It does not read TV local properties or import signing material.
No server service-role/admin keys belong in the app. No unrelated auth fallback is configured.

Override version values with `'-Pnuvioplus.mobile.versionName=...'` and `'-Pnuvioplus.mobile.versionCode=...'`.
The debug APK is generated at `androidApp/build/outputs/apk/full/debug/androidApp-full-debug.apk`.
Mobile release signing uses a dedicated identity separate from TV. See `scripts/MOBILE_RELEASE.md` for the protected local signing setup and Beta publishing command.
The APK updater checks this fork only; it cannot offer upstream APKs with a different application/signing identity.
The signed Mobile Beta release and repeatable publishing command are documented in `scripts/MOBILE_RELEASE.md`.

## Enrollment, authorization and recovery

Sign in with an existing managed email or username. Usernames follow TV's ASCII lowercase policy:
3–24 characters from `a-z`, `0-9`, `_`, mapped to the managed auth identity. Email login is unchanged.
The official Nuvio device-link login is an authentication flow, not Manager pairing.

After validated authentication, `POST /api/device/enroll` uses the Nuvio access JWT, an installation UUID and
phone name. Valid same-account enrollment is reused. Runtime refresh and token renewal do not rotate credentials.
Sign-out attempts `POST /api/device/sign-out` and always removes local managed access. Account changes invalidate
old in-flight work. Revocation is persistent; an administrator must permit a revoked installation. Explicitly sign
out and sign in after permission is restored. Standalone manual Manager pairing is deliberately not offered because
the existing runtime response cannot prove its ownership against a signed-in Nuvio account.

Each discovery/launch uses a fresh `GET /api/device/runtime`. Cached runtime state never authorizes playback.
The response must match the credential device ID, known profile/capability enum, exact variants, HTTPS manifest and
64-character revision. Authorization failures block access; temporary outages fail closed without ordinary-source
fallback. Launch provenance must still match the account, active profile and runtime. Internal playback checks again
every minute; external-player handoff is authorized immediately before launch, but another app cannot be stopped
remotely by this app after handoff. Managed playback links are not saved in the ordinary reuse-link cache.

Defaults are English audio, original-language audio fallback, full English subtitles and first compatible managed
source. Saved user preferences continue to apply. Touch controls, rotation, Picture-in-Picture and player internals
remain upstream implementations.

## Phone acceptance checks

Install the local debug APK using `adb install -r <apk>` or transfer it to a phone and allow that installer.
Use an isolated provisioned test account; do not use existing production accounts for automated tests.
Verify login, enrollment, account-specific metadata/sources, all Manager profiles, a movie and episode, subtitles/audio,
rotation, buffering, Picture-in-Picture, Continue Watching and progress on TV. Revoke only the isolated test device,
then confirm playback denial and administrator-permit recovery. Repeat with an account/profile switch and stale
progress on a second device. These require live credentials/devices and are not implied by a successful build.
## Verification — 2026-10-27

- Unmodified pinned release: `:androidApp:assembleFullDebug` passed; APK retained locally in `build/baseline/`.
- Integrated source: Android library/application compilation and `:androidApp:assembleFullDebug` passed.
- Full Android host suite: 1,802 tests, 1,799 passed, 3 failed, 0 skipped. All 29 added integration tests passed.
- The three failures were independently reproduced at the exact unmodified pinned commit (73 filtered baseline
  tests, same three failures):
  - `RatingsVisibilityTest.allEpisodeLayoutsRespectVisibilityForFetchedAndAddonRatings`
  - `DownloadSubtitlesTest.externalPlayersReceiveReadableLocalSubtitlesWithoutAddonRequests`
  - `WatchProgressIdentityTest.legacy payload derives episode progress key and preserves completed position`
- There are no new test regressions. The combined Gradle invocation exits nonzero because of those upstream
  failures; APK assembly itself succeeds. These unrelated baseline failures were not changed.
- Final APK manifest/package/version/name and debug signature were verified with Android SDK tools.
  The universal APK includes arm64-v8a, armeabi-v7a, x86 and x86_64; min SDK 24, target SDK 36.
- `git diff --check` passed. TV and Manager branches and their pre-existing local changes remain untouched.
- Public backend discovery confirms email/password and Nuvio device-link capability. No production login,
  enrollment, pairing, revocation or Manager runtime request was made during automated verification.
- No emulator AVD/system image or attached physical phone was available. Installation/launch, live managed login,
  account-specific manifests, playback/audio/subtitles/Picture-in-Picture and cross-device Sync remain unverified
  on a device. Follow the acceptance checks above using an isolated provisioned account and phone.
- No Manager changes were prepared or deployed. Mobile release signing is not configured; this is a debug-signed
  Beta candidate. No APK was published publicly. External-player revocation remains limited to authorizing handoff;
  the phone cannot stop playback in another application after handoff.

Local validation log: `build/nuvioplus-validation.log`. Full test report:
`composeApp/build/reports/tests/testAndroidHostTest/index.html`.
Final local artifact: `build/artifacts/NuvioPlus-Mobile-0.1.0-beta.1-debug.apk` with adjacent `.sha256` file.
