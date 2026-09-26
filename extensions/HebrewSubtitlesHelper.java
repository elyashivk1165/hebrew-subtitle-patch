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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hebrew captions for YouTube. Select an existing caption through the native
 * ListView callback, which performs both selection and rendering. Caption rows
 * are recognised by shape, so no obfuscated names are tied to one version.
 * Keep timedtext rewriting armed until the user selects another native row.
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
    private static boolean selectingHebrew = false;
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

    private static boolean matchesHebrewVideo(Object unused) {
        return hebrewSelected;
    }

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
                        if (!selectingHebrew) hebrewSelected = false;
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

    private static final String TRANSLATE_LANG = "iw";

    private static boolean selectHebrew() {
        ListView list = listViewRef.get();
        if (list == null || list.getAdapter() == null) return false;
        AdapterView.OnItemClickListener listener = list.getOnItemClickListener();
        if (listener == null) return false;
        try {
            android.widget.ListAdapter adapter = list.getAdapter();
            // Caption rows are found by shape, not by obfuscated class/field
            // names, which change with every YouTube version: a row holds a
            // track object with a language-code String and a display name.
            int count = adapter.getCount();
            Object[] tracks = new Object[count];
            Map<Class<?>, Integer> rowClasses = new HashMap<>();
            for (int position = 0; position < count; position++) {
                Object row = adapter.getItem(position);
                if (row == null) continue;
                tracks[position] = findTrack(row);
                if (tracks[position] != null) rowClasses.merge(row.getClass(), 1, Integer::sum);
            }
            // Real caption rows share one class; ignore stray matches of other row types.
            Class<?> rowClass = null;
            for (Map.Entry<Class<?>, Integer> e : rowClasses.entrySet()) {
                if (rowClass == null || e.getValue() > rowClasses.get(rowClass)) rowClass = e.getKey();
            }
            for (int position = 0; position < count; position++) {
                Object track = tracks[position];
                if (track == null || adapter.getItem(position).getClass() != rowClass) continue;
                View rowView = list.getChildAt(position - list.getFirstVisiblePosition());
                if (rowView == null) rowView = adapter.getView(position, null, list);
                borrowedName = displayName(track, rowView);
                hebrewVideoId = null;
                hebrewSelected = true;
                selectingHebrew = true;
                try {
                    listener.onItemClick(list, rowView, position, adapter.getItemId(position));
                } finally {
                    selectingHebrew = false;
                }
                android.util.Log.d(TAG, "Selected native caption row for Hebrew: "
                        + rowClass.getName() + " / " + track.getClass().getName()
                        + " (" + borrowedName + ")");
                return true;
            }
            android.util.Log.w(TAG, "No real caption row in current menu");
        } catch (Exception e) {
            android.util.Log.e(TAG, "Native Hebrew selection failed", e);
        }
        hebrewSelected = false;
        return false;
    }

    /** Returns the row field holding a caption track, or null if this is not a caption row. */
    private static Object findTrack(Object row) throws IllegalAccessException {
        for (Field field : instanceFields(row.getClass())) {
            if (field.getType().isPrimitive() || isFrameworkType(field.getType())) continue;
            Object value = field.get(row);
            if (value == null || isFrameworkType(value.getClass())) continue;
            if (hasLanguageCode(value) && !displayNames(value).isEmpty()) return value;
        }
        return null;
    }

    private static boolean hasLanguageCode(Object track) throws IllegalAccessException {
        for (Field field : instanceFields(track.getClass())) {
            if (field.getType() != String.class) continue;
            String value = (String) field.get(track);
            if (value != null && value.matches("[a-z]{2,3}([_-][A-Za-z0-9]{2,8})*")) return true;
        }
        return false;
    }

    private static List<String> displayNames(Object track) throws IllegalAccessException {
        List<String> names = new ArrayList<>();
        for (Field field : instanceFields(track.getClass())) {
            if (field.getType() != CharSequence.class) continue;
            CharSequence value = (CharSequence) field.get(track);
            if (value != null && value.length() > 0) names.add(value.toString());
        }
        return names;
    }

    /** Prefers the track name shown in the row, so the relabel targets the right text. */
    private static String displayName(Object track, View rowView) throws IllegalAccessException {
        List<String> names = displayNames(track);
        for (String name : names) {
            if (rowView != null && containsText(rowView, name)) return name;
        }
        return names.isEmpty() ? null : names.get(0);
    }

    private static boolean containsText(View v, String text) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            return t != null && text.contentEquals(t);
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (containsText(vg.getChildAt(i), text)) return true;
            }
        }
        return false;
    }

    private static List<Field> instanceFields(Class<?> cls) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = cls; c != null && !isFrameworkType(c); c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                fields.add(field);
            }
        }
        return fields;
    }

    private static boolean isFrameworkType(Class<?> cls) {
        String name = cls.getName();
        return cls.isArray() || name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("android.") || name.startsWith("kotlin.");
    }

    private static int dp(Context ctx, float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics()));
    }
}
