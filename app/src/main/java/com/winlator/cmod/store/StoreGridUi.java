package com.winlator.cmod.store;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

import com.winlator.cmod.R;

/**
 * Shared visual building blocks for the storefront library grids
 * (Steam / GOG / Amazon / Epic).
 *
 * Only the look-and-feel lives here — it is API independent. Each store keeps
 * its own data binding (model, image loading, installed check, launch/uninstall,
 * available sort keys) and feeds it into these views.
 *
 * Layout of one cell:  [ portrait cover ] / [ name ] / [ ▶ launch  🗑 uninstall ]
 */
public final class StoreGridUi {

    private StoreGridUi() {}

    public static final int COLUMNS         = 6;
    public static final int CARD_BG         = 0xFF23262E;
    public static final int ART_BG          = 0xFF15171C;
    public static final int STROKE          = 0x14FFFFFF;   // faint card edge highlight
    public static final int FOCUS_STROKE    = 0xFFFFD700;   // gold focus highlight
    public static final int TEXT            = 0xFFE6E6EA;
    public static final int LAUNCH_TINT     = 0xFF4CAF50;   // green
    public static final int UNINSTALL_TINT  = 0xFFE53935;   // red

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    /** Configure a GridView the way every store library uses it (6 uniform columns). */
    public static void styleGrid(GridView g) {
        Context c = g.getContext();
        g.setNumColumns(COLUMNS);
        g.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        g.setHorizontalSpacing(dp(c, 6));
        g.setVerticalSpacing(dp(c, 10));
        g.setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 8));
        g.setClipToPadding(false);
        g.setVerticalScrollBarEnabled(false);
    }

    /** References to a built cell's sub-views, for binding. */
    public static final class Cell {
        public final LinearLayout root;
        public final ImageView    art;
        public final TextView     name;
        public final LinearLayout iconRow;
        public final ImageView    launch;
        public final ImageView    uninstall;

        Cell(LinearLayout root, ImageView art, TextView name,
             LinearLayout iconRow, ImageView launch, ImageView uninstall) {
            this.root = root; this.art = art; this.name = name;
            this.iconRow = iconRow; this.launch = launch; this.uninstall = uninstall;
        }
    }

    /** Build one grid cell. The icon row starts INVISIBLE — call {@link #setInstalled}. */
    public static Cell buildCell(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        final GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(CARD_BG);
        cardBg.setCornerRadius(dp(ctx, 10));
        cardBg.setStroke(dp(ctx, 1), STROKE);
        root.setBackground(cardBg);
        root.setPadding(dp(ctx, 5), dp(ctx, 5), dp(ctx, 5), dp(ctx, 6));
        // Gold focus highlight for D-pad / controller navigation
        root.setFocusable(true);
        root.setOnFocusChangeListener((v, hasFocus) ->
                cardBg.setStroke(dp(ctx, hasFocus ? 2 : 1),
                        hasFocus ? FOCUS_STROKE : STROKE));

        // Cover art — 2:3 portrait, whole image shown (FIT_CENTER), rounded corners
        PortraitImageView art = new PortraitImageView(ctx);
        art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        GradientDrawable artBg = new GradientDrawable();
        artBg.setColor(ART_BG);
        artBg.setCornerRadius(dp(ctx, 6));
        art.setBackground(artBg);
        art.setClipToOutline(true);
        root.addView(art, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Name — single line keeps every cell the same height
        TextView name = new TextView(ctx);
        name.setTextSize(11f);
        name.setTextColor(TEXT);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        name.setLetterSpacing(0.01f);
        name.setPadding(0, dp(ctx, 6), 0, dp(ctx, 1));
        root.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Launch + Uninstall icons — INVISIBLE until installed (reserves space so
        // every cell stays a uniform height).
        LinearLayout iconRow = new LinearLayout(ctx);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        iconRow.setGravity(Gravity.CENTER);
        iconRow.setVisibility(View.INVISIBLE);
        ImageView launch    = iconButton(ctx, R.drawable.ic_game_launch,    LAUNCH_TINT);
        ImageView uninstall = iconButton(ctx, R.drawable.ic_game_uninstall, UNINSTALL_TINT);
        LinearLayout.LayoutParams launchLp =
                new LinearLayout.LayoutParams(dp(ctx, 30), dp(ctx, 30));
        launchLp.rightMargin = dp(ctx, 10);
        iconRow.addView(launch, launchLp);
        iconRow.addView(uninstall, new LinearLayout.LayoutParams(dp(ctx, 30), dp(ctx, 30)));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(ctx, 4);
        root.addView(iconRow, rowLp);

        return new Cell(root, art, name, iconRow, launch, uninstall);
    }

    /** Show or hide the launch/uninstall icon row based on install state. */
    public static void setInstalled(Cell cell, boolean installed) {
        cell.iconRow.setVisibility(installed ? View.VISIBLE : View.INVISIBLE);
    }

    private static ImageView iconButton(Context ctx, int resId, int tint) {
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(resId);
        iv.setColorFilter(tint);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6));
        iv.setClickable(true);
        iv.setFocusable(false);
        return iv;
    }

    /** A larger, modern back arrow for store headers (white icon + circular ripple). */
    public static ImageView backButton(Context ctx, View.OnClickListener onClick) {
        ImageView b = new ImageView(ctx);
        b.setImageResource(R.drawable.ic_arrow_back);
        b.setColorFilter(0xFFFFFFFF);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp(ctx, 8);
        b.setPadding(pad, pad, pad, pad);
        TypedValue tv = new TypedValue();
        if (ctx.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, tv, true)) {
            b.setBackgroundResource(tv.resourceId);
        }
        b.setClickable(true);
        b.setFocusable(true);
        b.setContentDescription("Back");
        b.setOnClickListener(onClick);
        return b;
    }

    /** A compact rounded pill used for the filter / sort controls. */
    public static TextView pillButton(Context ctx, String label) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(12f);
        t.setTextColor(TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(ctx, 14), dp(ctx, 7), dp(ctx, 14), dp(ctx, 7));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD_BG);
        bg.setCornerRadius(dp(ctx, 16));
        bg.setStroke(dp(ctx, 1), STROKE);
        t.setBackground(bg);
        t.setClickable(true);
        return t;
    }

    /** Recursively delete a directory — used by store uninstall actions. */
    public static void deleteDir(File f) {
        if (f == null) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteDir(k);
        f.delete();
    }

    /** ImageView locked to a 2:3 portrait box with rounded corners. */
    public static class PortraitImageView extends ImageView {
        public PortraitImageView(Context ctx) {
            super(ctx);
            final int radius = dp(ctx, 6);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            int w = getMeasuredWidth();
            if (w > 0) setMeasuredDimension(w, w * 3 / 2);  // 2:3 portrait
        }
    }
}
