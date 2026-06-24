# Hebrew Subtitle Patch — How It Works

A ReVanced patch that adds a one‑tap **"Hebrew (auto‑translate)"** option to
YouTube's caption menu. Hebrew is **not** offered in YouTube's native
auto‑translate list, so the patch synthesises it.

This document records everything reverse‑engineered about YouTube's subtitle
internals and the approach that actually works — including the dead‑ends, so they
are not re‑attempted.

---

## 1. The core idea (TL;DR)

YouTube's caption "Auto‑translate" entries are all the **same English‑ASR source**
(`lang=en&kind=asr`) with a different `&tlang=XX`. Hebrew simply isn't in that
list. So we:

1. Borrow **any** existing auto‑translate track as a template.
2. Select it through YouTube's own selection path so it actually renders.
3. Intercept the `timedtext` network request and **force `&tlang=iw`**, turning
   the English source into Hebrew.
4. Make it global + add a clean menu UX (checkmark, relabel, disarm).

`iw` is YouTube's legacy code for Hebrew (the app's own UI sends `hl=iw`).

---

## 2. YouTube internals (reverse‑engineered)

Names like `oju`, `alis`, `alxc` are R8‑obfuscated and **change between
versions** — the patch never hardcodes them; it finds everything by shape. The
names below are from the version analysed (YouTube ~20.x) for reference only.

### 2.1 The caption bottom‑sheet fragment — `oju`
Found by the const‑string `"SUBTITLE_MENU_BOTTOM_SHEET_FRAGMENT"` + a call to
`ListView.addFooterView`. Relevant instance fields:

| field | type | role |
|-------|------|------|
| `al`  | `aliq` (interface) | selection controller — `a(SubtitleTrack)` selects a track |
| `an`  | `brg` | **renderer** — `L(SubtitleTrack)` actually displays the track |
| `ak`  | `SubtitleTrack` | currently‑selected track |

### 2.2 The native selection sequence — `oju.onItemClick`
Decompiling `onItemClick` revealed YouTube makes **two** calls when a row is
tapped — both are required:

```java
this.al.a(track);   // aliq — select   (without this nothing is "chosen")
this.an.L(track);   // brg  — render   (without this it never displays)
```

Calling only `a()` selects but shows nothing ("error retrieving subtitle"). The
patch replicates both.

### 2.3 The track list — `alis` → `alxc.t()`
`oju.al` (`aliq`) holds a field whose object (`alxc`) has a public `t()` returning
`List<SubtitleTrack>`. **That list is the auto‑translate options**, e.g. for one
video: `uk, it, id, de, nl, hi, vi, ja, zh‑Hant, es, ar, pt, fr, ko, ru, th` —
**no Hebrew**. Every entry has:

- `g()` → target lang code (e.g. `uk`)
- `n()` → vssId like `ta.en.uk` ("**t**ranslate‑**a**sr, en→uk")
- the timedtext URL → `...&kind=asr&lang=en&variant=gemini&fmt=srv3&tlang=uk`

They all share the **same English‑ASR base**; only `tlang` differs. That's why
borrowing any one and rewriting `tlang` to `iw` yields valid English→Hebrew.

### 2.4 `SubtitleTrack` (NOT obfuscated)
`com.google.android.libraries.youtube.player.subtitles.model.SubtitleTrack` —
an abstract AutoValue class. Useful members discovered:

- `g()` `String` — language code
- `f()` `CharSequence` — display name (e.g. "Ukrainian"); the only no‑arg
  `CharSequence` getter, so it's findable by signature
- `e()` `Optional` — (translation‑related field)
- `t(String)` `SubtitleTrack` — builds a **translated copy** (sets the translation
  field + an "is translated" flag). This is YouTube's own translate‑builder.
- `s(String)` static — the `"DISABLE_CAPTIONS_OPTION"` factory
- `v()` → `g().equals("AUTO_TRANSLATE_CAPTIONS_OPTION")` (the "Auto‑translate" row)
- `w()` → `n().startsWith("t")` (is an already‑translated track)

> Note: `t(String)` looks perfect but produced "error retrieving subtitle" when
> selected — see dead‑ends. We do **not** use it.

### 2.5 The network layer — Cronet
Subtitle content is fetched via `org.chromium.net.CronetEngine.newUrlRequestBuilder(
String, UrlRequest$Callback, Executor)`. **There are ~5–6 different call sites**
across the dexes (classes `bibb`, `aqhg`, `ainx`, `qon`, `aaxr`, …) — YouTube has
several HTTP clients and subtitle fetches don't always use the same one. Hooking
only one caused intermittent English / slow return after backgrounding.

### 2.6 The menu row layout (for the cosmetic fixes)
Native rows and our footer use the same resource ids (**not** obfuscated):
`list_item_text` (the label `TextView`) and `list_item_icon_primary` (the
checkmark `ImageView`, drawn only on the selected row). The full menu reads e.g.
`Auto‑translate · Ukrainian ✓`.

---

## 3. What the patch injects (`HebrewSubtitlesPatch.kt`)

1. **URL interceptor at EVERY Cronet site.** Iterate all classes
   (`classes.toList()` → `proxy(classDef).mutableClass.methods`), and at every
   `newUrlRequestBuilder` call inject:
   ```
   invoke-static { vUrl }, …->interceptTimedtextUrl(String)String
   move-result-object vUrl
   ```
   `interceptTimedtextUrl` ignores any URL without `"timedtext"`, so all other
   requests are untouched.

