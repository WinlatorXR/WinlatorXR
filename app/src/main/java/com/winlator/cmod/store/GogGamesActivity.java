package com.winlator.cmod.store;

import android.content.SharedPreferences;
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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import android.content.Intent;

import androidx.recyclerview.widget.GridLayoutManager;
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

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private TextView syncText;
    private LinearLayout gameListLayout;
    private ScrollView scrollView;
    private SharedPreferences prefs;
    private Button refreshBtn;
    private EditText searchBar;
    private List<GogGame> allGames = new ArrayList<>();

    private enum Filter { ALL, INSTALLED, NOT_INSTALLED }
    private Filter filter = Filter.ALL;
    private boolean sortAsc = true;  // Title A→Z / Z→A (GOG model has no size to sort on)

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

        header.addView(StoreGridUi.backButton(this, v -> goBack()),
                new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView titleTV = new TextView(this);
        titleTV.setText("GOG Library");
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
            new android.app.AlertDialog.Builder(GogGamesActivity.this)
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

        // Filter + sort controls (right-aligned, mirrors the other stores)
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        controls.setBackgroundColor(0xFF0D0D0D);
        controls.setPadding(dp(8), dp(8), dp(8), dp(4));
        TextView filterBtn = StoreGridUi.pillButton(this, "Filter: All");
        TextView dirBtn = StoreGridUi.pillButton(this, "Title ↑");
        filterBtn.setOnClickListener(v -> showFilterMenu(filterBtn));
        dirBtn.setOnClickListener(v -> {
            sortAsc = !sortAsc;
            dirBtn.setText(sortAsc ? "Title ↑" : "Title ↓");
            applyFilter(currentQuery());
        });
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(-2, -2);
        fLp.rightMargin = dp(8);
        controls.addView(filterBtn, fLp);
        controls.addView(dirBtn, new LinearLayout.LayoutParams(-2, -2));
        root.addView(controls, new LinearLayout.LayoutParams(-1, -2));

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

    private void signOut() {
        getSharedPreferences("bh_gog_prefs", 0).edit().clear().apply();
        finish();
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
        List<GogGame> base;
        if (query == null || query.trim().isEmpty()) {
            base = allGames;
        } else {
            String q = query.trim().toLowerCase();
            base = new ArrayList<>();
            for (GogGame g : allGames) {
                if (g.title.toLowerCase().contains(q)) base.add(g);
            }
        }
        List<GogGame> filtered = new ArrayList<>();
        for (GogGame g : base) {
            boolean inst = isInstalled(g);
            if (filter == Filter.INSTALLED && !inst) continue;
            if (filter == Filter.NOT_INSTALLED && inst) continue;
            filtered.add(g);
        }
        Collections.sort(filtered, (a, b) -> a.title.compareToIgnoreCase(b.title));
        if (!sortAsc) Collections.reverse(filtered);

        final List<GogGame> result = filtered;
        uiHandler.post(() -> {
            gameListLayout.removeAllViews();
            if (result.isEmpty()) {
                gameListLayout.setPadding(dp(8), dp(8), dp(8), dp(8));
                TextView emptyTV = new TextView(GogGamesActivity.this);
                String q2 = query == null ? "" : query.trim();
                emptyTV.setText(!q2.isEmpty() ? "No results for \u201c" + q2 + "\u201d"
                        : filter != Filter.ALL ? "No games match the current filter."
                        : "Your GOG library is empty");
                emptyTV.setTextColor(0xFF666666);
                emptyTV.setTextSize(14f);
                emptyTV.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams emLp = new LinearLayout.LayoutParams(-1, -2);
                emLp.topMargin = dp(32);
                gameListLayout.addView(emptyTV, emLp);
            } else {
                gameListLayout.setPadding(dp(8), dp(8), dp(8), dp(8));

                RecyclerView recyclerView = new RecyclerView(this);
                recyclerView.setLayoutManager(new GridLayoutManager(this, StoreGridUi.COLUMNS));
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

    // ── GRID view: shared store cells + per-store actions ─────────────────────

    private boolean isInstalled(GogGame g) {
        return prefs.getString("gog_exe_" + g.gameId, null) != null;
    }

    private String currentQuery() {
        return searchBar != null ? searchBar.getText().toString() : "";
    }

    private void launchGog(GogGame g) {
        String exe = prefs.getString("gog_exe_" + g.gameId, null);
        if (exe == null) { openDetailScreen(g); return; }
        GogLaunchHelper.addToLauncher(this, g.title, exe);
    }

    private void uninstallGog(GogGame g) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Uninstall " + g.title + "?")
                .setMessage("This will delete all installed game files.")
                .setPositiveButton("Uninstall", (d, w) -> {
                    String dir = prefs.getString("gog_dir_" + g.gameId, null);
                    new Thread(() -> {
                        if (dir != null) StoreGridUi.deleteDir(new File(dir));
                        prefs.edit()
                                .remove("gog_dir_" + g.gameId)
                                .remove("gog_exe_" + g.gameId)
                                .remove("gog_cover_" + g.gameId)
                                .apply();
                        uiHandler.post(() -> {
                            Toast.makeText(this, g.title + " uninstalled", Toast.LENGTH_SHORT).show();
                            applyFilter(currentQuery());
                        });
                    }).start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showFilterMenu(TextView anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 0, 0, "All");
        pm.getMenu().add(0, 1, 1, "Installed");
        pm.getMenu().add(0, 2, 2, "Not installed");
        pm.getMenu().setGroupCheckable(0, true, true);
        pm.getMenu().getItem(filter.ordinal()).setChecked(true);
        pm.setOnMenuItemClickListener(item -> {
            filter = Filter.values()[item.getItemId()];
            anchor.setText("Filter: " + (filter == Filter.ALL ? "All"
                    : filter == Filter.INSTALLED ? "Installed" : "Not installed"));
            applyFilter(currentQuery());
            return true;
        });
        pm.show();
    }

    private class GogGameAdapter extends RecyclerView.Adapter<GogGameAdapter.VH> {
        private final List<GogGame> games;

        GogGameAdapter(List<GogGame> games) { this.games = games; }

        class VH extends RecyclerView.ViewHolder {
            final StoreGridUi.Cell cell;
            VH(StoreGridUi.Cell cell) { super(cell.root); this.cell = cell; }
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            StoreGridUi.Cell cell = StoreGridUi.buildCell(GogGamesActivity.this);
            cell.root.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new VH(cell);
        }

        @Override
        public void onBindViewHolder(VH h, int position) {
            GogGame game = games.get(position);
            h.cell.name.setText(game.title);
            loadImage(game, h.cell.art);

            boolean installed = isInstalled(game);
            StoreGridUi.setInstalled(h.cell, installed);
            if (installed) {
                h.cell.launch.setOnClickListener(v -> launchGog(game));
                h.cell.uninstall.setOnClickListener(v -> uninstallGog(game));
            } else {
                h.cell.launch.setOnClickListener(null);
                h.cell.uninstall.setOnClickListener(null);
            }
            h.cell.root.setOnClickListener(v -> openDetailScreen(game));
        }

        @Override
        public int getItemCount() { return games.size(); }
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private void loadImage(GogGame game, ImageView iv) {
        String url = game.imageUrl;
        if (url != null && url.startsWith("//")) url = "https:" + url;
        StoreImageLoader.load(iv, url, "GOG Galaxy");
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