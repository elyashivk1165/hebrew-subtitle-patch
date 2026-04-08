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

import java.lang.ref.WeakReference;

public final class HebrewSubtitlesHelper {
    private static final String PREFS_NAME = "revanced_prefs";
    private static final String KEY_ENABLED = "revanced_hebrew_subtitles_enabled";
    private static final String KEY_BUTTON_HIDDEN = "revanced_hebrew_subtitles_button_hidden";
    private static final int BUTTON_VIEW_ID = 0x4865_6272; // "Hebr" as int

    private static WeakReference<Activity> activityRef = new WeakReference<>(null);

    public static boolean isEnabled(Context context) {
        try {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ENABLED, true);
        } catch (Exception e) {
            return true;
        }
    }

    public static void toggle(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_ENABLED, !prefs.getBoolean(KEY_ENABLED, true)).apply();
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * Called from YouTube's main Activity onCreate injection.
     * Stores the Activity reference and schedules button creation after layout.
     */
    public static void setActivity(Activity activity) {
        try {
            activityRef = new WeakReference<>(activity);
            // Post to decorView so we run after the layout is fully set up.
            activity.getWindow().getDecorView().post(new Runnable() {
                @Override
                public void run() {
                    initButton(activity);
                }
            });
        } catch (Exception e) {
            // ignore
        }
    }

    private static void initButton(Activity activity) {
        try {
            if (activity == null || activity.isFinishing()) return;

            boolean buttonHidden = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_BUTTON_HIDDEN, false);
            if (buttonHidden) return;

            View decorView = activity.getWindow().getDecorView();
            if (!(decorView instanceof ViewGroup)) return;
            ViewGroup root = (ViewGroup) decorView;

            // Don't add twice
            if (root.findViewById(BUTTON_VIEW_ID) != null) return;

            TextView btn = new TextView(activity);
            btn.setId(BUTTON_VIEW_ID);
            btn.setText("עב");
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(14f);
            btn.setTypeface(Typeface.DEFAULT_BOLD);
            btn.setPadding(px(activity, 14), px(activity, 8), px(activity, 14), px(activity, 8));
            btn.setBackground(buildBg(activity, isEnabled(activity)));

            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggle(activity);
                    boolean on = isEnabled(activity);
                    v.setBackground(buildBg(activity, on));
                    android.widget.Toast.makeText(activity,
                            on ? "כתוביות עברית: פעיל" : "כתוביות עברית: כבוי",
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            });

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.bottomMargin = px(activity, 80);
            lp.rightMargin = px(activity, 16);

            root.addView(btn, lp);
        } catch (Exception e) {
            // ignore
        }
    }

    private static android.graphics.drawable.GradientDrawable buildBg(Context ctx, boolean enabled) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(px(ctx, 6));
        bg.setColor(enabled ? Color.argb(210, 0, 100, 200) : Color.argb(160, 50, 50, 50));
        return bg;
    }

    private static int px(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }
}
