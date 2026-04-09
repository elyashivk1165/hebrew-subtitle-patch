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
    private static final String KEY_ON = "revanced_hebrew_subtitles_enabled";

    private static WeakReference<View> btnRef = new WeakReference<>(null);

    // Saved timedtext request components for in-video reload
    private static Object savedEngine;
    private static String savedBaseUrl;   // base URL WITHOUT &tlang=iw
    private static Object savedCallback;
    private static Object savedExecutor;

    // ── URL interceptor hooks ───────────────────────────────────────────────────

    public static boolean isEnabled(Context context) {
        try {
            return context.getSharedPreferences(PREFS, 0)
                    .getBoolean(KEY_ON, true);
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Called from the URL interceptor BEFORE &tlang=iw is injected.
     * Saves the CronetEngine, base URL, callback, and executor so we can
     * replay the request when the toggle button is tapped.
     */
    public static void saveTimedtextRequest(Object engine, String url,
                                             Object callback, Object executor) {
        try {
            if (url != null && url.contains("timedtext")) {
                savedEngine   = engine;
                // Strip any existing tlang= to get the canonical base URL
                savedBaseUrl  = url.replaceAll("[&?]tlang=[^&]*", "");
                savedCallback = callback;
                savedExecutor = executor;
                android.util.Log.d("HebrewSubs", "saveTimedtextRequest: saved " + savedBaseUrl);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Re-fires the last timedtext request with or without &tlang=iw
     * depending on the current preference, causing YouTube's own callback
     * to load the new subtitle track without any seek.
     */
    private static void reloadSubtitles(Context ctx) {
        try {
            if (savedEngine == null || savedBaseUrl == null
                    || savedCallback == null || savedExecutor == null) {
                android.util.Log.d("HebrewSubs", "reloadSubtitles: no saved state");
                return;
            }

            String newUrl = savedBaseUrl + (isEnabled(ctx) ? "&tlang=iw" : "");
            android.util.Log.d("HebrewSubs", "reloadSubtitles: firing " + newUrl);

            // Locate CronetEngine.newUrlRequestBuilder(String, Callback, Executor)
            Method newUrlRequestBuilder = null;
            for (Method m : savedEngine.getClass().getMethods()) {
                if ("newUrlRequestBuilder".equals(m.getName())
                        && m.getParameterCount() == 3) {
                    newUrlRequestBuilder = m;
                    break;
                }
            }
            if (newUrlRequestBuilder == null) {
                android.util.Log.e("HebrewSubs", "reloadSubtitles: newUrlRequestBuilder not found");
                return;
            }

            Object builder = newUrlRequestBuilder.invoke(savedEngine, newUrl, savedCallback, savedExecutor);
            Object request = builder.getClass().getMethod("build").invoke(builder);
            request.getClass().getMethod("start").invoke(request);

            android.util.Log.d("HebrewSubs", "reloadSubtitles: request started");
        } catch (Exception e) {
            android.util.Log.e("HebrewSubs", "reloadSubtitles failed: " + e);
        }
    }

    // ── Player-button hooks ──────────────────────────────────────────────────────

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
                // Reload subtitle track immediately using saved Cronet components
                reloadSubtitles(ctx);
                android.widget.Toast.makeText(
                        ctx,
                        nowOn
                                ? "\u05DB\u05EA\u05D5\u05D1\u05D9\u05D5\u05EA \u05E2\u05D1\u05E8\u05D9\u05EA: \u05E4\u05E2\u05D9\u05DC"
                                : "\u05DB\u05EA\u05D5\u05D1\u05D9\u05D5\u05EA \u05E2\u05D1\u05E8\u05D9\u05EA: \u05DB\u05D1\u05D5\u05D9",
                        android.widget.Toast.LENGTH_SHORT).show();
            });

            int sizePx = dp(ctx, 48);
            int marginB = dp(ctx, 80);
            int marginR = dp(ctx, 8);

            ViewGroup frameParent = findFrameLayout(controlsView);

            if (frameParent != null) {
                FrameLayout.LayoutParams lp =
                        new FrameLayout.LayoutParams(sizePx, sizePx);
                lp.gravity = Gravity.BOTTOM | Gravity.END;
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

    // ── Helpers ──────────────────────────────────────────────────────────────────

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
