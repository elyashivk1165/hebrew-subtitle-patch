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
import java.util.List;

/**
 * Adds a "Hebrew (auto-translate)" option to YouTube's CC bottom sheet.
 *
 * Design (deliberately simple to stay robust across YouTube/R8 versions):
 *
 *   1. We NEVER build or clone a SubtitleTrack object. Cloning YouTube's
 *      internal AutoValue track via reflection was the root cause of every
 *      recurring bug (selection silently failing, the original track getting
 *      corrupted so subtitles broke after fullscreen, wrong checkmark).
 *
 *   2. When the user taps our option we hand YouTube ONE OF ITS OWN existing
 *      tracks back to its own selection method. YouTube can never reject its
 *      own object, so selection always succeeds and no internal state is
 *      corrupted.
 *
 *   3. That selection makes YouTube fetch a `timedtext` URL. Our Cronet URL
 *      interceptor forces `&tlang=iw` (appending it when absent), which triggers
 *      Google's server-side auto-translation to Hebrew. The rewrite is STICKY:
 *      it applies to every timedtext request while Hebrew is active, so the
 *      subtitles survive fullscreen / seek / quality-change re-fetches instead
 *      of reverting after the first request.
 *
 *   4. We show our own checkmark + a toast and dismiss the sheet. We do NOT
 *      fight YouTube's native checkmark rendering — a cosmetic mismatch on the
 *      base track is acceptable and trying to suppress it caused most of the
 *      churn in this file's history.
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

    /**
     * Set just before we trigger a track-selection call. The URL interceptor
     * watches for this and forces &tlang=iw on the next timedtext request.
     */
    private static volatile boolean hebrewPending  = false;
    private static volatile boolean hebrewSelected = false;

    // ── URL interceptor ───────────────────────────────────────────────────────

    public static void saveTimedtextRequest(Object engine, String url,
                                             Object callback, Object executor) {
        // kept for hook compatibility
    }

    public static String interceptTimedtextUrl(String url) {
        if (url == null || !url.contains("timedtext")) return url;

        // STICKY: force Hebrew on EVERY timedtext request while Hebrew is the
        // active choice — not just the first one. YouTube re-fetches this URL
        // after fullscreen, seeking, or a quality change; rewriting only the
        // first request was the root cause of "subtitles revert after
        // fullscreen". hebrewPending covers the very first request (fired before
        // hebrewSelected is observed); hebrewSelected keeps every later one.
        if (!hebrewSelected && !hebrewPending) {
            return url;
        }
        hebrewPending = false;

        String swapped;
        if (url.contains("&tlang=")) {
            swapped = url.replaceFirst("&tlang=[^&]*", "&tlang=iw");
        } else if (url.contains("tlang=")) {
            // tlang present as the first query param ("?tlang=..")
            swapped = url.replaceFirst("([?&])tlang=[^&]*", "$1tlang=iw");
        } else {
            // No translation param at all — append one to trigger auto-translate.
            swapped = url + "&tlang=iw";
        }
        android.util.Log.d(TAG, "timedtext forced to tlang=iw (sticky=" + hebrewSelected + ")");
        return swapped;
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
     * Hands YouTube one of its OWN existing subtitle tracks back to its own
     * selection method, with hebrewPending armed so the interceptor rewrites
     * the resulting timedtext URL to tlang=iw.
     *
     * No object is ever cloned or mutated, so YouTube's internal state stays
     * consistent and subtitles no longer break after fullscreen / re-open.
     */
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

            // Any real, translatable track works as the translation base.
            Object baseTrack = pickBaseTrack(tracks);
            android.util.Log.d(TAG, "base track lang=" + getLanguageCode(baseTrack));

            hebrewPending = true;

            // Primary path: alis.a(track)
            Method aMethod = findTrackMethod(alis);
            if (aMethod != null && invokeTrackMethod(alis, aMethod, baseTrack, "alis.a()")) {
                return true;
            }

            // Fallback path: alxc.Q(track)
            Method qMethod = findTrackMethod(alxc);
            if (qMethod != null && invokeTrackMethod(alxc, qMethod, baseTrack, "alxc.Q()")) {
                return true;
            }

            hebrewPending = false;
            android.util.Log.w(TAG, "no selection method worked");
            return false;

        } catch (Exception e) {
            hebrewPending = false;
            android.util.Log.e(TAG, "selectHebrew failed: " + e);
            return false;
        }
    }

    /** Prefer a non-Hebrew track (so translation has a source); else the first. */
    private static Object pickBaseTrack(List<Object> tracks) {
        for (Object t : tracks) {
            String lang = getLanguageCode(t);
            if (lang != null && !lang.startsWith("iw") && !lang.startsWith("he")) {
                return t;
            }
        }
        return tracks.get(0);
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
