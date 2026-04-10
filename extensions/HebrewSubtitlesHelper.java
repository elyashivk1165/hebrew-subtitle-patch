package app.revanced.extension.youtube.subtitle;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

public final class HebrewSubtitlesHelper {

    private static final String PREFS = "revanced_prefs";
    private static final String KEY_ON  = "revanced_hebrew_subtitles_enabled";

    private static WeakReference<View> btnRef   = new WeakReference<>(null);
    private static WeakReference<View> ccBtnRef = new WeakReference<>(null);

    // Saved timedtext request components
    private static Object savedEngine;
    private static String savedBaseUrl;
    private static Object savedCallback;
    private static Object savedExecutor;

    // CC panel injection state
    private static boolean ccPanelInjected = false;
    private static boolean probeRegistered = false;

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

    // ── Subtitle reload ───────────────────────────────────────────────────────

    /** Called from "עב" button and from Hebrew CC panel option. */
    private static void reloadSubtitles(Context ctx) {
        // Primary: find CC button and toggle off→on to trigger fresh timedtext load
        View ccBtn = ccBtnRef.get();
        if (ccBtn == null) {
            View myBtn = btnRef.get();
            if (myBtn != null) {
                ccBtn = findCcButtonById(myBtn.getRootView(), ctx);
                if (ccBtn != null) ccBtnRef = new WeakReference<>(ccBtn);
            }
        }
        if (ccBtn != null) {
            final View btn = ccBtn;
            btn.post(() -> {
                btn.performClick();
                btn.postDelayed(() -> btn.performClick(), 250);
            });
        } else {
            reloadSubtitlesCronet(ctx);
        }
    }

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

    private static View findCcButtonById(View root, Context ctx) {
        try {
            int resId = ctx.getResources().getIdentifier(
                    "youtube_controls_overlay_subtitle_button", "id", ctx.getPackageName());
            if (resId != 0) {
                View v = root.findViewById(resId);
                if (v != null) {
                    android.util.Log.d("HebrewSubs", "CC button found by resource ID");
                    return v;
                }
            }
        } catch (Exception ignored) {}
        return searchCcButton(root, 0);
    }

