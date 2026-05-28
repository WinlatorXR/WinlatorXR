package com.winlator.cmod.store;

import android.app.AlertDialog;
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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import android.content.Intent;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.NavActivity;
import com.winlator.cmod.R;

/**
 * Displays the signed-in user's GOG library as scrollable game cards.
 *
 * Library sync flow:
 *   1. Proactive token expiry check → refresh if needed
 *   2. GET user/data/games → owned game IDs
 *   3. Per ID: GET products/{id}?expand=downloads,description → metadata
 *   4. Check builds?generation=2 → store gog_gen_{id}
 *   5. Build card views on main thread
 */
public class GogGamesActivity extends NavActivity {

    private static final String TAG = "BH_GOG";
    private static final String CACHE_KEY = "gog_library_cache";
    private static final int REQ_GAME_DETAIL = 1001;
    private static final int REQ_DOWNLOADS   = 1002;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private TextView syncText;
    private LinearLayout gameListLayout;
    private ScrollView scrollView;
    private SharedPreferences prefs;
    private Button refreshBtn;
    private EditText searchBar;
    private List<GogGame> allGames = new ArrayList<>();
    private View expandedSection = null;
    private TextView expandedArrow = null;
    private final Map<String, List<String[]>> gogDlcBuffer = new HashMap<>();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("bh_gog_prefs", 0);
        buildUi();
        List<GogGame> cached = loadCachedGames();
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
        root.setBackgroundColor(0xFF0D0D0D);

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
        titleTV.setText("GOG Library");
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
        searchBar.setBackgroundColor(0xFF222233);
        searchBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        searchBar.setSingleLine(true);
        searchBar.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        root.addView(searchBar, new LinearLayout.LayoutParams(-1, -2));

        // Sync status
        syncText = new TextView(this);
        syncText.setText("Loading GOG library…");
        syncText.setTextColor(0xFFCCCCCC);
        syncText.setTextSize(13f);
        syncText.setPadding(dp(12), dp(6), dp(12), dp(6));
        syncText.setBackgroundColor(0xFF111111);
        root.addView(syncText, new LinearLayout.LayoutParams(-1, -2));

        // Scrollable game list
        scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(0xFF0D0D0D);
        scrollView.setVisibility(View.GONE);

        gameListLayout = new LinearLayout(this);
        gameListLayout.setOrientation(LinearLayout.VERTICAL);
        gameListLayout.setPadding(dp(8), dp(8), dp(8), dp(8));
        scrollView.addView(gameListLayout, new FrameLayout.LayoutParams(-1, -2));

        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    // ── Library sync (background thread) ─────────────────────────────────────

    private void startSync(boolean showProgress) {
        uiHandler.post(() -> {
            if (refreshBtn != null) refreshBtn.setEnabled(false);
            if (showProgress) setSync("Loading GOG library…");
        });
        new Thread(() -> syncLibrary(showProgress), "gog-sync").start();
    }

    private void syncLibrary(boolean showProgress) {
        try {
            if (showProgress) setSync("Checking token…");

            String token = prefs.getString("access_token", null);
            if (token == null) { setSync("Not logged in"); enableRefresh(); return; }

            int loginTime = prefs.getInt("bh_gog_login_time", 0);
            int expiresIn = prefs.getInt("bh_gog_expires_in", 3600);
            int nowSec    = (int) (System.currentTimeMillis() / 1000L);
            if (loginTime == 0 || nowSec >= loginTime + expiresIn) {
                if (showProgress) setSync("Refreshing token…");
                String newToken = GogTokenRefresh.refresh(this);
                if (newToken == null) { setSync("Session expired — please sign in again"); enableRefresh(); return; }
                token = newToken;
            }

            if (showProgress) setSync("Fetching game list…");

            String gamesJson = httpGet("https://embed.gog.com/user/data/games", token);
            if (gamesJson == null) { setSync("Failed to fetch library"); enableRefresh(); return; }

            List<String> ids = new ArrayList<>();
            try {
                JSONObject obj = new JSONObject(gamesJson);
                JSONArray ownedArr = obj.optJSONArray("owned");
                if (ownedArr != null) {
                    for (int i = 0; i < ownedArr.length(); i++) {
                        String id = String.valueOf(ownedArr.getLong(i));
                        if (!"1801418160".equals(id)) ids.add(id);
                    }
                }
            } catch (Exception e) {
                setSync("Error parsing library"); enableRefresh(); return;
            }

            if (ids.isEmpty()) { setSync("No games found in library"); enableRefresh(); return; }

            if (showProgress) setSync("Syncing " + ids.size() + " games…");

            final String finalToken = token;
            ExecutorService pool = Executors.newFixedThreadPool(5);
            List<Future<GogGame>> futures = new ArrayList<>();
            for (String id : ids) {
                futures.add(pool.submit(() -> fetchGame(id, finalToken)));
            }
            pool.shutdown();

            List<GogGame> games = new ArrayList<>();
            for (Future<GogGame> f : futures) {
                try {
                    GogGame g = f.get();
                    if (g != null) games.add(g);
                } catch (Exception ignored) {}
            }

            saveCachedGames(games);

            final List<GogGame> finalGames = games;
            uiHandler.post(() -> {
                if (finalGames.isEmpty()) {
                    setSync("No compatible games found");
                } else {
                    showGames(finalGames);
                    int fn = finalGames.size(); setSync(fn + (fn == 1 ? " game" : " games") + " — tap a card to install");
                }
                enableRefresh();
            });
        } catch (Exception e) {
            Log.e(TAG, "syncLibrary error", e);
            setSync("Error: " + e.getMessage());
            enableRefresh();
        }
    }

