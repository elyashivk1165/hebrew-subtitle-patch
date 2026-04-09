package app.revanced.extension.youtube.subtitle;

import android.content.Context;
import android.content.SharedPreferences;
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

public final class HebrewSubtitlesHelper {

    private static final String PREFS = "revanced_prefs";
    private static final String KEY_ON = "revanced_hebrew_subtitles_enabled";

    private static WeakReference<View> btnRef = new WeakReference<>(null);

    // ── URL interceptor hook ────────────────────────────────────────────────────

    /**
     * Injection point 0 – called by the URL interceptor before every
     * CronetEngine.newUrlRequestBuilder() call.
     * Returns true  → inject &tlang=iw
     * Returns false → leave URL untouched
     */
    public static boolean isEnabled(Context context) {
        try {
            return context.getSharedPreferences(PREFS, 0)
                    .getBoolean(KEY_ON, true);
        } catch (Exception e) {
            return true;
        }
    }

    // ── Player-button hooks (called from Smali injection points) ────────────────

    /**
     * Injection point 1 – called right after ViewStub.inflate() returns
     * the youtube_controls_bottom_ui_container ConstraintLayout.
     *
     * We walk up the view hierarchy to find a FrameLayout parent so we can
     * use Gravity.BOTTOM|END for clean positioning without needing
     * ConstraintLayout.LayoutParams (which lives in a separate library).
     */
    public static void initializeButton(View controlsView) {
        try {
            Context ctx = controlsView.getContext();

            // Remove stale button from a previous player session.
            View old = btnRef.get();
            if (old != null && old.getParent() instanceof ViewGroup) {
                ((ViewGroup) old.getParent()).removeView(old);
            }

            // --- Build the toggle button ------------------------------------------
            TextView btn = new TextView(ctx);
            btn.setText("\u05E2\u05D1"); // "עב" (Hebrew initials)
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            btn.setTypeface(null, Typeface.BOLD);
            btn.setGravity(Gravity.CENTER);
            // Start VISIBLE so the user can see and tap it even if visibility hooks
            // are not injected (fingerprint miss).
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
                android.widget.Toast.makeText(
                        ctx,
                        nowOn
                                ? "\u05DB\u05EA\u05D5\u05D1\u05D9\u05D5\u05EA \u05E2\u05D1\u05E8\u05D9\u05EA: \u05E4\u05E2\u05D9\u05DC"
                                : "\u05DB\u05EA\u05D5\u05D1\u05D9\u05D5\u05EA \u05E2\u05D1\u05E8\u05D9\u05EA: \u05DB\u05D1\u05D5\u05D9",
                        android.widget.Toast.LENGTH_SHORT).show();
            });

            // --- Attach to view hierarchy ----------------------------------------
            int sizePx = dp(ctx, 48);
            int marginB = dp(ctx, 80);
            int marginR = dp(ctx, 8);

            // Walk up looking for a FrameLayout (player overlay is usually one).
            ViewGroup frameParent = findFrameLayout(controlsView);

            if (frameParent != null) {
                FrameLayout.LayoutParams lp =
                        new FrameLayout.LayoutParams(sizePx, sizePx);
                lp.gravity = Gravity.BOTTOM | Gravity.END;
                lp.bottomMargin = marginB;
                lp.rightMargin = marginR;
                btn.setLayoutParams(lp);
                frameParent.addView(btn);
            } else {
                // Fallback: add directly to the bottom-controls container.
                ViewGroup container = (controlsView instanceof ViewGroup)
                        ? (ViewGroup) controlsView
                        : (ViewGroup) controlsView.getParent();
                if (container == null) return;
                container.addView(btn, new ViewGroup.LayoutParams(sizePx, sizePx));
            }

            btnRef = new WeakReference<>(btn);
        } catch (Exception ignored) { /* never crash YouTube */ }
    }

    /**
     * Injection point 2 – player controls animated show/hide.
     * Mirrors PlayerControlButton.setVisibility(visible, animated).
     */
    public static void setVisibility(boolean visible, boolean animated) {
        try {
            View btn = btnRef.get();
            if (btn != null) {
                btn.setVisibility(visible ? View.VISIBLE : View.GONE);
            }
        } catch (Exception ignored) { }
    }

    /**
     * Injection point 3 – immediate show/hide (no animation).
     */
    public static void setVisibilityImmediate(boolean visible) {
        setVisibility(visible, false);
    }

    /**
     * Injection point 4 – hide immediately on touch / hide-controls event.
     */
    public static void setVisibilityNegatedImmediate() {
        try {
            View btn = btnRef.get();
            if (btn != null) {
                btn.setVisibility(View.GONE);
            }
        } catch (Exception ignored) { }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Walk up at most 6 levels looking for a FrameLayout.
     */
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
