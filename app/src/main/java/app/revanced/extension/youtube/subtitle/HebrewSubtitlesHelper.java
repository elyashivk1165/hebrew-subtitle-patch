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
import java.util.List;

public final class HebrewSubtitlesHelper {

    // WeakRef to the oju (SubtitleMenuBottomSheetFragment) instance
    private static WeakReference<Object> ojuRef = new WeakReference<>(null);

    // Saved timedtext request components (for Cronet fallback)
    private static Object savedEngine;
    private static String savedBaseUrl;
    private static Object savedCallback;
    private static Object savedExecutor;

    // One-shot flag: set before calling alxc.Q() so the next timedtext URL gets &tlang=iw
    private static volatile boolean hebrewEnabled = false;

    // ── URL interceptor ───────────────────────────────────────────────────────

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
     * ONE-SHOT: appends &tlang=iw only when hebrewEnabled is true,
     * then immediately resets the flag so future requests are not touched.
     */
    public static String interceptTimedtextUrl(String url) {
        try {
            if (url == null || !url.contains("timedtext")) return url;
            if (!hebrewEnabled) return url;
            hebrewEnabled = false; // one-shot reset
            // Replace any existing tlang= or append fresh
            String base = url.replaceAll("[&?]tlang=[^&]*", "");
            return base + "&tlang=iw";
        } catch (Exception ignored) {
            return url;
        }
    }

    // ── CC Panel injection ────────────────────────────────────────────────────

    /**
     * Called by the bytecode hook immediately before oju.N() calls addFooterView.
     * Uses addFooterView(view, null, false) so our item has isSelectable=false
     * and never shifts the adapter item positions used in onItemClick.
     */
    public static void injectHebrewOption(Object ojuInstance, ListView listView) {
        try {
            if (listView == null) return;
            // We inject BEFORE YouTube's addFooterView; count=0 on first call.
            if (listView.getFooterViewsCount() > 0) return;

            ojuRef = new WeakReference<>(ojuInstance);

            Context ctx = listView.getContext();
            View item = createHebrewListItem(ctx);
            listView.addFooterView(item, null, false);
            android.util.Log.d("HebrewSubs", "Hebrew option injected");
        } catch (Exception e) {
            android.util.Log.e("HebrewSubs", "injectHebrewOption failed: " + e);
        }
    }

    /**
     * Inflates YouTube's own bottom_sheet_list_checkmark_item layout so the
     * Hebrew entry looks identical to every other CC track entry.
     * Falls back to a plain TextView if the resource lookup fails.
     */
    private static View createHebrewListItem(Context ctx) {
        try {
            int layoutId = ctx.getResources().getIdentifier(
                    "bottom_sheet_list_checkmark_item", "layout", ctx.getPackageName());
            if (layoutId != 0) {
                android.view.LayoutInflater inflater = android.view.LayoutInflater.from(ctx);
                View itemView = inflater.inflate(layoutId, null, false);
                TextView tv = findFirstTextView(itemView);
                if (tv != null) {
                    tv.setText("\u05E2\u05D1\u05E8\u05D9\u05EA (\u05EA\u05E8\u05D2\u05D5\u05DD \u05D0\u05D5\u05D8\u05D5\u05DE\u05D8\u05D9)");
                    tv.setTextColor(Color.WHITE);
                }
                itemView.setOnClickListener(v -> onHebrewItemClicked(v.getContext()));
                return itemView;
            }
        } catch (Exception ignored) {}
        // Fallback: plain TextView
        return createHebrewItem(ctx);
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
        tv.setOnClickListener(v -> onHebrewItemClicked(v.getContext()));
        return tv;
    }