    /** Fetches metadata + generation for a single game ID. Returns null to skip. */
    private GogGame fetchGame(String id, String token) {
        try {
            String productJson = httpGet(
                    "https://api.gog.com/products/" + id + "?expand=downloads,description", token);
            if (productJson == null) return null;

            JSONObject prod = new JSONObject(productJson);
            if (prod.optBoolean("is_secret", false)) return null;
            if ("dlc".equals(prod.optString("game_type"))) return null;

            JSONObject titleObj = prod.optJSONObject("title");
            String titleStr = titleObj != null ? titleObj.optString("*") : null;
            if (titleStr == null) titleStr = prod.optString("title");
            if (titleStr == null || titleStr.isEmpty()) return null;

            // Try SteamGridDB first for vivid portrait cover art
            String imageUrl = sgdbFetchCover(titleStr);
            if (imageUrl.isEmpty()) {
                JSONObject images = prod.optJSONObject("images");
                imageUrl = images != null ? images.optString("icon", "") : "";
                if (imageUrl == null || imageUrl.isEmpty())
                    imageUrl = images != null ? images.optString("background", "") : "";
                if (imageUrl == null) imageUrl = "";
            }

            JSONObject descObj = prod.optJSONObject("description");
            String desc = descObj != null ? descObj.optString("lead", "") : "";
            if (desc == null) desc = "";

            JSONObject company = prod.optJSONObject("developers");
            String developer = company != null ? company.optString("name", "") : prod.optString("developer", "");
            if (developer == null) developer = "";

            JSONArray genres = prod.optJSONArray("genres");
            String category = "";
            if (genres != null && genres.length() > 0) {
                JSONObject g = genres.optJSONObject(0);
                if (g != null) category = g.optString("name", "");
            }

            int generation = 1;
            try {
                String buildsJson = httpGet(
                        "https://api.gog.com/products/" + id + "/os/windows/builds?generation=2", token);
                if (buildsJson != null) {
                    JSONObject bObj = new JSONObject(buildsJson);
                    JSONArray bitems = bObj.optJSONArray("items");
                    if (bitems != null && bitems.length() > 0) generation = 2;
                }
            } catch (Exception ignored) {}

            prefs.edit().putInt("gog_gen_" + id, generation).apply();
            return new GogGame(id, titleStr, imageUrl, desc, developer, category, generation);
        } catch (Exception e) {
            Log.w(TAG, "fetchGame " + id + " error: " + e.getMessage());
            return null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!allGames.isEmpty()) {
            applyFilter(searchBar != null ? searchBar.getText().toString() : "");
        }
    }

    private void showGames(List<GogGame> games) {
        Collections.sort(games, (a, b) -> a.title.compareToIgnoreCase(b.title));
        allGames = games;
        String query = searchBar != null ? searchBar.getText().toString() : "";
        applyFilter(query);
        scrollView.setVisibility(View.VISIBLE);
    }

