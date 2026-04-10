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

    private static final String PREFS = "revanced_prefs";
    private static final String KEY_ON  = "revanced_hebrew_subtitles_enabled";

    // WeakRef to the oju (SubtitleMenuBottomSheetFragment) instance for reflection-based track selection
    private static WeakReference<Object> ojuRef = new WeakReference<>(null);

    // Saved timedtext request components (for Cronet fallback)
    private static Object savedEngine;
    private static String savedBaseUrl;
    private static Object savedCallback;
    private static Object savedExecutor;

    // ── Preference ───────────────────────────────────────────────────────────

    public static boolean isEnabled(Context context) {
        try {
            return context.getSharedPreferences(PREFS, 0).getBoolean(KEY_ON, true);
        } catch (Exception e) {
            return true;
        }
    }

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

    // ── Subtitle reload (Cronet fallback) ────────────────────────────────────

    public static void reloadSubtitlesCronet(Context ctx) {
        try {
            if (savedEngine == null || savedBaseUrl == null
                    || savedCallback == null || savedExecutor == null) return;
            String newUrl = savedBaseUrl + (isEnabled(ctx) ? "&tlang=iw" : "");
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
        } catch (Exception e) {
            android.util.Log.e("HebrewSubs", "reloadSubtitlesCronet failed: " + e);
        }
    }

    // ── CC Panel injection (called from bytecode hook) ────────────────────────

    /**
     * Called by the bytecode hook immediately before oju.N() calls addFooterView.
     * Receives the oju instance and the ListView directly.
     *
     * Uses addFooterView (NOT addHeaderView): footers are placed AFTER adapter
     * items, so adapter positions remain unchanged.  addHeaderView would shift
     * all adapter positions by 1, causing oju.onItemClick to call adapter.getItem
     * with the wrong index → ClassCastException on any tap.
     *
     * Stores a WeakRef to the oju instance for reflection-based Hebrew track
     * selection when the user taps the injected item.
     */
    public static void injectHebrewOption(Object ojuInstance, ListView listView) {
        try {
            if (listView == null) return;
            // We inject BEFORE oju.N()'s own addFooterView, so count=0 on first call.
            if (listView.getFooterViewsCount() > 0) return;

            // Store WeakRef to oju for later track selection via reflection
            ojuRef = new WeakReference<>(ojuInstance);

            Context ctx = listView.getContext();
            View item = createHebrewListItem(ctx);
            listView.addFooterView(item, null, false);
            android.util.Log.d("HebrewSubs", "Hebrew option injected via addFooterView");
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
        return createHebrewItem(ctx, ViewGroup.LayoutParams.MATCH_PARENT);
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
        ctx.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_ON, true).apply();
        selectHebrewTrackViaApi(ctx);
        android.widget.Toast.makeText(ctx,
                "\u05DB\u05EA\u05D5\u05D1\u05D9\u05D5\u05EA \u05E2\u05D1\u05E8\u05D9\u05EA: \u05E4\u05E2\u05D9\u05DC",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * Selects the Hebrew auto-translate track directly via reflection on YouTube internals.
     *
     * Reflection chain (found via jadx reverse-engineering of YouTube 20.40.45):
     *   oju.al       → aliq (alis implementation)
     *   alis.b       → alxc (player subtitle component) — found by scanning for a field
     *                  whose class has a method t() returning List
     *   alxc.t()     → List<SubtitleTrack> — available language tracks
     *   find Hebrew  → track whose toString() contains Hebrew chars (\u05D0–\u05EA),
     *                  "iw", or "Hebrew"
     *   alis.a(track) → directly selects the Hebrew track without opening sub-menus
     *
     * Falls back to Cronet URL reload if any step fails.
     */
    private static void selectHebrewTrackViaApi(Context ctx) {
        try {
            Object oju = ojuRef.get();
            if (oju == null) {
                android.util.Log.w("HebrewSubs", "selectHebrewTrack: no oju ref, falling back");
                reloadSubtitlesCronet(ctx);
                return;
            }

            // Step 1: get oju.al (public aliq field)
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

            // Step 2: find alxc in alis fields — the field whose class has t() returning List
            Object alxc = null;
            for (Field f : alis.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val;
                try { val = f.get(alis); } catch (Exception ignored) { continue; }
                if (val == null) continue;
                try {
                    Method tMethod = val.getClass().getMethod("t");
                    if (List.class.isAssignableFrom(tMethod.getReturnType())) {
                        alxc = val;
                        break;
                    }
                } catch (NoSuchMethodException ignored) {}
            }
            if (alxc == null) {
                android.util.Log.w("HebrewSubs", "alxc not found in alis fields");
                reloadSubtitlesCronet(ctx);
                return;
            }

            // Step 3: call alxc.t() to get list of available language tracks
            Method tMethod = alxc.getClass().getMethod("t");
            @SuppressWarnings("unchecked")
            List<Object> tracks = (List<Object>) tMethod.invoke(alxc);
            if (tracks == null || tracks.isEmpty()) {
                android.util.Log.w("HebrewSubs", "alxc.t() returned empty list");
                reloadSubtitlesCronet(ctx);
                return;
            }

            // Step 4: find Hebrew track by display name
            Object hebrewTrack = null;
            for (Object track : tracks) {
                String display = track.toString();
                boolean hasHebrew = false;
                for (char c : display.toCharArray()) {
                    if (c >= '\u05D0' && c <= '\u05EA') { hasHebrew = true; break; }
                }
                if (hasHebrew || display.contains("iw")
                        || display.toLowerCase().contains("hebrew")) {
                    hebrewTrack = track;
                    break;
                }
            }
            if (hebrewTrack == null) {
                android.util.Log.w("HebrewSubs", "Hebrew track not found in " + tracks.size() + " tracks");
                reloadSubtitlesCronet(ctx);
                return;
            }

            // Step 5: call alis.a(hebrewTrack) — the single-param aliq interface method
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
            android.util.Log.d("HebrewSubs", "selectHebrewTrack: selected " + hebrewTrack);

        } catch (Exception e) {
            android.util.Log.e("HebrewSubs", "selectHebrewTrackViaApi failed: " + e);
            reloadSubtitlesCronet(ctx);
        }
    }

    // ── Fallback programmatic item ────────────────────────────────────────────

    private static View createHebrewItem(Context ctx, int parentWidth) {
        TextView tv = new TextView(ctx);
        tv.setText("\u05E2\u05D1\u05E8\u05D9\u05EA (\u05EA\u05E8\u05D2\u05D5\u05DD \u05D0\u05D5\u05D8\u05D5\u05DE\u05D8\u05D9)");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTypeface(null, Typeface.NORMAL);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(ctx, 20), dp(ctx, 14), dp(ctx, 20), dp(ctx, 14));
        int w = parentWidth > 0 ? parentWidth : ViewGroup.LayoutParams.MATCH_PARENT;
        tv.setLayoutParams(new ViewGroup.LayoutParams(w, dp(ctx, 52)));
        tv.setOnClickListener(v -> onHebrewItemClicked(v.getContext()));
        return tv;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int dp(Context ctx, float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics()));
    }
}
