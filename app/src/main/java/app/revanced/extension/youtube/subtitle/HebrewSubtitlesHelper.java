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

public final class HebrewSubtitlesHelper {

    // WeakRef to the oju (SubtitleMenuBottomSheetFragment) instance
    private static WeakReference<Object> ojuRef = new WeakReference<>(null);

    // Saved timedtext request components for Cronet fallback
    private static Object savedEngine;
    private static String savedBaseUrl;
    private static Object savedCallback;
    private static Object savedExecutor;

    // Flag: true when we should append &tlang=iw on the NEXT timedtext request (one-shot)
    private static volatile boolean hebrewEnabled = false;

    // ── URL interceptor (save + conditional one-shot modify) ──────────────────

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

    /**
     * Called by the bytecode hook immediately before every
     * CronetEngine.newUrlRequestBuilder call.
     * Returns the (possibly modified) URL.
     * ONE-SHOT: resets hebrewEnabled after first use so future requests
     * (English, auto-translate etc.) are never touched.
     */
    public static String interceptTimedtextUrl(String url) {
        try {
            if (url == null || !url.contains("timedtext")) return url;
            if (!hebrewEnabled) return url;
            hebrewEnabled = false; // one-shot reset
            if (url.contains("tlang=")) {
                // Replace existing tlang= with iw
                return url.replaceAll("[&?]tlang=[^&]*", "") + "&tlang=iw";
            }
            return url + "&tlang=iw";
        } catch (Exception ignored) {
            return url;
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

        // Primary approach: call alxc.Q(hebrewTrack) directly
        boolean selected = selectHebrewDirectly();
        if (!selected) {
            android.util.Log.w("HebrewSubs", "selectHebrewDirectly failed, trying URL flag fallback");
            // Fallback: set one-shot flag and trigger a re-fetch
            hebrewEnabled = true;
            boolean refreshed = forceTimedtextRefetch();
            if (!refreshed) {
                android.util.Log.w("HebrewSubs", "forceTimedtextRefetch failed, trying Cronet fallback");
                reloadSubtitlesCronet(ctx);
            }
        }

        android.widget.Toast.makeText(ctx,
                "\u05DB\u05EA\u05D5\u05D1\u05D9\u05D5\u05EA \u05E2\u05D1\u05E8\u05D9\u05EA: \u05E4\u05E2\u05D9\u05DC",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    // ── Primary approach: directly call alxc.Q(hebrewTrack) ──────────────────

    /**
     * Finds alxc from oju.al (alis), creates a Hebrew SubtitleTrack via
     * autoTranslateTrack.s("iw"), then calls alxc.Q(hebrewTrack).
     * This triggers YouTube's own machinery to load subtitles, and our
     * one-shot URL hook ensures &tlang=iw is appended.
     */
    private static boolean selectHebrewDirectly() {
        try {
            Object oju = ojuRef.get();
            if (oju == null) {
                android.util.Log.w("HebrewSubs", "selectHebrewDirectly: no oju ref");
                return false;
            }

            // oju.al = alis (Laliq; implementation = Lalis;)
            Object alis = null;
            try {
                Field alField = oju.getClass().getField("al");
                alis = alField.get(oju);
            } catch (Exception e) {
                android.util.Log.w("HebrewSubs", "oju.al not found: " + e);
            }
            if (alis == null) return false;

            // Find alxc: the field in alis whose class has a void method taking SubtitleTrack
            Object alxc = findAlxcInAlis(alis);
            if (alxc == null) {
                android.util.Log.w("HebrewSubs", "alxc not found in alis fields");
                return false;
            }

            // Find Q: void method taking a SubtitleTrack parameter
            Method qMethod = findSubtitleTrackVoidMethod(alxc);
            if (qMethod == null) {
                android.util.Log.w("HebrewSubs", "Q(SubtitleTrack) not found on alxc");
                return false;
            }

            // Find the auto-translate track in oju.ap (ArrayList of tracks)
            Object autoTranslateTrack = findAutoTranslateTrack(oju);
            if (autoTranslateTrack == null) {
                android.util.Log.w("HebrewSubs", "no auto-translate track found in oju.ap");
                // Fall back to using current track (oju.ak) with URL flag
                hebrewEnabled = true;
                Object currentTrack = null;
                try {
                    Field akField = oju.getClass().getField("ak");
                    currentTrack = akField.get(oju);
                } catch (Exception ignored) {}
                if (currentTrack == null) return false;
                android.util.Log.d("HebrewSubs", "Calling Q(currentTrack) with URL flag");
                qMethod.invoke(alxc, currentTrack);
                return true;
            }

            // Create Hebrew copy: autoTranslateTrack.s("iw")
            Object hebrewTrack = createHebrewTrack(autoTranslateTrack, "iw");
            if (hebrewTrack == null) {
                android.util.Log.w("HebrewSubs", "could not create Hebrew track copy; using URL flag with auto-translate track");
                hebrewEnabled = true;
                qMethod.invoke(alxc, autoTranslateTrack);
                return true;
            }

            // Set one-shot flag and call Q(hebrewTrack)
            hebrewEnabled = true;
            android.util.Log.d("HebrewSubs", "Calling alxc." + qMethod.getName() + "(hebrewTrack=" + hebrewTrack + ")");
            qMethod.invoke(alxc, hebrewTrack);
            return true;

        } catch (Exception e) {
            android.util.Log.e("HebrewSubs", "selectHebrewDirectly failed: " + e);
            return false;
        }
    }

    /**
     * Finds alxc inside alis by scanning its declared fields for one
     * whose class has a void method taking a SubtitleTrack parameter.
     */
    private static Object findAlxcInAlis(Object alis) {
        for (Field f : alis.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            Object val;
            try { val = f.get(alis); } catch (Exception ignored) { continue; }
            if (val == null) continue;
            if (findSubtitleTrackVoidMethod(val) != null) {
                android.util.Log.d("HebrewSubs", "Found alxc in field " + f.getName() +
                        " type=" + val.getClass().getName());
                return val;
            }
        }
        return null;
    }

    /**
     * Finds a void method that takes exactly one parameter whose type name
     * contains "SubtitleTrack" (or the obfuscated equivalent — a non-primitive,
     * non-String, non-Boolean class with a short obfuscated name).
     *
     * We match on the parameter type's simple name containing "SubtitleTrack"
     * first; if that fails, we use the structural heuristic: single non-boolean
     * non-primitive parameter, void return, short method name (≤2 chars).
     */
    private static Method findSubtitleTrackVoidMethod(Object obj) {
        // Pass 1: explicit SubtitleTrack name match
        for (Method m : obj.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() != 1) continue;
            if (m.getReturnType() != void.class) continue;
            Class<?> paramType = m.getParameterTypes()[0];
            if (paramType.getSimpleName().contains("SubtitleTrack") ||
                paramType.getName().contains("SubtitleTrack")) {
                m.setAccessible(true);
                android.util.Log.d("HebrewSubs", "findSubtitleTrackVoidMethod (name match): " +
                        m.getName() + "(" + paramType.getName() + ")");
                return m;
            }
        }
        // Pass 2: structural heuristic — short obfuscated name, void, 1 non-primitive param
        Method candidate = null;
        for (Method m : obj.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() != 1) continue;
            if (m.getReturnType() != void.class) continue;
            String name = m.getName();
            if (name.length() > 2) continue;
            if (!Character.isUpperCase(name.charAt(0))) continue;
            Class<?> paramType = m.getParameterTypes()[0];
            if (paramType.isPrimitive()) continue;
            if (paramType == String.class) continue;
            if (paramType == Boolean.class) continue;
            // Prefer name "Q" as the known method name
            if ("Q".equals(name)) {
                m.setAccessible(true);
                android.util.Log.d("HebrewSubs", "findSubtitleTrackVoidMethod (Q heuristic): " +
                        m.getName() + "(" + paramType.getName() + ")");
                return m;
            }
            if (candidate == null) candidate = m;
        }
        if (candidate != null) {
            candidate.setAccessible(true);
            android.util.Log.d("HebrewSubs", "findSubtitleTrackVoidMethod (fallback heuristic): " +
                    candidate.getName() + "(" + candidate.getParameterTypes()[0].getName() + ")");
        }
        return candidate;
    }

    /**
     * Finds a track in oju's ArrayList fields where track.v() returns true
     * (i.e., the generic AUTO_TRANSLATE_CAPTIONS_OPTION track).
     */
    private static Object findAutoTranslateTrack(Object oju) {
        for (Field f : oju.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            Object val;
            try { val = f.get(oju); } catch (Exception ignored) { continue; }
            if (!(val instanceof java.util.ArrayList)) continue;
            for (Object item : (java.util.ArrayList<?>) val) {
                if (item == null) continue;
                try {
                    // Call item.v() — returns true for AUTO_TRANSLATE_CAPTIONS_OPTION
                    Method vMethod = item.getClass().getDeclaredMethod("v");
                    vMethod.setAccessible(true);
                    Object result = vMethod.invoke(item);
                    if (Boolean.TRUE.equals(result)) {
                        android.util.Log.d("HebrewSubs", "Found auto-translate track: " + item);
                        return item;
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /**
     * Creates a SubtitleTrack copy with the given language code by calling
     * track.s(langCode) — the known copy-with-language method on SubtitleTrack.
     */
    private static Object createHebrewTrack(Object autoTranslateTrack, String langCode) {
        try {
            // Find method: String param, returns same class (SubtitleTrack)
            for (Method m : autoTranslateTrack.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != String.class) continue;
                Class<?> ret = m.getReturnType();
                if (ret != autoTranslateTrack.getClass() &&
                    !ret.getSimpleName().contains("SubtitleTrack") &&
                    !ret.getName().contains("SubtitleTrack")) continue;
                m.setAccessible(true);
                Object copy = m.invoke(autoTranslateTrack, langCode);
                android.util.Log.d("HebrewSubs", "createHebrewTrack via " + m.getName() + "(" + langCode + "): " + copy);
                return copy;
            }
        } catch (Exception e) {
            android.util.Log.w("HebrewSubs", "createHebrewTrack failed: " + e);
        }
        return null;
    }

    // ── Legacy fallback: force re-fetch via alxc.Q(oju.ak) ───────────────────

    /**
     * Fallback: calls Q(currentTrack) so YouTube re-issues a timedtext request.
     * The one-shot hebrewEnabled flag must be set BEFORE calling this.
     */
    private static boolean forceTimedtextRefetch() {
        try {
            Object oju = ojuRef.get();
            if (oju == null) return false;

            Object currentTrack = null;
            try {
                Field akField = oju.getClass().getField("ak");
                currentTrack = akField.get(oju);
            } catch (Exception e) {
                android.util.Log.w("HebrewSubs", "oju.ak not found: " + e);
            }

            Object alis = null;
            try {
                Field alField = oju.getClass().getField("al");
                alis = alField.get(oju);
            } catch (Exception e) {
                android.util.Log.w("HebrewSubs", "oju.al not found: " + e);
            }
            if (alis == null) return false;

            Object alxc = findAlxcInAlis(alis);
            if (alxc == null) return false;

            Method qMethod = findSubtitleTrackVoidMethod(alxc);
            if (qMethod == null) return false;

            Object trackToPass = currentTrack != null ? currentTrack : findAnyTrack(oju);
            if (trackToPass == null) return false;

            android.util.Log.d("HebrewSubs", "forceTimedtextRefetch: calling " + qMethod.getName() + "(" + trackToPass + ")");
            qMethod.invoke(alxc, trackToPass);
            return true;

        } catch (Exception e) {
            android.util.Log.e("HebrewSubs", "forceTimedtextRefetch failed: " + e);
            return false;
        }
    }

    /** Finds any non-null track from oju's ArrayList fields. */
    private static Object findAnyTrack(Object oju) {
        for (Field f : oju.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            Object val;
            try { val = f.get(oju); } catch (Exception ignored) { continue; }
            if (!(val instanceof java.util.ArrayList)) continue;
            java.util.ArrayList<?> list = (java.util.ArrayList<?>) val;
            for (Object item : list) {
                if (item != null) return item;
            }
        }
        return null;
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int dp(Context ctx, float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics()));
    }
}
