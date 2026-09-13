package com.winlator.cmod.store

import android.content.Context
import android.util.Log
import com.winlator.cmod.store.proto.CloudConfigStore.CCloudConfigStore_Download_Request
import com.winlator.cmod.store.proto.CloudConfigStore.CCloudConfigStore_NamespaceVersion
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds the user's Steam collections and keeps them fresh.
 *
 * Steam keeps collections in its cloud config store rather than in PICS app data, so
 * they arrive over their own CloudConfigStore.Download RPC instead of with the library
 * sync. This object owns that fetch, the offline snapshot in SteamPrefs, and the appId
 * lookup the library filter needs.
 *
 * Refreshing deliberately rides the library sync rather than running to its own schedule:
 * it registers as a repository listener once and refetches when the library reports a sync,
 * so the library's own staleness rule covers both and visiting the library screen costs
 * nothing. Only a missing snapshot triggers a fetch of its own, once. Logging out drops the
 * data.
 */
object SteamCollectionStore : SteamRepository.SteamEventListener {

    private const val TAG = "SteamCollections"

    /** The user-collections namespace within the cloud config store. */
    private const val NAMESPACE_USER_COLLECTIONS = 1

    /** Version 0 asks for the whole namespace rather than a delta since some version. */
    private const val VERSION_FULL_DOWNLOAD = 0L

    private const val JOB_TIMEOUT_MS = 30_000L

    /** Grace on top of the job timeout before we stop waiting on the future ourselves. */
    private const val FUTURE_TIMEOUT_SLACK_MS = 5_000L

    /**
     * Retry backoff. Riding the library sync means the fetch no longer competes with the
     * post-login PICS burst, but the reply can still be dropped on a flaky link, and a
     * missed one leaves the filter empty until the next sync hours later.
     */
    private val RETRY_DELAYS_MS = longArrayOf(3_000L, 8_000L, 20_000L)

    /** Emitted on the repository's event bus once a fetch has stored new collections. */
    const val EVENT_SYNCED = "CollectionsSynced:"

    // null means never loaded, which has to read as "don't filter" rather than
    // "hide everything" — see allowedAppIds.
    @Volatile
    private var collections: List<SteamCollection>? = null

    /** How many dynamic collections the last fetch had to skip. */
    @Volatile
    var skippedDynamic: Int = 0
        private set

    @Volatile
    private var fetching = false

    private var registered = false

    /** Guards the one first-run fetch in fetchIfNoSnapshot(). */
    private val initialFetchAttempted = AtomicBoolean(false)

    /** The user's collections, or null if none have been loaded yet. */
    fun get(): List<SteamCollection>? = collections

    /**
     * Load the cached snapshot and start following the session.
     * Safe to call from every screen that shows the filter; only the first call works.
     */
    @Synchronized
    fun init(ctx: Context) {
        SteamPrefs.init(ctx)
        if (collections == null) {
            collections = SteamCollection.fromJson(SteamPrefs.collectionsCache)
            if (collections != null) skippedDynamic = SteamPrefs.collectionsSkippedDynamic
        }
        if (!registered) {
            SteamRepository.getInstance().addListener(this)
            registered = true
        }
    }

    override fun onEvent(event: String) {
        when {
            // Ride along with the library sync rather than keeping a schedule of our own.
            // The library's staleness rule (empty, or last sync over four hours ago) then
            // governs collections too, so opening the library screen does not refetch and
            // neither does a transient reconnect — logging in used to trigger this, which
            // meant every auto-reconnect pulled collections again. It also means the fetch
            // lands after the post-login PICS burst rather than competing with it.
            event.startsWith("LibrarySynced:") -> fetchAsync()
            event == "LoggedOut"               -> clear()
        }
    }

    /**
     * First run only: fetch because no snapshot exists yet, rather than leaving the filter
     * empty until the library next happens to sync.
     *
     * At most one attempt per process, and never once anything has been stored — a user with
     * no collections gets an empty list, which counts as a snapshot. So this does not fire
     * on repeat visits to the library screen; after it, refreshes ride the library sync.
     */
    fun fetchIfNoSnapshot() {
        if (collections != null) return
        if (!initialFetchAttempted.compareAndSet(false, true)) return
        fetchAsync()
    }

    /** Fetch on a background thread. Ignored while a fetch is already in flight. */
    fun fetchAsync() {
        synchronized(this) {
            if (fetching) return
            fetching = true
        }
        Thread({
            try {
                fetchBlocking()
            } finally {
                synchronized(this) { fetching = false }
            }
        }, "SteamCollections").start()
    }

