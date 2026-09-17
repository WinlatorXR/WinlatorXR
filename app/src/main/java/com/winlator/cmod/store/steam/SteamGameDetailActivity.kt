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
import com.winlator.cmod.contents.ContentProfile
import com.winlator.cmod.contents.ContentsManager
import com.winlator.xr.utils.GoldbergEmu
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
        // pending obb copy (package + source dir), keyed by appId
        private const val OBB_PREFS = "steam_obb_pending"
        private const val WARN_PREFS = "steam_apk_warning"
        private const val K_HIDE_APK_WARNING = "hide_apk_warning"
        private const val GOLDBERG_PREFS = "steam_goldberg_auto"
        private const val K_AUTO_APPLY_GOLDBERG = "auto_apply"
        private const val GOLDBERG_SCAN_MAX_DEPTH = 4
        private const val GOLDBERG_SCAN_MAX_VISITED_DIRS = 4000
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

    // Steam branch for PC downloads; Android downloads always use public
    private var branches: List<SteamDatabase.BranchRow> = emptyList()
    private var selectedBranch = SteamDepotDownloader.BRANCH_PUBLIC
    private var lastBranch = SteamDepotDownloader.BRANCH_PUBLIC
    private var userPickedBranch = false

    // obb copy state, see the OBB section further down
    @Volatile private var obbCopyRunning = false
    private var installReceiver: android.content.BroadcastReceiver? = null

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

    // Branch picker — visible only when the PC build has more than the public branch.
    private lateinit var branchBtn: Button

    // Auto-applies the Goldberg Steam fix (steam_api.dll swap) right after a PC install
    // finishes — persisted globally so it carries over to every Steam store download.
    private lateinit var autoGoldbergCheck: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appId = intent.getIntExtra(EXTRA_APP_ID, 0)
        if (appId == 0) { finish(); return }

        setContentView(buildUI())
        SteamRepository.getInstance().addListener(this)
        loadGame()
    }

    override fun onResume() {
        super.onResume()
        // installer was in front of us, so finish off any queued obb copy now
        if (obbPrefs().contains("pkg_$appId")) {
            registerInstallReceiver()
            placePendingObb()
        }
    }

    override fun onDestroy() {
        SteamRepository.getInstance().removeListener(this)
        installReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        installReceiver = null
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
                    lastOs,
                    lastBranch
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

                if (lastOs == SteamDepotDownloader.OS_WINDOWS &&
                    goldbergPrefs().getBoolean(K_AUTO_APPLY_GOLDBERG, false)) {
                    applyGoldbergAutomatically(id)
                }

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
        branches = SteamRepository.getInstance().database.getBranches(appId)
        if (!userPickedBranch) selectedBranch = if (row.isInstalled)
            SteamDepotDownloader.installedBranch(this, appId) else SteamDepotDownloader.BRANCH_PUBLIC
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
        autoGoldbergCheck.visibility = if (androidSelected) View.GONE else View.VISIBLE
        branchBtn.visibility = if (!androidSelected && branches.size > 1) View.VISIBLE else View.GONE
        branchBtn.text = "Branch: $selectedBranch"
        val branchSize = branches.firstOrNull { it.name == selectedBranch }?.sizeBytes ?: 0L
        sizeText.text = when {
            androidSelected && g.androidDownloadable -> "~${fmtSize(g.androidSizeBytes)}  ·  APK"
            androidSelected                          -> "Android build not publicly available"
            selectedBranch != SteamDepotDownloader.BRANCH_PUBLIC && branchSize > 0 -> "~${fmtSize(branchSize)}"
            g.sizeBytes > 0                          -> "~${fmtSize(g.sizeBytes)}"
            else                                     -> "Size unknown"
        }

        // installed_variant is "" for legacy rows — treat a bare installed flag as the PC build.
        val installedOs = if (g.isInstalled)
            g.installedVariant.ifEmpty { SteamDepotDownloader.OS_WINDOWS } else ""
        val installedThisVariant = g.isInstalled && installedOs == selectedOs
        val installedBranch = SteamDepotDownloader.installedBranch(this, appId)
        val otherBranchSelected = installedThisVariant && installedBranch != downloadBranch()

        if (installedThisVariant) {
            statusText.text = when {
                androidSelected -> "APK downloaded"
                installedBranch != SteamDepotDownloader.BRANCH_PUBLIC -> "Installed (branch: $installedBranch)"
                else -> "Installed"
            }
            statusText.setTextColor(Color.parseColor("#4CAF50"))
            // Android only removes the downloaded APK file — it can't uninstall an APK the
            // user already pushed through the system installer, so don't imply that it does.
            installBtn.text = if (otherBranchSelected) "Switch branch" else if (androidSelected) "Delete APK" else "Uninstall"
            installBtn.setBackgroundColor(if (otherBranchSelected) COLOR_INSTALL else COLOR_UNINSTALL)
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

    /** Branch the next download uses: the picked one for PC, always public for Android. */
    private fun downloadBranch(): String =
        if (selectedOs == SteamDepotDownloader.OS_ANDROID) SteamDepotDownloader.BRANCH_PUBLIC else selectedBranch

    private fun showBranchPicker() {
        val installed = if (game?.isInstalled == true) SteamDepotDownloader.installedBranch(this, appId) else ""
        val dateFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val labels = branches.map { b ->
            val details = listOfNotNull(
                b.description.ifEmpty { null },
                if (b.sizeBytes > 0) "~${fmtSize(b.sizeBytes)}" else null,
                if (b.timeUpdated > 0) "updated ${dateFmt.format(java.util.Date(b.timeUpdated * 1000))}" else null,
            ).joinToString("  ·  ")
            (if (b.name == installed) "${b.name} (installed)" else b.name) +
                (if (details.isNotEmpty()) "\n$details" else "")
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select branch")
            .setSingleChoiceItems(labels, branches.indexOfFirst { it.name == selectedBranch }) { dialog, which ->
                userPickedBranch = true
                selectedBranch = branches[which].name
                dialog.dismiss()
                refreshUI()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

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
            // A cancelled branch switch leaves a half-updated install, so it's removed like an uninstall
            val cancelledSwitch = g.isInstalled && dir.isNotEmpty() && dir == g.installDir
            if (cancelledSwitch) db.markUninstalled(appId)
            if (dir.isNotEmpty()) {
                Thread {
                    if (cancelledSwitch) LudashiLaunchBridge.deleteShortcut(this, g.name)
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

            if (cancelledSwitch) loadGame()
            return
        }

        val installedOs = if (g.isInstalled)
            g.installedVariant.ifEmpty { SteamDepotDownloader.OS_WINDOWS } else ""
        val installedThisVariant = g.isInstalled && installedOs == selectedOs

        // -------------------------------------------------------------
        // SWITCH BRANCH — download the changed files into the existing install
        // -------------------------------------------------------------
        if (installedThisVariant && SteamDepotDownloader.installedBranch(this, appId) != downloadBranch()) {
            AlertDialog.Builder(this)
                .setTitle("Switch branch")
                .setMessage("Switch ${g.name} to the \"${downloadBranch()}\" branch? Only changed files are downloaded.\n\nCancelling the download part way removes the game.")
                .setPositiveButton("Switch") { _, _ -> startDownload(g, db, installedThisVariant) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

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

        // Android depots are just apk downloads, make sure the user knows what they're
        // getting before anything lands on disk.
        if (selectedOs == SteamDepotDownloader.OS_ANDROID && !apkWarningHidden()) {
            showApkWarningDialog { startDownload(g, db, installedThisVariant) }
            return
        }
        startDownload(g, db, installedThisVariant)
    }

    private fun startDownload(g: SteamGame, db: SteamDatabase, installedThisVariant: Boolean) {
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
        lastBranch = downloadBranch()

        installBtn.isEnabled = false
        installBtn.text = "Starting…"
        statusText.text = "Preparing download..."
        statusText.setTextColor(Color.parseColor("#4CAF50"))
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        progressText.visibility = View.VISIBLE
        progressText.text = "Initializing download…"

        downloadHandle = SteamDepotDownloader.installApp(appId, applicationContext, DOWNLOAD_THREADS, selectedOs, lastBranch)
        StoreDownloadQueue.registerHandle(appId, downloadHandle)
    }

    // -------------------------------------------------------------------------
    // Goldberg Steam fix (auto-apply)
    // -------------------------------------------------------------------------

    private fun goldbergPrefs() = getSharedPreferences(GOLDBERG_PREFS, android.content.Context.MODE_PRIVATE)

    /**
     * Auto-applies the Goldberg Steam fix right after a PC/Windows install finishes. Unlike
     * the manual "Apply Goldberg Steam Fix" shortcut-menu flow, this needs no AppID prompt —
     * it's already known here, since this whole screen is keyed off it.
     */
    private fun applyGoldbergAutomatically(id: Int) {
        Thread {
            val row = SteamRepository.getInstance().database.getGame(id) ?: return@Thread
            if (row.installDir.isEmpty()) return@Thread
            val installDir = File(row.installDir)
            if (!installDir.isDirectory) return@Thread

            val contentsManager = ContentsManager(applicationContext)
            contentsManager.syncContents()
            val installed = contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_GOLDBERG)
                ?.filter { ContentsManager.getInstallDir(applicationContext, it).isDirectory }
                ?: emptyList()

            if (installed.isEmpty()) {
                ui.post {
                    Toast.makeText(this,
                        "Auto Goldberg fix skipped: install a Goldberg profile from Downloader → Goldberg first.",
                        Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            val profile = installed[0]

            val targetDirs = mutableListOf<File>()
            scanForSteamApiDirs(installDir, 0, targetDirs, intArrayOf(0))
            if (targetDirs.isEmpty()) targetDirs.add(installDir)

            var succeeded = 0
            for (targetDir in targetDirs) {
                if (!GoldbergEmu.applyContentToDir(applicationContext, profile, targetDir)) continue
                val settingsDir = File(targetDir, "steam_settings")
                settingsDir.mkdirs()
                try {
                    java.io.FileWriter(File(settingsDir, "steam_appid.txt")).use { it.write(id.toString()) }
                } catch (_: Exception) {}
                succeeded++
            }

            ui.post {
                Toast.makeText(this,
                    if (succeeded > 0) "Goldberg Steam fix auto-applied." else "Auto Goldberg fix failed.",
                    Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** Same bounded recursive steam_api(64).dll scan the manual shortcut-menu flow uses. */
    private fun scanForSteamApiDirs(dir: File, depth: Int, found: MutableList<File>, visited: IntArray) {
        if (!dir.isDirectory || depth > GOLDBERG_SCAN_MAX_DEPTH) return
        if (++visited[0] > GOLDBERG_SCAN_MAX_VISITED_DIRS) return

        val children = dir.listFiles() ?: return
        var hasApi = false
        val subDirs = mutableListOf<File>()
        for (f in children) {
            if (f.isDirectory) subDirs.add(f)
            else if (f.name.equals("steam_api.dll", true) || f.name.equals("steam_api64.dll", true)) hasApi = true
        }
        if (hasApi) found.add(dir)
        for (sub in subDirs) scanForSteamApiDirs(sub, depth + 1, found, visited)
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
            val vrSupport = SteamRepository.getInstance().database.getVrSupport(g.appId)

            if (exeFiles.size == 1) {
                ui.post { LudashiLaunchBridge.addToLauncher(this, g.name, exeFiles[0].absolutePath, null, vrSupport, *g.artworkUrls) }
                return@Thread
            }

            // Multiple exes — show picker
            val candidates = exeFiles.map { it.absolutePath }
            showExePicker(candidates) { chosen ->
                ui.post { LudashiLaunchBridge.addToLauncher(this, g.name, chosen, null, vrSupport, *g.artworkUrls) }
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

            // Only copy the obb after the apk is installed, a fresh install recreates
            // Android/obb/<pkg> and wipes whatever was put there first.
            val obbs = mutableListOf<File>()
            collectObbs(dir, obbs)
            val obbPkg = if (obbs.isEmpty()) null else archivePackageName(apk)
            if (obbs.isNotEmpty() && obbPkg == null) {
                obbLog("No package name from ${apk.name}, leaving ${obbs.size} obb file(s) alone")
            }

            startApkInstall(apk, dir, apks.size, obbPkg, obbs.size)
        }.start()
    }

    /** Fire up the system installer and queue the obb copy for when it's done. */
    private fun startApkInstall(apk: File, dir: File, apkCount: Int, obbPkg: String?, obbCount: Int) {
        val uri = try {
            androidx.core.content.FileProvider.getUriForFile(this, "$packageName.tileprovider", apk)
        } catch (e: Exception) {
            ui.post { Toast.makeText(this, "Can't share APK: ${e.message}", Toast.LENGTH_LONG).show() }
            return
        }

        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ui.post {
            try {
                startActivity(intent)
                when {
                    obbPkg != null -> {
                        armObbCopy(obbPkg, dir)
                        Toast.makeText(this,
                            "$obbCount OBB file(s) will be copied once the install finishes.",
                            Toast.LENGTH_LONG).show()
                    }
                    obbCount > 0 -> Toast.makeText(this,
                        "Couldn't read the APK's package name, place the OBB data manually.",
                        Toast.LENGTH_LONG).show()
                    apkCount > 1 -> Toast.makeText(this,
                        "This title ships extra split APKs — some VR games may need them placed manually.",
                        Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "No installer available: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // -------------------------------------------------------------------------
    // OBB placement
    // -------------------------------------------------------------------------
    // Steam ships the obb next to the apk but games only read /sdcard/Android/obb/<pkg>/.
    // Copy after the install (a fresh install wipes that dir) and name it
    // main|patch.<versionCode>.<pkg>.obb using the installed version. The pending copy is
    // kept in prefs so it survives the activity being recreated behind the installer.

    private fun apkWarningHidden() =
        getSharedPreferences(WARN_PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(K_HIDE_APK_WARNING, false)

    /** What downloading an android depot does and, more to the point, doesn't do. */
    private fun showApkWarningDialog(onUnderstood: () -> Unit) {
        val pad = (resources.displayMetrics.density * 20).toInt()
        val message = TextView(this).apply {
            text = "This feature is NOT intended to enable you to play your APK files from Steam " +
                "as is. It is purely intended as a means to downloading APK files available for " +
                "games you already own and facilitating installing them. This feature is intended for " +
                "people that are working on ways of getting these games working on unsupported " +
                "headsets."
        }
        val dontShowAgain = CheckBox(this).apply {
            text = "Do not show this message again"
            setPadding(0, pad, 0, 0)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(message)
            addView(dontShowAgain)
        }
        AlertDialog.Builder(this)
            .setTitle("Before you download")
            .setView(box)
            .setPositiveButton("Understood") { _, _ ->
                if (dontShowAgain.isChecked) {
                    getSharedPreferences(WARN_PREFS, android.content.Context.MODE_PRIVATE)
                        .edit().putBoolean(K_HIDE_APK_WARNING, true).apply()
                }
                onUnderstood()
            }
            .show()
    }

    private fun obbPrefs() = getSharedPreferences(OBB_PREFS, android.content.Context.MODE_PRIVATE)

    /** Note down that [pkg] still needs its obb data out of [sourceDir]. */
    private fun armObbCopy(pkg: String, sourceDir: File) {
        obbPrefs().edit()
            .putString("pkg_$appId", pkg)
            .putString("dir_$appId", sourceDir.absolutePath)
            .apply()
        obbLog("Armed OBB copy for $pkg from ${sourceDir.absolutePath}")
        registerInstallReceiver()
    }

    /** Catches the install if we're alive but not resumed when it finishes. */
    private fun registerInstallReceiver() {
        if (installReceiver != null) return
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                val pkg = intent?.data?.schemeSpecificPart ?: return
                if (pkg == obbPrefs().getString("pkg_$appId", null)) placePendingObb()
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
            addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(receiver, filter)
        installReceiver = receiver
    }

    /**
     * Runs the queued copy if the package is there yet, does nothing otherwise. Safe to call
     * on every resume, so tapping Install APK again also fixes a missing obb.
     */
    private fun placePendingObb() {
        val prefs = obbPrefs()
        val pkg = prefs.getString("pkg_$appId", null) ?: return
        val dirPath = prefs.getString("dir_$appId", null) ?: return
        if (!isPackageInstalled(pkg)) return   // not installed yet, or cancelled
        if (obbCopyRunning || isFinishing) return
        obbCopyRunning = true

        val (dialog, message) = showObbProgress()
        Thread {
            val result = try {
                placeObbFiles(pkg, File(dirPath)) { done, total ->
                    ui.post { message.text = "Copying game data ${fmtBytes(done)} / ${fmtBytes(total)}" }
                }
            } catch (e: Exception) {
                obbLog("OBB copy threw: $e")
                ObbResult(0, e.message ?: e.toString())
            }
            ui.post {
                obbCopyRunning = false
                try { dialog.dismiss() } catch (_: Exception) {}
                if (result.error != null) {
                    if (!isFinishing) showObbFailureDialog(pkg, result.error)
                    return@post
                }
                prefs.edit().remove("pkg_$appId").remove("dir_$appId").apply()
                if (result.placed > 0) {
                    if (isFinishing) {
                        Toast.makeText(this, "Placed ${result.placed} OBB file(s) in Android/obb/$pkg",
                            Toast.LENGTH_LONG).show()
                    } else {
                        offerToDeleteSources(result.placed, pkg, result.sources)
                    }
                }
            }
        }.start()
    }

    /** Copies are in place and checked, so the download folder's ones are just dead weight. */
    private fun offerToDeleteSources(placed: Int, pkg: String, sources: List<File>) {
        val total = sources.sumOf { it.length() }
        if (sources.isEmpty() || total <= 0L) return
        AlertDialog.Builder(this)
            .setTitle("Game data copied")
            .setMessage("Placed $placed file(s) in Android/obb/$pkg.\n\nThe originals in the " +
                "download folder are still taking up ${fmtBytes(total)}. Delete them? The game " +
                "keeps working, you'd just have to download it again to reinstall it later.")
            .setPositiveButton("Delete") { _, _ -> deleteSources(sources) }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun deleteSources(sources: List<File>) {
        Thread {
            var freed = 0L
            for (f in sources) {
                val size = f.length()
                if (f.delete()) freed += size else obbLog("Could not delete ${f.absolutePath}")
            }
            obbLog("Deleted source obb files, freed ${fmtBytes(freed)}")
            val msg = "Freed ${fmtBytes(freed)}"
            ui.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }

    private class ObbResult(val placed: Int, val error: String?, val sources: List<File> = emptyList())

    /**
     * Copies every .obb under [sourceDir] to /sdcard/Android/obb/[pkg]/ with the name the game
     * expects. [pkg] has to be installed already so we can read its versionCode. Returns the
     * file count, or a message if it went wrong.
     */
    private fun placeObbFiles(pkg: String, sourceDir: File, onProgress: (Long, Long) -> Unit): ObbResult {
        val sources = mutableListOf<File>()
        collectObbs(sourceDir, sources)
        if (sources.isEmpty()) {
            obbLog("No .obb files under ${sourceDir.absolutePath}")
            return ObbResult(0, null)
        }

        val versionCode = installedVersionCode(pkg)
        val destDir = File(android.os.Environment.getExternalStorageDirectory(), "Android/obb/$pkg")
        if (!destDir.isDirectory && !destDir.mkdirs()) {
            obbLog("mkdirs failed for ${destDir.absolutePath}")
            return ObbResult(0, "Could not create ${destDir.absolutePath}")
        }

        // Depots ship these named the way they need to land, chunk files and all, so keep the
        // name. Only build one when there's nothing to go on, ie a bare main.obb.
        val seen = HashSet<String>()
        val plan = ArrayList<Pair<File, File>>()
        for (src in sources) {
            val name = if (src.name.contains(pkg, ignoreCase = true) || versionCode <= 0) {
                src.name
            } else {
                val kind = if (src.name.contains("patch", ignoreCase = true)) "patch" else "main"
                "$kind.$versionCode.$pkg.obb"
            }
            if (!seen.add(name)) {
                obbLog("Skipping ${src.absolutePath}, something else already claimed $name")
                continue
            }
            plan.add(src to File(destDir, name))
        }

        val todo = plan.filterNot { (src, dest) -> dest.isFile && dest.length() == src.length() }
        val totalBytes = todo.sumOf { it.first.length() }
        val free = try { destDir.usableSpace } catch (_: Exception) { 0L }
        if (totalBytes > 0L && free > 0L && free < totalBytes + 64L * 1024 * 1024) {
            return ObbResult(0, "Not enough free space, ${fmtBytes(totalBytes)} needed, ${fmtBytes(free)} free")
        }

        var copied = 0L
        for ((src, dest) in todo) {
            obbLog("Copying ${src.absolutePath} -> ${dest.absolutePath} (${fmtBytes(src.length())})")
            try {
                copyFile(src, dest) { done -> onProgress(copied + done, totalBytes) }
            } catch (e: Exception) {
                obbLog("Copy failed: $e")
                dest.delete()   // don't leave a half copied obb around, the game would load it
                return ObbResult(0, "${dest.name}: ${e.message ?: e.toString()}")
            }
            copied += src.length()
        }

        // Check they're acutally still there, this is what the old code got wrong.
        val bad = plan.filterNot { (src, dest) -> dest.isFile && dest.length() == src.length() }
        if (bad.isNotEmpty()) {
            val names = bad.joinToString(", ") { it.second.name }
            obbLog("Verification failed in ${destDir.absolutePath}: $names")
            return ObbResult(0, "$names is missing from ${destDir.absolutePath} after copying")
        }
        obbLog("Placed ${plan.size} OBB file(s) in ${destDir.absolutePath}")
        return ObbResult(plan.size, null, plan.map { it.first })
    }

    private fun copyFile(src: File, dest: File, onBytes: (Long) -> Unit) {
        java.io.FileInputStream(src).use { input ->
            java.io.FileOutputStream(dest).use { output ->
                val buf = ByteArray(1 shl 20)   // these run to several GB, 1MB chunks
                var done = 0L
                var reported = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (done - reported >= 16L * 1024 * 1024) { reported = done; onBytes(done) }
                }
                output.flush()
                output.fd.sync()
                onBytes(done)
            }
        }
    }

    private fun collectObbs(dir: File, out: MutableList<File>) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) collectObbs(f, out)
            else if (f.name.endsWith(".obb", ignoreCase = true)) out.add(f)
        }
    }

    /** Package name off the apk file itself, no install needed. */
    private fun archivePackageName(apk: File): String? = try {
        packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName
    } catch (e: Exception) {
        obbLog("getPackageArchiveInfo failed for ${apk.absolutePath}: $e")
        null
    }

    private fun isPackageInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) { false }

    private fun installedVersionCode(pkg: String): Long = try {
        val info = packageManager.getPackageInfo(pkg, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: Exception) { -1L }

    private fun showObbProgress(): Pair<AlertDialog, TextView> {
        val message = TextView(this).apply {
            setPadding(48, 48, 48, 48)
            text = "Copying game data…"
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Placing game data")
            .setView(message)
            .setCancelable(false)
            .create()
        dialog.show()
        return dialog to message
    }

    /** Say what broke and offer the permission screen. */
    private fun showObbFailureDialog(pkg: String, error: String) {
        val hasAccess = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R
            || android.os.Environment.isExternalStorageManager()
        // File access is decided when the process starts, so a permission granted while we were
        // running doesn't apply until the app is opened again.
        val advice = if (error.contains("EACCES") && hasAccess)
            "Close WinlatorXR and open it again, then tap Install APK. File access only takes " +
                "effect from the next start."
        else
            "The game reads its data from Android/obb/$pkg. If access was denied, grant " +
                "WinlatorXR All-Files-Access and tap Install APK again."
        AlertDialog.Builder(this)
            .setTitle("Couldn't place game data")
            .setMessage("$error\n\n$advice")
            .setPositiveButton("Open settings") { _, _ -> openAllFilesAccessSettings() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openAllFilesAccessSettings() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        try {
            startActivity(android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try {
                startActivity(android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
                Toast.makeText(this, "No All-Files-Access screen on this device", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun fmtBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
        else -> "$bytes B"
    }

    /** Seperate log next to the depot one, a missing obb leaves no other trace. */
    private fun obbLog(msg: String) {
        android.util.Log.i("SteamObb", msg)
        try {
            val dir = getExternalFilesDir(null) ?: return
            java.io.FileWriter(File(dir, "steam_obb.txt"), true).use { w ->
                val ts = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                w.write("[$ts] $msg\n")
            }
        } catch (_: Exception) {}
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

        branchBtn = Button(this).apply {
            text = "Branch: ${SteamDepotDownloader.BRANCH_PUBLIC}"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3A3A3A"))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(4); bottomMargin = dp(4) }
            setOnClickListener { showBranchPicker() }
        }
        info.addView(branchBtn)

        autoGoldbergCheck = CheckBox(this).apply {
            text = "Auto-apply Goldberg Steam fix after install"
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
            isChecked = goldbergPrefs().getBoolean(K_AUTO_APPLY_GOLDBERG, false)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnCheckedChangeListener { _, checked ->
                goldbergPrefs().edit().putBoolean(K_AUTO_APPLY_GOLDBERG, checked).apply()
            }
        }
        info.addView(autoGoldbergCheck)

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
