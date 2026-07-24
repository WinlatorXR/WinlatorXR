package com.winlator.cmod.store

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.winlator.cmod.R
import java.io.File
import java.net.URL

/**
 * Game detail screen — shows header art, metadata, Install/Cancel/Launch buttons.
 *
 * Cancel: installBtn toggles Install → Cancel while downloading (same pattern as Epic/GOG/Amazon).
 * Launch: uses AmazonLaunchHelper.choosePrimaryExe() + picker dialog for multiple exes.
 */
class SteamGameDetailActivity : NavActivity(), SteamRepository.SteamEventListener {

    companion object {
        const val EXTRA_APP_ID = "steam_app_id"
        private const val DOWNLOAD_THREADS = 24
        private const val COLOR_INSTALL   = 0xFF1565C0.toInt()
        private const val COLOR_CANCEL    = 0xFFCC3333.toInt()
        private const val COLOR_UNINSTALL = 0xFFB71C1C.toInt()
        private const val COLOR_LAUNCH    = 0xFF2E7D32.toInt()
    }

    private val ui = Handler(Looper.getMainLooper())
    private var appId: Int = 0
    private var game: SteamGame? = null

    @Volatile private var downloadHandle: SteamDepotDownloader.DownloadControl? = null

    // Which platform variant the buttons act on: "windows" (PC/Wine) or "android" (native APK).
    private var selectedOs = SteamDepotDownloader.OS_WINDOWS
    // OS of the in-flight download, so retries re-request the same variant.
    private var lastOs = SteamDepotDownloader.OS_WINDOWS
    // True once the user manually taps a variant toggle — stops auto-defaulting on refresh.
    private var userPickedVariant = false

    // auto retry state
    private val retryCounts = mutableMapOf<Int, Int>()
    private val maxAutoRetries = 3
    private val retryBaseDelayMs = 3000L

    // views updated after load
    private lateinit var headerImage: ImageView
    private lateinit var nameText: TextView
    private lateinit var typeText: TextView
    private lateinit var sizeText: TextView
    private lateinit var statusText: TextView
    private lateinit var installBtn: Button
    private lateinit var launchBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView

    // Version selector — visible only for titles that ship an Android (APK) build.
    private lateinit var versionRow: LinearLayout
    private lateinit var pcVariantBtn: Button
    private lateinit var androidVariantBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appId = intent.getIntExtra(EXTRA_APP_ID, 0)
        if (appId == 0) { finish(); return }

