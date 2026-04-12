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

public final class HebrewSubtitlesHelper {

    private static WeakReference<Object>   ojuRef        = new WeakReference<>(null);
    private static WeakReference<View>     hebrewItemRef = new WeakReference<>(null);
    private static WeakReference<ListView> listViewRef   = new WeakReference<>(null);

    /**
     * Flag set just before we trigger a track-selection call.
     * The URL interceptor watches for this and swaps &tlang=XX → &tlang=iw
     * on the very next timedtext request YouTube builds.
     */
    private static volatile boolean hebrewPending  = false;
    private static volatile boolean hebrewSelected = false;

    // ── URL interceptor ───────────────────────────────────────────────────────

    public static void saveTimedtextRequest(Object engine, String url,
                                             Object callback, Object executor) {
        // kept for hook compatibility
    }

    public static String interceptTimedtextUrl(String url) {
        if (url != null && url.contains("timedtext")) {
            android.util.Log.d("HebrewSubs", "interceptor called, pending=" + hebrewPending
                    + " tlang=" + extractTlang(url));
        }
        if (hebrewPending && url != null && url.contains("&tlang=")) {
            hebrewPending = false;
            url = url.replaceFirst("&tlang=[^&]*", "&tlang=iw");
            android.util.Log.d("HebrewSubs", "URL swapped → " + url.substring(url.lastIndexOf("&tlang=")));
        }
        return url;
    }

