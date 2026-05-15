package com.winlator.cmod;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;

public class NavActivity extends AppCompatActivity {
    private GridLayout gridLayout;
    private NavigationView navigationView;

    @Override
    public void setContentView(View view) {
        setContentView(view, false);
    }

    public void setContentView(View view, boolean addHeader) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        if (addHeader) {
            addHeader(root);
        }
        if (view != null) {
            addFillView(root, view);
        }
        addNavigationView(root);
        addNavigationGrid(root);
        setNavigationGrid();
        super.setContentView(root);
    }

    private void addHeader(LinearLayout root) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setBackgroundColor(getColor(R.color.colorPrimary));
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(8), dp(8), dp(8));
        addView(root, header);

        Button backBtn = new Button(this);
        backBtn.setText("←");
        backBtn.setTextColor(Color.WHITE);
        backBtn.setTextSize(18f);
        backBtn.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        backBtn.setPadding(dp(8), 0, dp(8), 0);
        backBtn.setOnClickListener(v -> finish());
        header.addView(backBtn);

        TextView titleTV = new TextView(this);
        titleTV.setText(getString(R.string.store));
        titleTV.setTextColor(Color.WHITE);
        titleTV.setTextSize(18f);
        titleTV.setTypeface(null, Typeface.BOLD);
        titleTV.setPadding(dp(12), 0, 0, 0);
        header.addView(titleTV, new LinearLayout.LayoutParams(0, -2, 1f));
    }

    private void addView(LinearLayout root, View view) {
        root.addView(view, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addFillView(LinearLayout root, View view) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                );

        root.addView(view, params);
    }

    protected int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    public int dpToPx(float dp, Context context){
        return (int) (dp * context.getResources().getDisplayMetrics().densityDpi / DisplayMetrics.DENSITY_DEFAULT);
    }

    protected void setNavigationGrid() {
        Context context = getBaseContext();
        Menu menu = navigationView.getMenu();
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            if (!item.isVisible()) {
                continue;
            }

            int padding = dpToPx(5, context);
            LinearLayout layout = new LinearLayout(context);
            layout.setPadding(padding, padding, padding, padding);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setOnClickListener(view -> {
                SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
                SharedPreferences.Editor e = sharedPreferences.edit();
                e.putString("tab_last", item.getTitle().toString());
                e.commit();
                finish();
            });

            layout.setOnFocusChangeListener((view, focused) -> {
                if (focused) {
                    layout.setBackgroundColor(Color.GRAY);
                } else {
                    layout.setBackgroundColor(Color.TRANSPARENT);
                }
            });

            int size = dpToPx(32, context);
            View icon = new View(context);
            item.getIcon().setTint(Color.WHITE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            icon.setLayoutParams(lp);
            icon.setBackground(item.getIcon());
            layout.addView(icon);

            int width = dpToPx(80, context);
            TextView text = new TextView(context);
            text.setLayoutParams(new ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
            text.setText(item.getTitle());
            text.setTextColor(Color.WHITE);
            text.setGravity(Gravity.CENTER);
            text.setLines(2);
            layout.addView(text);

            gridLayout.addView(layout);
        }
    }

    private void addNavigationGrid(LinearLayout root) {
        Context context = getBaseContext();
        RelativeLayout bottomBar = new RelativeLayout(context);
        LinearLayout.LayoutParams bottomParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        bottomBar.setLayoutParams(bottomParams);
        bottomBar.setBackgroundColor(ContextCompat.getColor(context, R.color.colorPrimary));

        gridLayout = new GridLayout(context);
        gridLayout.setId(R.id.NavigationGrid);

        RelativeLayout.LayoutParams gridParams =
                new RelativeLayout.LayoutParams(
                        RelativeLayout.LayoutParams.WRAP_CONTENT,
                        RelativeLayout.LayoutParams.WRAP_CONTENT
                );

        gridParams.addRule(RelativeLayout.CENTER_IN_PARENT);
        gridLayout.setLayoutParams(gridParams);
        gridLayout.setPadding(0, dpToPx(5, context), 0, 0);

        bottomBar.addView(gridLayout);
        root.addView(bottomBar);
    }

    private void addNavigationView(LinearLayout root) {
        Context context = new ContextThemeWrapper(getBaseContext(), R.style.AppTheme);
        navigationView = new NavigationView(context);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );

        navigationView.setLayoutParams(params);
        navigationView.inflateMenu(R.menu.main_menu);
        navigationView.setVisibility(View.GONE);
        root.addView(navigationView);
    }
}
