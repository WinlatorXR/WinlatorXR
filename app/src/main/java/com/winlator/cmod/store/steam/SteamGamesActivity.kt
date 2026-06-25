package com.winlator.cmod.store

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.winlator.cmod.NavActivity
import com.winlator.cmod.R
import java.util.concurrent.Executors

/**
 * Steam library screen — shows only type="game" entries.
 *
 * Each row shows the Steam library portrait art (600x900) loaded async,
 * falling back to the header image (header.jpg) if portrait isn't available.
 */
class SteamGamesActivity : NavActivity(), SteamRepository.SteamEventListener {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var searchBar: EditText
    private lateinit var gridView: GridView
    private lateinit var emptyText: TextView
    private var games: List<SteamGame> = emptyList()
    private var searchQuery: String = ""
    private var installFilter = InstallFilter.ALL
    private var sortKey = SortKey.TITLE
    private var sortAsc = true

    private enum class InstallFilter { ALL, INSTALLED, NOT_INSTALLED }
    private enum class SortKey { TITLE, SIZE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUI())
        SteamRepository.getInstance().addListener(this)
        loadGames()
        maybeAutoSync()
    }

    override fun onResume() {
        super.onResume()
        // Refresh list from cache when returning from detail screen (installed state may have changed)
        loadGames()
    }

    override fun onDestroy() {
        SteamRepository.getInstance().removeListener(this)
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // SteamRepository.SteamEventListener
    // -------------------------------------------------------------------------

    override fun onEvent(event: String) {
        when {
            event.startsWith("LibraryProgress:") -> {
                val parts = event.split(":")
                val phase = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val count = parts.getOrNull(2)?.toIntOrNull() ?: 0
                ui.post {
                    statusText.text = if (phase == 0)
                        "Syncing packages ($count)…"
                    else
                        "Fetching $count app records…"
                }
            }
            event.startsWith("LibrarySynced:") -> {
                // Reload from DB and derive count from what's actually showing —
                // the event count can be 0 if Steam returned empty "no change" buffers
                // for apps that haven't changed since last request.
                ui.post {
                    loadGames()
                    statusText.text = "${games.size} games in library"
                }
            }
            event == "LoggedOut" -> {
                ui.post { finish() }
            }
            event == "Disconnected" -> {
                // Transient disconnect — auto-reconnect is in progress.
                // Don't close the activity; just show status.
                ui.post { statusText.text = "Disconnected — reconnecting…" }
            }
            event == "Connected" -> {
                // After reconnect, retry sync if still empty.
                val repo = SteamRepository.getInstance()
                if (games.isEmpty() && repo.isLoggedIn) {
                    ui.post { statusText.text = "Reconnected — syncing library…" }
                    repo.syncLibrary()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Data — only show type="game" entries
    // -------------------------------------------------------------------------

    private fun loadGames() {
        val repo = try {
            SteamRepository.getInstance()
        } catch (e: IllegalStateException) {
            // Process was restarted without going through SteamMainActivity.
            startActivity(android.content.Intent(this, SteamMainActivity::class.java))
            finish()
            return
        }

        repo.initialize(this)

        // Use in-memory cache — avoids a SQLite read on every resume/rotate.
        // Cache is invalidated on LibrarySynced, DownloadComplete, DownloadCancelled,
        // and markUninstalled(), so installed state is always current.
        val rows = repo.getCachedGameRows()
        games = rows
            .filter { it.type == "demo" || it.type == "game" }
            .map { SteamGame.fromGameRow(it) }
            .sortedBy { it.name.lowercase() }
        if (games.isNotEmpty()) {
            statusText.text = "${games.size} games in library"
        }
        refreshList()
    }

    /**
     * Auto-sync rules:
     *  - Always sync if the library is empty (new login or wiped DB)
     *  - Otherwise sync only if the last sync was more than 4 hours ago
     *  - Never auto-sync if already syncing or not logged in
     */
    private fun maybeAutoSync() {
        val repo = SteamRepository.getInstance()
        if (!repo.isLoggedIn) return
        val staleThresholdSec = 4 * 60 * 60L  // 4 hours
        val elapsed = System.currentTimeMillis() / 1000L - repo.lastSyncTime
        if (games.isEmpty() || elapsed > staleThresholdSec) {
            statusText.text = if (games.isEmpty()) "Syncing library…" else "Refreshing library…"
            repo.syncLibrary()
        }
    }

    private fun refreshList() {
        var seq = games.asSequence()
        if (searchQuery.isNotEmpty())
            seq = seq.filter { it.name.contains(searchQuery, ignoreCase = true) }
        seq = when (installFilter) {
            InstallFilter.INSTALLED     -> seq.filter { it.isInstalled }
            InstallFilter.NOT_INSTALLED -> seq.filter { !it.isInstalled }
            InstallFilter.ALL           -> seq
        }
        val cmp: Comparator<SteamGame> = when (sortKey) {
            SortKey.TITLE -> compareBy { it.name.lowercase() }
            SortKey.SIZE  -> compareBy { it.sizeBytes }
        }
        val filtered = seq.sortedWith(if (sortAsc) cmp else cmp.reversed()).toList()

        if (filtered.size != games.size) {
            statusText.text = "${filtered.size} of ${games.size} games"
        } else if (games.isNotEmpty()) {
            statusText.text = "${games.size} games in library"
        }
        val adapter = object : ArrayAdapter<SteamGame>(this, 0, filtered) {
            override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                val game = getItem(pos)!!
                val cell = (convertView as? LinearLayout) ?: StoreGridUi.buildCell(this@SteamGamesActivity).root
                // Tag the cell with appId so the async image loader can detect recycling
                cell.tag = game.appId

                val artView      = cell.getChildAt(0) as ImageView
                val nameView     = cell.getChildAt(1) as TextView
                val btnRow       = cell.getChildAt(2) as LinearLayout
                val launchBtn    = btnRow.getChildAt(0) as ImageView
                val uninstallBtn = btnRow.getChildAt(1) as ImageView

                nameView.text = game.name.ifEmpty { "App ${game.appId}" }

                // Launch / Uninstall icons — shown only for installed games.
                // INVISIBLE (not GONE) reserves the space so every cell stays the same height.
                if (game.isInstalled) {
                    btnRow.visibility = View.VISIBLE
                    launchBtn.setOnClickListener { launchGame(game) }
                    uninstallBtn.setOnClickListener { uninstallGame(game) }
                } else {
                    btnRow.visibility = View.INVISIBLE
                    launchBtn.setOnClickListener(null)
                    uninstallBtn.setOnClickListener(null)
                }

                cell.setOnClickListener {
                    startActivity(Intent(this@SteamGamesActivity, SteamGameDetailActivity::class.java)
                        .putExtra(SteamGameDetailActivity.EXTRA_APP_ID, game.appId))
                }

                // Clear any recycled bitmap (the card background shows as the placeholder)
                // then kick off the async cover load.
                artView.setImageDrawable(null)
                loadCoverArt(artView, game)
                return cell
            }
        }
        gridView.adapter = adapter
        emptyText.text = when {
            searchQuery.isNotEmpty()           -> "No games match \"$searchQuery\"."
            installFilter != InstallFilter.ALL -> "No games match the current filter."
            else -> "No games found.\nIf sync just finished, tap Refresh."
        }
        emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        gridView.visibility  = if (filtered.isEmpty()) View.GONE   else View.VISIBLE
    }

    // -------------------------------------------------------------------------
    // Cover art loading
    // -------------------------------------------------------------------------

    private fun loadCoverArt(view: ImageView, game: SteamGame) {
        val appId = game.appId
        imageCache.get(appId)?.let { cached ->
            view.setImageBitmap(cached)
            return
        }
        imageExecutor.submit {
            // Try portrait art first (600x900), fall back to wide header
            val bmp = tryBitmap("https://shared.steamstatic.com/store_item_assets/steam/apps/$appId/library_600x900.jpg")
                   ?: tryBitmap("https://steamcdn-a.akamaihd.net/steam/apps/$appId/library_600x900.jpg")
                   ?: tryBitmap("https://shared.steamstatic.com/store_item_assets/steam/apps/$appId/header.jpg")
                   ?: tryBitmap("https://shared.steamstatic.com/store_item_assets/steam/apps/$appId/capsule_616x353.jpg")
                   ?: tryBitmap("https://cdn.cloudflare.steamstatic.com/steamcommunity/public/images/apps/$appId/${game.iconHash}.jpg")
            if (bmp != null) {
                imageCache.put(appId, bmp)
                ui.post {
                    // Only set if this view still shows the same appId (not recycled)
                    val parent = view.parent as? LinearLayout
                    if (parent?.tag == appId) view.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun tryBitmap(url: String): Bitmap? {
        val data = StoreImageLoader.fetch(url, null) ?: return null
        // Downsample to the on-screen cell width (RGB_565) so big libraries stay light on RAM.
        val target = resources.displayMetrics.widthPixels / StoreGridUi.COLUMNS
        return StoreImageLoader.decodeSampled(data, target)
    }

    // -------------------------------------------------------------------------
    // UI construction
    // -------------------------------------------------------------------------

    private fun buildUI(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        // Header bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(getColor(R.color.colorPrimary))
            gravity = Gravity.CENTER_VERTICAL
        }
        val backBtn = StoreGridUi.backButton(this) { finish() }
        val title = TextView(this).apply {
            text = "Steam Library"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val refreshBtn = Button(this).apply {
            text = "Refresh"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { SteamRepository.getInstance().syncLibrary() }
        }
        val logoutBtn = Button(this).apply {
            text = "Logout"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener {
                android.app.AlertDialog.Builder(this@SteamGamesActivity)
                    .setTitle("Sign out of Steam?")
                    .setMessage("Your saved login will be removed. You will need to sign in again.")
                    .setPositiveButton("Sign Out") { _, _ ->
                        SteamRepository.getInstance().logout()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        header.addView(backBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(title)
        header.addView(refreshBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(6) })
        header.addView(logoutBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        root.addView(header)

        // Status bar
        statusText = TextView(this).apply {
            text = "Loading library…"
            textSize = 12f
            setTextColor(GRAY)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            setBackgroundColor(Color.parseColor("#1A1A2E"))
        }
        root.addView(statusText)

        // Search bar
        searchBar = EditText(this).apply {
            hint = "Search games…"
            setHintTextColor(0xFF666666.toInt())
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            textSize = 14f
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    searchQuery = s?.toString()?.trim() ?: ""
                    refreshList()
                }
            })
        }
        root.addView(searchBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        // Filter + sort controls
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        val filterBtn = StoreGridUi.pillButton(this, "Filter: All")
        val sortBtn   = StoreGridUi.pillButton(this, "Sort: Title")
        val dirBtn    = StoreGridUi.pillButton(this, "↑")
        filterBtn.setOnClickListener {
            PopupMenu(this, filterBtn).apply {
                menu.add(0, 0, 0, "All")
                menu.add(0, 1, 1, "Installed")
                menu.add(0, 2, 2, "Not installed")
                menu.setGroupCheckable(0, true, true)
                menu.getItem(installFilter.ordinal).isChecked = true
                setOnMenuItemClickListener { item ->
                    installFilter = InstallFilter.values()[item.itemId]
                    filterBtn.text = "Filter: " + when (installFilter) {
                        InstallFilter.ALL           -> "All"
                        InstallFilter.INSTALLED     -> "Installed"
                        InstallFilter.NOT_INSTALLED -> "Not installed"
                    }
                    refreshList()
                    true
                }
            }.show()
        }
        sortBtn.setOnClickListener {
            PopupMenu(this, sortBtn).apply {
                menu.add(0, 0, 0, "Title")
                menu.add(0, 1, 1, "Size")
                menu.setGroupCheckable(0, true, true)
                menu.getItem(sortKey.ordinal).isChecked = true
                setOnMenuItemClickListener { item ->
                    sortKey = SortKey.values()[item.itemId]
                    sortBtn.text = "Sort: " + when (sortKey) {
                        SortKey.TITLE -> "Title"
                        SortKey.SIZE  -> "Size"
                    }
                    refreshList()
                    true
                }
            }.show()
        }
        dirBtn.setOnClickListener {
            sortAsc = !sortAsc
            dirBtn.text = if (sortAsc) "↑" else "↓"
            refreshList()
        }
        controls.addView(filterBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = dp(8) })
        controls.addView(sortBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = dp(8) })
        controls.addView(dirBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(controls, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Empty state
        emptyText = TextView(this).apply {
            text = "No games found.\nIf sync just finished, tap Refresh."
            textSize = 14f
            setTextColor(GRAY)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(48), dp(24), dp(24))
            visibility = View.GONE
        }
        root.addView(emptyText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Game grid — shared 6-column store grid styling
        gridView = GridView(this).apply {
            setBackgroundColor(BG)
            StoreGridUi.styleGrid(this)
        }
        root.addView(gridView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        return root
    }

    /** Resolve the best .exe in the install dir and hand it to the launcher. */
    private fun launchGame(game: SteamGame) {
        if (game.installDir.isEmpty()) {
            Toast.makeText(this, "Install directory not found", Toast.LENGTH_SHORT).show()
            return
        }
        val installDir = java.io.File(game.installDir)
        val exeFiles   = mutableListOf<java.io.File>()
        AmazonLaunchHelper.collectExe(installDir, exeFiles)
        if (exeFiles.isEmpty()) {
            Toast.makeText(this, "No .exe found in install directory", Toast.LENGTH_SHORT).show()
            return
        }
        val lowerTitle = game.name.lowercase()
        exeFiles.sortWith(compareByDescending { AmazonLaunchHelper.scoreExe(it, lowerTitle) })
        if (exeFiles.size == 1) {
            LudashiLaunchBridge.addToLauncher(this, game.name, exeFiles[0].absolutePath)
        } else {
            val labels = exeFiles.map { it.name }.toTypedArray()
            android.app.AlertDialog.Builder(this)
                .setTitle("Choose executable")
                .setItems(labels) { _, which ->
                    LudashiLaunchBridge.addToLauncher(this, game.name, exeFiles[which].absolutePath)
                }
                .show()
        }
    }

    /** Mark the game uninstalled in the DB and delete its install directory off-thread. */
    private fun uninstallGame(game: SteamGame) {
        val db = SteamRepository.getInstance().database
        db.markUninstalled(game.appId)
        if (game.installDir.isNotEmpty()) {
            Thread { java.io.File(game.installDir).deleteRecursively() }.start()
        }
        loadGames()
    }

    companion object {
        private val BG      = Color.parseColor("#1B1B1B")
        private val GRAY    = Color.parseColor("#AAAAAA")

        // Byte-bounded LRU image cache (≈1/8 of the heap) and fixed thread pool across
        // instances. sizeOf() must report bytes, or the cap counts entries and the cache
        // grows unbounded — an OOM risk on Quest/Pico with thousand-game libraries.
        private val imageCache = object : LruCache<Int, Bitmap>(
            (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()  // cap in KB
        ) {
            override fun sizeOf(key: Int, value: Bitmap): Int =
                (value.allocationByteCount / 1024).coerceAtLeast(1)  // size in KB
        }
        private val imageExecutor = Executors.newFixedThreadPool(4)
    }
}