    private static String extractTlang(String url) {
        int i = url.indexOf("&tlang=");
        if (i < 0) return "none";
        int j = url.indexOf('&', i + 1);
        return j < 0 ? url.substring(i + 7) : url.substring(i + 7, j);
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
            android.util.Log.d("HebrewSubs", "Hebrew option injected");

            if (hebrewSelected) {
                // restore checkmark state from previous selection
                ImageView check = findFirstImageView(item);
                if (check != null) check.setVisibility(View.VISIBLE);
                // clear native items (they're added before our footer)
                for (int i = 0; i < listView.getChildCount(); i++) {
                    View child = listView.getChildAt(i);
                    if (child != item) setAllImageViewsInvisible(child);
                }
            }
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

    /**
     * Called after Hebrew is successfully selected.
     * 1. Shows checkmark on our item, clears checkmarks on native items.
     * 2. Dismisses the bottom sheet.
     */
    private static void onHebrewSelected() {
        hebrewSelected = true;
        // --- checkmark ---
        View hebrewItem = hebrewItemRef.get();
        ListView lv = listViewRef.get();

        if (hebrewItem != null) {
            ImageView check = findFirstImageView(hebrewItem);
            if (check != null) {
                check.setVisibility(View.VISIBLE);
                android.util.Log.d("HebrewSubs", "checkmark shown on Hebrew item");
            } else {
                android.util.Log.w("HebrewSubs", "checkmark ImageView not found in Hebrew item");
            }
        }

        if (lv != null) {
            // Clear checkmarks from all native list items (non-footer)
            int cleared = 0;
            for (int i = 0; i < lv.getChildCount(); i++) {
                View child = lv.getChildAt(i);
                if (child == hebrewItem) continue;
                cleared += setAllImageViewsInvisible(child);
            }
            android.util.Log.d("HebrewSubs", "cleared checkmarks in " + cleared + " native views");
        }

        // --- dismiss bottom sheet ---
        Object oju = ojuRef.get();
        if (oju != null) {
            try {
                Method dismiss = findMethodInHierarchy(oju.getClass(), "dismissAllowingStateLoss");
                if (dismiss == null) dismiss = findMethodInHierarchy(oju.getClass(), "dismiss");
                if (dismiss != null) {
                    dismiss.invoke(oju);
                    android.util.Log.d("HebrewSubs", "bottom sheet dismissed via " + dismiss.getName());
                } else {
                    android.util.Log.w("HebrewSubs", "dismiss method not found on " + oju.getClass().getName());
                }
            } catch (Exception e) {
                android.util.Log.w("HebrewSubs", "dismiss failed: " + e);
            }
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

    private static int setAllImageViewsInvisible(View v) {
        int count = 0;
        if (v instanceof ImageView) {
            if (v.getVisibility() == View.VISIBLE) {
                v.setVisibility(View.INVISIBLE);
                count++;
            }
        } else if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++)
                count += setAllImageViewsInvisible(vg.getChildAt(i));
        }
        return count;
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
     * Strategy:
     *  1. Brute-force: try every single-String-param method that returns a SubtitleTrack
     *     and check if the result has g()=="iw".
     *  2. Field modification: directly set fields g/l/n in the track object via reflection.
     *  3. URL interceptor (hebrewPending) as last-resort safety net.
     */
    private static boolean selectHebrew() {
        try {
            Object oju = ojuRef.get();
            if (oju == null) { android.util.Log.w("HebrewSubs", "no oju ref"); return false; }

            Object alis = getDeclaredFieldValue(oju, "al");
            if (alis == null) { android.util.Log.w("HebrewSubs", "oju.al is null"); return false; }

            Object alxc = findAlxc(alis);
            if (alxc == null) { android.util.Log.w("HebrewSubs", "alxc not found"); return false; }

            Method tMethod = alxc.getClass().getMethod("t");
            @SuppressWarnings("unchecked")
            List<Object> tracks = (List<Object>) tMethod.invoke(alxc);
            if (tracks == null || tracks.isEmpty()) {
                android.util.Log.w("HebrewSubs", "alxc.t() returned empty"); return false;
            }

            Object triggerTrack = tracks.get(0);
            android.util.Log.d("HebrewSubs", "trigger track: g=" + getLanguageCode(triggerTrack));

            // Step 1: brute-force clone
            Object hebrewTrack = findAndCloneWithLang(triggerTrack, "iw");
            if (hebrewTrack != null && "iw".equals(getLanguageCode(hebrewTrack))) {
                android.util.Log.d("HebrewSubs", "clone ok: g=iw");
            } else {
                // Step 2: modify fields directly
                hebrewTrack = tryModifyTrackFields(triggerTrack, "iw");
                android.util.Log.d("HebrewSubs", "field-mod track: g=" + getLanguageCode(hebrewTrack));
            }

            // Step 3: URL interceptor backup
            hebrewPending = true;

            Method aMethod = findAliqAMethod(alis);
            if (aMethod != null) {
                try {
                    aMethod.invoke(alis, hebrewTrack);
                    android.util.Log.d("HebrewSubs", "alis.a() ok");
                    onHebrewSelected();
                    return true;
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    android.util.Log.w("HebrewSubs", "alis.a() threw: " + cause);
                    if (cause != null) {
                        for (StackTraceElement el : cause.getStackTrace()) {
                            android.util.Log.w("HebrewSubs", "  at " + el);
                            if (el.toString().contains("alxc") || el.toString().contains("alis")) break;
                        }
                    }
                }
            }

            Method qMethod = findSubtitleTrackMethod(alxc);
            if (qMethod != null) {
                try {
                    qMethod.invoke(alxc, hebrewTrack);
                    android.util.Log.d("HebrewSubs", "alxc.Q() ok");
                    onHebrewSelected();
                    return true;
                } catch (InvocationTargetException e) {
                    android.util.Log.w("HebrewSubs", "alxc.Q() threw: " + e.getCause());
                }
            }

            hebrewPending  = false;
            hebrewSelected = false;
            android.util.Log.w("HebrewSubs", "no method worked");
            return false;

        } catch (Exception e) {
            hebrewPending  = false;
            hebrewSelected = false;
            android.util.Log.e("HebrewSubs", "selectHebrew failed: " + e);
            return false;
        }
    }

    /**
     * Brute-force: tries every declared method on the track that takes a single
     * String param and returns the same type.  Returns the copy if g()=="iw".
     */
    private static Object findAndCloneWithLang(Object track, String lang) {
        for (Method m : track.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() != 1) continue;
            if (m.getParameterTypes()[0] != String.class) continue;
            if (!m.getReturnType().isInstance(track)) continue;
            m.setAccessible(true);
            try {
                Object copy = m.invoke(track, lang);
                String code = getLanguageCode(copy);
                android.util.Log.d("HebrewSubs", "clone method " + m.getName() + "(" + lang + ") → g=" + code);
                if (lang.equals(code)) return copy;
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Directly modifies fields g (lang code), l (URL) and n (track id) in the
     * AutoValue_SubtitleTrack via reflection.  Works in-place and returns the
     * same object (modified).
     */
    private static Object tryModifyTrackFields(Object track, String lang) {
        String origLang = getLanguageCode(track);
        // field g — language code
        try {
            Field f = findFieldInHierarchy(track.getClass(), "g");
            if (f != null) { f.setAccessible(true); f.set(track, lang); }
        } catch (Exception e) { android.util.Log.w("HebrewSubs", "field g mod: " + e); }
        // field l — URL
        try {
            Field f = findFieldInHierarchy(track.getClass(), "l");
            if (f != null) {
                f.setAccessible(true);
                String url = (String) f.get(track);
                if (url != null && url.contains("&tlang=")) {
                    f.set(track, url.replaceFirst("&tlang=[^&]*", "&tlang=" + lang));
                }
            }
        } catch (Exception e) { android.util.Log.w("HebrewSubs", "field l mod: " + e); }
        // field n — track id (e.g. t.en-US.uk → t.en-US.iw)
        try {
            Field f = findFieldInHierarchy(track.getClass(), "n");
            if (f != null) {
                f.setAccessible(true);
                String id = (String) f.get(track);
                if (id != null && origLang != null && id.contains(origLang)) {
                    f.set(track, id.replace(origLang, lang));
                }
            }
        } catch (Exception e) { android.util.Log.w("HebrewSubs", "field n mod: " + e); }
        android.util.Log.d("HebrewSubs", "after field mods: g=" + getLanguageCode(track));
        return track;
    }

    private static Field findFieldInHierarchy(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    // ── Reflection helpers ────────────────────────────────────────────────────

    private static String getLanguageCode(Object track) {
        try {
            return (String) track.getClass().getMethod("g").invoke(track);
        } catch (Exception ignored) {
            return track.toString();
        }
    }

    /** Gets a declared field value (handles private fields). */
    private static Object getDeclaredFieldValue(Object obj, String name) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(obj);
        } catch (NoSuchFieldException ignored) {
        } catch (Exception ignored) {}
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

    /** Finds the single-parameter void method on alis that accepts a SubtitleTrack. */
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

    /** Finds a void method on alxc that takes a SubtitleTrack parameter. */
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

    private static int dp(Context ctx, float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics()));
    }
}