    private void applyFilter(String query) {
        List<GogGame> filtered;
        if (query == null || query.trim().isEmpty()) {
            filtered = allGames;
        } else {
            String q = query.trim().toLowerCase();
            filtered = new ArrayList<>();
            for (GogGame g : allGames) {
                if (g.title.toLowerCase().contains(q)) filtered.add(g);
            }
        }
        final List<GogGame> result = filtered;
        uiHandler.post(() -> {
            gameListLayout.removeAllViews();
            if (result.isEmpty()) {
                gameListLayout.setPadding(dp(8), dp(8), dp(8), dp(8));
                TextView emptyTV = new TextView(GogGamesActivity.this);
                String q2 = query == null ? "" : query.trim();
                emptyTV.setText(q2.isEmpty() ? "Your GOG library is empty"
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
                recyclerView.setAdapter(new GogGameAdapter(result));
                gameListLayout.addView(recyclerView);
            }
            scrollView.setVisibility(View.VISIBLE);
        });
    }

    private void enableRefresh() {
        uiHandler.post(() -> { if (refreshBtn != null) refreshBtn.setEnabled(true); });
    }

    private List<GogGame> loadCachedGames() {
        String json = prefs.getString(CACHE_KEY, null);
        if (json == null) return null;
        try {
            JSONArray arr = new JSONArray(json);
            List<GogGame> games = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                games.add(new GogGame(
                        o.getString("gameId"),
                        o.getString("title"),
                        o.optString("imageUrl", ""),
                        o.optString("description", ""),
                        o.optString("developer", ""),
                        o.optString("category", ""),
                        o.optInt("generation", 1)));
            }
            return games;
        } catch (Exception e) { return null; }
    }

