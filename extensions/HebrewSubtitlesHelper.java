package app.revanced.extension.youtube.subtitle;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

public final class HebrewSubtitlesHelper {
    private static final String PREFS_NAME = "revanced_prefs";
    private static final String KEY_ENABLED = "revanced_hebrew_subtitles_enabled";
    private static final String KEY_BUTTON_HIDDEN = "revanced_hebrew_subtitles_button_hidden";

    private static final int BUTTON_VIEW_ID = 0x7e_ab_1234; // arbitrary stable fake resource id

    public static boolean isEnabled(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getBoolean(KEY_ENABLED, true);
        } catch (Exception e) {
            return true;
        }
    }

    public static void toggle(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean current = prefs.getBoolean(KEY_ENABLED, true);
            prefs.edit().putBoolean(KEY_ENABLED, !current).apply();
        } catch (Exception e) {
            // ignore
        }
    }

    public static boolean isButtonHidden(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getBoolean(KEY_BUTTON_HIDDEN, false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Called from the player bottom controls inflate method.
     * Adds a small "עב" toggle button to the player control bar.
     */
    public static void initButton(Activity activity) {
        try {
            if (isButtonHidden(activity)) return;

            // Find the root view — the button will be placed inside the decor view
            // overlaid on the player controls area.
            View decorView = activity.getWindow().getDecorView();
            if (!(decorView instanceof ViewGroup)) return;
            ViewGroup root = (ViewGroup) decorView;

            // Avoid adding twice (e.g. if method is called more than once)
            if (root.findViewById(BUTTON_VIEW_ID) != null) return;

            TextView btn = new TextView(activity);
            btn.setId(BUTTON_VIEW_ID);
            btn.setText("עב");
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(14f);
            btn.setTypeface(Typeface.DEFAULT_BOLD);
            btn.setPadding(18, 10, 18, 10);
            btn.setBackground(createBackground(activity, isEnabled(activity)));

            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggle(activity);
                    boolean nowEnabled = isEnabled(activity);
                    v.setBackground(createBackground(activity, nowEnabled));
                    android.widget.Toast.makeText(
                        activity,
                        nowEnabled ? "כתוביות עברית: פעיל" : "כתוביות עברית: כבוי",
                        android.widget.Toast.LENGTH_SHORT
                    ).show();
                }
            });

            // Position: bottom-right, just above the system navigation area
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.gravity = Gravity.BOTTOM | Gravity.END;
            params.bottomMargin = dpToPx(activity, 72);
            params.rightMargin = dpToPx(activity, 12);

            root.addView(btn, params);
        } catch (Exception e) {
            // Silently ignore — URL injection still works without the button
        }
    }

    private static android.graphics.drawable.GradientDrawable createBackground(Context ctx, boolean enabled) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(ctx, 6));
        bg.setColor(enabled ? Color.argb(200, 0, 120, 215) : Color.argb(150, 60, 60, 60));
        return bg;
    }

    private static int dpToPx(Context ctx, int dp) {
        float density = ctx.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
