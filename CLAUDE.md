# CLAUDE.md

Project context for Claude Code. Read **`docs/HOW-IT-WORKS.md`** for the full
reverse-engineering reference before changing subtitle logic.

## What this is
A ReVanced patch adding a one-tap **"Hebrew (auto-translate)"** option to
YouTube's caption menu. Hebrew is NOT in YouTube's native auto-translate list, so
the patch synthesises it.

## Architecture (essentials)
YouTube's "Auto-translate" entries are all the same **English-ASR source**
(`lang=en&kind=asr`) differing only by `&tlang=XX`. So:
1. Borrow any existing auto-translate track as a template (`pickBaseTrack`).
2. Select it via YouTube's own two-call sequence — `oju.al.a(track)` **and**
   `oju.an.L(track)` (both required: `a()` selects, `L()` renders).
3. Intercept the Cronet `timedtext` request and force `&tlang=iw` → English
   becomes Hebrew. **Global** while active; disarmed by tapping any native row.

`iw` = YouTube's legacy code for Hebrew. All obfuscated names (`oju`, `t`, `a`,
`L`, `g`, `f`) are matched **by signature/shape, never hardcoded** — they change
per YouTube version.

## Key files
- `extensions/HebrewSubtitlesHelper.java` — runtime logic. **Mirror copy** at
  `app/src/main/java/app/revanced/extension/youtube/subtitle/HebrewSubtitlesHelper.java`
  — keep both in sync.
- `patches/.../subtitle/HebrewSubtitlesPatch.kt` — injects the interceptor at
  **every** `newUrlRequestBuilder` call site + the menu footer hook.

## Rules / gotchas (learned the hard way)
- **Hook ALL Cronet sites**, not one — there are ~5-6; a single hook gives
  intermittent English / slow resume.
- **Do NOT build/clone a custom `SubtitleTrack`** (incl. `SubtitleTrack.t()`) and
  select it → "error retrieving subtitle". Render must come from a real track +
  the interceptor.
- Interceptor must be **sticky** or subtitles revert after fullscreen/seek.
- Hebrew strings are written as `\u` escapes (encoding-safe). A `node` one-liner
  in the build converts any non-ASCII → `\u`.
- After install via ReVanced Manager you **must force-stop + clear cache** or the
  old dex stays in memory (= "sometimes works").

## Build & test
- **Build:** push to `main` → GitHub Actions releases `patches.rvp`. Watch the run
  via the GitHub API; download the latest release's `patches.rvp`.
- **Local helper compile-check:** `javac -cp <full API-34 android.jar> -source 8
  -target 8` (the gradle 4.5 MB android.jar stub does NOT work locally).
- **Device:** wireless adb; IP/port rotate — rediscover with
  `adb mdns services | grep _adb-tls-connect` then `adb connect`.
- **Verify visually:** `screencap` is all black (DRM) — use `uiautomator dump`
  and read the caption-menu accessibility tree instead.

See also Claude Code memory: `hebrew-subtitle-working-architecture`,
`install-requires-forcestop-clearcache`.
