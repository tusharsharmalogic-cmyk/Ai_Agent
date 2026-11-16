package io.github.mthli.Ninja.Unit;

import android.app.Activity;
import android.content.Context;
import android.preference.PreferenceManager;
import io.github.mthli.Ninja.R;

public class ThemeUnit {
    public static boolean isDark(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(context.getString(R.string.sp_dark_theme), false);
    }

    // Call BEFORE super.onCreate()
    public static void apply(Activity activity, boolean browser) {
        if (!isDark(activity)) {
            return;
        }
        if (browser) {
            activity.setTheme(R.style.BrowserActivityThemeDark);
        } else {
            activity.setTheme(android.R.style.Theme_Holo);
        }
    }
}
