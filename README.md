# Hebrew Subtitle Patch for Morphe

Adds a Hebrew auto-translate option to YouTube's caption menu.

## Build

Use JDK 17 and an Android SDK with platform and build tools installed. Configure
`gpr.user` / `gpr.key` in your user Gradle properties for GitHub Packages, or use
`GITHUB_ACTOR` / `GITHUB_TOKEN` environment variables. Never commit credentials.

The existing GitHub Actions workflow compiles the Java helper to DEX, then runs
`./gradlew :patches:verifyPatchBundle`. This builds the `.mpp`, checks that both
DEX files are packaged, and loads the patch using Morphe's actual bundle loader.
The build artifact is named `hebrew-subtitles-morphe`.

For local builds, first compile `extensions/HebrewSubtitlesHelper.java` with
`javac` and Android's `d8`, following the workflow, to
`patches/src/main/resources/hebrew-helper.dex`.

## Use

Import the built `.mpp` into Morphe Manager and select
**Hebrew auto-translated subtitles** when patching YouTube.
After installing the patched app, force-stop it and clear its cache.

A successful build verifies bundle loading, not compatibility with every YouTube
version. On-device verification is still required: choose Hebrew in the CC menu,
then check seeking, fullscreen, the next video, and switching to another language.

## Migration

The build uses Morphe's Gradle plugin and patcher API. The runtime Java helper and
its `app.revanced.extension` namespace are retained so injected method references
continue to match. The `app/` directory is a legacy mirror; Gradle builds `patches/`.
See `docs/HOW-IT-WORKS.md` for the original runtime design and known limitations.

## YouTube 21.07.247 correction

The older helper expected the obsolete SubtitleTrack model and fixed controller
fields. This version uses the real caption row and YouTube's native click handler,
which performs both selection and rendering. The runtime row mapping is scoped
to 21.07.247; patch-time checks reject other models. Both caption sheets are hooked.

`exportVerificationTools` exports the patcher and a local APK verification runner.
The user APK remains local; it is not uploaded to this repository or CI.

## YouTube 21.13.164 support (bundle 1.2.0)

The supported versions are 21.07.247, 21.13.164 and 21.16.256. Inspection of the new APK
confirmed that native caption selection still performs both selection and
rendering, but the row/track classes and label field changed. The patch selects
and validates the model by the APK version.

The remote source stays at `morphe-source/patches-bundle.json`. Its version and
binary are updated together after APK patch execution and DEX serialization
pass locally. Playback still requires an on-device check.

## Version-independent caption detection

The patch no longer keeps a per-version table of obfuscated names. At runtime the
helper recognises a caption row by shape: it holds a track object with a
language-code `String` and a non-empty `CharSequence` display name, and real
caption rows share one class. Patch-time hooks were already located by
signature. A new YouTube version therefore needs only an on-device check, not a
code change, unless YouTube changes the caption model itself.

## Remote source URL

Add this URL as a remote source in Morphe Manager:

    https://raw.githubusercontent.com/elyashivk1165/hebrew-subtitle-patch/refs/heads/morphe-source/patches-bundle.json

Keep the `refs/heads/` form. Morphe Manager rewrites the branch of a plain
`raw.githubusercontent.com/<owner>/<repo>/<branch>/...` URL to `main` (or `dev`
for pre-releases), and this repository has no bundle JSON on `main`.
