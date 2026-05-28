package com.winlator.cmod.store;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.NavActivity;
import com.winlator.cmod.R;

/**
 * Amazon Games library screen — UI mirrors GogGamesActivity.
 *
 * Install flow: Install → progress bar → Cancel → Add to Launcher
 * Exe picker shown on install complete if multiple .exe files found.
 * Installed state stored in bh_amazon_prefs: amazon_exe_{productId}.
 */
public class AmazonGamesActivity extends NavActivity {

    private static final String TAG          = "BH_AMAZON";
    private static final String PREFS_NAME   = "bh_amazon_prefs";
    private static final String CACHE_KEY    = "amazon_library_cache";

    // Amazon brand colours
    private static final int COLOR_ACCENT   = 0xFFFF9900;   // orange — install btn / title
    private static final int COLOR_ADD      = 0xFF2E7D32;   // green  — Add to Launcher btn
    private static final int COLOR_CANCEL   = 0xFFCC3333;   // red    — cancel btn
    private static final int COLOR_CARD_BG  = 0xFF1A1410;   // dark brownish card background
    private static final int COLOR_HDR_BG   = 0xFF1A1410;
    private static final int COLOR_ROOT_BG  = 0xFF0D0D0D;
    private static final int REQ_GAME_DETAIL  = 1001;
    private static final int REQ_DOWNLOADS    = 1002;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private TextView    syncText;
    private LinearLayout gameListLayout;
    private ScrollView  scrollView;
    private Button      refreshBtn;
    private EditText    searchBar;
    private List<AmazonGame> allGames = new ArrayList<>();
    private View        expandedSection = null;
    private TextView    expandedArrow   = null;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs    = getSharedPreferences(PREFS_NAME, 0);
        buildUi();
        List<AmazonGame> cached = loadCachedGames();
        if (cached != null && !cached.isEmpty()) {
            showGames(cached);
            int cn = cached.size(); setSync(cn + (cn == 1 ? " game" : " games") + " — cached  •  tap ↺ to refresh");
        }
        startSync(cached == null || cached.isEmpty());
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_ROOT_BG);

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setBackgroundColor(getColor(R.color.colorPrimary));
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(8), dp(8), dp(8));

        Button backBtn = new Button(this);
        backBtn.setText("←");
        backBtn.setTextColor(0xFFFFFFFF);
        backBtn.setBackgroundColor(Color.TRANSPARENT);
        backBtn.setTextSize(16f);
        backBtn.setPadding(dp(12), 0, dp(12), 0);
        backBtn.setOnClickListener(v -> finish());
        header.addView(backBtn, new LinearLayout.LayoutParams(-2, dp(40)));

        TextView titleTV = new TextView(this);
        titleTV.setText("Amazon Games");
        titleTV.setTextColor(Color.WHITE);
        titleTV.setTextSize(18f);
        titleTV.setTypeface(null, Typeface.BOLD);
        titleTV.setPadding(dp(12), 0, 0, 0);
        header.addView(titleTV, new LinearLayout.LayoutParams(0, -2, 1f));

        refreshBtn = new Button(this);
        refreshBtn.setText("↺");
        refreshBtn.setTextColor(0xFFFFFFFF);
        refreshBtn.setBackgroundColor(Color.TRANSPARENT);
        refreshBtn.setTextSize(16f);
        refreshBtn.setPadding(dp(12), 0, dp(12), 0);
        refreshBtn.setOnClickListener(v -> startSync(true));
        header.addView(refreshBtn, new LinearLayout.LayoutParams(-2, dp(40)));

        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        Button dlBtn = new Button(this);
        dlBtn.setText("\u2b07");
        dlBtn.setTextColor(0xFFFFFFFF);
        dlBtn.setBackgroundColor(Color.TRANSPARENT);
        dlBtn.setTextSize(16f);
        dlBtn.setPadding(dp(12), 0, dp(12), 0);
        dlBtn.setOnClickListener(v -> startActivityForResult(
                new Intent(this, DownloadsActivity.class), REQ_DOWNLOADS));
        header.addView(dlBtn, new LinearLayout.LayoutParams(-2, dp(40)));

        // Search bar
        searchBar = new EditText(this);
        searchBar.setHint("Search games…");
        searchBar.setHintTextColor(0xFF666666);
        searchBar.setTextColor(0xFFFFFFFF);
        searchBar.setTextSize(14f);
        searchBar.setBackgroundColor(0xFF221A10);
        searchBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        searchBar.setSingleLine(true);
        searchBar.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                applyFilter(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        root.addView(searchBar, new LinearLayout.LayoutParams(-1, -2));

        // Sync status
        syncText = new TextView(this);
        syncText.setText("Loading Amazon library…");
        syncText.setTextColor(0xFFCCCCCC);
        syncText.setTextSize(13f);
        syncText.setPadding(dp(12), dp(6), dp(12), dp(6));
        syncText.setBackgroundColor(0xFF111111);
        root.addView(syncText, new LinearLayout.LayoutParams(-1, -2));

        // Scrollable game list
        scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(COLOR_ROOT_BG);
        scrollView.setVisibility(View.GONE);

        gameListLayout = new LinearLayout(this);
        gameListLayout.setOrientation(LinearLayout.VERTICAL);
        gameListLayout.setPadding(dp(8), dp(8), dp(8), dp(8));
        scrollView.addView(gameListLayout, new FrameLayout.LayoutParams(-1, -2));

        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root, false);
    }

    // ── Library sync ──────────────────────────────────────────────────────────

    private void startSync(boolean showProgress) {
        uiHandler.post(() -> {
            if (refreshBtn != null) refreshBtn.setEnabled(false);
            if (showProgress) setSync("Loading Amazon library…");
        });
        new Thread(() -> syncLibrary(showProgress), "amazon-sync").start();
    }

    private void syncLibrary(boolean showProgress) {
        try {
            if (showProgress) setSync("Checking credentials…");
            AmazonCredentialStore.Credentials creds =
                    AmazonCredentialStore.load(AmazonGamesActivity.this);
            if (creds == null || creds.accessToken == null) {
                setSync("Not logged in");
                enableRefresh();
                uiHandler.post(() -> {
                    Toast.makeText(this, "Please log in to Amazon Games first",
                            Toast.LENGTH_SHORT).show();
                    finish();
                });
                return;
            }

            if (showProgress) setSync("Refreshing token…");
            String token = AmazonCredentialStore.getValidAccessToken(this);
            if (token == null) { setSync("Token refresh failed"); enableRefresh(); return; }

            if (showProgress) setSync("Fetching game list…");
            List<AmazonGame> games = AmazonApiClient.getEntitlements(token, creds.deviceSerial);

            if (games == null || games.isEmpty()) {
                setSync("No games found in Amazon library");
                enableRefresh();
                return;
            }

            Collections.sort(games, (a, b) -> a.title.compareToIgnoreCase(b.title));

            // Restore install state from cache
            List<AmazonGame> cached = loadCachedGames();
            if (cached != null) {
                for (AmazonGame fresh : games) {
                    for (AmazonGame old : cached) {
                        if (old.productId.equals(fresh.productId)) {
                            fresh.isInstalled  = old.isInstalled;
                            fresh.installPath  = old.installPath;
                            fresh.versionId    = old.versionId;
                            fresh.downloadSize = old.downloadSize;
                            fresh.installSize  = old.installSize;
                            break;
                        }
                    }
                }
            }

            // Check for updates on installed games
            checkForUpdates(token, games);

            saveCachedGames(games);

            final List<AmazonGame> finalGames = games;
            uiHandler.post(() -> {
                showGames(finalGames);
                int fn = finalGames.size(); setSync(fn + (fn == 1 ? " game" : " games") + " — tap a card to install");
                enableRefresh();
            });
        } catch (Exception e) {
            Log.e(TAG, "syncLibrary error", e);
            setSync("Error: " + e.getMessage());
            enableRefresh();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!allGames.isEmpty()) {
            applyFilter(searchBar != null ? searchBar.getText().toString() : "");
        }
    }

    private void showGames(List<AmazonGame> games) {
        allGames = games;
        String q = searchBar != null ? searchBar.getText().toString() : "";
        applyFilter(q);
        scrollView.setVisibility(View.VISIBLE);
    }

    private void applyFilter(String query) {
        List<AmazonGame> filtered;
        if (query == null || query.trim().isEmpty()) {
            filtered = allGames;
        } else {
            String q = query.trim().toLowerCase();
            filtered = new ArrayList<>();
            for (AmazonGame g : allGames)
                if (g.title.toLowerCase().contains(q)) filtered.add(g);
        }
        final List<AmazonGame> result = filtered;
        uiHandler.post(() -> {
            gameListLayout.removeAllViews();
            if (result.isEmpty()) {
                gameListLayout.setPadding(dp(8), dp(8), dp(8), dp(8));
                TextView emptyTV = new TextView(AmazonGamesActivity.this);
                String q2 = query == null ? "" : query.trim();
                emptyTV.setText(q2.isEmpty() ? "Your Amazon library is empty"
                                             : "No results for \u201c" + q2 + "\u201d");
                emptyTV.setTextColor(0xFF666666);
                emptyTV.setTextSize(14f);
                emptyTV.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams emLp = new LinearLayout.LayoutParams(-1, -2);
                emLp.topMargin = dp(32);
                gameListLayout.addView(emptyTV, emLp);
            } else {
                gameListLayout.setPadding(dp(8), dp(8), dp(8), dp(8));

                RecyclerView recyclerView = new RecyclerView(this);
                recyclerView.setLayoutManager(new LinearLayoutManager(this));
                recyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
                recyclerView.setPadding(0, 0, 0, dp(12));
                recyclerView.setClipToPadding(false);

                LinearLayout.LayoutParams rvLp =
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                        );
                recyclerView.setLayoutParams(rvLp);
                recyclerView.setAdapter(
                        new AmazonGamesAdapter(
                                this,
                                result,
                                prefs,
                                uiHandler
                        )
                );
                gameListLayout.addView(recyclerView);
            }
            scrollView.setVisibility(View.VISIBLE);
        });
    }

    private void checkForUpdates(String token, List<AmazonGame> games) {
        for (AmazonGame game : games) {
            String installedExe = prefs.getString("amazon_exe_" + game.productId, null);
            if (installedExe == null || game.productId.isEmpty()) continue;
            try {
                String liveVersion = AmazonApiClient.getLiveVersionId(token, game.productId);
                if (liveVersion != null && !liveVersion.isEmpty()
                        && !liveVersion.equals(game.versionId)) {
                    Log.d(TAG, "Update available: " + game.title
                            + " (" + game.versionId + " → " + liveVersion + ")");
                    game.versionId = liveVersion + "_UPDATE_AVAILABLE";
                }
            } catch (Exception e) {
                Log.w(TAG, "Update check failed for: " + game.title, e);
            }
        }
    }

    private void enableRefresh() {
        uiHandler.post(() -> { if (refreshBtn != null) refreshBtn.setEnabled(true); });
    }

    // ── LIST view: collapsible game cards ─────────────────────────────────────

    public class AmazonGamesAdapter
            extends RecyclerView.Adapter<AmazonGamesAdapter.GameViewHolder> {

        private final AmazonGamesActivity activity;
        private final List<AmazonGame> games;
        private final SharedPreferences prefs;
        private final Handler uiHandler;

        private int expandedPosition = RecyclerView.NO_POSITION;

        public AmazonGamesAdapter(
                AmazonGamesActivity activity,
                List<AmazonGame> games,
                SharedPreferences prefs,
                Handler uiHandler
        ) {
            this.activity = activity;
            this.games = games;
            this.prefs = prefs;
            this.uiHandler = uiHandler;

            setHasStableIds(true);
        }

        @Override
        public long getItemId(int position) {
            return games.get(position).productId.hashCode();
        }

        @NonNull
        @Override
        public GameViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {

            Context ctx = parent.getContext();

            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10));

            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(activity.COLOR_CARD_BG);
            cardBg.setCornerRadius(dp(ctx, 6));

            card.setBackground(cardBg);
            card.setFocusable(true);
            card.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

            RecyclerView.LayoutParams cardLp =
                    new RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    );

            cardLp.bottomMargin = dp(ctx, 8);
            card.setLayoutParams(cardLp);

            // =========================================================
            // HEADER
            // =========================================================

            LinearLayout topRow = new LinearLayout(ctx);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);

            // cover
            ImageView coverIV = new ImageView(ctx);
            coverIV.setScaleType(ImageView.ScaleType.CENTER_CROP);

            GradientDrawable coverBg = new GradientDrawable();
            coverBg.setColor(0xFF221A10);
            coverBg.setCornerRadius(dp(ctx, 4));

            coverIV.setBackground(coverBg);

            LinearLayout.LayoutParams coverLp =
                    new LinearLayout.LayoutParams(dp(ctx, 60), dp(ctx, 60));

            coverLp.rightMargin = dp(ctx, 10);

            topRow.addView(coverIV, coverLp);

            // info column
            LinearLayout infoCol = new LinearLayout(ctx);
            infoCol.setOrientation(LinearLayout.VERTICAL);
            infoCol.setGravity(Gravity.CENTER_VERTICAL);

            // title row
            LinearLayout titleRow = new LinearLayout(ctx);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView titleTV = new TextView(ctx);
            titleTV.setTextColor(Color.WHITE);
            titleTV.setTextSize(15f);
            titleTV.setTypeface(null, Typeface.BOLD);
            titleTV.setMaxLines(1);
            titleTV.setEllipsize(TextUtils.TruncateAt.END);

            titleRow.addView(titleTV);

            TextView collapsedCheckTV = new TextView(ctx);
            collapsedCheckTV.setText(" ✓");
            collapsedCheckTV.setTextColor(0xFF4CAF50);
            collapsedCheckTV.setTextSize(14f);
            collapsedCheckTV.setTypeface(null, Typeface.BOLD);

            titleRow.addView(collapsedCheckTV);

            titleRow.addView(
                    new View(ctx),
                    new LinearLayout.LayoutParams(0, 0, 1f)
            );

            infoCol.addView(
                    titleRow,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    )
            );

            TextView subTV = new TextView(ctx);
            subTV.setTextColor(0xFF888888);
            subTV.setTextSize(11f);
            subTV.setMaxLines(1);
            subTV.setEllipsize(TextUtils.TruncateAt.END);

            infoCol.addView(subTV);

            topRow.addView(
                    infoCol,
                    new LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f
                    )
            );

            // arrow
            TextView arrowTV = new TextView(ctx);
            arrowTV.setText("▼");
            arrowTV.setTextColor(0xFF888888);
            arrowTV.setTextSize(14f);
            arrowTV.setPadding(dp(ctx, 8), 0, 0, 0);

            topRow.addView(arrowTV);

            card.addView(topRow);

            // =========================================================
            // EXPAND SECTION
            // =========================================================

            LinearLayout expandSection = new LinearLayout(ctx);
            expandSection.setOrientation(LinearLayout.VERTICAL);
            expandSection.setVisibility(View.GONE);

            TextView metaTV = new TextView(ctx);
            metaTV.setTextColor(0xFF888888);
            metaTV.setTextSize(11f);

            LinearLayout.LayoutParams metaLp =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    );

            metaLp.topMargin = dp(ctx, 6);

            expandSection.addView(metaTV, metaLp);

            TextView checkmark = new TextView(ctx);
            checkmark.setTextSize(10f);

            LinearLayout.LayoutParams ckLp =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    );

            ckLp.topMargin = dp(ctx, 4);

            expandSection.addView(checkmark, ckLp);

            ProgressBar progressBar =
                    new ProgressBar(
                            ctx,
                            null,
                            android.R.attr.progressBarStyleHorizontal
                    );

            progressBar.setMax(100);
            progressBar.setVisibility(View.GONE);

            progressBar.getProgressDrawable().setColorFilter(
                    activity.COLOR_ACCENT,
                    PorterDuff.Mode.SRC_IN
            );

            LinearLayout.LayoutParams pbLp =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(ctx, 6)
                    );

            pbLp.topMargin = dp(ctx, 6);

            expandSection.addView(progressBar, pbLp);

            TextView pctTV = new TextView(ctx);
            pctTV.setTextColor(activity.COLOR_ACCENT);
            pctTV.setTextSize(12f);
            pctTV.setTypeface(null, Typeface.BOLD);
            pctTV.setVisibility(View.GONE);

            expandSection.addView(pctTV);

            TextView statusTV = new TextView(ctx);
            statusTV.setTextColor(0xFFAAAAAA);
            statusTV.setTextSize(11f);
            statusTV.setVisibility(View.GONE);

            LinearLayout.LayoutParams stLp =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    );

            stLp.topMargin = dp(ctx, 2);

            expandSection.addView(statusTV, stLp);

            Button actionBtn = new Button(ctx);
            actionBtn.setTextColor(Color.WHITE);
            actionBtn.setTextSize(13f);

            LinearLayout.LayoutParams abLp =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(ctx, 40)
                    );

            abLp.topMargin = dp(ctx, 8);

            expandSection.addView(actionBtn, abLp);

            card.addView(expandSection);

            return new GameViewHolder(
                    card,
                    cardBg,
                    coverIV,
                    titleTV,
                    subTV,
                    collapsedCheckTV,
                    arrowTV,
                    expandSection,
                    metaTV,
                    checkmark,
                    progressBar,
                    pctTV,
                    statusTV,
                    actionBtn
            );
        }

        @Override
        public void onBindViewHolder(@NonNull GameViewHolder h, int position) {

            AmazonGame game = games.get(position);

            boolean isInstalled =
                    prefs.getString("amazon_exe_" + game.productId, null) != null;

            boolean expanded = position == expandedPosition;

            // =========================================================
            // BASIC
            // =========================================================

            h.titleTV.setText(game.title);

            activity.loadImage(game, h.coverIV);

            String subtitle = "";

            if (!game.developer.isEmpty() && !game.publisher.isEmpty()) {
                subtitle = game.developer + "  ·  " + game.publisher;
            } else if (!game.developer.isEmpty()) {
                subtitle = game.developer;
            } else if (!game.publisher.isEmpty()) {
                subtitle = game.publisher;
            }

            h.subTV.setText(subtitle);
            h.subTV.setVisibility(
                    subtitle.isEmpty() ? View.GONE : View.VISIBLE
            );

            h.metaTV.setText(subtitle);
            h.metaTV.setVisibility(
                    subtitle.isEmpty() ? View.GONE : View.VISIBLE
            );

            // =========================================================
            // INSTALLED
            // =========================================================

            boolean updateAvailable =
                    isInstalled
                            && game.versionId != null
                            && game.versionId.endsWith("_UPDATE_AVAILABLE");

            h.collapsedCheckTV.setVisibility(
                    isInstalled ? View.VISIBLE : View.GONE
            );

            h.checkmark.setVisibility(
                    isInstalled ? View.VISIBLE : View.GONE
            );

            h.checkmark.setText(
                    updateAvailable
                            ? "✓ Installed — Update Available"
                            : "✓ Installed"
            );

            h.checkmark.setTextColor(
                    updateAvailable
                            ? 0xFFFFAA00
                            : 0xFF4CAF50
            );

            // =========================================================
            // EXPANSION
            // =========================================================

            h.expandSection.setVisibility(
                    expanded ? View.VISIBLE : View.GONE
            );

            h.arrowTV.setText(expanded ? "▲" : "▼");

            // =========================================================
            // BUTTON
            // =========================================================

            h.actionBtn.setText(
                    isInstalled ? "Add to Launcher" : "Install"
            );

            h.actionBtn.setBackgroundColor(
                    isInstalled
                            ? activity.COLOR_ADD
                            : activity.COLOR_ACCENT
            );

            // =========================================================
            // FOCUS
            // =========================================================

            h.itemView.setOnFocusChangeListener((v, hasFocus) -> {
                h.cardBg.setColor(
                        hasFocus
                                ? 0xFF2B251A
                                : activity.COLOR_CARD_BG
                );

                h.cardBg.setStroke(
                        hasFocus ? dp(v.getContext(), 3) : 0,
                        hasFocus ? 0xFFFFD700 : 0x00000000
                );
            });

            // =========================================================
            // CLICK
            // =========================================================

            h.itemView.setOnClickListener(v -> {

                if (expanded) {
                    activity.openDetailScreen(game);
                    return;
                }

                int oldPos = expandedPosition;
                expandedPosition = h.getBindingAdapterPosition();

                if (oldPos != RecyclerView.NO_POSITION) {
                    notifyItemChanged(oldPos);
                }

                notifyItemChanged(expandedPosition);
            });

            h.arrowTV.setOnClickListener(v -> {
                if (expanded) {
                    int old = expandedPosition;
                    expandedPosition = RecyclerView.NO_POSITION;
                    notifyItemChanged(old);
                }
            });

            // =========================================================
            // ACTION BUTTON
            // =========================================================

            final Runnable[] cancelRef = {null};

            h.actionBtn.setOnClickListener(v -> {

                String lbl = h.actionBtn.getText().toString();

                if ("Cancel".equals(lbl)) {
                    if (cancelRef[0] != null) {
                        cancelRef[0].run();
                    }
                    return;
                }

                if ("Add to Launcher".equals(lbl)
                        || "Add Game".equals(lbl)) {

                    String exe = prefs.getString(
                            "amazon_exe_" + game.productId,
                            null
                    );

                    if (exe != null) {
                        activity.pendingLaunchExe(game.title, exe);
                    }

                    return;
                }

                activity.showInstallConfirm(game, () -> {

                    h.actionBtn.setEnabled(true);
                    h.actionBtn.setText("Cancel");
                    h.actionBtn.setBackgroundColor(activity.COLOR_CANCEL);

                    h.progressBar.setVisibility(View.VISIBLE);
                    h.statusTV.setVisibility(View.VISIBLE);

                    h.pctTV.setVisibility(View.VISIBLE);
                    h.pctTV.setText("0%");

                    cancelRef[0] =
                            activity.startAmazonDownload(
                                    game,
                                    new DownloadCallback() {

                                        @Override
                                        public void onProgress(
                                                String msg,
                                                int pct
                                        ) {
                                            uiHandler.post(() -> {
                                                h.statusTV.setText(msg);
                                                h.progressBar.setProgress(pct);
                                                h.pctTV.setText(pct + "%");
                                            });
                                        }

                                        @Override
                                        public void onComplete(
                                                String exePath
                                        ) {
                                            uiHandler.post(() -> {

                                                h.progressBar.setProgress(100);

                                                h.pctTV.setVisibility(View.GONE);

                                                h.checkmark.setVisibility(
                                                        View.VISIBLE
                                                );

                                                h.collapsedCheckTV.setVisibility(
                                                        View.VISIBLE
                                                );

                                                h.statusTV.setText("Installed");

                                                h.actionBtn.setText(
                                                        "Add to Launcher"
                                                );

                                                h.actionBtn.setBackgroundColor(
                                                        activity.COLOR_ADD
                                                );
                                            });
                                        }

                                        @Override
                                        public void onError(String msg) {
                                            uiHandler.post(() -> {

                                                h.pctTV.setVisibility(View.GONE);

                                                h.statusTV.setText(
                                                        "Error: " + msg
                                                );

                                                h.actionBtn.setText("Install");

                                                h.actionBtn.setBackgroundColor(
                                                        activity.COLOR_ACCENT
                                                );
                                            });
                                        }

                                        @Override
                                        public void onCancelled() {
                                            uiHandler.post(() -> {

                                                h.progressBar.setProgress(0);

                                                h.progressBar.setVisibility(
                                                        View.GONE
                                                );

                                                h.pctTV.setVisibility(View.GONE);

                                                h.statusTV.setText("");

                                                h.actionBtn.setText("Install");

                                                h.actionBtn.setBackgroundColor(
                                                        activity.COLOR_ACCENT
                                                );
                                            });
                                        }

                                        @Override
                                        public void onSelectExe(
                                                List<String> candidates,
                                                Consumer<String> onSelected
                                        ) {
                                            activity.showExePicker(
                                                    candidates,
                                                    onSelected
                                            );
                                        }
                                    }
                            );
                });
            });

            // =========================================================
            // RESTORE ACTIVE DOWNLOAD
            // =========================================================

            StoreDownloadQueue.DownloadEntry entry =
                    StoreDownloadQueue.findActiveEntry(
                            "amz-" + game.productId + "-list",
                            "amz-" + game.productId + "-grid",
                            "amazon_" + game.productId
                    );

            if (entry != null) {

                expandedPosition = position;

                h.expandSection.setVisibility(View.VISIBLE);
                h.arrowTV.setText("▲");

                h.actionBtn.setText("Cancel");
                h.actionBtn.setBackgroundColor(0xFFCC3333);

                h.progressBar.setVisibility(View.VISIBLE);
                h.progressBar.setProgress(entry.percent);

                h.pctTV.setVisibility(View.VISIBLE);
                h.pctTV.setText(entry.percent + "%");

                h.statusTV.setVisibility(View.VISIBLE);
                h.statusTV.setText(entry.status);

                String dlKey = entry.dlKey;

                StoreDownloadQueue.addListener(
                        dlKey,
                        new StoreDownloadQueue.DownloadListener() {

                            @Override
                            public void onProgress(String msg, int pct) {
                                uiHandler.post(() -> {
                                    h.statusTV.setText(msg);
                                    h.progressBar.setProgress(pct);
                                    h.pctTV.setText(pct + "%");
                                });
                            }

                            @Override
                            public void onComplete(String exePath) {
                                uiHandler.post(() -> {

                                    h.progressBar.setProgress(100);

                                    h.pctTV.setVisibility(View.GONE);

                                    h.checkmark.setVisibility(View.VISIBLE);

                                    h.collapsedCheckTV.setVisibility(
                                            View.VISIBLE
                                    );

                                    h.statusTV.setText("Installed");

                                    h.actionBtn.setText("Add to Launcher");

                                    h.actionBtn.setBackgroundColor(
                                            activity.COLOR_ADD
                                    );
                                });
                            }

                            @Override
                            public void onError(String msg) {
                                uiHandler.post(() -> {

                                    h.pctTV.setVisibility(View.GONE);

                                    h.statusTV.setText("Error: " + msg);

                                    h.actionBtn.setText("Install");

                                    h.actionBtn.setBackgroundColor(
                                            activity.COLOR_ACCENT
                                    );
                                });
                            }

                            @Override
                            public void onCancelled() {
                                uiHandler.post(() -> {

                                    h.progressBar.setProgress(0);

                                    h.progressBar.setVisibility(View.GONE);

                                    h.pctTV.setVisibility(View.GONE);

                                    h.statusTV.setText("");

                                    h.actionBtn.setText("Install");

                                    h.actionBtn.setBackgroundColor(
                                            activity.COLOR_ACCENT
                                    );
                                });
                            }
                        }
                );

                cancelRef[0] =
                        () -> StoreDownloadQueue.cancel(activity, dlKey);
            }
        }

        @Override
        public int getItemCount() {
            return games.size();
        }

        // =============================================================
        // VIEW HOLDER
        // =============================================================

        static class GameViewHolder extends RecyclerView.ViewHolder {

            GradientDrawable cardBg;

            ImageView coverIV;

            TextView titleTV;
            TextView subTV;
            TextView collapsedCheckTV;
            TextView arrowTV;

            LinearLayout expandSection;

            TextView metaTV;
            TextView checkmark;
            ProgressBar progressBar;
            TextView pctTV;
            TextView statusTV;

            Button actionBtn;

            public GameViewHolder(
                    @NonNull View itemView,
                    GradientDrawable cardBg,
                    ImageView coverIV,
                    TextView titleTV,
                    TextView subTV,
                    TextView collapsedCheckTV,
                    TextView arrowTV,
                    LinearLayout expandSection,
                    TextView metaTV,
                    TextView checkmark,
                    ProgressBar progressBar,
                    TextView pctTV,
                    TextView statusTV,
                    Button actionBtn
            ) {
                super(itemView);

                this.cardBg = cardBg;
                this.coverIV = coverIV;

                this.titleTV = titleTV;
                this.subTV = subTV;
                this.collapsedCheckTV = collapsedCheckTV;
                this.arrowTV = arrowTV;

                this.expandSection = expandSection;

                this.metaTV = metaTV;
                this.checkmark = checkmark;
                this.progressBar = progressBar;
                this.pctTV = pctTV;
                this.statusTV = statusTV;

                this.actionBtn = actionBtn;
            }
        }

        // =============================================================
        // UTIL
        // =============================================================

        private static int dp(Context ctx, int v) {
            return (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    v,
                    ctx.getResources().getDisplayMetrics()
            );
        }
    }

    // ── Download wrapper ──────────────────────────────────────────────────────

    private interface DownloadCallback {
        void onProgress(String msg, int pct);
        void onComplete(String exePath);
        void onError(String msg);
        void onCancelled();
        void onSelectExe(List<String> candidates,
                         java.util.function.Consumer<String> onSelected);
    }

    /**
     * Starts an Amazon game download on a background thread.
     * Returns a Runnable cancel token (same pattern as GogDownloadManager.startDownload).
     */
    private Runnable startAmazonDownload(AmazonGame game, DownloadCallback cb) {
        AtomicBoolean cancelled = new AtomicBoolean(false);

        new Thread(() -> {
            String token = AmazonCredentialStore.getValidAccessToken(this);
            if (token == null) { cb.onError("Login required"); return; }

            String sanitized = game.title.replaceAll("[^a-zA-Z0-9 \\-_]", "").trim();
            if (sanitized.isEmpty()) sanitized = "game_" + game.productId.hashCode();
            File installDir = new File(new File(getFilesDir(), "imagefs/Amazon"), sanitized);

            // Store install dir in prefs for uninstall
            prefs.edit().putString("amazon_dir_" + game.productId,
                    installDir.getAbsolutePath()).apply();

            boolean ok = AmazonDownloadManager.install(this, game, token, installDir,
                (dl, total, file) -> {
                    if (cancelled.get()) return;
                    int pct = (total > 0) ? (int) (dl * 100L / total) : 0;
                    String name = (file != null && !file.isEmpty()) ? file : "Downloading…";
                    cb.onProgress(name, pct);
                },
                cancelled::get
            );

            if (cancelled.get()) { cb.onCancelled(); return; }
            if (!ok) { cb.onError("Download failed"); return; }

            // Scan for executables
            List<File> exeFiles = new ArrayList<>();
            AmazonLaunchHelper.collectExe(installDir, exeFiles);

            if (exeFiles.isEmpty()) {
                cb.onError("No executable found after install");
                return;
            }

            // Sort: best scored first
            String lowerTitle = game.title.toLowerCase();
            Collections.sort(exeFiles, (a, b) ->
                    AmazonLaunchHelper.scoreExe(b, lowerTitle)
                    - AmazonLaunchHelper.scoreExe(a, lowerTitle));

            if (exeFiles.size() == 1) {
                String path = exeFiles.get(0).getAbsolutePath();
                prefs.edit().putString("amazon_exe_" + game.productId, path).apply();
                cb.onComplete(path);
                return;
            }

            // Multiple exes → ask user
            List<String> candidates = new ArrayList<>();
            for (File f : exeFiles) candidates.add(f.getAbsolutePath());

            cb.onSelectExe(candidates, selected -> {
                String chosen = (selected != null && !selected.isEmpty())
                        ? selected
                        : exeFiles.get(0).getAbsolutePath(); // default: best scored
                prefs.edit().putString("amazon_exe_" + game.productId, chosen).apply();
                cb.onComplete(chosen);
            });

        }, "amazon-dl-" + game.productId).start();

        return () -> cancelled.set(true);
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private void showInstallConfirm(AmazonGame game, Runnable onConfirm) {
        long freeBytes = -1;
        try {
            File base = new File(new File(getFilesDir(), "Amazon"), "_check");
            File parent = base.getParentFile();
            if (parent != null) parent.mkdirs();
            android.os.StatFs sf = new android.os.StatFs(
                    parent != null ? parent.getAbsolutePath()
                            : getCacheDir().getAbsolutePath());
            freeBytes = sf.getAvailableBlocksLong() * sf.getBlockSizeLong();
        } catch (Exception ignored) {}

        final long freeBytesF = freeBytes;

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(8));

        TextView sizeTV = new TextView(this);
        sizeTV.setText("Game size:  Fetching…");
        sizeTV.setTextColor(0xFFCCCCCC);
        sizeTV.setTextSize(14f);
        content.addView(sizeTV);

        TextView freeTV = new TextView(this);
        freeTV.setText("Available storage:  " + formatBytes(freeBytesF));
        freeTV.setTextColor(0xFF88CC88);
        freeTV.setTextSize(14f);
        content.addView(freeTV);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Install " + game.title + "?")
                .setView(content)
                .setPositiveButton("Install", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            dialog.dismiss();
            onConfirm.run();
        });

        // Fetch game size in background and update label
        if (game.installSize > 0) {
            sizeTV.setText("Game size:  " + formatBytes(game.installSize));
        } else {
            new Thread(() -> {
                long size = 0;
                try {
                    String token = AmazonCredentialStore.getValidAccessToken(this);
                    if (token != null) {
                        AmazonApiClient.GameDownloadSpec spec =
                                AmazonApiClient.getGameDownload(token, game.entitlementId);
                        if (spec != null && !spec.downloadUrl.isEmpty()) {
                            String manifestUrl = AmazonApiClient.appendPath(
                                    spec.downloadUrl, "manifest.proto");
                            byte[] manifestBytes = AmazonApiClient.getBytes(
                                    manifestUrl, token);
                            if (manifestBytes != null) {
                                AmazonManifest.ParsedManifest manifest =
                                        AmazonManifest.parse(manifestBytes);
                                size = manifest.totalInstallSize;
                                game.installSize = size;
                            }
                        }
                    }
                } catch (Exception ignored) {}
                final long finalSize = size;
                uiHandler.post(() -> {
                    if (dialog.isShowing()) {
                        sizeTV.setText("Game size:  "
                                + (finalSize > 0 ? formatBytes(finalSize) : "Unknown"));
                    }
                });
            }, "amazon-size-" + game.productId).start();
        }
    }

    private void showDetailDialog(AmazonGame game, View checkmark, Button actionBtn, Runnable onUninstalled) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(8), dp(20), dp(4));

        StringBuilder msg = new StringBuilder();
        if (!game.developer.isEmpty()) msg.append("Developer: ").append(game.developer).append("\n");
        if (!game.publisher.isEmpty()) msg.append("Publisher: ").append(game.publisher).append("\n");
        msg.append("ID: ").append(game.shortId());

        TextView msgView = new TextView(this);
        msgView.setText(msg.toString().trim());
        msgView.setTextColor(0xFFCCCCCC);
        container.addView(msgView);

        String installedExe = prefs.getString("amazon_exe_" + game.productId, null);
        String installedDir = prefs.getString("amazon_dir_" + game.productId, null);

        if (installedExe != null) {
            TextView exeView = new TextView(this);
            exeView.setText("\n.exe: " + new File(installedExe).getName());
            exeView.setTextColor(0xFF888888);
            exeView.setTextSize(12f);
            container.addView(exeView);

            // Set .exe button
            Button setExeBtn = new Button(this);
            setExeBtn.setText("Set .exe…");
            setExeBtn.setTextColor(0xFFFFFFFF);
            setExeBtn.setBackgroundColor(0xFF444444);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = dp(10);
            setExeBtn.setOnClickListener(v -> {
                File dir = installedDir != null ? new File(installedDir) : null;
                if (dir == null || !dir.isDirectory()) {
                    Toast.makeText(this, "Install directory not found", Toast.LENGTH_SHORT).show();
                    return;
                }
                new Thread(() -> {
                    List<File> exeFiles = new ArrayList<>();
                    AmazonLaunchHelper.collectExe(dir, exeFiles);
                    if (exeFiles.isEmpty()) {
                        uiHandler.post(() -> Toast.makeText(this,
                                "No .exe files found", Toast.LENGTH_SHORT).show());
                        return;
                    }
                    List<String> candidates = new ArrayList<>();
                    for (File f : exeFiles) candidates.add(f.getAbsolutePath());
                    showExePicker(candidates, selected -> {
                        if (selected != null && !selected.isEmpty()) {
                            prefs.edit().putString("amazon_exe_" + game.productId, selected).apply();
                            uiHandler.post(() -> {
                                exeView.setText("\n.exe: " + new File(selected).getName());
                                Toast.makeText(this,
                                        "Exe set: " + new File(selected).getName(),
                                        Toast.LENGTH_SHORT).show();
                            });
                        }
                    });
                }).start();
            });
            container.addView(setExeBtn, lp);
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(game.title)
                .setView(container)
                .setPositiveButton("Close", null);

        if (installedDir != null) {
            b.setNegativeButton("Uninstall", (d, w) -> {
                new Thread(() -> {
                    deleteDir(new File(installedDir));
                    prefs.edit()
                            .remove("amazon_exe_" + game.productId)
                            .remove("amazon_dir_" + game.productId)
                            .apply();
                    uiHandler.post(() -> {
                        onUninstalled.run();
                        actionBtn.setEnabled(true);
                        Toast.makeText(this, game.title + " uninstalled",
                                Toast.LENGTH_SHORT).show();
                    });
                }).start();
            });
        }

        b.show();
    }

    private void showExePicker(List<String> candidates,
                                java.util.function.Consumer<String> onSelected) {
        String[] labels = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            File f      = new File(candidates.get(i));
            File parent = f.getParentFile();
            labels[i] = (parent != null)
                    ? parent.getName() + "/" + f.getName()
                    : f.getName();
        }
        uiHandler.post(() ->
            new AlertDialog.Builder(this)
                .setTitle("Select game executable")
                .setItems(labels, (d, which) ->
                    new Thread(() -> onSelected.accept(candidates.get(which))).start())
                .setCancelable(false)
                .show()
        );
    }

    // ── Launch ────────────────────────────────────────────────────────────────

    private void pendingLaunchExe(String gameName, String absPath) {
        LudashiLaunchBridge.addToLauncher(this, gameName, absPath);
    }

    // ── Cache ─────────────────────────────────────────────────────────────────

    private void saveCachedGames(List<AmazonGame> games) {
        try {
            JSONArray arr = new JSONArray();
            for (AmazonGame g : games) {
                JSONObject j = new JSONObject();
                j.put("productId",     g.productId);
                j.put("entitlementId", g.entitlementId);
                j.put("title",         g.title);
                j.put("artUrl",        g.artUrl);
                j.put("heroUrl",       g.heroUrl);
                j.put("developer",     g.developer);
                j.put("publisher",     g.publisher);
                j.put("productSku",    g.productSku);
                j.put("isInstalled",   g.isInstalled);
                j.put("installPath",   g.installPath);
                j.put("versionId",     g.versionId);
                j.put("downloadSize",  g.downloadSize);
                j.put("installSize",   g.installSize);
                arr.put(j);
            }
            prefs.edit().putString(CACHE_KEY, arr.toString()).apply();
        } catch (Exception e) { Log.e(TAG, "saveCachedGames failed", e); }
    }

    private List<AmazonGame> loadCachedGames() {
        try {
            String json = prefs.getString(CACHE_KEY, null);
            if (json == null) return null;
            JSONArray arr = new JSONArray(json);
            List<AmazonGame> games = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject j = arr.getJSONObject(i);
                AmazonGame g = new AmazonGame();
                g.productId     = j.optString("productId", "");
                g.entitlementId = j.optString("entitlementId", "");
                g.title         = j.optString("title", "");
                g.artUrl        = j.optString("artUrl", "");
                g.heroUrl       = j.optString("heroUrl", "");
                g.developer     = j.optString("developer", "");
                g.publisher     = j.optString("publisher", "");
                g.productSku    = j.optString("productSku", "");
                g.isInstalled   = j.optBoolean("isInstalled", false);
                g.installPath   = j.optString("installPath", "");
                g.versionId     = j.optString("versionId", "");
                g.downloadSize  = j.optLong("downloadSize", 0L);
                g.installSize   = j.optLong("installSize", 0L);
                games.add(g);
            }
            return games;
        } catch (Exception e) { Log.e(TAG, "loadCachedGames failed", e); return null; }
    }

    // ── Image loading ─────────────────────────────────────────────────────────

    private void loadImage(AmazonGame game, ImageView iv) {
        String url = game.artUrl;
        if (url == null || url.isEmpty()) url = game.heroUrl;
        if (url == null || url.isEmpty()) return;
        final String finalUrl = url;
        new Thread(() -> {
            try {
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new java.net.URL(finalUrl).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                if (conn.getResponseCode() == 200) {
                    Bitmap bmp = BitmapFactory.decodeStream(conn.getInputStream());
                    if (bmp != null) uiHandler.post(() -> iv.setImageBitmap(bmp));
                }
                conn.disconnect();
            } catch (Exception ignored) {}
        }, "amazon-cover-" + game.productId).start();
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private void setSync(String msg) {
        uiHandler.post(() -> {
            if (syncText == null) return;
            syncText.setText(msg);
            if (msg.startsWith("Error") || msg.startsWith("Not logged in")
                    || msg.startsWith("Token refresh") || msg.startsWith("No games")) {
                syncText.setTextColor(0xFFFF6B6B);
            } else if (msg.contains("game") && (msg.contains("tap") || msg.contains("cached"))) {
                syncText.setTextColor(0xFF81C784);
            } else {
                syncText.setTextColor(0xFFCCCCCC);
            }
        });
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "Unknown";
        if (bytes < 1024L)            return bytes + " B";
        if (bytes < 1024L * 1024L)    return (bytes / 1024L) + " KB";
        if (bytes < 1024L * 1024L * 1024L)
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children != null) for (File c : children) deleteDir(c);
        dir.delete();
    }

    // ── Full-screen detail ────────────────────────────────────────────────────

    private void openDetailScreen(AmazonGame game) {
        Intent intent = new Intent(this, AmazonGameDetailActivity.class);
        intent.putExtra("product_id",     game.productId);
        intent.putExtra("entitlement_id", game.entitlementId);
        intent.putExtra("title",          game.title);
        intent.putExtra("developer",      game.developer);
        intent.putExtra("publisher",      game.publisher);
        intent.putExtra("art_url",        game.artUrl);
        intent.putExtra("product_sku",    game.productSku);
        startActivityForResult(intent, REQ_GAME_DETAIL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_GAME_DETAIL && resultCode == AmazonGameDetailActivity.RESULT_REFRESH) {
            applyFilter(searchBar != null ? searchBar.getText().toString() : "");
        }
    }
}