    /**
     * The union of appIds across the selected collections, or null to keep every game.
     *
     * Failing open matters in three cases that all mean "don't filter" rather than
     * "hide everything": collections never loaded, nothing selected, and a selection
     * left over from collections the user has since deleted.
     */
    fun allowedAppIds(selectedIds: Set<String>): Set<Int>? {
        val all = collections ?: return null
        if (selectedIds.isEmpty()) return null
        val selected = all.filter { it.id in selectedIds }
        if (selected.isEmpty()) return null
        return buildSet { selected.forEach { addAll(it.appIds) } }
    }

    /**
     * Drop selected ids that no longer exist, so a collection deleted in the Steam client
     * stops being counted. No-op while nothing is loaded — an unknown id then just means
     * "not fetched yet", not "deleted". Returns true if anything was removed.
     */
    fun reconcile(selectedIds: MutableSet<String>): Boolean {
        val all = collections ?: return false
        val present = all.mapTo(HashSet()) { it.id }
        return selectedIds.retainAll(present)
    }

    /** Forget the collections and the snapshot — the session they belong to is over. */
    fun clear() {
        collections = null
        skippedDynamic = 0
        SteamPrefs.collectionsCache = ""
        SteamPrefs.collectionsSkippedDynamic = 0
    }

    // -------------------------------------------------------------------------
    // Fetch
    // -------------------------------------------------------------------------

    private fun fetchBlocking() {
        val repo = SteamRepository.getInstance()
        // Null until initialize() has run, i.e. the Steam tab was never opened.
        val client = repo.steamClient ?: return
        // Pin the account: a slow reply must not be stored against a different login.
        val sessionSteamId = repo.steamId64
        val unified = client.getHandler(SteamUnifiedMessages::class.java)
        if (unified == null) {
            Log.w(TAG, "SteamUnifiedMessages handler unavailable; cannot fetch collections")
            return
        }
        // JavaSteam routes ServiceMethodResponse packets by service name, and only
        // createService() registers that name — without it the reply is dropped and
        // the job just times out. See CloudConfigStoreService.
        val service = try {
            unified.createService(CloudConfigStoreService::class.java)
        } catch (t: Throwable) {
            Log.e(TAG, "Cannot create CloudConfigStore service; keeping cached snapshot", t)
            return
        }

        val request = CCloudConfigStore_Download_Request.newBuilder()
            .addVersions(
                CCloudConfigStore_NamespaceVersion.newBuilder()
                    .setEnamespace(NAMESPACE_USER_COLLECTIONS)
                    .setVersion(VERSION_FULL_DOWNLOAD),
            )
            .build()

        val attempts = RETRY_DELAYS_MS.size + 1
        for (attempt in 0 until attempts) {
            if (!sameSession(repo, sessionSteamId)) return
            try {
                val job = service.download(request)
                job.timeout = JOB_TIMEOUT_MS
                // Wait on the future with our own deadline rather than runBlock()'s
                // unbounded get(), so this thread can't wedge if the job's timeout
                // never fires (the reply is completed from the callback pump).
                val response = job.toFuture()
                    .get(JOB_TIMEOUT_MS + FUTURE_TIMEOUT_SLACK_MS, TimeUnit.MILLISECONDS)
                if (response.result != EResult.OK) {
                    throw IllegalStateException("CloudConfigStore.Download returned ${response.result}")
                }
                val entries = response.body.build().dataList.flatMap { ns ->
                    ns.entriesList.map { SteamCollection.Entry(it.key, it.value, it.isDeleted) }
                }
                val parsed = SteamCollection.parse(entries)
                if (!sameSession(repo, sessionSteamId)) return
                store(parsed)
                Log.i(TAG, "Fetched ${parsed.collections.size} collections " +
                        "(${parsed.skippedDynamic} dynamic skipped) on attempt ${attempt + 1}")
                repo.emit(EVENT_SYNCED + parsed.collections.size)
                return
            } catch (t: Throwable) {
                val lastAttempt = attempt == attempts - 1
                Log.w(TAG, "Collections fetch attempt ${attempt + 1}/$attempts failed" +
                        if (lastAttempt) "; keeping cached snapshot" else "; retrying", t)
                if (lastAttempt) return
                try {
                    Thread.sleep(RETRY_DELAYS_MS[attempt])
                } catch (e: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun sameSession(repo: SteamRepository, steamId: Long): Boolean =
        repo.isLoggedIn && repo.steamId64 == steamId

    private fun store(parsed: SteamCollection.ParseResult) {
        collections = parsed.collections
        skippedDynamic = parsed.skippedDynamic
        SteamPrefs.collectionsCache = SteamCollection.toJson(parsed.collections)
        SteamPrefs.collectionsSkippedDynamic = parsed.skippedDynamic
    }
}
