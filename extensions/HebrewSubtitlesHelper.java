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
     * Scans the window for a visible ViewGroup whose children contain ≥ 2
     * CC-option text items (Off / English / Auto-translate / etc.), then injects
     * "עברית" into that container.  This is both the ViewTreeObserver path and
     * the post-hook delayed path.
     */
    private static void injectByCcTextScan(View rootView) {
        try {
            ViewGroup list = findCcListByText(rootView);
            if (list == null) {
                ccPanelInjected = false;
                return;
            }
            if (ccPanelInjected) return;

            // Don't add twice
            for (int i = 0; i < list.getChildCount(); i++) {
                if ("hebrew_cc_option".equals(list.getChildAt(i).getTag())) {
                    ccPanelInjected = true;
                    return;
                }
            }

            Context ctx = list.getContext();
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
     * BFS: finds the first visible ViewGroup whose direct or one-level-deep
     * children include ≥ 2 CC option text strings.
     */
    private static ViewGroup findCcListByText(View root) {
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View v = queue.poll();
            if (!(v instanceof ViewGroup) || !v.isShown()) continue;
            ViewGroup vg = (ViewGroup) v;
            if (vg.getChildCount() >= 2 && hasCcOptionTexts(vg)) return vg;
            for (int i = 0; i < vg.getChildCount(); i++) queue.add(vg.getChildAt(i));
        }
        return null;
    }

    /** Returns true if this ViewGroup has ≥ 2 direct/shallow CC-option text items. */
    private static boolean hasCcOptionTexts(ViewGroup vg) {
        int hits = 0;
        for (int i = 0; i < vg.getChildCount(); i++) {
            String t = shallowText(vg.getChildAt(i));
            if (t != null && isCcOptionText(t)) {
                hits++;
                if (hits >= 2) return true;
            }
        }
        return false;
    }

    /** Returns the text of a View or its first TextView child. */
    private static String shallowText(View v) {
        if (v instanceof TextView) return ((TextView) v).getText().toString().trim();
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (vg.getChildAt(i) instanceof TextView) {
                    String t = ((TextView) vg.getChildAt(i)).getText().toString().trim();
                    if (!t.isEmpty()) return t;
                }
            }
        }
        return null;
    }

    private static boolean isCcOptionText(String text) {
        String lower = text.toLowerCase();
        return lower.equals("off") || lower.equals("כבוי")
                || lower.contains("english") || lower.contains("auto-translat")
                || lower.contains("תרגום") || lower.contains("caption")
                || lower.contains("subtitle") || lower.contains("כתוביות");
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