    private static View searchCcButton(View view, int depth) {
        if (depth > 15 || view == null) return null;
        CharSequence desc = view.getContentDescription();
        if (desc != null) {
            String d = desc.toString().toLowerCase();
            if (d.contains("caption") || d.contains("subtitle") || d.contains("כתוביות"))
                return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = searchCcButton(vg.getChildAt(i), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ── CC Panel injection (called from bytecode hook) ────────────────────────

    // ── Direct ListView injection (primary path, RE-based) ───────────────────

    /**
     * Called by the bytecode hook immediately after oju.N() calls addFooterView.
     * Receives the ListView directly — no view-tree scanning needed.
     *
     * Uses addHeaderView(view, null, false) so the Hebrew item is NOT selectable
     * through the adapter's onItemClick (which casts adapter items to oix and
     * would throw ClassCastException for any non-oix item).  The header gets its
     * own OnClickListener instead.
     */
    public static void injectHebrewOption(ListView listView) {
        try {
            if (listView == null) return;
            // Don't inject twice (header count > 0 means we already added ours)
            if (listView.getHeaderViewsCount() > 0) return;

            Context ctx = listView.getContext();
            View item = createHebrewListItem(ctx);
            listView.addHeaderView(item, null, false);
            android.util.Log.d("HebrewSubs", "Hebrew option injected via addHeaderView");
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
                if (tv != null) tv.setText("\u05E2\u05D1\u05E8\u05D9\u05EA (\u05EA\u05E8\u05D2\u05D5\u05DD \u05D0\u05D5\u05D8\u05D5\u05DE\u05D8\u05D9)");
                itemView.setOnClickListener(v -> onHebrewItemClicked(v.getContext()));
                return itemView;
            }
        } catch (Exception ignored) {}
        // Fallback: programmatic item matching existing createHebrewItem style
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
        View myBtn = btnRef.get();
        if (myBtn != null) myBtn.setAlpha(1.0f);
        reloadSubtitlesCronet(ctx);
        android.widget.Toast.makeText(ctx,
                "\u05DB\u05EA\u05D5\u05D1\u05D9\u05D5\u05EA \u05E2\u05D1\u05E8\u05D9\u05EA: \u05E4\u05E2\u05D9\u05DC",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * Called by the patch when the captions bottom-sheet builder method runs.
     * Posts a delayed scan so Elements JS has time to populate the list items.
     */
    public static void onCaptionsSheetBuilt(Object sheetObject) {
        try {
            View panelRoot = findViewInObject(sheetObject, 0);
            if (panelRoot == null) {
                android.util.Log.w("HebrewSubs", "onCaptionsSheetBuilt: no View in sheet object");
                return;
            }
            android.util.Log.d("HebrewSubs", "onCaptionsSheetBuilt: got "
                    + panelRoot.getClass().getSimpleName());
            // Delay 500ms so Elements JS can populate the list before we scan
            panelRoot.postDelayed(() -> injectByCcTextScan(panelRoot.getRootView()), 500);
        } catch (Exception e) {
            android.util.Log.e("HebrewSubs", "onCaptionsSheetBuilt failed: " + e);
        }
    }

    /** Recursively searches object fields for the first View instance (depth ≤ 3). */
    private static View findViewInObject(Object obj, int depth) {
        if (obj == null || depth > 3) return null;
        if (obj instanceof View) return (View) obj;
        try {
            for (java.lang.reflect.Field f : obj.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(obj);
                if (val instanceof View) return (View) val;
                if (val != null && depth < 2) {
                    View found = findViewInObject(val, depth + 1);
                    if (found != null) return found;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Primary injection path — called from both the ViewTreeObserver and the
     * bytecode-hook post-delay.
     *
     * Detection strategy (language-independent):
     *   1. Look for a visible TextView whose text contains the beginning of
     *      YouTube's own "subtitle_menu_settings_footer_info" string resource.
     *      This string appears only in the CC (subtitles) bottom-sheet footer.
     *   2. Walk UP from that TextView to find its nearest ancestor ViewGroup
     *      that has ≥ 2 children AND has a sibling (or is the list itself).
     *   3. Among the siblings/children of that ancestor, inject "עברית" into
     *      the ViewGroup with the most children (= the track list).
     *
     * If the resource string is missing (shouldn't happen for YouTube), falls
     * back to a structural approach: largest visible ViewGroup inside the
     * deepest bottom-sheet container.
     */
    private static void injectByCcTextScan(View rootView) {
        try {
            Context ctx = rootView.getContext();

            // ── Step 1: confirm CC panel is open ────────────────────────────────
            View footerAnchor = findCcFooterAnchor(rootView, ctx);
            if (footerAnchor == null) {
                ccPanelInjected = false;
                return;
            }
            if (ccPanelInjected) return;

            // ── Step 2: find the track-list container ────────────────────────────
            // Walk up from the footer anchor until we find a parent that has
            // a sibling ViewGroup with ≥ 2 children — that sibling IS the list.
            ViewGroup list = findListSiblingOf(footerAnchor);
            if (list == null) {
                android.util.Log.w("HebrewSubs", "footer found but list sibling not found");
                return;
            }

            // Don't add twice
            for (int i = 0; i < list.getChildCount(); i++) {
                if ("hebrew_cc_option".equals(list.getChildAt(i).getTag())) {
                    ccPanelInjected = true;
                    return;
                }
            }

            View item = createHebrewItem(ctx, list.getMeasuredWidth());
            int insertAt = Math.max(0, list.getChildCount() - 1);
            list.addView(item, insertAt);
            ccPanelInjected = true;
            android.util.Log.d("HebrewSubs",
                    "Hebrew CC option injected at index " + insertAt
                    + " into " + list.getClass().getSimpleName()
                    + " [" + list.getChildCount() + " children]");
        } catch (Exception e) {
            android.util.Log.e("HebrewSubs", "injectByCcTextScan failed: " + e);
        }
    }

    /**
     * Finds the CC panel footer by searching for a visible TextView whose text
     * starts with the first 12 characters of YouTube's own
     * {@code subtitle_menu_settings_footer_info} string resource.
     * Returns null if the CC panel is not currently open.
     */
    private static View findCcFooterAnchor(View root, Context ctx) {
        // Resolve YouTube's own footer string (language-independent key).
        int resId = ctx.getResources().getIdentifier(
                "subtitle_menu_settings_footer_info", "string", ctx.getPackageName());
        String prefix = null;
        if (resId != 0) {
            String full = ctx.getString(resId);
            if (full.length() > 8) prefix = full.substring(0, Math.min(12, full.length()));
        }
        if (prefix == null) {
            android.util.Log.w("HebrewSubs",
                    "subtitle_menu_settings_footer_info not found in resources");
            return null;
        }
        final String needle = prefix;
        return findVisibleTextView(root, tv ->
                tv.getText().toString().startsWith(needle));
    }

    /**
     * Walks UP from {@code anchor} looking for a parent ViewGroup that has
     * ≥ 2 children, then returns the child among them with the MOST children
     * (that is not the direct ancestor of anchor) — this is the track list.
     */
    private static ViewGroup findListSiblingOf(View anchor) {
        android.view.ViewParent p = anchor.getParent();
        View prev = anchor;
        for (int depth = 0; depth < 12 && p instanceof ViewGroup; depth++) {
            ViewGroup parent = (ViewGroup) p;
            if (parent.getChildCount() >= 2) {
                // Find the sibling (not our ancestry chain) with the most children
                ViewGroup best = null;
                int bestCount = 0;
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View child = parent.getChildAt(i);
                    if (child == prev) continue;
                    if (child instanceof ViewGroup) {
                        int cnt = ((ViewGroup) child).getChildCount();
                        if (cnt > bestCount) { bestCount = cnt; best = (ViewGroup) child; }
                    }
                }
                if (best != null && bestCount >= 1) return best;
            }
            prev = parent;
            p = parent.getParent();
        }
        return null;
    }

    /** DFS: finds the first visible TextView matching {@code predicate}. */
    private static View findVisibleTextView(View v,
            java.util.function.Predicate<TextView> predicate) {
        if (!v.isShown()) return null;
        if (v instanceof TextView && predicate.test((TextView) v)) return v;
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findVisibleTextView(vg.getChildAt(i), predicate);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View createHebrewItem(Context ctx, int parentWidth) {
        TextView tv = new TextView(ctx);
        tv.setText("עברית");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTypeface(null, Typeface.NORMAL);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(ctx, 20), dp(ctx, 14), dp(ctx, 20), dp(ctx, 14));
        tv.setTag("hebrew_cc_option");
        int w = parentWidth > 0 ? parentWidth : ViewGroup.LayoutParams.MATCH_PARENT;
        tv.setLayoutParams(new ViewGroup.LayoutParams(w, dp(ctx, 52)));
        tv.setOnClickListener(v -> {
            Context c = v.getContext();
            c.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_ON, true).apply();
            View myBtn = btnRef.get();
            if (myBtn != null) myBtn.setAlpha(1.0f);
            reloadSubtitlesCronet(c);
            android.widget.Toast.makeText(c,
                    "\u05DB\u05EA\u05D5\u05D1\u05D9\u05D5\u05EA \u05E2\u05D1\u05E8\u05D9\u05EA: \u05E4\u05E2\u05D9\u05DC",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        return tv;
    }

    // ── ViewTreeObserver fallback (if bytecode hook misses) ───────────────────

    private static void startCcPanelProbe(View rootView) {
        if (probeRegistered) return;
        probeRegistered = true;
        try {
            android.view.ViewTreeObserver vto = rootView.getViewTreeObserver();
            if (!vto.isAlive()) return;
            vto.addOnGlobalLayoutListener(() -> {
                try { injectByCcTextScan(rootView); }
                catch (Exception ignored) {}
            });
            android.util.Log.d("HebrewSubs", "CC panel probe registered");
        } catch (Exception e) {
            android.util.Log.e("HebrewSubs", "startCcPanelProbe failed: " + e);
        }
    }

    // ── Player-button hooks ───────────────────────────────────────────────────

    public static void initializeButton(View controlsView) {
        try {
            Context ctx = controlsView.getContext();

            View old = btnRef.get();
            if (old != null && old.getParent() instanceof ViewGroup) {
                ((ViewGroup) old.getParent()).removeView(old);
            }

            TextView btn = new TextView(ctx);
            btn.setText("\u05E2\u05D1");
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            btn.setTypeface(null, Typeface.BOLD);
            btn.setGravity(Gravity.CENTER);
            btn.setVisibility(View.VISIBLE);
            btn.setAlpha(isEnabled(ctx) ? 1.0f : 0.35f);

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(dp(ctx, 6));
            bg.setColor(Color.argb(180, 0, 0, 0));
            btn.setBackground(bg);

            btn.setOnClickListener(v -> {
                boolean nowOn = !isEnabled(ctx);
                ctx.getSharedPreferences(PREFS, 0)
                        .edit().putBoolean(KEY_ON, nowOn).apply();
                v.setAlpha(nowOn ? 1.0f : 0.35f);
                reloadSubtitles(ctx);
                android.widget.Toast.makeText(
                        ctx,
                        nowOn
                            ? "\u05DB\u05EA\u05D5\u05D1\u05D9\u05D5\u05EA \u05E2\u05D1\u05E8\u05D9\u05EA: \u05E4\u05E2\u05D9\u05DC"
                            : "\u05DB\u05EA\u05D5\u05D1\u05D9\u05D5\u05EA \u05E2\u05D1\u05E8\u05D9\u05EA: \u05DB\u05D1\u05D5\u05D9",
                        android.widget.Toast.LENGTH_SHORT).show();
            });

            int sizePx  = dp(ctx, 48);
            int marginB = dp(ctx, 80);
            int marginR = dp(ctx, 8);

            ViewGroup frameParent = findFrameLayout(controlsView);
            if (frameParent != null) {
                FrameLayout.LayoutParams lp =
                        new FrameLayout.LayoutParams(sizePx, sizePx);
                lp.gravity      = Gravity.BOTTOM | Gravity.END;
                lp.bottomMargin = marginB;
                lp.rightMargin  = marginR;
                btn.setLayoutParams(lp);
                frameParent.addView(btn);
            } else {
                ViewGroup container = (controlsView instanceof ViewGroup)
                        ? (ViewGroup) controlsView
                        : (ViewGroup) controlsView.getParent();
                if (container == null) return;
                container.addView(btn, new ViewGroup.LayoutParams(sizePx, sizePx));
            }

            btnRef = new WeakReference<>(btn);
            startCcPanelProbe(controlsView.getRootView());
        } catch (Exception ignored) {}
    }

    public static void setVisibility(boolean visible, boolean animated) {
        try {
            View btn = btnRef.get();
            if (btn != null) btn.setVisibility(visible ? View.VISIBLE : View.GONE);
        } catch (Exception ignored) {}
    }

    public static void setVisibilityImmediate(boolean visible) {
        setVisibility(visible, false);
    }

    public static void setVisibilityNegatedImmediate() {
        try {
            View btn = btnRef.get();
            if (btn != null) btn.setVisibility(View.GONE);
        } catch (Exception ignored) {}
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ViewGroup findFrameLayout(View from) {
        ViewGroup candidate = (from.getParent() instanceof ViewGroup)
                ? (ViewGroup) from.getParent() : null;
        for (int i = 0; i < 6 && candidate != null; i++) {
            if (candidate instanceof FrameLayout) return candidate;
            candidate = (candidate.getParent() instanceof ViewGroup)
                    ? (ViewGroup) candidate.getParent() : null;
        }
        return null;
    }

    private static int dp(Context ctx, float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics()));
    }
}
