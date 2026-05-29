/*
 * Epic Games integration for BannerHub
 *
 * Credits: The Epic Games Store API pipeline, OAuth flow, manifest download
 * architecture, CDN selection logic, chunk decompression, and launch arguments
 * are based on the research and implementation of The GameNative Team.
 * https://github.com/utkarshdalal/GameNative
 */
package com.winlator.cmod.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
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
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.NavActivity;
import com.winlator.cmod.R;

/**
 * Epic Games library screen — mirrors AmazonGamesActivity structure.
 *
 * Install: manifest fetch → chunk download → file assembly → exe scan
 * Launch:  store pending_epic_exe → start LandscapeLauncherMainActivity
 *
 * Storage in bh_epic_prefs:
 *   epic_exe_{appName}  — abs path to selected .exe
 *   epic_dir_{appName}  — abs path to install directory
 *   epic_cache          — JSON array of library cache
 */
public class EpicGamesActivity extends NavActivity {

    private static final String TAG           = "BH_EPIC";
    private static final String PREFS_NAME    = "bh_epic_prefs";
    private static final String CACHE_KEY     = "epic_cache";

    // Epic brand colours
    private static final int COLOR_CARD_BG = 0xFF0F1117;  // dark card background
    private static final int COLOR_ROOT_BG = 0xFF0D0D0D;
    private static final int REQ_GAME_DETAIL  = 1001;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private TextView     syncText;
    private LinearLayout gameListLayout;
    private ScrollView   scrollView;
    private Button       refreshBtn;
    private EditText     searchBar;
    private List<EpicGame> allGames    = new ArrayList<>();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs    = getSharedPreferences(PREFS_NAME, 0);
        buildUi();
        List<EpicGame> cached = loadCachedGames();
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
        backBtn.setOnClickListener(v -> goBack());
        header.addView(backBtn, new LinearLayout.LayoutParams(-2, dp(40)));

        TextView titleTV = new TextView(this);
        titleTV.setText("Epic Games");
        titleTV.setTextColor(Color.WHITE);
        titleTV.setTextSize(18f);
        titleTV.setTypeface(null, Typeface.BOLD);
        titleTV.setPadding(dp(12), 0, 0, 0);
        header.addView(titleTV, new LinearLayout.LayoutParams(0, -2, 1f));

        refreshBtn = new Button(this);
        refreshBtn.setText("Refresh");
        refreshBtn.setTextSize(13f);
        refreshBtn.setTextColor(Color.WHITE);
        refreshBtn.setBackgroundColor(Color.TRANSPARENT);
        refreshBtn.setPadding(dp(12), 0, dp(12), 0);
        refreshBtn.setOnClickListener(v -> startSync(true));
        header.addView(refreshBtn, new LinearLayout.LayoutParams(-2, dp(40)));

        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        Button logoutBtn = new Button(this);
        logoutBtn.setText("Logout");
        logoutBtn.setTextSize(13f);
        logoutBtn.setTextColor(Color.WHITE);
        logoutBtn.setBackgroundColor(Color.TRANSPARENT);
        logoutBtn.setPadding(dp(12), 0, dp(12), 0);
        logoutBtn.setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(EpicGamesActivity.this)
                    .setTitle("Sign out of Steam?")
                    .setMessage("Your saved login will be removed. You will need to sign in again.")
                    .setPositiveButton("Sign Out", (dialog, which) ->
                            signOut()
                    )
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        header.addView(logoutBtn, new LinearLayout.LayoutParams(-2, dp(40)));

