package io.github.mthli.Ninja.Activity;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import io.github.mthli.Ninja.R;
import io.github.mthli.Ninja.View.NinjaToast;

public class SystemPromptActivity extends Activity {
    private EditText editText;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getActionBar().setDisplayHomeAsUpEnabled(true);
        setTitle(R.string.setting_title_system_prompt);

        final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        editText = new EditText(this);
        editText.setHint(R.string.system_prompt_hint);
        editText.setGravity(Gravity.TOP | Gravity.START);
        editText.setSingleLine(false);
        editText.setText(sp.getString(getString(R.string.sp_system_prompt), ""));
        root.addView(editText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Button save = new Button(this);
        save.setText(R.string.system_prompt_save);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sp.edit().putString(getString(R.string.sp_system_prompt), editText.getText().toString()).commit();
                NinjaToast.show(SystemPromptActivity.this, R.string.system_prompt_saved);
                finish();
            }
        });
        root.addView(save, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
        }
        return true;
    }
}