        setContentView(buildUI())
        SteamRepository.getInstance().addListener(this)
        loadGame()
    }

    override fun onDestroy() {
        SteamRepository.getInstance().removeListener(this)
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Auto retry handling
    // -------------------------------------------------------------------------

    private fun resetRetryState() {
        retryCounts.remove(appId)
    }

    private fun scheduleRetry(reason: String) {
        // Some failures are permanent — retrying just wastes time. An Android build that
        // isn't publicly available won't become available by trying again.
        if (reason.contains("not publicly available", ignoreCase = true)) {
            ui.post {
                progressBar.isIndeterminate = false
                progressBar.visibility = View.GONE
                progressText.visibility = View.GONE
                statusText.text = reason
                statusText.setTextColor(Color.parseColor("#FF9800"))
                installBtn.isEnabled = true
                installBtn.text = installLabel()
                installBtn.setBackgroundColor(COLOR_INSTALL)
            }
            return
        }

        val currentRetry = retryCounts[appId] ?: 0

        // -------------------------------------------------------------
        // Retry limit reached
        // -------------------------------------------------------------
        if (currentRetry >= maxAutoRetries) {

            val logPath = SteamDepotDownloader.debugLogPath

            ui.post {
                progressBar.isIndeterminate = false
                progressBar.visibility = View.GONE

                progressText.visibility = View.GONE

                statusText.text =
                    "Download failed after $maxAutoRetries retries:\n" +
                            "$reason\n\n" +
                            "Debug log:\n$logPath"

                statusText.setTextColor(Color.parseColor("#FF5555"))

                installBtn.isEnabled = true
                installBtn.text = "Retry"
                installBtn.setBackgroundColor(COLOR_INSTALL)
            }

            return
        }

        // -------------------------------------------------------------
        // Schedule retry
        // -------------------------------------------------------------
        val nextRetry = currentRetry + 1
        retryCounts[appId] = nextRetry

        // Exponential-ish backoff
        val delayMs = retryBaseDelayMs * nextRetry

        ui.post {
            statusText.text =
                "Download failed.\n" +
                        "Retrying automatically ($nextRetry/$maxAutoRetries) in ${delayMs / 1000}s…"

            statusText.setTextColor(Color.parseColor("#FF9800"))

            progressBar.visibility = View.VISIBLE
            progressBar.isIndeterminate = true

            progressText.visibility = View.VISIBLE
            progressText.text = "Waiting before retry…"

            installBtn.isEnabled = false
            installBtn.text = "Retrying…"
        }

        ui.postDelayed({

            // Activity already gone
            if (isFinishing || isDestroyed) {
                return@postDelayed
            }

            ui.post {
                statusText.text = "Restarting download…"
                statusText.setTextColor(Color.parseColor("#4CAF50"))

                progressBar.isIndeterminate = false
                progressBar.progress = 0

                progressText.text = "Reconnecting…"
            }

            downloadHandle =
                SteamDepotDownloader.installApp(
                    appId,
                    applicationContext,
                    DOWNLOAD_THREADS,
                    lastOs
                )

            StoreDownloadQueue.registerHandle(appId, downloadHandle)

        }, delayMs)
    }

    // -------------------------------------------------------------------------
    // SteamRepository.SteamEventListener
    // -------------------------------------------------------------------------

    override fun onEvent(event: String) {

        when {

            // -----------------------------------------------------------------
            // DOWNLOAD PROGRESS
            // -----------------------------------------------------------------
            event.startsWith("DownloadProgress:") -> {

                if (!StoreDownloadQueue.hasHandle(appId)) {
                    return
                }

                val parts = event.split(":")

                val id =
                    parts.getOrNull(1)?.toIntOrNull()
                        ?: return

                if (id != appId) {
                    return
                }

                val done =
                    parts.getOrNull(2)?.toLongOrNull()
                        ?: 0L

                val total =
                    parts.getOrNull(3)?.toLongOrNull()
                        ?: 1L

                val pct =
                    if (total > 0) {
                        (done * 100 / total)
                            .toInt()
                            .coerceIn(0, 100)
                    } else {
                        0
                    }

                ui.post {

                    statusText.text = "Downloading"
                    statusText.setTextColor(Color.parseColor("#4CAF50"))

                    progressBar.visibility = View.VISIBLE
                    progressBar.isIndeterminate = false
                    progressBar.progress = pct

                    progressText.visibility = View.VISIBLE
                    progressText.text =
                        "Downloading… $pct%  (${fmtSize(done)} / ${fmtSize(total)})"

                    installBtn.isEnabled = true
                    installBtn.text = "Cancel"
                    installBtn.setBackgroundColor(COLOR_CANCEL)
                }
            }

            // -----------------------------------------------------------------
            // DOWNLOAD COMPLETE
            // -----------------------------------------------------------------
            event.startsWith("DownloadComplete:") -> {

                val id =
                    event.substringAfter("DownloadComplete:")
                        .toIntOrNull()
                        ?: return

                if (id != appId) {
                    return
                }

                downloadHandle = null

                resetRetryState()

                ui.post {

                    progressBar.isIndeterminate = false
                    progressBar.visibility = View.GONE

                    progressText.visibility = View.GONE

                    loadGame()
                }
            }

            // -----------------------------------------------------------------
            // DOWNLOAD CANCELLED
            // -----------------------------------------------------------------
            event.startsWith("DownloadCancelled:") -> {

                val id =
                    event.substringAfter("DownloadCancelled:")
                        .toIntOrNull()
                        ?: return

                if (id != appId) {
                    return
                }

                downloadHandle = null

                resetRetryState()

                ui.post {

                    progressBar.isIndeterminate = false
                    progressBar.visibility = View.GONE

                    progressText.visibility = View.GONE

                    statusText.text = "Download cancelled"
                    statusText.setTextColor(Color.parseColor("#AAAAAA"))

                    installBtn.isEnabled = true
                    installBtn.text = installLabel()
                    installBtn.setBackgroundColor(COLOR_INSTALL)
                }
            }

            // -----------------------------------------------------------------
            // DOWNLOAD FAILED
            // -----------------------------------------------------------------
            event.startsWith("DownloadFailed:") -> {

                val parts = event.split(":")

                val id =
                    parts.getOrNull(1)?.toIntOrNull()
                        ?: return

                if (id != appId) {
                    return
                }

                val reason =
                    parts.drop(2).joinToString(":")

                downloadHandle = null

                scheduleRetry(reason)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Data
    // -------------------------------------------------------------------------

    private fun loadGame() {
        val row = SteamRepository.getInstance().database.getGame(appId)
        if (row == null) { finish(); return }
        game = SteamGame.fromGameRow(row)
        initVariantSelection()
        refreshUI()
        loadHeaderImage()

        // Check for an active / paused download
        val dlRow = SteamRepository.getInstance().database.getDownload(appId)
        if (dlRow != null) {
            val pct = if (dlRow.bytesTotal > 0) (dlRow.bytesDownloaded * 100 / dlRow.bytesTotal).toInt().coerceIn(0, 100) else 0
            when (dlRow.status) {
                SteamDatabase.DL_DOWNLOADING -> {
                    if (SteamDepotDownloader.isDownloading(appId)) {
                        progressBar.visibility  = View.VISIBLE
                        progressBar.progress    = pct
                        progressText.visibility = View.VISIBLE
                        progressText.text       = "Downloading… $pct%"
                        installBtn.isEnabled    = true
                        installBtn.text         = "Cancel"
                        installBtn.setBackgroundColor(COLOR_CANCEL)
                    } else {
                        // Stale record (app was killed mid-download) — clean up
                        val dlKey = "steam:${appId}"
                        SteamRepository.getInstance().database.deleteDownload(appId)
                        StoreDownloadQueue.stopDownload(dlKey)
                    }
                }
            }
        }
    }

    private fun refreshUI() {
        val g = game ?: return
        nameText.text = g.name.ifEmpty { "App ${g.appId}" }
        typeText.text = g.type.uppercase()
        typeText.setTextColor(if (g.type == "game") Color.parseColor("#4CAF50") else Color.parseColor("#FF9800"))

        // Version selector — only meaningful when an Android (APK) build exists.
        versionRow.visibility = if (g.hasAndroid) View.VISIBLE else View.GONE
        updateVariantButtons()

        val androidSelected = selectedOs == SteamDepotDownloader.OS_ANDROID
        sizeText.text = when {
            androidSelected && g.androidDownloadable -> "~${fmtSize(g.androidSizeBytes)}  ·  APK"
            androidSelected                          -> "Android build not publicly available"
            g.sizeBytes > 0                          -> "~${fmtSize(g.sizeBytes)}"
            else                                     -> "Size unknown"
        }

        // installed_variant is "" for legacy rows — treat a bare installed flag as the PC build.
        val installedOs = if (g.isInstalled)
            g.installedVariant.ifEmpty { SteamDepotDownloader.OS_WINDOWS } else ""
        val installedThisVariant = g.isInstalled && installedOs == selectedOs

        if (installedThisVariant) {
            statusText.text = if (androidSelected) "APK downloaded" else "Installed"
            statusText.setTextColor(Color.parseColor("#4CAF50"))
            // Android only removes the downloaded APK file — it can't uninstall an APK the
            // user already pushed through the system installer, so don't imply that it does.
            installBtn.text = if (androidSelected) "Delete APK" else "Uninstall"
            installBtn.setBackgroundColor(COLOR_UNINSTALL)
            installBtn.isEnabled = true
            launchBtn.isEnabled  = true
            launchBtn.alpha      = 1f
            launchBtn.text       = if (androidSelected) "Install APK" else "Launch"
        } else {
            if (progressBar.visibility != View.VISIBLE) {
                statusText.text = if (g.isInstalled)
                    "Installed: ${if (installedOs == SteamDepotDownloader.OS_ANDROID) "Android" else "PC"} version"
                else "Not installed"
            }
            statusText.setTextColor(Color.parseColor("#AAAAAA"))
            installBtn.text = installLabel()
            installBtn.setBackgroundColor(COLOR_INSTALL)
            installBtn.isEnabled = true
            launchBtn.isEnabled  = false
            launchBtn.alpha      = 0.4f
            launchBtn.text       = "Launch"
        }
    }

    /** Label for the primary action when nothing is installed: the Android variant only
     *  fetches the APK (installed separately via the Install APK button), so it reads "Download". */
    private fun installLabel(): String =
        if (selectedOs == SteamDepotDownloader.OS_ANDROID) "Download" else "Install"

    /** Highlight whichever variant button is currently selected. */
    private fun updateVariantButtons() {
        val androidSelected = selectedOs == SteamDepotDownloader.OS_ANDROID
        pcVariantBtn.setBackgroundColor(if (!androidSelected) COLOR_INSTALL else Color.parseColor("#3A3A3A"))
        androidVariantBtn.setBackgroundColor(if (androidSelected) COLOR_INSTALL else Color.parseColor("#3A3A3A"))
    }

    /**
     * Pick a sensible default variant the first time a game loads (before the user taps a
     * toggle): the installed variant if any, otherwise PC when a PC build exists, else Android.
     */
    private fun initVariantSelection() {
        if (userPickedVariant) return
        val g = game ?: return
        selectedOs = when {
            g.isInstalled && g.installedVariant.isNotEmpty() -> g.installedVariant
            !g.hasAndroid                                    -> SteamDepotDownloader.OS_WINDOWS
            g.sizeBytes > 0 ||
                g.oslist.split(",").any { it.trim().equals("windows", true) }
                                                             -> SteamDepotDownloader.OS_WINDOWS
            else                                             -> SteamDepotDownloader.OS_ANDROID
        }
    }

    private fun tryBitmap(url: String): Bitmap? {
        return try {
            BitmapFactory.decodeStream(URL(url).openStream())
        } catch (_ : Exception) {
            null
        }
    }

    private fun loadHeaderImage() {
        Thread {
            val bmp = tryBitmap("https://shared.steamstatic.com/store_item_assets/steam/apps/$appId/header.jpg")
                ?: tryBitmap("https://shared.steamstatic.com/store_item_assets/steam/apps/$appId/capsule_616x353.jpg")
                ?: tryBitmap("https://cdn.cloudflare.steamstatic.com/steamcommunity/public/images/apps/$appId/${game?.iconHash}.jpg")

            if (bmp != null) {
                ui.post {
                    ui.post { headerImage.setImageBitmap(bmp) }
                }
            }
        }.start()
    }

    // -------------------------------------------------------------------------
    // Button handlers
    // -------------------------------------------------------------------------

    private fun onInstallClicked() {
        val g = game ?: return

        val db = SteamRepository.getInstance().database
        val dlRow = db.getDownload(appId)

        // -------------------------------------------------------------
        // ACTIVE DOWNLOAD -> CANCEL
        // -------------------------------------------------------------
        if (dlRow != null) {

            // Try active runtime cancel first
            val dlKey = "steam:${appId}"
            StoreDownloadQueue.stopDownload(dlKey)

            // Remove DB row
            db.deleteDownload(appId)

            // Delete partial files
            val dir = dlRow.installDir
            if (dir.isNotEmpty()) {
                Thread {
                    try {
                        File(dir).deleteRecursively()
                    } catch (_: Exception) {}
                }.start()
            }

            // Reset UI immediately
            progressBar.visibility = View.GONE
            progressText.visibility = View.GONE

            statusText.text = "Download cancelled"
            statusText.setTextColor(Color.parseColor("#AAAAAA"))

            installBtn.text = installLabel()
            installBtn.setBackgroundColor(COLOR_INSTALL)
            installBtn.isEnabled = true

            downloadHandle = null

            return
        }

        val installedOs = if (g.isInstalled)
            g.installedVariant.ifEmpty { SteamDepotDownloader.OS_WINDOWS } else ""
        val installedThisVariant = g.isInstalled && installedOs == selectedOs

        // -------------------------------------------------------------
        // UNINSTALL (only when the *selected* variant is the installed one)
        // -------------------------------------------------------------
        if (installedThisVariant) {
            db.markUninstalled(appId)

            if (g.installDir.isNotEmpty()) {
                Thread {
                    LudashiLaunchBridge.deleteShortcut(this, g.name)
                    try {
                        File(g.installDir).deleteRecursively()
                    } catch (_: Exception) {}
                }.start()
            }

            loadGame()
            return
        }

        // -------------------------------------------------------------
        // Android build known to be unavailable — say so, don't try.
        // -------------------------------------------------------------
        if (selectedOs == SteamDepotDownloader.OS_ANDROID && !g.androidDownloadable) {
            statusText.text = "The Android version exists but is currently not publicly available"
            statusText.setTextColor(Color.parseColor("#FF9800"))
            return
        }

        // -------------------------------------------------------------
        // Switching variants — remove the other variant that's installed first
        // so its files don't linger orphaned on disk.
        // -------------------------------------------------------------
        if (g.isInstalled && !installedThisVariant) {
            db.markUninstalled(appId)
            if (g.installDir.isNotEmpty()) {
                val oldDir = g.installDir
                Thread {
                    LudashiLaunchBridge.deleteShortcut(this, g.name)
                    try { File(oldDir).deleteRecursively() } catch (_: Exception) {}
                }.start()
            }
        }

        // -------------------------------------------------------------
        // START DOWNLOAD
        // -------------------------------------------------------------
        resetRetryState()
        lastOs = selectedOs

        installBtn.isEnabled = false
        installBtn.text = "Starting…"
        statusText.text = "Preparing download..."
        statusText.setTextColor(Color.parseColor("#4CAF50"))
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        progressText.visibility = View.VISIBLE
        progressText.text = "Initializing download…"

        downloadHandle = SteamDepotDownloader.installApp(appId, applicationContext, DOWNLOAD_THREADS, selectedOs)
        StoreDownloadQueue.registerHandle(appId, downloadHandle)
    }

    private fun onLaunchClicked() {
        val g = game ?: return
        if (!g.isInstalled || g.installDir.isEmpty()) {
            Toast.makeText(this, "Game not installed", Toast.LENGTH_SHORT).show()
            return
        }
        // The Android variant is a native APK — hand it to the system package installer
        // rather than the Wine/Box64 exe launcher.
        if (g.isAndroidInstall) {
            installApkFromDir(File(g.installDir))
            return
        }
        val installDir = File(g.installDir)
        Thread {
            val exeFiles = mutableListOf<File>()
            AmazonLaunchHelper.collectExe(installDir, exeFiles)

            if (exeFiles.isEmpty()) {
                ui.post {
                    Toast.makeText(this, "No .exe found in install directory", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }

            // Sort by score — same heuristic as Epic/Amazon
            val lowerTitle = g.name.lowercase()
            exeFiles.sortWith { a, b ->
                AmazonLaunchHelper.scoreExe(b, lowerTitle) - AmazonLaunchHelper.scoreExe(a, lowerTitle)
            }

            if (exeFiles.size == 1) {
                ui.post { LudashiLaunchBridge.addToLauncher(this, g.name, exeFiles[0].absolutePath) }
                return@Thread
            }

            // Multiple exes — show picker
            val candidates = exeFiles.map { it.absolutePath }
            showExePicker(candidates) { chosen ->
                ui.post { LudashiLaunchBridge.addToLauncher(this, g.name, chosen) }
            }
        }.start()
    }

    /** Locate the downloaded APK under [dir] and hand it to Android's package installer. */
    private fun installApkFromDir(dir: File) {
        Thread {
            val apks = mutableListOf<File>()
            collectApks(dir, apks)
            if (apks.isEmpty()) {
                ui.post { Toast.makeText(this, "No APK found in ${dir.name}", Toast.LENGTH_LONG).show() }
                return@Thread
            }
            // Prefer the largest APK (the base package); split/config APKs are smaller.
            apks.sortByDescending { it.length() }
            val apk = apks[0]

            // Android 8+ requires the app to be allowed to install unknown apps.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                && !packageManager.canRequestPackageInstalls()) {
                ui.post {
                    AlertDialog.Builder(this)
                        .setTitle("Allow app installs")
                        .setMessage("To install the APK, allow WinlatorXR to install unknown apps, then tap Install APK again.")
                        .setPositiveButton("Open settings") { _, _ ->
                            try {
                                startActivity(android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    android.net.Uri.parse("package:$packageName")))
                            } catch (_: Exception) {
                                try {
                                    startActivity(android.content.Intent(
                                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                                } catch (_: Exception) {}
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                return@Thread
            }

            val uri = try {
                androidx.core.content.FileProvider.getUriForFile(this, "$packageName.tileprovider", apk)
            } catch (e: Exception) {
                ui.post { Toast.makeText(this, "Can't share APK: ${e.message}", Toast.LENGTH_LONG).show() }
                return@Thread
            }

            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ui.post {
                try {
                    startActivity(intent)
                    if (apks.size > 1) {
                        Toast.makeText(this,
                            "This title ships extra split/OBB files — some VR games may need them placed manually.",
                            Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "No installer available: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun collectApks(dir: File, out: MutableList<File>) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) collectApks(f, out)
            else if (f.name.endsWith(".apk", ignoreCase = true)) out.add(f)
        }
    }

    private fun showExePicker(candidates: List<String>, onSelected: (String) -> Unit) {
        val labels = candidates.map { path ->
            val f = File(path)
            val parent = f.parentFile
            if (parent != null) "${parent.name}/${f.name}" else f.name
        }.toTypedArray()

        ui.post {
            AlertDialog.Builder(this)
                .setTitle("Select game executable")
                .setItems(labels) { _, which ->
                    Thread { onSelected(candidates[which]) }.start()
                }
                .setCancelable(false)
                .show()
        }
    }

    // -------------------------------------------------------------------------
    // UI construction
    // -------------------------------------------------------------------------

    private fun buildUI(): View {
        val scroll = android.widget.ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#1B1B1B"))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(root)

        // Back button header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(getColor(R.color.colorPrimary))
        }
        val backBtn = StoreGridUi.backButton(this) { finish() }
        val row = SteamRepository.getInstance().database.getGame(appId)
        if (row == null) { finish(); return scroll }
        game = SteamGame.fromGameRow(row)
        val title = TextView(this).apply {
            text = game!!.name
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(backBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(title)
        root.addView(header)

        // Header image (16:7 aspect ratio approximation)
        headerImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#0D47A1"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180))
        }
        root.addView(headerImage)

        // Info section
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }

        nameText = TextView(this).apply {
            text = "Loading…"
            textSize = 22f
            setTextColor(Color.WHITE)
        }
        info.addView(nameText)

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        typeText = TextView(this).apply {
            text = "GAME"
            textSize = 11f
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setBackgroundColor(Color.parseColor("#263238"))
        }
        sizeText = TextView(this).apply {
            text = "Size unknown"
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(dp(12), 0, 0, 0)
        }
        row1.addView(typeText)
        row1.addView(sizeText)
        info.addView(row1)

        // Version selector (PC vs Android APK) — hidden unless the title ships an Android build.
        versionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(4))
            visibility = View.GONE
        }
        pcVariantBtn = Button(this).apply {
            text = "PC (Windows)"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(COLOR_INSTALL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(6) }
            setOnClickListener {
                userPickedVariant = true
                selectedOs = SteamDepotDownloader.OS_WINDOWS
                refreshUI()
            }
        }
        androidVariantBtn = Button(this).apply {
            text = "Android (VR)"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                userPickedVariant = true
                selectedOs = SteamDepotDownloader.OS_ANDROID
                refreshUI()
            }
        }
        versionRow.addView(pcVariantBtn)
        versionRow.addView(androidVariantBtn)
        info.addView(versionRow)

        statusText = TextView(this).apply {
            text = "Not installed"
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, dp(4), 0, dp(12))
        }
        info.addView(statusText)
        root.addView(info)

        // Progress bar (hidden until download starts)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            setPadding(dp(16), 0, dp(16), 0)
        }
        root.addView(progressBar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))

        progressText = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(dp(16), dp(2), dp(16), dp(4))
            visibility = View.GONE
        }
        root.addView(progressText)

        // Buttons
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }

        installBtn = Button(this).apply {
            text = "Install"
            setTextColor(Color.WHITE)
            setBackgroundColor(COLOR_INSTALL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { onInstallClicked() }
        }

        launchBtn = Button(this).apply {
            text = "Launch"
            setTextColor(Color.WHITE)
            setBackgroundColor(COLOR_LAUNCH)
            isEnabled = false
            alpha = 0.4f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onLaunchClicked() }
        }

        btnRow.addView(installBtn)
        btnRow.addView(launchBtn)
        root.addView(btnRow)

        return scroll
    }

    private fun fmtSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
            else                    -> "%.0f KB".format(bytes / 1024.0)
        }
    }
}