    private void saveCachedGames(List<GogGame> games) {
        try {
            JSONArray arr = new JSONArray();
            for (GogGame g : games) {
                JSONObject o = new JSONObject();
                o.put("gameId", g.gameId);
                o.put("title", g.title);
                o.put("imageUrl", g.imageUrl);
                o.put("description", g.description);
                o.put("developer", g.developer);
                o.put("category", g.category);
                o.put("generation", g.generation);
                arr.put(o);
            }
            prefs.edit().putString(CACHE_KEY, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    // ── LIST view: collapsible game cards (v0.3.4 style) ─────────────────────

    private class GogGameAdapter
            extends RecyclerView.Adapter<GogGameAdapter.ViewHolder> {

        private final List<GogGame> games;

        private int expandedPosition = -1;

        GogGameAdapter(List<GogGame> games) {
            this.games = games;
        }

        class ViewHolder extends RecyclerView.ViewHolder {

            LinearLayout card;
            LinearLayout expandSection;

            ImageView coverIV;

            TextView titleTV;
            TextView collapsedCheckTV;
            TextView arrowTV;
            TextView metaTV;
            TextView checkmark;
            TextView pctTV;
            TextView statusTV;

            ProgressBar progressBar;

            Button actionBtn;

            Runnable cancelRunnable;

            public ViewHolder(View itemView) {
                super(itemView);
            }
        }

        @Override
        public int getItemCount() {
            return games.size();
        }

        @Override
        public ViewHolder onCreateViewHolder(
                ViewGroup parent,
                int viewType
        ) {

            // ROOT CARD
            LinearLayout card = new LinearLayout(GogGamesActivity.this);

            card.setOrientation(LinearLayout.VERTICAL);

            card.setPadding(dp(10), dp(10), dp(10), dp(10));

            GradientDrawable cardBg = new GradientDrawable();

            cardBg.setColor(0xFF1A1A2E);

            cardBg.setCornerRadius(dp(6));

            card.setBackground(cardBg);

            card.setFocusable(true);

            card.setDescendantFocusability(
                    ViewGroup.FOCUS_BLOCK_DESCENDANTS
            );

            card.setOnFocusChangeListener((v, hasFocus) -> {

                cardBg.setColor(
                        hasFocus
                                ? 0xFF2A2A4E
                                : 0xFF1A1A2E
                );

                cardBg.setStroke(
                        hasFocus ? dp(3) : 0,
                        hasFocus
                                ? 0xFFFFD700
                                : 0x00000000
                );
            });

            RecyclerView.LayoutParams cardLp =
                    new RecyclerView.LayoutParams(-1, -2);

            cardLp.bottomMargin = dp(8);

            card.setLayoutParams(cardLp);

            ViewHolder h = new ViewHolder(card);

            h.card = card;

            // TOP ROW
            LinearLayout topRow = new LinearLayout(
                    GogGamesActivity.this
            );

            topRow.setOrientation(LinearLayout.HORIZONTAL);

            topRow.setGravity(Gravity.CENTER_VERTICAL);

            // COVER
            h.coverIV = new ImageView(GogGamesActivity.this);

            h.coverIV.setScaleType(
                    ImageView.ScaleType.CENTER_CROP
            );

            GradientDrawable coverBg = new GradientDrawable();

            coverBg.setColor(0xFF111122);

            coverBg.setCornerRadius(dp(4));

            h.coverIV.setBackground(coverBg);

            LinearLayout.LayoutParams coverLp =
                    new LinearLayout.LayoutParams(
                            dp(60),
                            dp(60)
                    );

            coverLp.rightMargin = dp(10);

            topRow.addView(h.coverIV, coverLp);

            // INFO COLUMN
            LinearLayout infoCol = new LinearLayout(
                    GogGamesActivity.this
            );

            infoCol.setOrientation(LinearLayout.VERTICAL);

            infoCol.setGravity(Gravity.CENTER_VERTICAL);

            // TITLE ROW
            LinearLayout titleRow = new LinearLayout(
                    GogGamesActivity.this
            );

            titleRow.setOrientation(LinearLayout.HORIZONTAL);

            titleRow.setGravity(Gravity.CENTER_VERTICAL);

            h.titleTV = new TextView(GogGamesActivity.this);

            h.titleTV.setTextColor(0xFFFFFFFF);

            h.titleTV.setTextSize(15f);

            h.titleTV.setTypeface(null, Typeface.BOLD);

            h.titleTV.setMaxLines(1);

            h.titleTV.setEllipsize(TextUtils.TruncateAt.END);

            titleRow.addView(
                    h.titleTV,
                    new LinearLayout.LayoutParams(-2, -2)
            );

            h.collapsedCheckTV = new TextView(
                    GogGamesActivity.this
            );

            h.collapsedCheckTV.setText(" ✓");

            h.collapsedCheckTV.setTextColor(0xFF4CAF50);

            h.collapsedCheckTV.setTextSize(14f);

            h.collapsedCheckTV.setTypeface(null, Typeface.BOLD);

            titleRow.addView(
                    h.collapsedCheckTV,
                    new LinearLayout.LayoutParams(-2, -2)
            );

            View spacer = new View(GogGamesActivity.this);

            titleRow.addView(
                    spacer,
                    new LinearLayout.LayoutParams(0, 0, 1f)
            );

            infoCol.addView(
                    titleRow,
                    new LinearLayout.LayoutParams(-1, -2)
            );

            topRow.addView(
                    infoCol,
                    new LinearLayout.LayoutParams(0, -2, 1f)
            );

            // ARROW
            h.arrowTV = new TextView(GogGamesActivity.this);

            h.arrowTV.setText("▼");

            h.arrowTV.setTextColor(0xFF888888);

            h.arrowTV.setTextSize(14f);

            h.arrowTV.setPadding(dp(8), 0, 0, 0);

            topRow.addView(
                    h.arrowTV,
                    new LinearLayout.LayoutParams(-2, -2)
            );

            card.addView(
                    topRow,
                    new LinearLayout.LayoutParams(-1, -2)
            );

            // EXPAND SECTION
            h.expandSection = new LinearLayout(
                    GogGamesActivity.this
            );

            h.expandSection.setOrientation(
                    LinearLayout.VERTICAL
            );

            h.expandSection.setVisibility(View.GONE);

            // META
            h.metaTV = new TextView(GogGamesActivity.this);

            h.metaTV.setTextColor(0xFF888888);

            h.metaTV.setTextSize(11f);

            LinearLayout.LayoutParams metaLp =
                    new LinearLayout.LayoutParams(-1, -2);

            metaLp.topMargin = dp(6);

            h.expandSection.addView(h.metaTV, metaLp);

            // CHECKMARK
            h.checkmark = new TextView(
                    GogGamesActivity.this
            );

            h.checkmark.setText("✓ Installed");

            h.checkmark.setTextColor(0xFF4CAF50);

            h.checkmark.setTextSize(10f);

            LinearLayout.LayoutParams ckLp =
                    new LinearLayout.LayoutParams(-1, -2);

            ckLp.topMargin = dp(4);

            h.expandSection.addView(h.checkmark, ckLp);

            // PROGRESS
            h.progressBar = new ProgressBar(
                    GogGamesActivity.this,
                    null,
                    android.R.attr.progressBarStyleHorizontal
            );

            h.progressBar.setMax(100);

            h.progressBar.setVisibility(View.GONE);

            h.progressBar.getProgressDrawable().setColorFilter(
                    0xFFFF9800,
                    PorterDuff.Mode.SRC_IN
            );

            LinearLayout.LayoutParams pbLp =
                    new LinearLayout.LayoutParams(
                            -1,
                            dp(6)
                    );

            pbLp.topMargin = dp(6);

            h.expandSection.addView(h.progressBar, pbLp);

            // PERCENT
            h.pctTV = new TextView(GogGamesActivity.this);

            h.pctTV.setTextColor(0xFFFF9800);

            h.pctTV.setTextSize(12f);

            h.pctTV.setTypeface(null, Typeface.BOLD);

            h.pctTV.setVisibility(View.GONE);

            h.expandSection.addView(h.pctTV);

            // STATUS
            h.statusTV = new TextView(
                    GogGamesActivity.this
            );

            h.statusTV.setTextColor(0xFFAAAAAA);

            h.statusTV.setTextSize(11f);

            h.statusTV.setVisibility(View.GONE);

            LinearLayout.LayoutParams stLp =
                    new LinearLayout.LayoutParams(-1, -2);

            stLp.topMargin = dp(2);

            h.expandSection.addView(h.statusTV, stLp);

            // BUTTON
            h.actionBtn = new Button(
                    GogGamesActivity.this
            );

            h.actionBtn.setTextColor(0xFFFFFFFF);

            h.actionBtn.setTextSize(13f);

            LinearLayout.LayoutParams abLp =
                    new LinearLayout.LayoutParams(
                            -1,
                            dp(40)
                    );

            abLp.topMargin = dp(8);

            h.expandSection.addView(h.actionBtn, abLp);

            card.addView(
                    h.expandSection,
                    new LinearLayout.LayoutParams(-1, -2)
            );

            return h;
        }

        @Override
        public void onBindViewHolder(
                ViewHolder h,
                int position
        ) {

            GogGame game = games.get(position);

            boolean isInstalled =
                    prefs.getString(
                            "gog_exe_" + game.gameId,
                            null
                    ) != null;

            boolean expanded = expandedPosition == position;

            h.expandSection.setVisibility(
                    expanded
                            ? View.VISIBLE
                            : View.GONE
            );

            h.arrowTV.setText(
                    expanded
                            ? "▲"
                            : "▼"
            );

            h.titleTV.setText(game.title);

            h.collapsedCheckTV.setVisibility(
                    isInstalled
                            ? View.VISIBLE
                            : View.GONE
            );

            h.checkmark.setVisibility(
                    isInstalled
                            ? View.VISIBLE
                            : View.GONE
            );

            if (!game.category.isEmpty()
                    || !game.developer.isEmpty()) {

                String meta = game.category.isEmpty()
                        ? game.developer
                        : game.developer.isEmpty()
                        ? game.category
                        : game.category
                        + " · "
                        + game.developer;

                h.metaTV.setText(meta);

                h.metaTV.setVisibility(View.VISIBLE);

            } else {

                h.metaTV.setVisibility(View.GONE);
            }

            h.actionBtn.setText(
                    isInstalled
                            ? "Add to Launcher"
                            : "Install"
            );

            h.actionBtn.setBackgroundColor(
                    isInstalled
                            ? 0xFF2E7D32
                            : 0xFF7033FF
            );

            loadImage(game, h.coverIV);

            h.card.setOnClickListener(v -> {

                if (expandedPosition == position) {

                    openDetailScreen(game);

                } else {

                    int old = expandedPosition;

                    expandedPosition = position;

                    if (old != -1)
                        notifyItemChanged(old);

                    notifyItemChanged(position);
                }
            });

            h.arrowTV.setOnClickListener(v -> {

                if (expandedPosition == position) {

                    expandedPosition = -1;

                    notifyItemChanged(position);
                }
            });

            h.actionBtn.setOnClickListener(v -> {

                String label =
                        h.actionBtn.getText().toString();

                if ("Cancel".equals(label)) {

                    if (h.cancelRunnable != null)
                        h.cancelRunnable.run();

                    return;
                }

                if ("Add Game".equals(label)
                        || "Add to Launcher".equals(label)) {

                    String exePath =
                            prefs.getString(
                                    "gog_exe_" + game.gameId,
                                    null
                            );

                    if (exePath != null) {

                        GogLaunchHelper.addToLauncher(
                                GogGamesActivity.this,
                                game.title,
                                exePath
                        );
                    }

                    return;
                }

                showInstallConfirm(game, () -> {

                    h.actionBtn.setText("Cancel");

                    h.actionBtn.setBackgroundColor(
                            0xFFCC3333
                    );

                    h.progressBar.setVisibility(
                            View.VISIBLE
                    );

                    h.statusTV.setVisibility(
                            View.VISIBLE
                    );

                    h.pctTV.setVisibility(
                            View.VISIBLE
                    );

                    h.pctTV.setText("0%");

                    String dlKey =
                            "gog-" + game.gameId + "-list";

                    StoreDownloadQueue.addListener(
                            dlKey,
                            new StoreDownloadQueue.DownloadListener() {

                                @Override
                                public void onProgress(
                                        String msg,
                                        int pct
                                ) {

                                    uiHandler.post(() -> {

                                        h.statusTV.setText(msg);

                                        h.progressBar.setProgress(pct);

                                        h.pctTV.setText(
                                                pct + "%"
                                        );
                                    });
                                }

                                @Override
                                public void onComplete(
                                        String exePath
                                ) {

                                    uiHandler.post(() -> {

                                        h.progressBar.setProgress(100);

                                        h.pctTV.setVisibility(
                                                View.GONE
                                        );

                                        h.checkmark.setVisibility(
                                                View.VISIBLE
                                        );

                                        h.collapsedCheckTV.setVisibility(
                                                View.VISIBLE
                                        );

                                        h.statusTV.setText(
                                                "Installed"
                                        );

                                        h.actionBtn.setText(
                                                "Add Game"
                                        );

                                        h.actionBtn.setBackgroundColor(
                                                0xFF2E7D32
                                        );
                                    });
                                }

                                @Override
                                public void onError(String msg) {

                                    uiHandler.post(() -> {

                                        h.pctTV.setVisibility(
                                                View.GONE
                                        );

                                        h.statusTV.setText(
                                                "Error: " + msg
                                        );

                                        h.actionBtn.setText(
                                                "Install"
                                        );

                                        h.actionBtn.setBackgroundColor(
                                                0xFF7033FF
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

                                        h.pctTV.setVisibility(
                                                View.GONE
                                        );

                                        h.statusTV.setText("");

                                        h.actionBtn.setText(
                                                "Install"
                                        );

                                        h.actionBtn.setBackgroundColor(
                                                0xFF7033FF
                                        );
                                    });
                                }
                            }
                    );

                    StoreDownloadQueue.startGog(
                            GogGamesActivity.this,
                            game,
                            dlKey
                    );

                    h.cancelRunnable = () ->
                            StoreDownloadQueue.cancel(
                                    GogGamesActivity.this,
                                    dlKey
                            );
                });
            });
        }
    }

    // ── Dialogs (list view detail) ────────────────────────────────────────────

    private void showDetailDialog(GogGame game, View checkmark, Button actionBtn, Runnable onUninstalled) {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(game.title);

        // Custom view: message text + optional Set .exe button
        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dp(20);
        container.setPadding(pad, dp(8), pad, dp(4));

        StringBuilder msg = new StringBuilder();
        if (!game.developer.isEmpty()) msg.append("Developer: ").append(game.developer).append("\n");
        if (!game.category.isEmpty())  msg.append("Genre: ").append(game.category).append("\n");
        if (!game.description.isEmpty()) msg.append("\n").append(game.description);

        android.widget.TextView msgView = new android.widget.TextView(this);
        msgView.setText(msg.toString().trim());
        msgView.setTextColor(0xFFCCCCCC);
        container.addView(msgView);

        String installedExe = prefs.getString("gog_exe_" + game.gameId, null);
        String dirName      = prefs.getString("gog_dir_" + game.gameId, null);

        if (installedExe != null && dirName != null) {
            android.widget.TextView exeView = new android.widget.TextView(this);
            exeView.setText("\n.exe: " + new java.io.File(installedExe).getName());
            exeView.setTextColor(0xFF888888);
            exeView.setTextSize(12f);
            container.addView(exeView);

            Button setExeBtn = new Button(this);
            setExeBtn.setText("Set .exe\u2026");
            setExeBtn.setTextColor(0xFFFFFFFF);
            setExeBtn.setBackgroundColor(0xFF444444);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = dp(10);
            setExeBtn.setOnClickListener(v -> {
                java.io.File installPath = GogInstallPath.getInstallDir(this, dirName);
                new Thread(() -> {
                    java.util.List<String> candidates =
                            GogDownloadManager.collectExeCandidates(installPath);
                    if (candidates.isEmpty()) {
                        uiHandler.post(() -> Toast.makeText(this,
                                "No .exe files found in install directory",
                                Toast.LENGTH_SHORT).show());
                        return;
                    }
                    showExePicker(candidates, selected -> {
                        if (selected != null && !selected.isEmpty()) {
                            prefs.edit().putString("gog_exe_" + game.gameId, selected).apply();
                            uiHandler.post(() -> {
                                exeView.setText("\n.exe: " + new java.io.File(selected).getName());
                                Toast.makeText(this,
                                        "Exe set to: " + new java.io.File(selected).getName(),
                                        Toast.LENGTH_SHORT).show();
                            });
                        }
                    });
                }).start();
            });
            container.addView(setExeBtn, lp);

            b.setNegativeButton("Uninstall", (dialog, which) -> uninstall(game, onUninstalled));
            b.setNeutralButton("Copy to Downloads", (dialog, which) -> copyToDownloads(game));
        }

        b.setView(container);
        b.setPositiveButton("Close", null);
        b.show();
    }

    private void uninstall(GogGame game, Runnable onUninstalled) {
        String dirName = prefs.getString("gog_dir_" + game.gameId, null);
        if (dirName != null) {
            new Thread(() -> {
                java.io.File installPath = GogInstallPath.getInstallDir(this, dirName);
                deleteDir(installPath);
                prefs.edit()
                        .remove("gog_dir_" + game.gameId)
                        .remove("gog_exe_" + game.gameId)
                        .remove("gog_cover_" + game.gameId)
                        .apply();
                uiHandler.post(() -> {
                    onUninstalled.run();
                    Toast.makeText(this, game.title + " uninstalled", Toast.LENGTH_SHORT).show();
                });
            }).start();
        }
    }

    private void copyToDownloads(GogGame game) {
        Toast.makeText(this, "Copying to Downloads…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String dest = GogDownloadManager.copyToDownloads(this, game.gameId);
            uiHandler.post(() -> {
                if (dest != null) {
                    Toast.makeText(this, "Copied to: " + dest, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Copy failed — check storage permission",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private TextView makeGenBadge(int generation) {
        TextView badge = new TextView(this);
        badge.setText("Gen " + generation);
        badge.setTextSize(10f);
        badge.setTextColor(0xFFFFFFFF);
        badge.setPadding(dp(5), dp(2), dp(5), dp(2));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(generation == 2 ? 0xFF4FC3F7 : 0xFFFF9800);
        bg.setCornerRadius(dp(3));
        badge.setBackground(bg);
        return badge;
    }

    private LinearLayout.LayoutParams makeGenBadgeLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(6);
        return lp;
    }

    private void loadImage(GogGame game, ImageView iv) {
        if (game.imageUrl == null || game.imageUrl.isEmpty()) return;
        String url = game.imageUrl.startsWith("//") ? "https:" + game.imageUrl : game.imageUrl;
        new Thread(() -> {
            try {
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "GOG Galaxy");
                if (conn.getResponseCode() == 200) {
                    Bitmap bmp = BitmapFactory.decodeStream(conn.getInputStream());
                    if (bmp != null) uiHandler.post(() -> iv.setImageBitmap(bmp));
                }
                conn.disconnect();
            } catch (Exception ignored) {}
        }, "gog-cover-" + game.gameId).start();
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private void setSync(String msg) {
        uiHandler.post(() -> {
            syncText.setText(msg);
            if (msg.startsWith("Error") || msg.startsWith("Session expired")
                    || msg.startsWith("Failed") || msg.startsWith("Not logged in")) {
                syncText.setTextColor(0xFFFF6B6B);
            } else if (msg.contains("game") && (msg.contains("tap") || msg.contains("cached"))) {
                syncText.setTextColor(0xFF81C784);
            } else {
                syncText.setTextColor(0xFFCCCCCC);
            }
        });
    }

    /** Shows a pre-install confirmation dialog with game size (async-fetched) and available storage. */
    private void showInstallConfirm(GogGame game, Runnable onConfirm) {
        long freeBytes = -1;
        try {
            java.io.File installBase = GogInstallPath.getInstallDir(this, "_check");
            java.io.File parent = installBase.getParentFile();
            if (parent != null) parent.mkdirs();
            android.os.StatFs sf = new android.os.StatFs(
                    parent != null ? parent.getAbsolutePath() : getCacheDir().getAbsolutePath());
            freeBytes = sf.getAvailableBlocksLong() * sf.getBlockSizeLong();
        } catch (Exception ignored) {}

        final long finalFree = freeBytes;

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(8));

        TextView gameSizeTV = new TextView(this);
        gameSizeTV.setText("Game size:  Fetching…");
        gameSizeTV.setTextColor(0xFFCCCCCC);
        gameSizeTV.setTextSize(14f);
        content.addView(gameSizeTV);

        TextView freeTV = new TextView(this);
        freeTV.setText("Available storage:  " + GogDownloadManager.formatBytes(finalFree));
        freeTV.setTextColor(0xFF88CC88);
        freeTV.setTextSize(14f);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(-2, -2);
        tvLp.topMargin = dp(6);
        content.addView(freeTV, tvLp);

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

        new Thread(() -> {
            long size = GogDownloadManager.fetchGameSize(this, game);
            runOnUiThread(() -> {
                if (!dialog.isShowing()) return;
                gameSizeTV.setText("Game size:  " + GogDownloadManager.formatBytes(size));
                if (size > 0 && finalFree > 0 && size > finalFree) {
                    gameSizeTV.setTextColor(0xFFFF5252);
                    gameSizeTV.setText("Game size:  " + GogDownloadManager.formatBytes(size)
                            + "  ⚠ Not enough space");
                    freeTV.setTextColor(0xFFFF5252);
                }
            });
        }).start();
    }

    /**
     * Shows a dialog letting the user pick from multiple exe candidates.
     * {@code candidates} contains absolute paths; display name is the last 2 path segments.
     * Calls {@code onSelected} with the chosen absolute path on the background thread.
     */
    private void showExePicker(java.util.List<String> candidates,
                                java.util.function.Consumer<String> onSelected) {
        String[] labels = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            java.io.File f = new java.io.File(candidates.get(i));
            java.io.File parent = f.getParentFile();
            labels[i] = (parent != null) ? parent.getName() + "/" + f.getName() : f.getName();
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

    private static String httpGet(String url, String token) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token);
            if (conn.getResponseCode() != 200) { conn.disconnect(); return null; }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            conn.disconnect();
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    private static final String SGDB_KEY = "cf89227f12c773bb1117b6b109ae1659";

    /** Returns the first SteamGridDB 600x900 cover URL for the given game title, or "" on failure. */
    private static String sgdbFetchCover(String title) {
        try {
            String encoded = java.net.URLEncoder.encode(title, "UTF-8");
            String searchJson = httpGet(
                    "https://www.steamgriddb.com/api/v2/search/autocomplete/" + encoded, SGDB_KEY);
            if (searchJson == null) return "";
            JSONArray results = new JSONObject(searchJson).optJSONArray("data");
            if (results == null || results.length() == 0) return "";
            int gameId = results.getJSONObject(0).getInt("id");

            String gridsJson = httpGet(
                    "https://www.steamgriddb.com/api/v2/grids/game/" + gameId
                            + "?dimensions=600x900&mimes=image/jpeg,image/png&limit=1",
                    SGDB_KEY);
            if (gridsJson == null) return "";
            JSONArray grids = new JSONObject(gridsJson).optJSONArray("data");
            if (grids == null || grids.length() == 0) return "";
            return grids.getJSONObject(0).optString("url", "");
        } catch (Exception e) { return ""; }
    }

    private static void deleteDir(java.io.File dir) {
        if (dir == null || !dir.exists()) return;
        java.io.File[] children = dir.listFiles();
        if (children != null) for (java.io.File c : children) deleteDir(c);
        dir.delete();
    }
    // ── Full-screen detail ────────────────────────────────────────────────────

    private void openDetailScreen(GogGame game) {
        Intent intent = new Intent(this, GogGameDetailActivity.class);
        intent.putExtra("game_id",     game.gameId);
        intent.putExtra("title",       game.title);
        intent.putExtra("image_url",   game.imageUrl);
        intent.putExtra("description", game.description);
        intent.putExtra("developer",   game.developer);
        intent.putExtra("category",    game.category);
        intent.putExtra("generation",  game.generation);
        startActivityForResult(intent, REQ_GAME_DETAIL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_GAME_DETAIL && resultCode == GogGameDetailActivity.RESULT_REFRESH) {
            applyFilter(searchBar != null ? searchBar.getText().toString() : "");
        }
    }
}