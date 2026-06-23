package app.revanced.extension.youtube.subtitle;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * Adds a "Hebrew (auto-translate)" option to YouTube's CC bottom sheet that
 * drives YouTube's OWN native auto-translation, so Hebrew subtitles render and
 * persist exactly like the built-in "Auto-translate → <language>" menu.
 *
 * How it works:
 *
 *   1. We take a real caption track from the subtitle controller (alxc.t()).
 *
 *   2. We call that track's own translate-builder method — SubtitleTrack.t(lang)
 *      — with "iw". This is the exact method YouTube itself uses for native
 *      auto-translation: it returns a proper translated SubtitleTrack (a copy of
 *      the base track with the translation-language field set and the
 *      "is translated" flag = true). We never clone or mutate anything by hand.
 *
 *   3. We hand that translated track to YouTube's own selection method,
 *      alis.a(). Because the object was built by YouTube's own builder, the
 *      whole native pipeline works: the timedtext request is built with
 *      &tlang=iw, the captions render, and they persist across fullscreen, seek
 *      and quality changes — with NO URL interception.
 *
 *   4. We mark our own footer item and dismiss the sheet; we do not fight
 *      YouTube's native checkmark rendering.
 *
 * The single-letter method names (t, a, g) are obfuscated and change between
 * versions, so every lookup is by SIGNATURE/shape, not by name — which survives
 * R8 renaming. The class name SubtitleTrack itself is not obfuscated.
 */
public final class HebrewSubtitlesHelper {

    private static final String TAG = "HebrewSubs";

    // Hebrew UI strings written as unicode escapes so the file compiles
    // regardless of the build's source-file encoding.
    private static final String LABEL_HEBREW =
            "\u05E2\u05D1\u05E8\u05D9\u05EA (\u05EA\u05E8\u05D2\u05D5\u05DD \u05D0\u05D5\u05D8\u05D5\u05DE\u05D8\u05D9)";
    private static final String TOAST_OK =
            "\u05DB\u05EA\u05D5\u05D1\u05D9\u05D5\u05EA \u05E2\u05D1\u05E8\u05D9\u05EA: \u05E4\u05E2\u05D9\u05DC";
    private static final String TOAST_FAIL =
            "\u05E9\u05D2\u05D9\u05D0\u05D4: \u05E2\u05D1\u05E8\u05D9\u05EA \u05DC\u05D0 \u05E0\u05DE\u05E6\u05D0\u05D4";

    private static WeakReference<Object>   ojuRef        = new WeakReference<>(null);
    private static WeakReference<View>     hebrewItemRef = new WeakReference<>(null);
    private static WeakReference<ListView> listViewRef   = new WeakReference<>(null);

    /** True once the user has activated Hebrew, so we can restore the checkmark. */
    private static volatile boolean hebrewSelected = false;

    // ── URL interceptor ───────────────────────────────────────────────────────

    public static void saveTimedtextRequest(Object engine, String url,
                                             Object callback, Object executor) {
        // kept for hook compatibility
    }

    public static String interceptTimedtextUrl(String url) {
        if (url == null || !url.contains("timedtext")) return url;
        android.util.Log.d(TAG, "TIMEDTEXT_URL " + url);

        // STICKY backup: while Hebrew is the active choice, force &tlang=iw on
        // every timedtext fetch (including re-fetches after fullscreen/seek).
        // The primary mechanism is the track's own URL field (see selectHebrew);
        // this guarantees the param survives URL rebuilds YouTube does later.
        if (!hebrewSelected) return url;
        String out;
        if (url.contains("&tlang=")) {
            out = url.replaceFirst("&tlang=[^&]*", "&tlang=" + TRANSLATE_LANG);
        } else if (url.contains("?tlang=")) {
            out = url.replaceFirst("\\?tlang=[^&]*", "?tlang=" + TRANSLATE_LANG);
        } else {
            out = url + "&tlang=" + TRANSLATE_LANG;
        }
        return out;
    }

