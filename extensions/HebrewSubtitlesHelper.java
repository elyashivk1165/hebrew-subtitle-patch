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

    // Saved timedtext request components for Cronet fallback
    private static Object savedEngine;
    private static String savedBaseUrl;
    private static Object savedCallback;
    private static Object savedExecutor;

    // ── URL interceptor (save-only) ───────────────────────────────────────────

    /**
     * Called from bytecode hook at CronetEngine.newUrlRequestBuilder.
     * Saves request params so reloadSubtitlesCronet() can reissue with &tlang=iw
     * as a fallback when reflection-based track selection fails.
     *
     * We intentionally do NOT modify the URL here — doing so caused every
     * timedtext request (English, Auto-translate, etc.) to be fetched in Hebrew.
     * The reflection path (selectHebrewTrackViaApi) handles selection natively
     * through YouTube's own subtitle player, so no URL mangling is needed.
     */
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

    /**
     * Re-issues the last saved timedtext request with &tlang=iw.
     * Used only when the reflection-based selectHebrewTrackViaApi() fails.
     */
    public static void reloadSubtitlesCronet(Context ctx) {
        try {
            if (savedEngine == null || savedBaseUrl == null
                    || savedCallback == null || savedExecutor == null) return;
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
            android.util.Log.d("HebrewSubs", "reloadSubtitlesCronet: issued " + newUrl);
        } catch (Exception e) {
            android.util.Log.e("HebrewSubs", "reloadSubtitlesCronet failed: " + e);
        }
    }

    // ── CC Panel injection ────────────────────────────────────────────────────

    /**
     * Called by the bytecode hook immediately before oju.N() calls addFooterView.
     * Stores a WeakRef to the oju instance for reflection-based track selection.
     */
    public static void injectHebrewOption(Object ojuInstance, ListView listView) {
        try {
            if (listView == null) return;
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
        return createHebrewItemFallback(ctx);
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

    private static void onHebrewItemClicked(Context ctx) {
        selectHebrewTrackViaApi(ctx);
        android.widget.Toast.makeText(ctx,
                "\u05DB\u05EA\u05D5\u05D1\u05D9\u05D5\u05EA \u05E2\u05D1\u05E8\u05D9\u05EA: \u05E4\u05E2\u05D9\u05DC",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    // ── Reflection-based Hebrew track selection ───────────────────────────────

    /**
     * Selects the Hebrew auto-translate track via reflection on YouTube internals.
     *
     * Chain (from jadx RE of YouTube 20.40.45):
     *   oju.al (public aliq field)
     *     → alis instance (implements aliq)
     *       → field whose class has t():List  = alxc
     *         → alxc.t() returns List<SubtitleTrack> (all auto-translate language options)
     *           → find Hebrew by language code "iw"/"he" via String methods
     *             → alis.a(hebrewTrack) — directly selects track, no sub-menu
     *
     * Falls back to one-shot Cronet URL reload if any step fails.
     */
    private static void selectHebrewTrackViaApi(Context ctx) {
        try {
            Object oju = ojuRef.get();
            if (oju == null) {
                android.util.Log.w("HebrewSubs", "selectHebrewTrack: no oju ref");
                reloadSubtitlesCronet(ctx);
                return;
            }

            // Step 1: oju.al → alis
            Field alField;
            try {
                alField = oju.getClass().getField("al");
            } catch (NoSuchFieldException e) {
                android.util.Log.w("HebrewSubs", "oju.al not found: " + e);
                reloadSubtitlesCronet(ctx);
                return;
            }
            Object alis = alField.get(oju);
            if (alis == null) { reloadSubtitlesCronet(ctx); return; }

            // Step 2: find alxc in alis — field whose class has t() returning List
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
            if (alxc == null) {
                android.util.Log.w("HebrewSubs", "alxc not found");
                reloadSubtitlesCronet(ctx);
                return;
            }

            // Step 3: alxc.t() → list of language tracks
            Method tMethod = alxc.getClass().getMethod("t");
            @SuppressWarnings("unchecked")
            List<Object> tracks = (List<Object>) tMethod.invoke(alxc);
            if (tracks == null || tracks.isEmpty()) {
                android.util.Log.w("HebrewSubs", "alxc.t() empty");
                reloadSubtitlesCronet(ctx);
                return;
            }

            // Step 4a: find Hebrew track by language code "iw" or "he"
            // Strategy: call every no-param String method on each track and look for
            // the BCP-47 language code.  This is locale-independent and avoids the
            // problem of display names being in the user's UI language (Hebrew), which
            // caused the generic Hebrew-chars check to match Ukrainian ("אוקראינית")
            // before Hebrew ("עברית").
            Object hebrewTrack = findTrackByCode(tracks, "iw", "he");

            // Step 4b: exact display name fallback — works even if the language
            // code method is renamed in a future YouTube version.
            if (hebrewTrack == null) {
                for (Object track : tracks) {
                    // "עברית" = \u05E2\u05D1\u05E8\u05D9\u05EA
                    if (track.toString().contains("\u05E2\u05D1\u05E8\u05D9\u05EA")) {
                        hebrewTrack = track;
                        break;
                    }
                }
            }

            if (hebrewTrack == null) {
                android.util.Log.w("HebrewSubs", "Hebrew track not found in " + tracks.size());
                reloadSubtitlesCronet(ctx);
                return;
            }

            // Step 5: alis.a(hebrewTrack) — single-param method from aliq interface
            Method aMethod = null;
            for (Method m : alis.getClass().getMethods()) {
                if ("a".equals(m.getName()) && m.getParameterCount() == 1) {
                    aMethod = m;
                    break;
                }
            }
            if (aMethod == null) {
                android.util.Log.w("HebrewSubs", "alis.a() not found");
                reloadSubtitlesCronet(ctx);
                return;
            }
            aMethod.invoke(alis, hebrewTrack);
            android.util.Log.d("HebrewSubs", "Hebrew track selected: " + hebrewTrack);

        } catch (Exception e) {
            android.util.Log.e("HebrewSubs", "selectHebrewTrackViaApi failed: " + e);
            reloadSubtitlesCronet(ctx);
        }
    }

    /**
     * Scans every no-param String-returning method on each track looking for
     * an exact match to one of the given language codes.
     */
    private static Object findTrackByCode(List<Object> tracks, String... codes) {
        for (Object track : tracks) {
            for (Method m : track.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (!String.class.equals(m.getReturnType())) continue;
                try {
                    String val = (String) m.invoke(track);
                    if (val == null) continue;
                    for (String code : codes) {
                        if (code.equals(val)) return track;
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    // ── Fallback programmatic item ────────────────────────────────────────────

    private static View createHebrewItemFallback(Context ctx) {
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

    private static int dp(Context ctx, float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics()));
    }
}