        // Search bar
        searchBar = new EditText(this);
        searchBar.setHint("Search games…");
        searchBar.setHintTextColor(0xFF666666);
        searchBar.setTextColor(0xFFFFFFFF);
        searchBar.setTextSize(14f);
        searchBar.setBackgroundColor(0xFF141820);
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
        syncText.setText("Loading Epic library…");
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
        setContentView(root);
    }

    private void signOut() {
        EpicCredentialStore.clear(this);
        finish();
    }

    // ── Library sync ──────────────────────────────────────────────────────────

    private void startSync(boolean showProgress) {
        uiHandler.post(() -> {
            if (refreshBtn != null) refreshBtn.setEnabled(false);
            if (showProgress) setSync("Loading Epic library…");
        });
        new Thread(() -> syncLibrary(showProgress), "epic-sync").start();
    }

    private void syncLibrary(boolean showProgress) {
        try {
            if (showProgress) setSync("Checking credentials…");
            String token = EpicCredentialStore.getValidAccessToken(this);
            if (token == null) {
                setSync("Not logged in");
                enableRefresh();
                uiHandler.post(() -> {
                    Toast.makeText(this, "Please log in to Epic Games first",
                            Toast.LENGTH_SHORT).show();
                    finish();
                });
                return;
            }

            if (showProgress) setSync("Fetching game list…");
            List<EpicGame> games = EpicApiClient.getLibraryItems(token);

            if (games == null || games.isEmpty()) {
                setSync("No games found in Epic library");
                enableRefresh();
                return;
            }

            // Enrich each game with catalog details (title, art, developer)
            if (showProgress) setSync("Loading game details…");
            int total = games.size();
            int done  = 0;
            for (EpicGame game : games) {
                EpicApiClient.enrichFromCatalog(token, game);
                done++;
                if (done % 5 == 0) {
                    final int d = done;
                    setSync("Loading game details… (" + d + "/" + total + ")");
                }
            }

            // Filter: skip DLC from top-level display
            List<EpicGame> mainGames = new ArrayList<>();
            for (EpicGame g : games) {
                if (!g.isDLC) mainGames.add(g);
            }
            if (mainGames.isEmpty()) mainGames = games;

            Collections.sort(mainGames, (a, b) -> a.title.compareToIgnoreCase(b.title));

            // Restore install state from cache
            List<EpicGame> cached = loadCachedGames();
            if (cached != null) {
                for (EpicGame fresh : mainGames) {
                    for (EpicGame old : cached) {
                        if (old.appName.equals(fresh.appName)) {
                            fresh.isInstalled = old.isInstalled;
                            fresh.installPath = old.installPath;
                            fresh.version     = old.version;
                            fresh.installSize = old.installSize;
                            break;
                        }
                    }
                }
            }

            saveCachedGames(mainGames);

            final List<EpicGame> finalGames = mainGames;
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

    private void showGames(List<EpicGame> games) {
        allGames = games;
        applyFilter(searchBar != null ? searchBar.getText().toString() : "");
        scrollView.setVisibility(View.VISIBLE);
    }

    private void applyFilter(String query) {
        List<EpicGame> filtered;
        if (query == null || query.trim().isEmpty()) {
            filtered = allGames;
        } else {
            String q = query.trim().toLowerCase();
            filtered = new ArrayList<>();
            for (EpicGame g : allGames)
                if (g.title.toLowerCase().contains(q)) filtered.add(g);
        }
        final List<EpicGame> result = filtered;
        uiHandler.post(() -> {
            gameListLayout.removeAllViews();
            if (result.isEmpty()) {
                gameListLayout.setPadding(dp(8), dp(8), dp(8), dp(8));
                TextView emptyTV = new TextView(EpicGamesActivity.this);
                String q2 = query == null ? "" : query.trim();
                emptyTV.setText(q2.isEmpty() ? "Your Epic library is empty"
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

                EpicGamesAdapter adapter = new EpicGamesAdapter(
                        this,
                        result,
                        prefs,
                        COLOR_CARD_BG,
                        new EpicGamesAdapter.Callbacks() {

                            @Override
                            public void loadImage(EpicGame game, ImageView iv) {
                                EpicGamesActivity.this.loadImage(game, iv);
                            }

                            @Override
                            public void openDetailScreen(EpicGame game) {
                                EpicGamesActivity.this.openDetailScreen(game);
                            }
                        }
                );
                recyclerView.setAdapter(adapter);
                gameListLayout.addView(recyclerView);
            }
            scrollView.setVisibility(View.VISIBLE);
        });
    }

    private void enableRefresh() {
        uiHandler.post(() -> { if (refreshBtn != null) refreshBtn.setEnabled(true); });
    }

    // ── LIST view: collapsible game cards ─────────────────────────────────────

    public class EpicGamesAdapter
            extends RecyclerView.Adapter<EpicGamesAdapter.GameViewHolder> {

        public interface Callbacks {
            void loadImage(EpicGame game, ImageView iv);
            void openDetailScreen(EpicGame game);
        }

        private final Context context;
        private final List<EpicGame> games;
        private final SharedPreferences prefs;

        private final int COLOR_CARD_BG;

        private final Callbacks callbacks;

        public EpicGamesAdapter(
                Context context,
                List<EpicGame> games,
                SharedPreferences prefs,
                int cardBg,
                Callbacks callbacks
        ) {
            this.context = context;
            this.games = games;
            this.prefs = prefs;

            this.COLOR_CARD_BG = cardBg;

            this.callbacks = callbacks;
        }

        @NonNull
        @Override
        public GameViewHolder onCreateViewHolder(
                @NonNull ViewGroup parent,
                int viewType
        ) {

            LinearLayout card = new LinearLayout(context);

            card.setOrientation(LinearLayout.VERTICAL);

            card.setPadding(dp(10), dp(10), dp(10), dp(10));

            RecyclerView.LayoutParams lp =
                    new RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    );

            lp.bottomMargin = dp(8);

            card.setLayoutParams(lp);

            GradientDrawable bg = new GradientDrawable();

            bg.setColor(COLOR_CARD_BG);

            bg.setCornerRadius(dp(6));

            card.setBackground(bg);

            card.setFocusable(true);

            return new GameViewHolder(card, bg);
        }

        @Override
        public void onBindViewHolder(
                @NonNull GameViewHolder holder,
                int position
        ) {

            EpicGame game = games.get(position);

            boolean isInstalled =
                    prefs.getString(
                            "epic_exe_" + game.appName,
                            null
                    ) != null;

            holder.card.removeAllViews();

            holder.card.setOnFocusChangeListener((v, hasFocus) -> {

                holder.bg.setColor(
                        hasFocus
                                ? 0xFF1B1F2A
                                : COLOR_CARD_BG
                );

                holder.bg.setStroke(
                        hasFocus ? dp(3) : 0,
                        hasFocus
                                ? 0xFFFFD700
                                : 0x00000000
                );
            });

            // =========================================================
            // HEADER
            // =========================================================

            LinearLayout topRow = new LinearLayout(context);

            topRow.setOrientation(LinearLayout.HORIZONTAL);

            topRow.setGravity(Gravity.CENTER_VERTICAL);

            // COVER
            ImageView coverIV = new ImageView(context);

            coverIV.setScaleType(ImageView.ScaleType.CENTER_CROP);

            GradientDrawable coverBg = new GradientDrawable();

            coverBg.setColor(0xFF141820);

            coverBg.setCornerRadius(dp(4));

            coverIV.setBackground(coverBg);

            LinearLayout.LayoutParams coverLp =
                    new LinearLayout.LayoutParams(
                            dp(60),
                            dp(60)
                    );

            coverLp.rightMargin = dp(10);

            topRow.addView(coverIV, coverLp);

            callbacks.loadImage(game, coverIV);

            // INFO COLUMN
            LinearLayout infoCol = new LinearLayout(context);

            infoCol.setOrientation(LinearLayout.VERTICAL);

            infoCol.setGravity(Gravity.CENTER_VERTICAL);

            // TITLE ROW
            LinearLayout titleRow = new LinearLayout(context);

            titleRow.setOrientation(LinearLayout.HORIZONTAL);

            titleRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView titleTV = new TextView(context);

            titleTV.setText(game.title);

            titleTV.setTextColor(Color.WHITE);

            titleTV.setTextSize(15f);

            titleTV.setTypeface(null, Typeface.BOLD);

            titleTV.setMaxLines(1);

            titleTV.setEllipsize(TextUtils.TruncateAt.END);

            titleRow.addView(titleTV);

            TextView collapsedCheckTV = new TextView(context);

            collapsedCheckTV.setText(" ✓");

            collapsedCheckTV.setTextColor(0xFF4CAF50);

            collapsedCheckTV.setTextSize(14f);

            collapsedCheckTV.setTypeface(null, Typeface.BOLD);

            collapsedCheckTV.setVisibility(
                    isInstalled
                            ? View.VISIBLE
                            : View.GONE
            );

            titleRow.addView(collapsedCheckTV);

            titleRow.addView(
                    new View(context),
                    new LinearLayout.LayoutParams(
                            0,
                            0,
                            1f
                    )
            );

            infoCol.addView(
                    titleRow,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    )
            );

            // META
            if (!game.developer.isEmpty()) {

                TextView metaTV = new TextView(context);

                metaTV.setText(game.developer);

                metaTV.setTextColor(0xFF888888);

                metaTV.setTextSize(11f);

                metaTV.setMaxLines(1);

                metaTV.setEllipsize(TextUtils.TruncateAt.END);

                LinearLayout.LayoutParams metaLp =
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                        );

                metaLp.topMargin = dp(4);

                infoCol.addView(metaTV, metaLp);
            }

            // INSTALLED CHECKMARK
            TextView checkmark = new TextView(context);

            checkmark.setText("✓ Installed");

            checkmark.setTextColor(0xFF4CAF50);

            checkmark.setTextSize(10f);

            checkmark.setVisibility(
                    isInstalled
                            ? View.VISIBLE
                            : View.GONE
            );

            LinearLayout.LayoutParams ckLp =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    );

            ckLp.topMargin = dp(2);

            infoCol.addView(checkmark, ckLp);

            topRow.addView(
                    infoCol,
                    new LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f
                    )
            );

            holder.card.addView(topRow);

            // =========================================================
            // OPEN DETAILS DIRECTLY
            // =========================================================

            holder.card.setOnClickListener(v ->
                    callbacks.openDetailScreen(game)
            );
        }

        @Override
        public int getItemCount() {
            return games.size();
        }

        static class GameViewHolder
                extends RecyclerView.ViewHolder {

            LinearLayout card;

            GradientDrawable bg;

            public GameViewHolder(
                    @NonNull View itemView,
                    GradientDrawable bg
            ) {
                super(itemView);

                card = (LinearLayout) itemView;

                this.bg = bg;
            }
        }

        private int dp(int v) {

            return (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    v,
                    context.getResources().getDisplayMetrics()
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

    private Runnable startEpicDownload(EpicGame game, DownloadCallback cb) {
        AtomicBoolean cancelled = new AtomicBoolean(false);

        new Thread(() -> {
            try {
                String token = EpicCredentialStore.getValidAccessToken(this);
                if (token == null) { cb.onError("Login required"); return; }

                // Fetch manifest API JSON
                cb.onProgress("Fetching manifest…", 0);
                String manifestJson = EpicApiClient.getManifestApiJson(
                        token, game.namespace, game.catalogItemId, game.appName);
                if (manifestJson == null) {
                    cb.onError("Failed to fetch manifest. If this is Fortnite, it is not supported.");
                    return;
                }

                // Install directory: getFilesDir()/epic_games/{sanitized title}
                String sanitized = game.title.replaceAll("[^a-zA-Z0-9 \\-_]", "").trim();
                if (sanitized.isEmpty()) sanitized = "epic_" + game.appName.hashCode();
                File installDir = new File(new File(getFilesDir(), "imagefs/epic_games"), sanitized);
                prefs.edit().putString("epic_dir_" + game.appName,
                        installDir.getAbsolutePath()).apply();

                // Run download pipeline
                final String finalToken = token;
                boolean ok = EpicDownloadManager.install(
                        EpicGamesActivity.this,
                        manifestJson,
                        finalToken,
                        installDir.getAbsolutePath(),
                        (msg, pct) -> {
                            if (cancelled.get()) return;
                            cb.onProgress(msg, pct);
                        });

                if (cancelled.get()) { cb.onCancelled(); return; }
                if (!ok) { cb.onError("Download failed"); return; }

                // Scan for Windows .exe files
                List<File> exeFiles = new ArrayList<>();
                AmazonLaunchHelper.collectExe(installDir, exeFiles);

                if (exeFiles.isEmpty()) {
                    cb.onError("No executable found after install");
                    return;
                }

                // Sort best-scored first
                String lowerTitle = game.title.toLowerCase();
                Collections.sort(exeFiles, (a, b) ->
                        AmazonLaunchHelper.scoreExe(b, lowerTitle)
                        - AmazonLaunchHelper.scoreExe(a, lowerTitle));

                if (exeFiles.size() == 1) {
                    String path = exeFiles.get(0).getAbsolutePath();
                    prefs.edit().putString("epic_exe_" + game.appName, path).apply();
                    cb.onComplete(path);
                    return;
                }

                // Multiple exes → ask user
                List<String> candidates = new ArrayList<>();
                for (File f : exeFiles) candidates.add(f.getAbsolutePath());
                cb.onSelectExe(candidates, selected -> {
                    String chosen = (selected != null && !selected.isEmpty())
                            ? selected : exeFiles.get(0).getAbsolutePath();
                    prefs.edit().putString("epic_exe_" + game.appName, chosen).apply();
                    cb.onComplete(chosen);
                });

            } catch (Exception e) {
                Log.e(TAG, "startEpicDownload failed", e);
                if (!cancelled.get()) cb.onError(e.getMessage() != null ? e.getMessage() : "Unknown error");
            }
        }, "epic-dl-" + game.appName).start();

        return () -> cancelled.set(true);
    }

    // ── Cache ─────────────────────────────────────────────────────────────────

    private void saveCachedGames(List<EpicGame> games) {
        try {
            JSONArray arr = new JSONArray();
            for (EpicGame g : games) {
                JSONObject j = new JSONObject();
                j.put("appName",       g.appName);
                j.put("namespace",     g.namespace);
                j.put("catalogItemId", g.catalogItemId);
                j.put("title",         g.title);
                j.put("artCover",      g.artCover);
                j.put("artSquare",     g.artSquare);
                j.put("developer",     g.developer);
                j.put("description",   g.description);
                j.put("version",       g.version);
                j.put("isInstalled",   g.isInstalled);
                j.put("installPath",   g.installPath);
                j.put("installSize",   g.installSize);
                j.put("canRunOffline", g.canRunOffline);
                arr.put(j);
            }
            prefs.edit().putString(CACHE_KEY, arr.toString()).apply();
        } catch (Exception e) { Log.e(TAG, "saveCachedGames failed", e); }
    }

    private List<EpicGame> loadCachedGames() {
        try {
            String json = prefs.getString(CACHE_KEY, null);
            if (json == null) return null;
            JSONArray arr = new JSONArray(json);
            List<EpicGame> games = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject j = arr.getJSONObject(i);
                EpicGame g = new EpicGame();
                g.appName       = j.optString("appName",       "");
                g.namespace     = j.optString("namespace",     "");
                g.catalogItemId = j.optString("catalogItemId", "");
                g.title         = j.optString("title",         "");
                g.artCover      = j.optString("artCover",      "");
                g.artSquare     = j.optString("artSquare",     "");
                g.developer     = j.optString("developer",     "");
                g.description   = j.optString("description",   "");
                g.version       = j.optString("version",       "");
                g.isInstalled   = j.optBoolean("isInstalled",  false);
                g.installPath   = j.optString("installPath",   "");
                long cachedSize = j.optLong("installSize", 0L);
                // Sanity-check: discard absurd cached values (> 1 TB = corrupt/stale cache)
                g.installSize   = (cachedSize > 1_099_511_627_776L) ? 0L : cachedSize;
                g.canRunOffline = j.optBoolean("canRunOffline", true);
                games.add(g);
            }
            return games;
        } catch (Exception e) { Log.e(TAG, "loadCachedGames failed", e); return null; }
    }

    // ── Image loading ─────────────────────────────────────────────────────────

    private void loadImage(EpicGame game, ImageView iv) {
        String url = game.artCover;
        if (url == null || url.isEmpty()) url = game.artSquare;
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
        }, "epic-cover-" + game.appName).start();
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private void setSync(String msg) {
        uiHandler.post(() -> {
            if (syncText == null) return;
            syncText.setText(msg);
            if (msg.startsWith("Error") || msg.startsWith("Not logged in")
                    || msg.startsWith("No games")) {
                syncText.setTextColor(0xFFFF6B6B);
            } else if (msg.contains("game") && (msg.contains("tap") || msg.contains("cached"))) {
                syncText.setTextColor(0xFF81C784);
            } else {
                syncText.setTextColor(0xFFCCCCCC);
            }
        });
    }

    // ── Full-screen detail ────────────────────────────────────────────────────

    private void openDetailScreen(EpicGame game) {
        Intent intent = new Intent(this, EpicGameDetailActivity.class);
        intent.putExtra("app_name",        game.appName);
        intent.putExtra("title",           game.title);
        intent.putExtra("description",     game.description);
        intent.putExtra("developer",       game.developer);
        intent.putExtra("art_cover",       game.artCover);
        intent.putExtra("namespace",       game.namespace);
        intent.putExtra("catalog_item_id", game.catalogItemId);
        startActivityForResult(intent, REQ_GAME_DETAIL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_GAME_DETAIL && resultCode == EpicGameDetailActivity.RESULT_REFRESH) {
            applyFilter(searchBar != null ? searchBar.getText().toString() : "");
        }
    }
}