2. **Footer injection.** In the caption fragment's `onCreateView`, just before
   YouTube's `addFooterView`, inject `injectHebrewOption(oju, listView)`.

---

## 4. What the helper does (`HebrewSubtitlesHelper.java`)

- **`selectHebrew()`** — get `oju.al` → `alxc` → `t()`, pick any real track
  (`pickBaseTrack`, skips Off/Auto‑translate option rows via `isLangCode`),
  remember its display name (`f()`) for relabeling, arm `hebrewSelected`, then run
  the native sequence `al.a(track)` + `an.L(track)`.
- **`interceptTimedtextUrl(url)`** — while `hebrewSelected`, force `&tlang=iw`
  (replace if present, else append). **Global**: every video is translated until
  the user disarms.
- **Disarm** — wrap the ListView's `OnItemClickListener`; our footer is
  non‑selectable so only native rows fire `onItemClick` → clear `hebrewSelected`.
- **`syncCheckmark`** — copy the native check drawable onto our footer's
  `list_item_icon_primary`, hide the native one, and relabel the borrowed row's
  language name to "Hebrew" (`relabelText`). Run via `post` + `postDelayed`
  because the adapter binds rows slightly after `onCreateView`.
- **`createHebrewListItem`** — inflate `bottom_sheet_list_checkmark_item` **with
  the ListView as parent** (`attachToRoot=false`) so the row gets native
  LayoutParams and aligns correctly.

All obfuscated lookups are by **shape**: `g()` is a no‑arg `String` getter, `f()`
the no‑arg `CharSequence` getter, `a()`/`L()` are void methods taking one
`SubtitleTrack`, etc.

---

## 5. Dead‑ends (do NOT retry)

| Approach | Why it failed |
|----------|---------------|
| Build/clone a custom Hebrew `SubtitleTrack` (via `SubtitleTrack.t(lang)` or constructor reflection) and select it | YouTube logs **"error retrieving subtitle"** — a hand‑built/translated‑again identity it refuses to fetch. Rendering must come from a **real** track + the interceptor. |
| Hook only ONE `newUrlRequestBuilder` site | Intermittent English / Hebrew came back slowly after backgrounding — some fetches used the other ~4 clients and bypassed us. **Hook them all.** |
| One‑shot (non‑sticky) interceptor | Subtitles reverted after fullscreen/seek (YouTube re‑fetches). Must stay armed. |
| URL swap only, no track selection | The fetch returns Hebrew but nothing renders — a track must be selected so the caption surface is active. |
| `curl` the timedtext URL to test `tlang=iw` | Google returns an anti‑bot "Sorry…" page from a server IP — **inconclusive**, not a real translation error. Test in‑app instead. |

---

## 6. Build & test workflow

- **Build:** push to `main` → GitHub Actions builds and releases `patches.rvp`.
  (Local `.rvp` build needs the `app.revanced.patches` Gradle plugin from GitHub
  Packages, which requires a token; the helper alone can be compiled locally.)
- **Local helper compile‑check:** `javac -cp android.jar -source 8 -target 8`
  against a real `android.jar` (the gradle‑transformed 4.5 MB stub does **not**
  work locally — use a full API‑34 `android.jar`).
- **dex build (if needed locally):** `javac` → `d8` (from `r8.jar` on Google
  Maven). Gradle needs **JDK 17** (JDK 25 is too new and errors).
- **Hebrew strings** are written as `\u` escapes so the source compiles
  regardless of the build's file encoding.
- **Install:** ReVanced Manager applies the `.rvp`. ⚠️ After install you **must
  force‑stop + clear cache** or YouTube keeps the old `hebrew-helper` dex in
  memory (this is the cause of "sometimes works / sometimes not").
- **Device (wireless adb):** the IP/port rotates; rediscover with
  `adb mdns services | grep _adb-tls-connect` then `adb connect`.

### Verification tricks
- **Which version is installed:** pull `base.apk`, unzip `classes*.dex` (the dex
  is zlib‑compressed inside the apk — grepping the apk directly finds nothing),
  then grep for a unique log‑string marker from the target commit.
- **`#5` coverage:** count `interceptTimedtextUrl` across the installed dexes —
  should be ~6 (one per Cronet site), not 1.
- **Visual checks without a screenshot:** the YouTube video surface is
  DRM‑protected so `screencap` is **all black**. Use `uiautomator dump` instead —
  caption menu rows (text, bounds, the `list_item_icon_primary` icon) appear in
  the accessibility tree, which is enough to verify the relabel/checkmark/alignment.

---

## 7. Behaviour & known limitations

- **Global "set and forget":** activate Hebrew once → every following video is
  translated until you pick "Off" or another language (disarm). Pairs well with
  YouTube's native "always show captions" setting for persistence across restarts.
- **#2 — transient "captions enabled: <borrowed language>" toast:** YouTube's own
  announcement fired by `al.a(track)`. Suppressing it needs deeper hooks that risk
  the working rendering, so it's left as a minor cosmetic artifact.
- The menu cosmetics (checkmark on our row, "Hebrew" relabel) are bound to the
  video Hebrew was explicitly activated on; on later videos the text is Hebrew but
  the menu reflects YouTube's own selection.

---

## 8. Tooling used

`baksmali`/`smali` (dex ↔ smali), `jadx` (readable Java), `d8`/`r8` (dex build),
ReVanced Patcher 22.x (`classes` / `proxy(classDef).mutableClass`), `adb` over
Wi‑Fi, `uiautomator dump` for UI verification.
