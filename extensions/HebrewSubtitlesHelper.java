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

    /**
     * Called by the patch when the captions bottom-sheet builder method runs.
     * {@code sheetObject} is {@code this} of that method — the controller that
     * builds/inflates the CC panel.  We post a Runnable on its first found View
     * so injection happens after the panel is fully laid out.
     */
    public static void onCaptionsSheetBuilt(Object sheetObject) {
        try {
            // Walk the object's fields to find the first View (the panel root)
            View panelRoot = findViewInObject(sheetObject, 0);
            if (panelRoot == null) {
                android.util.Log.w("HebrewSubs", "onCaptionsSheetBuilt: no View found in sheet object");
                // Fall back to ViewTreeObserver on the window
                return;
            }
            android.util.Log.d("HebrewSubs", "onCaptionsSheetBuilt: panel root = "
                    + panelRoot.getClass().getSimpleName());
            panelRoot.post(() -> injectIntoPanelRoot(panelRoot));
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

    private static void injectIntoPanelRoot(View root) {
        try {
            if (ccPanelInjected) return;

            Context ctx = root.getContext();

            // Find the list container: deepest ViewGroup with ≥ 2 children
            ViewGroup listContainer = findListContainer(root);
            if (listContainer == null) {
                android.util.Log.w("HebrewSubs", "injectIntoPanelRoot: list container not found");
                logViewHierarchy(root, 0, 6);
                return;
            }

            // Don't add twice
            for (int i = 0; i < listContainer.getChildCount(); i++) {
                if ("hebrew_cc_option".equals(listContainer.getChildAt(i).getTag())) return;
            }

            View item = createHebrewItem(ctx, listContainer.getMeasuredWidth());
            // Insert before last child (typically "Auto-translate" or footer)
            int insertAt = Math.max(0, listContainer.getChildCount() - 1);
            listContainer.addView(item, insertAt);

            ccPanelInjected = true;
            android.util.Log.d("HebrewSubs", "Hebrew option injected at index " + insertAt
                    + " in " + listContainer.getClass().getSimpleName());
        } catch (Exception e) {
            android.util.Log.e("HebrewSubs", "injectIntoPanelRoot failed: " + e);
        }
    }

    private static ViewGroup findListContainer(View root) {
        // BFS: find the ViewGroup with the most direct-children among candidates
        ViewGroup best = null;
        int bestCount = 1;
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View v = queue.poll();
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                if (vg.getChildCount() > bestCount) {
                    bestCount = vg.getChildCount();
                    best = vg;
                }
                for (int i = 0; i < vg.getChildCount(); i++) queue.add(vg.getChildAt(i));
            }
        }
        return best;
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
                try { checkForCcPanelViaResource(rootView); }
                catch (Exception ignored) {}
            });
        } catch (Exception e) {
            android.util.Log.e("HebrewSubs", "startCcPanelProbe failed: " + e);
        }
    }

    private static void checkForCcPanelViaResource(View rootView) {
        Context ctx = rootView.getContext();

        // Use bottom_sheet_footer_text (Material Design) as anchor for any bottom sheet
        int footerResId = ctx.getResources().getIdentifier(
                "bottom_sheet_footer_text", "id", ctx.getPackageName());
        if (footerResId == 0) return;

        View footerView = rootView.findViewById(footerResId);
        boolean panelOpen = footerView != null && footerView.isShown();

        if (!panelOpen) { ccPanelInjected = false; return; }
        if (ccPanelInjected) return;

        // Confirm it's the CC panel: footer text should match subtitle_menu_settings_footer_info
        int strResId = ctx.getResources().getIdentifier(
                "subtitle_menu_settings_footer_info", "string", ctx.getPackageName());
        if (strResId != 0 && footerView instanceof TextView) {
            String actual   = ((TextView) footerView).getText().toString();
            String expected = ctx.getString(strResId);
            if (!expected.isEmpty() && !actual.isEmpty()
                    && !actual.contains(expected.substring(0, Math.min(8, expected.length())))) {
                return; // Different bottom sheet
            }
        }

        android.util.Log.d("HebrewSubs", "CC panel detected via ViewTreeObserver, injecting...");
        injectIntoPanelRoot(footerView.getRootView());
    }

    // ── Logging helpers ───────────────────────────────────────────────────────

    private static void logViewHierarchy(View view, int depth, int maxDepth) {
        if (depth > maxDepth || view == null) return;
        String indent = "  ".repeat(depth);
        String info = indent + view.getClass().getSimpleName()
                + "[id=" + Integer.toHexString(view.getId()) + "]";
        if (view instanceof TextView) info += " text=\"" + ((TextView) view).getText() + "\"";
        if (view instanceof ViewGroup) info += " children=" + ((ViewGroup) view).getChildCount();
        android.util.Log.d("HebrewSubs", info);
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                logViewHierarchy(vg.getChildAt(i), depth + 1, maxDepth);
            }
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
