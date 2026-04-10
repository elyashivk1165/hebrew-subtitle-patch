package app.revanced.extension.youtube.subtitle;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class HebrewSubtitlesHelper {

    // WeakRef to the oju (SubtitleMenuBottomSheetFragment) instance
    private static WeakReference<Object> ojuRef = new WeakReference<>(null);

    // Saved timedtext request components for Cronet fallback
    private static Object savedEngine;
    private static String savedBaseUrl;
    private static Object savedCallback;
    private static Object savedExecutor;

    // ── URL interceptor (save-only) ───────────────────────────────────────────

    public static void saveTimedtextRequest(Object engine, String url,
                                             Object callback, Object executor) {
        try {
            if (url != null && url.contains("timedtext")) {
                savedEngine   = engine;
                savedBaseUrl  = url.replaceAll("[&?]tlang=[^&]*", "");
                savedCallback = callback;
                savedExecutor = executor;
            }
        } catch (Exception ignored) {}
    }

    // ── Cronet fallback ───────────────────────────────────────────────────────

    public static void reloadSubtitlesCronet(Context ctx) {
        try {
            if (savedEngine == null || savedBaseUrl == null
                    || savedCallback == null || savedExecutor == null) {
                android.util.Log.w("HebrewSubs", "Cronet fallback: no saved request");
                return;
            }
            String newUrl = savedBaseUrl + "&tlang=iw";
            Method newUrlRequestBuilder = null;
            for (Method m : savedEngine.getClass().getMethods()) {
                if ("newUrlRequestBuilder".equals(m.getName()) && m.getParameterCount() == 3) {
                    newUrlRequestBuilder = m;
                    break;
                }
            }
            if (newUrlRequestBuilder == null) return;
            Object builder = newUrlRequestBuilder.invoke(savedEngine, newUrl, savedCallback, savedExecutor);
            Object request = builder.getClass().getMethod("build").invoke(builder);
            request.getClass().getMethod("start").invoke(request);
            android.util.Log.d("HebrewSubs", "Cronet fallback: issued " + newUrl);
        } catch (Exception e) {
            android.util.Log.e("HebrewSubs", "reloadSubtitlesCronet failed: " + e);
        }
    }

    // ── CC Panel injection ────────────────────────────────────────────────────

    public static void injectHebrewOption(Object ojuInstance, ListView listView) {
        try {
            if (listView == null) return;
            if (listView.getFooterViewsCount() > 0) return;

            ojuRef = new WeakReference<>(ojuInstance);

            Context ctx = listView.getContext();
            View item = createHebrewItem(ctx);
            listView.addFooterView(item, null, false);
            android.util.Log.d("HebrewSubs", "Hebrew option injected");
        } catch (Exception e) {
            android.util.Log.e("HebrewSubs", "injectHebrewOption failed: " + e);
        }
    }

    /**
     * Creates a simple styled TextView for the Hebrew option.
     * Using a plain TextView (not an inflated layout) guarantees that the
     * OnClickListener works without any child-view interference.
     */
    private static View createHebrewItem(Context ctx) {
        TextView tv = new TextView(ctx);
        tv.setText("\u05E2\u05D1\u05E8\u05D9\u05EA (\u05EA\u05E8\u05D2\u05D5\u05DD \u05D0\u05D5\u05D8\u05D5\u05DE\u05D8\u05D9)");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTypeface(null, Typeface.NORMAL);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(ctx, 20), dp(ctx, 14), dp(ctx, 20), dp(ctx, 14));
        tv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 52)));
        tv.setClickable(true);
        tv.setFocusable(false);
        tv.setOnClickListener(v -> onHebrewItemClicked(v.getContext()));
        return tv;
    }

    private static void onHebrewItemClicked(Context ctx) {
        android.util.Log.d("HebrewSubs", "onHebrewItemClicked fired");
        android.widget.Toast.makeText(ctx,
                "\u05DE\u05E0\u05E1\u05D4 \u05DC\u05D1\u05D7\u05D5\u05E8 \u05E2\u05D1\u05E8\u05D9\u05EA...",
                android.widget.Toast.LENGTH_SHORT).show();
        selectHebrewTrackViaApi(ctx);
    }

    // ── Reflection-based Hebrew track selection ───────────────────────────────

    /**
     * Selects the Hebrew auto-translate track via reflection on YouTube internals.
     *
     * Primary path (mirrors exact user flow of Auto-translate → Hebrew):
     *   oju.ap (ArrayList of panel tracks) → find auto-translate track (v()==true)
     *   → call t("iw") on it to create a Hebrew-language copy
     *   → call alis.a(hebrewCopy)  [hebrewCopy.v()==false → alxc.Q() is called]
     *
     * Secondary path (alxc.t() language list):
     *   oju.al → alis → alxc → alxc.t() → scan for language code "iw"/"he"
     *   → alis.a(hebrewTrack)
     *
     * Fallback: one-shot Cronet request with &tlang=iw.
     */
    private static void selectHebrewTrackViaApi(Context ctx) {
        try {
            Object oju = ojuRef.get();
            if (oju == null) {
                android.util.Log.w("HebrewSubs", "no oju ref");
                reloadSubtitlesCronet(ctx);
                return;
            }

            // Get alis (oju.al public field — the aliq/track-selection callback)
            Object alis;
            try {
                Field alField = oju.getClass().getField("al");
                alis = alField.get(oju);
            } catch (Exception e) {
                android.util.Log.w("HebrewSubs", "oju.al not found: " + e);
                reloadSubtitlesCronet(ctx);
                return;
            }
            if (alis == null) { reloadSubtitlesCronet(ctx); return; }

            // Find the single-param "a" method on alis (the aliq interface method)
            Method alisA = null;
            for (Method m : alis.getClass().getMethods()) {
                if ("a".equals(m.getName()) && m.getParameterCount() == 1) {
                    alisA = m;
                    break;
                }
            }
            if (alisA == null) {
                android.util.Log.w("HebrewSubs", "alis.a() not found");
                reloadSubtitlesCronet(ctx);
                return;
            }

            // ── Primary path: auto-translate track → t("iw") ─────────────────
            Object hebrewTrack = getHebrewTrackViaAutoTranslate(oju);
            if (hebrewTrack != null) {
                android.util.Log.d("HebrewSubs", "Primary path: calling alis.a(" + hebrewTrack + ")");
                alisA.invoke(alis, hebrewTrack);
                android.widget.Toast.makeText(ctx,
                        "\u05DB\u05EA\u05D5\u05D1\u05D9\u05D5\u05EA \u05E2\u05D1\u05E8\u05D9\u05EA: \u05E4\u05E2\u05D9\u05DC",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }

            // ── Secondary path: alxc.t() language list ────────────────────────
            hebrewTrack = getHebrewTrackViaAlxc(alis);
            if (hebrewTrack != null) {
                android.util.Log.d("HebrewSubs", "Secondary path: calling alis.a(" + hebrewTrack + ")");
                alisA.invoke(alis, hebrewTrack);
                android.widget.Toast.makeText(ctx,
                        "\u05DB\u05EA\u05D5\u05D1\u05D9\u05D5\u05EA \u05E2\u05D1\u05E8\u05D9\u05EA: \u05E4\u05E2\u05D9\u05DC",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }

            android.util.Log.w("HebrewSubs", "Both reflection paths failed, using Cronet");
            reloadSubtitlesCronet(ctx);
            android.widget.Toast.makeText(ctx,
                    "\u05DB\u05EA\u05D5\u05D1\u05D9\u05D5\u05EA \u05E2\u05D1\u05E8\u05D9\u05EA: \u05E4\u05E2\u05D9\u05DC",
                    android.widget.Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            android.util.Log.e("HebrewSubs", "selectHebrewTrackViaApi failed: " + e);
            reloadSubtitlesCronet(ctx);
        }
    }

    /**
     * Primary path: mirrors the exact manual user flow.
     *
     * When a user taps "Auto-translate" → "Hebrew", YouTube does:
     *   1. alis.a(autoTranslateTrack)  — autoTranslateTrack.v() == true
     *   2. Inside alis.a: calls alxcVar.t() and shows sub-menu
     *   3. User picks Hebrew → alis.a(hebrewLangTrack) where hebrewLangTrack
     *      is autoTranslateTrack.t("iw")
     *
     * We replicate step 3 directly.
     *
     * oju.ap is the private ArrayList of tracks shown in the CC panel.
     * We find the auto-translate entry (v()==true), call t("iw") on it,
     * and return the resulting Hebrew-language track.
     */
    private static Object getHebrewTrackViaAutoTranslate(Object oju) {
        try {
            for (Field f : oju.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val;
                try { val = f.get(oju); } catch (Exception ignored) { continue; }
                if (!(val instanceof ArrayList)) continue;
                ArrayList<?> list = (ArrayList<?>) val;
                if (list.isEmpty()) continue;

                for (Object track : list) {
                    if (track == null) continue;
                    // Check v() — true means this is the generic "Auto-translate" option
                    Boolean isAutoTranslate = callBooleanMethod(track, "v");
                    if (!Boolean.TRUE.equals(isAutoTranslate)) continue;

                    // Found it. Call t("iw") to create a Hebrew-language copy.
                    Object hebrewCopy = callStringArgMethod(track, "iw");
                    if (hebrewCopy != null) {
                        android.util.Log.d("HebrewSubs",
                                "auto-translate path: created Hebrew copy: " + hebrewCopy);
                        return hebrewCopy;
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.w("HebrewSubs", "getHebrewTrackViaAutoTranslate: " + e);
        }
        return null;
    }

    /**
     * Secondary path: alxc.t() returns the list of language tracks.
     * Scans all String-valued fields and methods on each track for "iw" or "he".
     */
    private static Object getHebrewTrackViaAlxc(Object alis) {
        try {
            // Find alxc: field of alis whose class has t():List
            Object alxc = null;
            for (Field f : alis.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val;
                try { val = f.get(alis); } catch (Exception ignored) { continue; }
                if (val == null) continue;
                try {
                    Method t = val.getClass().getMethod("t");
                    if (List.class.isAssignableFrom(t.getReturnType())) {
                        alxc = val;
                        break;
                    }
                } catch (NoSuchMethodException ignored) {}
            }
            if (alxc == null) return null;

            Method tMethod = alxc.getClass().getMethod("t");
            @SuppressWarnings("unchecked")
            List<Object> tracks = (List<Object>) tMethod.invoke(alxc);
            if (tracks == null || tracks.isEmpty()) return null;

            android.util.Log.d("HebrewSubs", "alxc.t() returned " + tracks.size() + " tracks");

            // Scan String fields then String methods for "iw" or "he"
            for (Object track : tracks) {
                if (hasStringValue(track, "iw") || hasStringValue(track, "he")) {
                    return track;
                }
            }
            // Fallback: exact display name "עברית"
            for (Object track : tracks) {
                if (track.toString().contains("\u05E2\u05D1\u05E8\u05D9\u05EA")) return track;
            }
        } catch (Exception e) {
            android.util.Log.w("HebrewSubs", "getHebrewTrackViaAlxc: " + e);
        }
        return null;
    }

    // ── Reflection utilities ──────────────────────────────────────────────────

    /** Calls the no-param boolean method named {@code name} on {@code obj}. */
    private static Boolean callBooleanMethod(Object obj, String name) {
        try {
            Method m = obj.getClass().getMethod(name);
            if (boolean.class.equals(m.getReturnType()) || Boolean.class.equals(m.getReturnType())) {
                return (Boolean) m.invoke(obj);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Finds a method on {@code obj} that takes exactly one String argument
     * and returns an instance of the same class, then invokes it with {@code arg}.
     * This is SubtitleTrack.t(String) — creates a language-specific copy.
     */
    private static Object callStringArgMethod(Object obj, String arg) {
        for (Method m : obj.getClass().getMethods()) {
            if (m.getParameterCount() != 1) continue;
            if (!String.class.equals(m.getParameterTypes()[0])) continue;
            if (!obj.getClass().isAssignableFrom(m.getReturnType())
                    && !m.getReturnType().isAssignableFrom(obj.getClass())) continue;
            try {
                Object result = m.invoke(obj, arg);
                if (result != null) return result;
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** Returns true if any String field or no-param String method on {@code obj} equals {@code target}. */
    private static boolean hasStringValue(Object obj, String target) {
        // Check declared fields
        for (Field f : obj.getClass().getDeclaredFields()) {
            if (!String.class.equals(f.getType())) continue;
            f.setAccessible(true);
            try {
                if (target.equals(f.get(obj))) return true;
            } catch (Exception ignored) {}
        }
        // Check no-param methods
        for (Method m : obj.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (!String.class.equals(m.getReturnType())) continue;
            try {
                if (target.equals(m.invoke(obj))) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int dp(Context ctx, float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics()));
    }
}
