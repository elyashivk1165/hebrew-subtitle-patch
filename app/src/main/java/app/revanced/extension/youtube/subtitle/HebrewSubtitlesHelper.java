package app.revanced.extension.youtube.subtitle;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
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
 * Adds a "Hebrew (auto-translate)" option to YouTube's CC bottom sheet. Hebrew
 * is NOT in YouTube's native auto-translate list, so we synthesise it.
 *
 * How it works:
 *
 *   1. alxc.t() returns YouTube's auto-translate options \u2014 they all share one
 *      English-ASR source (lang=en) and differ only by &tlang=XX. We pick any
 *      entry as a template (pickBaseTrack).
 *
 *   2. We select that REAL track unmodified via YouTube's own two-call sequence
 *      (copied from oju.onItemClick): oju.al.a(track) selects it and oju.an.L(track)
 *      renders it \u2014 both are required, a() alone selects but never displays.
 *
 *   3. A Cronet URL interceptor (injected at EVERY newUrlRequestBuilder call site)
 *      forces &tlang=iw on the timedtext fetch, turning the English source into
 *      Hebrew. It is GLOBAL while active \u2014 every video is translated until the
 *      user disarms \u2014 and sticky so Hebrew survives fullscreen/seek/navigation.
 *
 *   4. Tapping any native caption row disarms Hebrew (the footer is non-selectable
 *      so only native rows fire onItemClick). We move the checkmark onto our row
 *      and relabel the borrowed language's row to "Hebrew".
 *
 * Single-letter names (t, a, g, L) are obfuscated and change between versions, so
 * every lookup is by SIGNATURE/shape, not by name. The class name SubtitleTrack
 * and the resource ids list_item_text / list_item_icon_primary are not obfuscated.
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
    /** Just "Hebrew" \u2014 used to relabel the borrowed track's row in the menu. */
    private static final String HEBREW_SHORT = "\u05E2\u05D1\u05E8\u05D9\u05EA";

    private static WeakReference<Object>   ojuRef        = new WeakReference<>(null);
    private static WeakReference<View>     hebrewItemRef = new WeakReference<>(null);
    private static WeakReference<ListView> listViewRef   = new WeakReference<>(null);

    /** True once the user has activated Hebrew, so we can restore the checkmark. */
    private static volatile boolean hebrewSelected = false;
    /** Video id Hebrew was activated for; the interceptor only translates this
     *  video, so other videos keep their normal captions. */
    private static volatile String hebrewVideoId = null;
    /** Display name of the borrowed track (e.g. "Ukrainian") so we can relabel
     *  its menu row to "Hebrew". */
    private static volatile String borrowedName = null;

    // \u2500\u2500 URL interceptor \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    public static String interceptTimedtextUrl(String url) {
        if (url == null || !url.contains("timedtext")) return url;

        // GLOBAL "set and forget": while Hebrew is active, force &tlang=iw on
        // EVERY timedtext fetch \u2014 so the current video and every following video
        // are translated, surviving fullscreen/seek and navigation. Tapping any
        // native caption option (Off / a language) disarms it (see the
        // onItemClick wrapper in injectHebrewOption).
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

    /** Extracts the v= video id from any String field of the track that holds a URL. */
    private static String extractVideoId(Object track) {
        for (Field f : track.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(track);
                if (v instanceof String && ((String) v).contains("timedtext")) {
                    String s = (String) v;
                    int i = s.indexOf("v=");
                    if (i >= 0) {
                        int j = s.indexOf('&', i + 2);
                        return j < 0 ? s.substring(i + 2) : s.substring(i + 2, j);
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** The track's display name via its only no-arg CharSequence getter (f()). */
    private static String getTrackName(Object track) {
        try {
            for (Method m : track.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (m.getReturnType() != CharSequence.class) continue;
                m.setAccessible(true);
                Object v = m.invoke(track);
                if (v != null) return v.toString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** True if the menu currently shown belongs to the video Hebrew was activated for. */
    private static boolean matchesHebrewVideo(Object oju) {
        if (hebrewVideoId == null || hebrewVideoId.isEmpty()) return hebrewSelected;
        try {
            Object alis = getDeclaredFieldValue(oju, "al");
            Object alxc = alis != null ? findAlxc(alis) : null;
            if (alxc != null) {
                @SuppressWarnings("unchecked")
                List<Object> tracks = (List<Object>) alxc.getClass().getMethod("t").invoke(alxc);
                if (tracks != null && !tracks.isEmpty()) {
                    return hebrewVideoId.equals(extractVideoId(tracks.get(0)));
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    // \u2500\u2500 CC Panel injection \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    public static void injectHebrewOption(Object ojuInstance, ListView listView) {
        try {
            if (listView == null) return;
            if (listView.getFooterViewsCount() > 0) return;

            ojuRef      = new WeakReference<>(ojuInstance);
            listViewRef = new WeakReference<>(listView);

            final Context ctx = listView.getContext();
            View item = createHebrewListItem(ctx, listView);
            hebrewItemRef = new WeakReference<>(item);
            listView.addFooterView(item, null, false);
            android.util.Log.d(TAG, "Hebrew option injected");

            final ListView lv = listView;

            // Disarm Hebrew when the user taps any NATIVE caption option (English,
            // Off, another language). Our footer is non-selectable so it never
            // triggers onItemClick \u2014 only native rows do \u2014 so any onItemClick means
            // "the user chose something other than Hebrew". Without this the sticky
            // flag stayed on forever: the checkmark got stuck on Hebrew and the
            // interceptor kept turning English into Hebrew. Wrapped in post() so
            // YouTube's own listener is already set by the time we wrap it.
            lv.post(() -> {
                try {
                    final AdapterView.OnItemClickListener orig = lv.getOnItemClickListener();
                    lv.setOnItemClickListener((parent, view, pos, id) -> {
                        hebrewSelected = false;
                        hebrewVideoId = null;
                        android.util.Log.d(TAG, "native option tapped \u2192 Hebrew disarmed");
                        if (orig != null) orig.onItemClick(parent, view, pos, id);
                    });
                } catch (Exception e) {
                    android.util.Log.w(TAG, "wrap onItemClick failed: " + e);
                }
            });

            // After the native rows are laid out, move the checkmark to our item:
            // copy the real check drawable onto ours and hide the native one. Two
            // passes (immediate + delayed) because the adapter may bind its rows
            // slightly after onCreateView. Only when THIS video is the one Hebrew
            // was activated for \u2014 otherwise the checkmark would wrongly persist on
            // other videos.
            if (hebrewSelected && matchesHebrewVideo(ojuInstance)) {
                lv.post(() -> syncCheckmark(ctx, lv));
                lv.postDelayed(() -> syncCheckmark(ctx, lv), 250);
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "injectHebrewOption failed: " + e);
        }
    }

    /**
     * Makes the checkmark appear on OUR Hebrew row instead of the native
     * "Auto-translate \u00B7 <lang>" row: copies the native check drawable onto our
     * (otherwise empty) icon and hides the native check. Native rows expose the
     * check as the resource id "list_item_icon_primary".
     */
    private static void syncCheckmark(Context ctx, ListView listView) {
        try {
            if (!hebrewSelected) return;
            View hebrewItem = hebrewItemRef.get();
            if (hebrewItem == null) return;

            int iconId = ctx.getResources().getIdentifier(
                    "list_item_icon_primary", "id", ctx.getPackageName());
            ImageView ourCheck = iconId != 0 ? hebrewItem.findViewById(iconId)
                                             : findFirstImageView(hebrewItem);

            android.graphics.drawable.Drawable nativeDrawable = null;
            for (int i = 0; i < listView.getChildCount(); i++) {
                View row = listView.getChildAt(i);
                ImageView ic = iconId != 0 ? row.findViewById(iconId) : null;
                if (ic != null && ic != ourCheck
                        && ic.getVisibility() == View.VISIBLE && ic.getDrawable() != null) {
                    nativeDrawable = ic.getDrawable();
                    ic.setVisibility(View.INVISIBLE); // hide native check
                }
                // Relabel the borrowed track's name (e.g. "Ukrainian") to "Hebrew"
                // so the menu reads "Auto-translate \u00B7 Hebrew" instead.
                if (borrowedName != null && row != hebrewItem) {
                    relabelText(row, borrowedName, HEBREW_SHORT);
                }
            }
            if (ourCheck != null) {
                if (nativeDrawable != null) ourCheck.setImageDrawable(nativeDrawable);
                ourCheck.setVisibility(View.VISIBLE);
                android.util.Log.d(TAG, "checkmark moved to Hebrew (drawable="
                        + (nativeDrawable != null) + ")");
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "syncCheckmark failed: " + e);
        }
    }

    /** Recursively replaces any TextView whose text equals {@code from} with {@code to}. */
    private static void relabelText(View v, String from, String to) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && from.contentEquals(t)) ((TextView) v).setText(to);
        } else if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) relabelText(vg.getChildAt(i), from, to);
        }
    }

    private static View createHebrewListItem(Context ctx, ViewGroup parent) {
        try {
            int layoutId = ctx.getResources().getIdentifier(
                    "bottom_sheet_list_checkmark_item", "layout", ctx.getPackageName());
            if (layoutId != 0) {
                android.view.LayoutInflater inflater = android.view.LayoutInflater.from(ctx);
                // Inflate WITH the ListView as parent (attachToRoot=false) so the
                // row gets the same LayoutParams as native rows \u2014 otherwise a
                // null parent drops them and the row sits/aligns differently.
                View itemView = inflater.inflate(layoutId, parent, false);
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

    // \u2500\u2500 Click handler \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

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

    // \u2500\u2500 Core selection logic \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

    /**
     * Produces Hebrew subtitles the NATIVE way:
     *
     *   1. Take a real caption track from alxc.t().
     *   2. Call the track's own translate-builder method, SubtitleTrack.t(lang),
     *      with "iw" \u2014 this is exactly what YouTube's native "Auto-translate \u2192
     *      <language>" menu does. It returns a proper translated SubtitleTrack
     *      (a copy of the base with the translation-language field set and the
     *      "is translated" flag = true).
     *   3. Hand that track to YouTube's own selection method, alis.a().
     *
     * Because the track is built by YouTube's own builder, the whole native
     * pipeline (timedtext fetch with &tlang=iw, rendering, persistence across
     * fullscreen/seek) just works \u2014 no URL interception, no cloning.
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

            Object baseTrack = pickBaseTrack(tracks);
            if (baseTrack == null) { android.util.Log.w(TAG, "no translatable base track"); return false; }
            android.util.Log.d(TAG, "base track lang=" + getLanguageCode(baseTrack));

            // Select the CLEAN existing track, UNMODIFIED. YouTube fetches its
            // real (English-source) timedtext URL and renders it; the sticky URL
            // interceptor then swaps &tlang to iw, turning the fetched content
            // into Hebrew. Verified on-device: a real track + the armed
            // interceptor renders Hebrew, whereas a hand-built custom track had a
            // broken identity YouTube refused to fetch ("error retrieving
            // subtitle"). So we do NOT build/inject a custom track anymore.
            Object hebrewTrack = baseTrack;

            // Scope the interceptor to THIS video, then arm it (before the fetch).
            hebrewVideoId = extractVideoId(baseTrack);
            borrowedName  = getTrackName(baseTrack); // e.g. "Ukrainian" \u2192 relabel to Hebrew
            android.util.Log.d(TAG, "hebrew video id=" + hebrewVideoId + " borrowed=" + borrowedName);
            hebrewSelected = true;

            // Replicate YouTube's OWN native selection sequence (from
            // oju.onItemClick), which makes TWO calls on TWO different objects:
            //
            //   this.al.a(track);   // selection controller (aliq)  \u2014 picks the track
            //   this.an.L(track);   // renderer (brg)               \u2014 actually displays it
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
     * Picks a real base track: every entry in YouTube's auto-translate list shares
     * the SAME source (e.g. English ASR, lang=en) and differs only by its &tlang=
     * target. Hebrew is usually NOT offered, so we take any entry as the template
     * and let the interceptor swap its tlang to iw \u2014 giving en\u2192Hebrew.
     */
    private static Object pickBaseTrack(List<Object> tracks) {
        Object fallback = null;
        for (Object t : tracks) {
            String lang = getLanguageCode(t);
            // Skip the "Off"/"Auto-translate" menu options (non-lang-code values).
            if (lang == null || !isLangCode(lang)) continue;
            if (fallback == null) fallback = t;
            // Prefer a non-Hebrew entry as the template.
            if (!lang.startsWith("iw") && !lang.startsWith("he")) return t;
        }
        return fallback;
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

    // \u2500\u2500 Reflection helpers \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500

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

    /** Finds alxc in alis: the field whose class has a public t()\u2192List method. */
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