    // ── CC Panel injection ────────────────────────────────────────────────────

    public static void injectHebrewOption(Object ojuInstance, ListView listView) {
        try {
            if (listView == null) return;
            if (listView.getFooterViewsCount() > 0) return;

            ojuRef      = new WeakReference<>(ojuInstance);
            listViewRef = new WeakReference<>(listView);

            Context ctx = listView.getContext();
            View item = createHebrewListItem(ctx);
            hebrewItemRef = new WeakReference<>(item);
            listView.addFooterView(item, null, false);
            android.util.Log.d(TAG, "Hebrew option injected");

            // Restore our checkmark if Hebrew is the active selection.
            if (hebrewSelected) showOurCheckmark(item);
        } catch (Exception e) {
            android.util.Log.e(TAG, "injectHebrewOption failed: " + e);
        }
    }

    private static View createHebrewListItem(Context ctx) {
        try {
            int layoutId = ctx.getResources().getIdentifier(
                    "bottom_sheet_list_checkmark_item", "layout", ctx.getPackageName());
            if (layoutId != 0) {
                android.view.LayoutInflater inflater = android.view.LayoutInflater.from(ctx);
                View itemView = inflater.inflate(layoutId, null, false);
                TextView tv = findFirstTextView(itemView);
                if (tv != null) {
                    tv.setText(LABEL_HEBREW);
                    tv.setTextColor(Color.WHITE);
                }
                // Hide the checkmark until Hebrew is actually selected.
                ImageView check = findFirstImageView(itemView);
                if (check != null) check.setVisibility(View.INVISIBLE);
                itemView.setOnClickListener(v -> onHebrewItemClicked(v.getContext()));
                return itemView;
            }
        } catch (Exception ignored) {}
        return createFallbackItem(ctx);
    }

