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

    // Flag: true when the user has selected Hebrew and we should append &tlang=iw
    private static volatile boolean hebrewEnabled = false;

    // ── URL interceptor (save + conditional modify) ───────────────────────────

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
     */
    public static String interceptTimedtextUrl(String url) {
        try {
            if (url == null || !url.contains("timedtext")) return url;
            if (!hebrewEnabled) return url;
            if (url.contains("tlang=")) return url;
            return url + "&tlang=iw";
        } catch (Exception ignored) {
            return url;
        }
    }

    // ── Called when user selects any normal CC track (not our footer item) ────

    public static void onCcItemSelected() {
        hebrewEnabled = false;
        android.util.Log.d("HebrewSubs", "hebrewEnabled = false (user picked another track)");
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

        hebrewEnabled = true;
        android.util.Log.d("HebrewSubs", "hebrewEnabled = true");

        // Force YouTube to re-issue a timedtext request through its own code path.
        // alxc.Q(currentTrack) triggers a real CronetEngine.newUrlRequestBuilder call,
        // which our bytecode hook intercepts and appends &tlang=iw.
        boolean refreshed = forceTimedtextRefetch();
        if (!refreshed) {
            android.util.Log.w("HebrewSubs", "forceTimedtextRefetch failed, trying Cronet fallback");
            reloadSubtitlesCronet(ctx);
        }

        android.widget.Toast.makeText(ctx,
                "\u05DB\u05EA\u05D5\u05D1\u05D9\u05D5\u05EA \u05E2\u05D1\u05E8\u05D9\u05EA: \u05E4\u05E2\u05D9\u05DC",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    // ── Force re-fetch via alxc.Q(oju.ak) ────────────────────────────────────

    /**
     * Mirrors what YouTube does internally when a track is selected:
     *   alxc.Q(track)  →  issues a new CronetEngine.newUrlRequestBuilder call
     *
     * We call alxc.Q(oju.ak) where oju.ak is the currently-active SubtitleTrack.
     * Because hebrewEnabled is now true, our URL hook will append &tlang=iw.
     *
     * @return true if the call was issued successfully
     */
    private static boolean forceTimedtextRefetch() {
        try {
            Object oju = ojuRef.get();
            if (oju == null) {
                android.util.Log.w("HebrewSubs", "forceTimedtextRefetch: no oju ref");
                return false;
            }

            // oju.ak = current SubtitleTrack (public field)
            Object currentTrack = null;
            try {
                Field akField = oju.getClass().getField("ak");
                currentTrack = akField.get(oju);
            } catch (Exception e) {
                android.util.Log.w("HebrewSubs", "oju.ak not found: " + e);
            }

            // oju.al = alis (aliq, the track-selection callback)
            Object alis = null;
            try {
                Field alField = oju.getClass().getField("al");
                alis = alField.get(oju);
            } catch (Exception e) {
                android.util.Log.w("HebrewSubs", "oju.al not found: " + e);
            }
            if (alis == null) return false;

            // Find alxc: field of alis whose class has a method Q(Object) or Q(SubtitleTrack)
            Object alxc = findAlxc(alis);
            if (alxc == null) {
                android.util.Log.w("HebrewSubs", "alxc not found in alis");
                return false;
            }

            // Find Q method: single-param, not named standard Java methods
            Method qMethod = findQMethod(alxc);
            if (qMethod == null) {
                android.util.Log.w("HebrewSubs", "alxc.Q() not found");
                return false;
            }

            // If we have the current track, call Q(currentTrack).
            // If not, try to get any track from oju.ap ArrayList.
            Object trackToPass = currentTrack;
            if (trackToPass == null) {
                trackToPass = findAnyTrack(oju);
            }
            if (trackToPass == null) {
                android.util.Log.w("HebrewSubs", "no track to pass to Q()");
                return false;
            }

            android.util.Log.d("HebrewSubs", "Calling alxc." + qMethod.getName() + "(" + trackToPass + ")");
            qMethod.invoke(alxc, trackToPass);
            return true;

        } catch (Exception e) {
            android.util.Log.e("HebrewSubs", "forceTimedtextRefetch failed: " + e);
            return false;
        }
    }

    /** Finds the alxc field inside alis by looking for a class that has a Q() method. */
    private static Object findAlxc(Object alis) {
        for (Field f : alis.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            Object val;
            try { val = f.get(alis); } catch (Exception ignored) { continue; }
            if (val == null) continue;
            if (findQMethod(val) != null) {
                return val;
            }
        }
        return null;
    }

    /** Finds a method named "Q" (or any single-char uppercase) that takes one Object param. */
    private static Method findQMethod(Object obj) {
        for (Method m : obj.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() != 1) continue;
            // Method name should be a short obfuscated name (1-2 chars, uppercase)
            String name = m.getName();
            if (name.length() > 2) continue;
            if (!Character.isUpperCase(name.charAt(0))) continue;
            // Return type void or the same class
            if (m.getReturnType() != void.class && m.getReturnType() != Void.class) continue;
            return m;
        }
        // Broader search: any single-param void method with short obfuscated name
        for (Method m : obj.getClass().getMethods()) {
            if (m.getParameterCount() != 1) continue;
            String name = m.getName();
            if (name.length() > 2) continue;
            if (!Character.isUpperCase(name.charAt(0))) continue;
            if (m.getReturnType() != void.class && m.getReturnType() != Void.class) continue;
            return m;
        }
        return null;
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
