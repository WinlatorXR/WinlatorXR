package com.winlator.cmod.store;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

public class DownloadsActivity extends Activity {

    private static final int COLOR_GOG  = 0xFF6C3483;
    private static final int COLOR_EPIC = 0xFF0078F0;
    private static final int COLOR_AMZ  = 0xFFFF9900;
    private static final int COLOR_DONE = 0xFF2E7D32;
    private static final int COLOR_ERR  = 0xFFCC3333;
    private static final int COLOR_CARD = 0xFF1A1A1A;
    private static final int COLOR_BG   = 0xFF0D0D0D;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_SUB  = 0xFF999999;

    private LinearLayout listContainer;
    private TextView     emptyText;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean polling = false;

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            if (!polling) return;
            refresh();
            uiHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setBackgroundColor(0xFF111111);
        header.setPadding(dp(8), dp(12), dp(16), dp(12));
        header.setGravity(Gravity.CENTER_VERTICAL);

        Button backBtn = new Button(this);
        backBtn.setText("←");
        backBtn.setTextColor(COLOR_TEXT);
        backBtn.setTextSize(18f);
        backBtn.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        backBtn.setPadding(dp(8), 0, dp(8), 0);
        backBtn.setOnClickListener(v -> finish());
        header.addView(backBtn);

        TextView titleTv = new TextView(this);
        titleTv.setText("Downloads");
        titleTv.setTextColor(COLOR_TEXT);
        titleTv.setTextSize(20f);
        titleTv.setPadding(dp(8), 0, 0, 0);
        header.addView(titleTv);

        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Empty state
        emptyText = new TextView(this);
        emptyText.setText("No active downloads");
        emptyText.setTextColor(COLOR_SUB);
        emptyText.setTextSize(16f);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(dp(16), dp(48), dp(16), dp(16));
        emptyText.setVisibility(View.GONE);
        root.addView(emptyText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Scrollable list
        ScrollView scroll = new ScrollView(this);
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(dp(8), dp(8), dp(8), dp(8));
        scroll.addView(listContainer);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        polling = true;
        uiHandler.post(pollTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        polling = false;
        uiHandler.removeCallbacks(pollTask);
    }

    private void refresh() {
        List<StoreDownloadQueue.DownloadEntry> all = StoreDownloadQueue.getAll();
        listContainer.removeAllViews();

        if (all.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            return;
        }
        emptyText.setVisibility(View.GONE);

        for (StoreDownloadQueue.DownloadEntry e : all) {
            listContainer.addView(buildCard(e));
        }
    }

    private View buildCard(StoreDownloadQueue.DownloadEntry e) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(COLOR_CARD);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(8);
        card.setLayoutParams(cardLp);

        // Top row: store badge + title + cancel button
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = new TextView(this);
        badge.setText(e.store);
        badge.setTextColor(COLOR_TEXT);
        badge.setTextSize(11f);
        badge.setPadding(dp(6), dp(2), dp(6), dp(2));
        badge.setBackgroundColor(storeColor(e.store));
        topRow.addView(badge);

        TextView gameTitleTv = new TextView(this);
        gameTitleTv.setText(e.title);
        gameTitleTv.setTextColor(COLOR_TEXT);
        gameTitleTv.setTextSize(14f);
        gameTitleTv.setPadding(dp(8), 0, 0, 0);
        topRow.addView(gameTitleTv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (e.active) {
            Button cancelBtn = new Button(this);
            cancelBtn.setText("Cancel");
            cancelBtn.setTextColor(COLOR_TEXT);
            cancelBtn.setTextSize(12f);
            cancelBtn.setBackgroundColor(COLOR_ERR);
            cancelBtn.setPadding(dp(8), dp(2), dp(8), dp(2));
            cancelBtn.setOnClickListener(v -> StoreDownloadQueue.cancel(this, e.dlKey));
            topRow.addView(cancelBtn);
        }
        card.addView(topRow);

        // Progress bar
        ProgressBar bar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(e.percent);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
        barLp.topMargin = dp(6);
        bar.setLayoutParams(barLp);
        card.addView(bar);

        // Status line
        boolean isErr = e.status != null && e.status.startsWith("Error");
        TextView statusTv = new TextView(this);
        statusTv.setText(e.status);
        statusTv.setTextColor(e.active ? COLOR_SUB : (isErr ? COLOR_ERR : COLOR_DONE));
        statusTv.setTextSize(12f);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(4);
        statusTv.setLayoutParams(statusLp);
        card.addView(statusTv);

        return card;
    }

    private int storeColor(String store) {
        if ("GOG".equals(store))    return COLOR_GOG;
        if ("EPIC".equals(store))   return COLOR_EPIC;
        if ("AMAZON".equals(store)) return COLOR_AMZ;
        return 0xFF555555;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