    private static void onHebrewItemClicked(Context ctx) {
        android.util.Log.d("HebrewSubs", "onHebrewItemClicked");
        android.widget.Toast.makeText(ctx,
                "\u05DE\u05E0\u05E1\u05D4 \u05DC\u05D1\u05D7\u05D5\u05E8 \u05E2\u05D1\u05E8\u05D9\u05EA...",
                android.widget.Toast.LENGTH_SHORT).show();

        boolean selected = selectHebrewDirectly();
        if (!selected) {
            android.util.Log.w("HebrewSubs", "selectHebrewDirectly failed, falling back to Cronet");
            hebrewEnabled = true;
            reloadSubtitlesCronet(ctx);
        }

        android.widget.Toast.makeText(ctx,
                "\u05DB\u05EA\u05D5\u05D1\u05D9\u05D5\u05EA \u05E2\u05D1\u05E8\u05D9\u05EA: \u05E4\u05E2\u05D9\u05DC",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    // ── Hebrew track selection ────────────────────────────────────────────────

    /**
     * Primary approach:
     *  1. oju.al  → alis
     *  2. scan alis fields for alxc (has a void method taking a non-primitive single param)
     *  3. find auto-translate track in oju.ap where track.v() == true
     *  4. call autoTrack.s("iw") to create Hebrew copy
     *  5. set hebrewEnabled = true (one-shot URL flag)
     *  6. call alxc.Q(hebrewTrack) → YouTube builds timedtext URL → our hook appends &tlang=iw
     */
    private static boolean selectHebrewDirectly() {
        try {
            Object oju = ojuRef.get();
            if (oju == null) return false;

            // oju.al → alis
            Object alis = getFieldValue(oju, "al");
            if (alis == null) return false;

            // Find alxc in alis: field whose class has a void method for one non-primitive param
            Object alxc = findAlxc(alis);
            if (alxc == null) {
                android.util.Log.w("HebrewSubs", "alxc not found in alis");
                return false;
            }

            // Find Q: void method taking exactly one non-primitive param (the SubtitleTrack)
            Method qMethod = findSubtitleTrackVoidMethod(alxc);
            if (qMethod == null) {
                android.util.Log.w("HebrewSubs", "Q method not found on alxc");
                return false;
            }

            // Find auto-translate track in oju.ap (v() == true)
            Object autoTrack = findAutoTranslateTrack(oju);
            if (autoTrack == null) {
                android.util.Log.w("HebrewSubs", "auto-translate track not found");
                return false;
            }

            // Create Hebrew copy via autoTrack.s("iw")
            Object hebrewTrack = createHebrewTrack(autoTrack);
            if (hebrewTrack == null) {
                android.util.Log.w("HebrewSubs", "could not create Hebrew track via s()");
                // Fall back: call Q with auto-translate track + URL flag
                hebrewEnabled = true;
                qMethod.invoke(alxc, autoTrack);
                return true;
            }

            hebrewEnabled = true;
            android.util.Log.d("HebrewSubs", "Calling " + qMethod.getName() + "(hebrewTrack)");
            qMethod.invoke(alxc, hebrewTrack);
            return true;

        } catch (Exception e) {
            android.util.Log.e("HebrewSubs", "selectHebrewDirectly: " + e);
            return false;
        }
    }

    // ── Reflection helpers ────────────────────────────────────────────────────

    /**
     * Gets a field value from obj, trying getDeclaredField first (handles private fields),
     * then falling back to scanning all declared fields by name.
     */
    private static Object getFieldValue(Object obj, String name) {
        // Try declared field (works for private/package-private)
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(obj);
        } catch (NoSuchFieldException ignored) {}
        // Scan superclass chain
        Class<?> cls = obj.getClass().getSuperclass();
        while (cls != null && cls != Object.class) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {}
            catch (Exception e) { break; }
            cls = cls.getSuperclass();
        }
        android.util.Log.w("HebrewSubs", "field '" + name + "' not found on " + obj.getClass().getName());
        return null;
    }

    /**
     * Finds alxc in alis: the field whose class has a void method taking
     * exactly one non-primitive, non-String, non-Boolean parameter.
     */
    private static Object findAlxc(Object alis) {
        for (Field f : alis.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            Object val;
            try { val = f.get(alis); } catch (Exception ignored) { continue; }
            if (val == null) continue;
            if (findSubtitleTrackVoidMethod(val) != null) return val;
        }
        return null;
    }

    /**
     * Finds the void method on obj that takes exactly one non-primitive, non-String param.
     * Prefers method named "Q"; otherwise returns the best candidate.
     */
    private static Method findSubtitleTrackVoidMethod(Object obj) {
        Method best = null;
        for (Method m : obj.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() != 1) continue;
            if (m.getReturnType() != void.class) continue;
            Class<?> p = m.getParameterTypes()[0];
            if (p.isPrimitive() || p == String.class || p == Boolean.class) continue;
            // Exact name match wins immediately
            if ("Q".equals(m.getName())) {
                m.setAccessible(true);
                return m;
            }
            if (best == null) best = m;
        }
        if (best != null) best.setAccessible(true);
        return best;
    }

    /**
     * Finds the track in oju's ArrayList fields where track.v() returns true
     * (the generic AUTO_TRANSLATE_CAPTIONS_OPTION track).
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
                    Method v = item.getClass().getDeclaredMethod("v");
                    v.setAccessible(true);
                    if (Boolean.TRUE.equals(v.invoke(item))) return item;
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /**
     * Creates a Hebrew-language copy of the given SubtitleTrack via track.s("iw").
     * s() is the single-String-param method that returns the same type as the track.
     */
    private static Object createHebrewTrack(Object autoTrack) {
        try {
            for (Method m : autoTrack.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != String.class) continue;
                Class<?> ret = m.getReturnType();
                if (ret == autoTrack.getClass() ||
                    ret.getSimpleName().contains("SubtitleTrack") ||
                    ret.getName().contains("SubtitleTrack")) {
                    m.setAccessible(true);
                    return m.invoke(autoTrack, "iw");
                }
            }
        } catch (Exception e) {
            android.util.Log.w("HebrewSubs", "createHebrewTrack: " + e);
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
            Method builder = null;
            for (Method m : savedEngine.getClass().getMethods()) {
                if ("newUrlRequestBuilder".equals(m.getName()) && m.getParameterCount() == 3) {
                    builder = m;
                    break;
                }
            }
            if (builder == null) return;
            Object req = builder.invoke(savedEngine, newUrl, savedCallback, savedExecutor);
            Object built = req.getClass().getMethod("build").invoke(req);
            built.getClass().getMethod("start").invoke(built);
            android.util.Log.d("HebrewSubs", "Cronet fallback: issued " + newUrl);
        } catch (Exception e) {
            android.util.Log.e("HebrewSubs", "reloadSubtitlesCronet: " + e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int dp(Context ctx, float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics()));
    }
}