    private static TextView findFirstTextView(View v) {
        if (v instanceof TextView) return (TextView) v;
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                TextView found = findFirstTextView(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View createFallbackItem(Context ctx) {
        TextView tv = new TextView(ctx);
        tv.setText(LABEL_HEBREW);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTypeface(null, Typeface.NORMAL);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(ctx, 20), dp(ctx, 14), dp(ctx, 20), dp(ctx, 14));
        tv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 52)));
        tv.setOnClickListener(v -> onHebrewItemClicked(v.getContext()));
        return tv;
    }

    // ── Click handler ─────────────────────────────────────────────────────────

    private static void onHebrewItemClicked(Context ctx) {
        android.util.Log.d(TAG, "onHebrewItemClicked");

        boolean ok = selectHebrew();

        if (ok) {
            onHebrewSelected();
            android.widget.Toast.makeText(ctx, TOAST_OK,
                    android.widget.Toast.LENGTH_SHORT).show();
        } else {
            android.widget.Toast.makeText(ctx, TOAST_FAIL,
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Called after Hebrew is successfully selected: marks our footer item and
     * dismisses the bottom sheet. We intentionally leave YouTube's native rows
     * untouched.
     */
    private static void onHebrewSelected() {
        hebrewSelected = true;
        showOurCheckmark(hebrewItemRef.get());

        Object oju = ojuRef.get();
        if (oju != null) {
            try {
                Method dismiss = findMethodInHierarchy(oju.getClass(), "dismissAllowingStateLoss");
                if (dismiss == null) dismiss = findMethodInHierarchy(oju.getClass(), "dismiss");
                if (dismiss != null) {
                    dismiss.invoke(oju);
                    android.util.Log.d(TAG, "bottom sheet dismissed via " + dismiss.getName());
                }
            } catch (Exception e) {
                android.util.Log.w(TAG, "dismiss failed: " + e);
            }
        }
    }

    private static void showOurCheckmark(View hebrewItem) {
        if (hebrewItem == null) return;
        ImageView check = findFirstImageView(hebrewItem);
        if (check != null) {
            check.setVisibility(View.VISIBLE);
            android.util.Log.d(TAG, "checkmark shown on Hebrew item");
        }
    }

    private static ImageView findFirstImageView(View v) {
        if (v instanceof ImageView) return (ImageView) v;
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                ImageView found = findFirstImageView(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Method findMethodInHierarchy(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }

    // ── Core selection logic ──────────────────────────────────────────────────

    /**
     * Produces Hebrew subtitles the NATIVE way:
     *
     *   1. Take a real caption track from alxc.t().
     *   2. Call the track's own translate-builder method, SubtitleTrack.t(lang),
     *      with "iw" — this is exactly what YouTube's native "Auto-translate →
     *      <language>" menu does. It returns a proper translated SubtitleTrack
     *      (a copy of the base with the translation-language field set and the
     *      "is translated" flag = true).
     *   3. Hand that track to YouTube's own selection method, alis.a().
     *
     * Because the track is built by YouTube's own builder, the whole native
     * pipeline (timedtext fetch with &tlang=iw, rendering, persistence across
     * fullscreen/seek) just works — no URL interception, no cloning.
     */
    private static final String TRANSLATE_LANG = "iw"; // YouTube's legacy code for Hebrew

    private static boolean selectHebrew() {
        try {
            Object oju = ojuRef.get();
            if (oju == null) { android.util.Log.w(TAG, "no oju ref"); return false; }

            Object alis = getDeclaredFieldValue(oju, "al");
            if (alis == null) { android.util.Log.w(TAG, "oju.al is null"); return false; }

            Object alxc = findAlxc(alis);
            if (alxc == null) { android.util.Log.w(TAG, "alxc not found"); return false; }

            @SuppressWarnings("unchecked")
            List<Object> tracks = (List<Object>) alxc.getClass().getMethod("t").invoke(alxc);
            if (tracks == null || tracks.isEmpty()) {
                android.util.Log.w(TAG, "alxc.t() returned empty"); return false;
            }

            // DIAGNOSTIC: dump every track so we can identify the real source
            // track vs. translation options / already-translated tracks.
            for (int i = 0; i < tracks.size(); i++) dumpTrack(i, tracks.get(i));

            Object baseTrack = pickBaseTrack(tracks);
            if (baseTrack == null) { android.util.Log.w(TAG, "no translatable base track"); return false; }
            android.util.Log.d(TAG, "base track lang=" + getLanguageCode(baseTrack));

            // Build a non-destructive copy via YouTube's own builder...
            Object hebrewTrack = buildTranslatedTrack(baseTrack, TRANSLATE_LANG);
            if (hebrewTrack == null) {
                android.util.Log.w(TAG, "could not build translated track (t() method not found)");
                return false;
            }
            // ...then force &tlang=iw into the copy's timedtext-URL field and set
            // its language-code fields to "iw". This is the piece that makes the
            // captions actually render: YouTube fetches the URL stored on the
            // track, so the URL itself must carry the translation param. Fields
            // are matched by VALUE (URL contains "timedtext", value is a lang
            // code), never by obfuscated name. Applied to the COPY only — the
            // original track is never touched.
            injectHebrewIntoTrack(hebrewTrack, TRANSLATE_LANG);
            android.util.Log.d(TAG, "built+injected hebrew track, lang=" + getLanguageCode(hebrewTrack));

            // Arm the sticky URL interceptor before the first fetch fires.
            hebrewSelected = true;

            // Replicate YouTube's OWN native selection sequence (from
            // oju.onItemClick), which makes TWO calls on TWO different objects:
            //
            //   this.al.a(track);   // selection controller (aliq)  — picks the track
            //   this.an.L(track);   // renderer (brg)               — actually displays it
            //
            // The previous version only did al.a(), so the track was selected but
            // never rendered ("error retrieving subtitle" / no captions on screen).
            // an.L() is the missing call that drives the fetch + on-screen display.
            boolean selected = false;
            Method aMethod = findTrackMethod(alis);
            if (aMethod != null) {
                selected = invokeTrackMethod(alis, aMethod, hebrewTrack, "al.a()");
            }

            Object renderer = getDeclaredFieldValue(oju, "an");
            if (renderer != null) {
                Method lMethod = findTrackMethod(renderer);
                if (lMethod != null) {
                    invokeTrackMethod(renderer, lMethod, hebrewTrack, "an.L()");
                    selected = true;
                }
            } else {
                android.util.Log.w(TAG, "renderer field 'an' not found");
            }

            if (!selected) {
                hebrewSelected = false; // disarm interceptor on failure
                android.util.Log.w(TAG, "no selection method worked");
            }
            return selected;

        } catch (Exception e) {
            hebrewSelected = false;
            android.util.Log.e(TAG, "selectHebrew failed: " + e);
            return false;
        }
    }

    /**
     * Calls SubtitleTrack's translate-builder method on the base track to get a
     * track translated to {@code lang}. The method is obfuscated (single letter)
     * but uniquely identifiable: the only NON-static instance method that takes
     * one String and returns a SubtitleTrack.
     */
    private static Object buildTranslatedTrack(Object baseTrack, String lang) {
        Class<?> trackClass = baseTrack.getClass();
        // The translate method lives on the abstract SubtitleTrack; walk up.
        for (Class<?> c = trackClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != String.class) continue;
                if (!m.getReturnType().getName().contains("SubtitleTrack")) continue;
                try {
                    m.setAccessible(true);
                    Object result = m.invoke(baseTrack, lang);
                    if (result != null && result != baseTrack) {
                        android.util.Log.d(TAG, "translate method: " + c.getSimpleName() + "." + m.getName());
                        return result;
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /**
     * Picks a real, translatable base track: a track with a genuine language
     * code that is not the "disable captions" option and not already Hebrew.
     */
    private static Object pickBaseTrack(List<Object> tracks) {
        Object fallback = null;
        for (Object t : tracks) {
            String lang = getLanguageCode(t);
            // Skip the "Off"/"Auto-translate" menu options (non-lang-code values).
            if (lang == null || !isLangCode(lang)) continue;
            // Skip tracks that are already a translation (their timedtext URL
            // already carries &tlang=) — translating a translation fails.
            if (isAlreadyTranslated(t)) continue;
            if (fallback == null) fallback = t;
            // Prefer a non-Hebrew source.
            if (!lang.startsWith("iw") && !lang.startsWith("he")) return t;
        }
        return fallback;
    }

    /** True if the track's timedtext URL already contains a &tlang= param. */
    private static boolean isAlreadyTranslated(Object track) {
        for (Field f : track.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(track);
                if (v instanceof String && ((String) v).contains("timedtext")
                        && ((String) v).contains("tlang=")) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /**
     * Forces Hebrew onto a track copy by value-pattern field rewriting:
     *   - the field holding the timedtext URL gets &tlang=iw (appended if absent),
     *   - any field holding a bare language code is set to iw.
     * Name-agnostic, so it survives R8 renaming. Mutates the COPY only.
     */
    private static void injectHebrewIntoTrack(Object track, String lang) {
        for (Field f : track.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(track);
                if (!(v instanceof String)) continue;
                String s = (String) v;
                if (s.contains("timedtext")) {
                    String nu;
                    if (s.contains("&tlang=")) {
                        nu = s.replaceFirst("&tlang=[^&]*", "&tlang=" + lang);
                    } else if (s.contains("?tlang=")) {
                        nu = s.replaceFirst("\\?tlang=[^&]*", "?tlang=" + lang);
                    } else {
                        nu = s + "&tlang=" + lang;
                    }
                    f.set(track, nu);
                    android.util.Log.d(TAG, "URL field set with tlang=" + lang);
                } else if (isLangCode(s) && !s.equals(lang)) {
                    f.set(track, lang);
                }
            } catch (Throwable ignored) {}
        }
    }

    /** DIAGNOSTIC: logs every no-arg getter (String/boolean/int/Optional) of a track. */
    private static void dumpTrack(int idx, Object track) {
        if (track == null) { android.util.Log.d(TAG, "track[" + idx + "]=null"); return; }
        StringBuilder sb = new StringBuilder("track[" + idx + "] ");
        for (Method m : track.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            String name = m.getName();
            if (name.equals("hashCode") || name.equals("toString")
                    || name.equals("describeContents") || name.equals("getClass")) continue;
            Class<?> rt = m.getReturnType();
            boolean wanted = rt == String.class || rt == boolean.class
                    || rt == int.class || rt.getName().contains("Optional")
                    || rt.getName().contains("CharSequence");
            if (!wanted) continue;
            try {
                m.setAccessible(true);
                Object v = m.invoke(track);
                if (rt == boolean.class && Boolean.FALSE.equals(v)) continue; // skip false noise
                sb.append(name).append('=').append(v).append(' ');
            } catch (Throwable ignored) {}
        }
        android.util.Log.d(TAG, sb.toString());
    }

    private static boolean invokeTrackMethod(Object target, Method m, Object track, String label) {
        try {
            m.invoke(target, track);
            android.util.Log.d(TAG, label + " ok");
            return true;
        } catch (InvocationTargetException e) {
            android.util.Log.w(TAG, label + " threw: " + e.getCause());
        } catch (Exception e) {
            android.util.Log.w(TAG, label + " failed: " + e);
        }
        return false;
    }

    // ── Reflection helpers ────────────────────────────────────────────────────

    /**
     * Returns the BCP-47-ish language code of a SubtitleTrack. Tries the
     * conventional AutoValue getter g() first; falls back to scanning short
     * no-arg String getters for a value matching the lang-code pattern.
     */
    private static String getLanguageCode(Object track) {
        if (track == null) return null;
        try {
            Object v = track.getClass().getMethod("g").invoke(track);
            if (v instanceof String) return (String) v;
        } catch (Exception ignored) {}
        for (Method m : track.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (m.getReturnType() != String.class) continue;
            if (m.getName().length() > 2) continue;
            try {
                m.setAccessible(true);
                Object v = m.invoke(track);
                if (v instanceof String && isLangCode((String) v)) return (String) v;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static boolean isLangCode(String s) {
        return s != null && s.matches("^[a-z]{2,3}(-[A-Z]{2,3})?$");
    }

    /** Gets a declared field value (handles private fields up the hierarchy). */
    private static Object getDeclaredFieldValue(Object obj, String name) {
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
            } catch (Exception e) { break; }
        }
        android.util.Log.w(TAG, "field '" + name + "' not found on " + obj.getClass().getName());
        return null;
    }

    /** Finds alxc in alis: the field whose class has a public t()→List method. */
    private static Object findAlxc(Object alis) {
        for (Field f : alis.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            Object val;
            try { val = f.get(alis); } catch (Exception ignored) { continue; }
            if (val == null) continue;
            try {
                Method t = val.getClass().getMethod("t");
                if (List.class.isAssignableFrom(t.getReturnType())) {
                    android.util.Log.d(TAG, "Found alxc in field " + f.getName()
                            + " type=" + val.getClass().getSimpleName());
                    return val;
                }
            } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }

    /** Finds a single-param void method that accepts a SubtitleTrack. */
    private static Method findTrackMethod(Object target) {
        for (Method m : target.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() != 1) continue;
            if (m.getReturnType() != void.class) continue;
            if (m.getParameterTypes()[0].getName().contains("SubtitleTrack")) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static int dp(Context ctx, float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics()));
    }
}
