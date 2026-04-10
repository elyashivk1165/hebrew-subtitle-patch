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

    private static WeakReference<Object> ojuRef = new WeakReference<>(null);

    // ── URL interceptor (save only — no longer used for selection) ────────────

    public static void saveTimedtextRequest(Object engine, String url,
                                             Object callback, Object executor) {
        // kept for potential future use
    }

    public static String interceptTimedtextUrl(String url) {
        return url; // no longer needed — YouTube builds the URL natively
    }

    // ── CC Panel injection ────────────────────────────────────────────────────

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

    // ── Click handler ─────────────────────────────────────────────────────────

    private static void onHebrewItemClicked(Context ctx) {
        android.util.Log.d("HebrewSubs", "onHebrewItemClicked");
        android.widget.Toast.makeText(ctx,
                "\u05DE\u05E0\u05E1\u05D4 \u05DC\u05D1\u05D7\u05D5\u05E8 \u05E2\u05D1\u05E8\u05D9\u05EA...",
                android.widget.Toast.LENGTH_SHORT).show();

        boolean ok = selectHebrew();

        if (ok) {
            android.widget.Toast.makeText(ctx,
                    "\u05DB\u05EA\u05D5\u05D1\u05D9\u05D5\u05EA \u05E2\u05D1\u05E8\u05D9\u05EA: \u05E4\u05E2\u05D9\u05DC",
                    android.widget.Toast.LENGTH_SHORT).show();
        } else {
            android.widget.Toast.makeText(ctx,
                    "\u05E9\u05D2\u05D9\u05D0\u05D4: \u05E2\u05D1\u05E8\u05D9\u05EA \u05DC\u05D0 \u05E0\u05DE\u05E6\u05D0\u05D4",
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    // ── Core selection logic ──────────────────────────────────────────────────

    /**
     * Selects Hebrew via YouTube's own track-selection interface:
     *
     *   oju.al  → alis  (Laliq; implementation)
     *   alxc    ← field in alis whose class has t()→List
     *   alxc.t() → List of auto-translate language tracks
     *   find track whose toString() contains Hebrew chars / "iw" / "Hebrew"
     *   alis.a(hebrewTrack) → YouTube natively calls alxc.Q(track) → builds
     *                          timedtext URL with &tlang=iw → displays subtitles
     *
     * No URL interception needed — YouTube handles the URL itself.
     */
    private static boolean selectHebrew() {
        try {
            Object oju = ojuRef.get();
            if (oju == null) {
                android.util.Log.w("HebrewSubs", "no oju ref");
                return false;
            }

            // Step 1: oju.al → alis
            Object alis = getDeclaredFieldValue(oju, "al");
            if (alis == null) {
                android.util.Log.w("HebrewSubs", "oju.al is null");
                return false;
            }

            // Step 2: find alxc — field in alis whose class has t()→List
            Object alxc = findAlxc(alis);
            if (alxc == null) {
                android.util.Log.w("HebrewSubs", "alxc not found");
                return false;
            }

            // Step 3: alxc.t() → language list
            Method tMethod = alxc.getClass().getMethod("t");
            @SuppressWarnings("unchecked")
            List<Object> tracks = (List<Object>) tMethod.invoke(alxc);
            if (tracks == null || tracks.isEmpty()) {
                android.util.Log.w("HebrewSubs", "alxc.t() returned empty");
                return false;
            }
            android.util.Log.d("HebrewSubs", "alxc.t() returned " + tracks.size() + " tracks");

            // Step 4: find Hebrew track by display name
            Object hebrewTrack = null;
            for (Object track : tracks) {
                String s = track.toString();
                if (isHebrewString(s)) {
                    hebrewTrack = track;
                    android.util.Log.d("HebrewSubs", "Found Hebrew track: " + s);
                    break;
                }
            }
            if (hebrewTrack == null) {
                android.util.Log.w("HebrewSubs", "Hebrew not found in language list:");
                for (Object t : tracks) android.util.Log.d("HebrewSubs", "  " + t);
                return false;
            }

            // Step 5: alis.a(hebrewTrack) — the aliq interface method
            Method aMethod = findAliqAMethod(alis);
            if (aMethod != null) {
                android.util.Log.d("HebrewSubs", "Calling alis." + aMethod.getName() + "(hebrewTrack)");
                aMethod.invoke(alis, hebrewTrack);
                return true;
            }

            // Fallback: find Q directly on alxc (method taking SubtitleTrack)
            Method qMethod = findSubtitleTrackMethod(alxc);
            if (qMethod != null) {
                android.util.Log.d("HebrewSubs", "Calling alxc." + qMethod.getName() + "(hebrewTrack)");
                qMethod.invoke(alxc, hebrewTrack);
                return true;
            }

            android.util.Log.w("HebrewSubs", "no suitable method found on alis or alxc");
            return false;

        } catch (Exception e) {
            android.util.Log.e("HebrewSubs", "selectHebrew failed: " + e);
            return false;
        }
    }

    // ── Reflection helpers ────────────────────────────────────────────────────

    /** Gets a declared field value (handles private fields). */
    private static Object getDeclaredFieldValue(Object obj, String name) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(obj);
        } catch (NoSuchFieldException ignored) {
        } catch (Exception ignored) {}
        // Walk superclass chain
        for (Class<?> c = obj.getClass().getSuperclass();
             c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {}
            catch (Exception e) { break; }
        }
        android.util.Log.w("HebrewSubs", "field '" + name + "' not found on "
                + obj.getClass().getName());
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
                    android.util.Log.d("HebrewSubs", "Found alxc in field " + f.getName()
                            + " type=" + val.getClass().getSimpleName());
                    return val;
                }
            } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }

    /**
     * Finds the single-parameter void method on alis that accepts a SubtitleTrack.
     * Matches by "SubtitleTrack" appearing anywhere in the parameter type name.
     */
    private static Method findAliqAMethod(Object alis) {
        for (Method m : alis.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() != 1) continue;
            if (m.getReturnType() != void.class) continue;
            if (m.getParameterTypes()[0].getName().contains("SubtitleTrack")) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    /**
     * Finds a void method on alxc that takes a SubtitleTrack parameter.
     * Specifically avoids boolean/primitive params that trip up the old heuristic.
     */
    private static Method findSubtitleTrackMethod(Object alxc) {
        for (Method m : alxc.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() != 1) continue;
            if (m.getReturnType() != void.class) continue;
            if (m.getParameterTypes()[0].getName().contains("SubtitleTrack")) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    /** Returns true if the string contains Hebrew chars, "iw", or "Hebrew". */
    private static boolean isHebrewString(String s) {
        if (s == null) return false;
        if (s.contains("iw") || s.toLowerCase().contains("hebrew")) return true;
        for (char c : s.toCharArray()) {
            if (c >= '\u05D0' && c <= '\u05EA') return true;
        }
        return false;
    }

    private static int dp(Context ctx, float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics()));
    }
}
