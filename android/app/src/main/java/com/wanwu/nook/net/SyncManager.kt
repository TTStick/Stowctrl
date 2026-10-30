package com.wanwu.nook.net

import android.content.SharedPreferences
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wanwu.nook.data.HomeStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Account, server connection and the push/pull loop (mirrors iOS SyncManager). */
class SyncManager(
    private val store: HomeStore,
    private val prefs: SharedPreferences,
    private val secrets: SecretStore?,
    private val scope: CoroutineScope,
) {
    sealed interface Status {
        data object Idle : Status
        data object Syncing : Status
        data class Failed(val message: String) : Status
    }

    enum class LoginOutcome { Done, NeedsMergeDecision }

    var serverUrl by mutableStateOf(prefs.getString("sync.serverURL", "") ?: "")
        private set
    var username by mutableStateOf(prefs.getString("sync.username", null))
        private set
    var status by mutableStateOf<Status>(Status.Idle)
        private set
    var lastSyncAt by mutableStateOf(prefs.getLong("sync.lastSyncAt", 0L).takeIf { it > 0 })
        private set
    var serverInfo by mutableStateOf<ServerInfo?>(null)
        private set
    private var token: String? = secrets?.get("sync.token")
    var isLoggedIn by mutableStateOf(token != null && serverUrl.isNotEmpty())
        private set
    /** Offline mode: never talk to the sync server; edits queue up locally. */
    var offline by mutableStateOf(prefs.getBoolean("sync.offline", false))
        private set
    /** Signed in and allowed to reach the server right now. */
    val isOnline get() = isLoggedIn && !offline

    private var debounce: Job? = null
    private var timer: Job? = null
    private var running = false
    private var again = false

    init {
        store.onLocalChange = { scheduleSync() }
        store.files.remoteFetcher = { id -> fetchFile(id) }
    }

    val client: ApiClient? get() = ApiClient.normalize(serverUrl)?.let { ApiClient(it, token) }
    val canGenerateIcons get() = isOnline && serverInfo?.features?.icons == true
    val canServerCutout get() = isOnline && serverInfo?.features?.cutout == true

    fun updateOffline(value: Boolean) {
        offline = value
        prefs.edit().putBoolean("sync.offline", value).apply()
        if (value) { debounce?.cancel(); status = Status.Idle } else scope.launch { syncNow() }
    }

    suspend fun checkServer(raw: String): ServerInfo {
        val url = ApiClient.normalize(raw) ?: throw ApiException("服务器地址不正确")
        return ApiClient(url).info()
    }

    suspend fun signIn(server: String, user: String, password: String, register: Boolean, inviteCode: String = ""): LoginOutcome {
        val url = ApiClient.normalize(server) ?: throw ApiException("服务器地址不正确")
        val api = ApiClient(url)
        val info = api.info()
        val auth = api.login(user.trim(), password, Build.MODEL ?: "Android", register, inviteCode)
        serverUrl = url
        token = auth.token
        updateOffline(false)
        username = auth.user.username
        serverInfo = info
        persist()
        val remote = ApiClient(url, auth.token).sync(0, emptyList(), 1)
        if (!store.hasAnyData) {
            store.cursor = 0
            isLoggedIn = true
            syncNow()
            return LoginOutcome.Done
        }
        if (remote.changes.isEmpty()) {
            store.markAllPending()
            isLoggedIn = true
            syncNow()
            return LoginOutcome.Done
        }
        return LoginOutcome.NeedsMergeDecision
    }

    suspend fun resolveMerge(keepLocal: Boolean) {
        if (keepLocal) store.markAllPending() else store.wipeLocal()
        store.cursor = 0
        isLoggedIn = true
        syncNow()
    }

    suspend fun abandonSignIn() {
        runCatching { client?.logout() }
        clearAccount()
    }

    suspend fun signOut(wipeLocal: Boolean) {
        runCatching { client?.logout() }
        clearAccount()
        if (wipeLocal) store.wipeLocal()
    }

    suspend fun changePassword(old: String, new: String) {
        (client ?: throw ApiException("服务器地址不正确")).changePassword(old, new)
    }

    suspend fun deleteAccount(password: String) {
        (client ?: throw ApiException("服务器地址不正确")).deleteAccount(password)
        clearAccount()
    }

    private fun clearAccount() {
        token = null
        username = null
        isLoggedIn = false
        serverInfo = null
        status = Status.Idle
        lastSyncAt = null
        store.cursor = 0
        persist()
    }

    private fun persist() {
        prefs.edit()
            .putString("sync.serverURL", serverUrl)
            .putString("sync.username", username)
            .putLong("sync.lastSyncAt", lastSyncAt ?: 0L)
            .apply()
        secrets?.put("sync.token", token)
    }

    suspend fun refreshServerInfo() {
        if (!isOnline) return
        serverInfo = runCatching { client?.info() }.getOrNull() ?: serverInfo
    }

    fun scheduleSync(afterMs: Long = 2000) {
        if (!isOnline) return
        debounce?.cancel()
        debounce = scope.launch {
            delay(afterMs)
            syncNow()
        }
    }

    fun startPeriodicSync() {
        timer?.cancel()
        timer = scope.launch {
            while (isActive) {
                syncNow()
                delay(60_000)
            }
        }
    }

    fun stopPeriodicSync() { timer?.cancel(); timer = null }

    suspend fun syncNow() {
        val api = client ?: return
        if (!isOnline) return
        if (running) { again = true; return }
        running = true
        status = Status.Syncing
        try {
            do {
                again = false
                runOnce(api)
            } while (again)
            status = Status.Idle
            lastSyncAt = System.currentTimeMillis()
            persist()
            if (serverInfo == null) serverInfo = runCatching { api.info() }.getOrNull()
        } catch (e: CancellationException) {
            status = Status.Idle
            throw e
        } catch (e: UnauthorizedException) {
            clearAccount()
            status = Status.Failed("登录已失效，请重新登录")
        } catch (e: Exception) {
            status = Status.Failed(e.message ?: "同步失败")
        } finally {
            running = false
        }
    }

    private suspend fun runOnce(api: ApiClient) {
        // Photos first, so records never point at files the server lacks.
        for (fileId in store.pendingUploads) {
            val bytes = store.files.read(fileId)
            if (bytes == null) { store.markUploaded(fileId); continue }
            try {
                api.upload(fileId, bytes)
                store.markUploaded(fileId)
            } catch (e: ApiException) {
                if (e.status in 400..499 && e !is UnauthorizedException) store.markUploaded(fileId) else throw e
            }
        }
        var pulled = false
        while (true) {
            val out = store.outgoingChanges(400)
            val r = api.sync(store.cursor, out.map { it.change })
            if (r.reset == true) { store.markAllPending(); continue }
            store.acknowledge(out)
            store.applyRemote(r.changes)
            store.cursor = r.cursor
            pulled = pulled || r.changes.isNotEmpty()
            if (!r.hasMore && (store.pendingCount == 0 || out.isEmpty())) break
        }
        if (pulled) store.repairOrphans()
    }

    suspend fun fetchFile(id: String): ByteArray? {
        if (!isOnline) return null
        return runCatching { client?.download(id) }.getOrNull()
    }
}
