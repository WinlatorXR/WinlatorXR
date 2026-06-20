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
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
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
    private static final int COLOR_ROOT_BG  = 0xFF0D0D0D;
    private static final int REQ_GAME_DETAIL  = 1001;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private TextView    syncText;
    private LinearLayout gameListLayout;
    private ScrollView  scrollView;
    private Button      refreshBtn;
    private EditText    searchBar;
    private List<AmazonGame> allGames = new ArrayList<>();

    private enum Filter { ALL, INSTALLED, NOT_INSTALLED }
    private enum Sort { TITLE, SIZE }
    private Filter filter = Filter.ALL;
    private Sort sort = Sort.TITLE;
    private boolean sortAsc = true;

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

        header.addView(StoreGridUi.backButton(this, v -> goBack()),
                new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView titleTV = new TextView(this);
        titleTV.setText("Amazon Games");
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
            new android.app.AlertDialog.Builder(AmazonGamesActivity.this)
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

        // Filter + sort controls (right-aligned)
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        controls.setBackgroundColor(COLOR_ROOT_BG);
        controls.setPadding(dp(8), dp(8), dp(8), dp(4));
        TextView filterBtn = StoreGridUi.pillButton(this, "Filter: All");
        TextView sortBtn = StoreGridUi.pillButton(this, "Sort: Title");
        TextView dirBtn = StoreGridUi.pillButton(this, "↑");
        filterBtn.setOnClickListener(v -> showFilterMenu(filterBtn));
        sortBtn.setOnClickListener(v -> showSortMenu(sortBtn));
        dirBtn.setOnClickListener(v -> {
            sortAsc = !sortAsc;
            dirBtn.setText(sortAsc ? "↑" : "↓");
            applyFilter(currentQuery());
        });
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(-2, -2);
        p1.rightMargin = dp(8);
        controls.addView(filterBtn, p1);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(-2, -2);
        p2.rightMargin = dp(8);
        controls.addView(sortBtn, p2);
        controls.addView(dirBtn, new LinearLayout.LayoutParams(-2, -2));
        root.addView(controls, new LinearLayout.LayoutParams(-1, -2));

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

    private void signOut() {
        AmazonCredentialStore.Credentials creds = AmazonCredentialStore.load(this);
        if (creds != null && creds.accessToken != null) {
            String token = creds.accessToken;
            new Thread(() -> AmazonAuthClient.deregisterDevice(token)).start();
        }
        AmazonCredentialStore.clear(this);
        finish();
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
        List<AmazonGame> base;
        if (query == null || query.trim().isEmpty()) {
            base = allGames;
        } else {
            String q = query.trim().toLowerCase();
            base = new ArrayList<>();
            for (AmazonGame g : allGames)
                if (g.title.toLowerCase().contains(q)) base.add(g);
        }
        List<AmazonGame> filtered = new ArrayList<>();
        for (AmazonGame g : base) {
            boolean inst = isInstalled(g);
            if (filter == Filter.INSTALLED && !inst) continue;
            if (filter == Filter.NOT_INSTALLED && inst) continue;
            filtered.add(g);
        }
        Collections.sort(filtered, (a, b) -> sort == Sort.SIZE
                ? Long.compare(a.installSize, b.installSize)
                : a.title.compareToIgnoreCase(b.title));
        if (!sortAsc) Collections.reverse(filtered);

        final List<AmazonGame> result = filtered;
        uiHandler.post(() -> {
            gameListLayout.removeAllViews();
            if (result.isEmpty()) {
                gameListLayout.setPadding(dp(8), dp(8), dp(8), dp(8));
                TextView emptyTV = new TextView(AmazonGamesActivity.this);
                String q2 = query == null ? "" : query.trim();
                emptyTV.setText(!q2.isEmpty() ? "No results for \u201c" + q2 + "\u201d"
                        : filter != Filter.ALL ? "No games match the current filter."
                        : "Your Amazon library is empty");
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
                recyclerView.setAdapter(new AmazonGamesAdapter(result));
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

    // ── GRID view: shared store cells + per-store actions ─────────────────────

    private boolean isInstalled(AmazonGame g) {
        return prefs.getString("amazon_exe_" + g.productId, null) != null;
    }

    private String currentQuery() {
        return searchBar != null ? searchBar.getText().toString() : "";
    }

    private void launchAmazon(AmazonGame g) {
        String exe = prefs.getString("amazon_exe_" + g.productId, null);
        if (exe == null) { openDetailScreen(g); return; }
        LudashiLaunchBridge.addToLauncher(this, g.title, exe);
    }

    private void uninstallAmazon(AmazonGame g) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Uninstall " + g.title + "?")
                .setMessage("This will delete all installed game files.")
                .setPositiveButton("Uninstall", (d, w) -> {
                    String dir = prefs.getString("amazon_dir_" + g.productId, null);
                    new Thread(() -> {
                        if (dir != null) StoreGridUi.deleteDir(new File(dir));
                        prefs.edit()
                                .remove("amazon_exe_" + g.productId)
                                .remove("amazon_dir_" + g.productId)
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

    private void showSortMenu(TextView anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 0, 0, "Title");
        pm.getMenu().add(0, 1, 1, "Size");
        pm.getMenu().setGroupCheckable(0, true, true);
        pm.getMenu().getItem(sort.ordinal()).setChecked(true);
        pm.setOnMenuItemClickListener(item -> {
            sort = Sort.values()[item.getItemId()];
            anchor.setText("Sort: " + (sort == Sort.SIZE ? "Size" : "Title"));
            applyFilter(currentQuery());
            return true;
        });
        pm.show();
    }

    public class AmazonGamesAdapter extends RecyclerView.Adapter<AmazonGamesAdapter.VH> {
        private final List<AmazonGame> games;

        AmazonGamesAdapter(List<AmazonGame> games) {
            this.games = games;
            setHasStableIds(true);
        }

        @Override
        public long getItemId(int position) {
            return games.get(position).productId.hashCode();
        }

        class VH extends RecyclerView.ViewHolder {
            final StoreGridUi.Cell cell;
            VH(StoreGridUi.Cell cell) { super(cell.root); this.cell = cell; }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            StoreGridUi.Cell cell = StoreGridUi.buildCell(AmazonGamesActivity.this);
            cell.root.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new VH(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            AmazonGame game = games.get(position);
            h.cell.name.setText(game.title);
            h.cell.art.setImageDrawable(null);
            loadImage(game, h.cell.art);

            boolean installed = isInstalled(game);
            StoreGridUi.setInstalled(h.cell, installed);
            if (installed) {
                h.cell.launch.setOnClickListener(v -> launchAmazon(game));
                h.cell.uninstall.setOnClickListener(v -> uninstallAmazon(game));
            } else {
                h.cell.launch.setOnClickListener(null);
                h.cell.uninstall.setOnClickListener(null);
            }
            h.cell.root.setOnClickListener(v -> openDetailScreen(game));
        }

        @Override
        public int getItemCount() { return games.size(); }
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