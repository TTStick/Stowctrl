package com.wanwu.nook.net

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wanwu.nook.data.Furniture
import com.wanwu.nook.data.HomeStore
import com.wanwu.nook.data.NookJson
import com.wanwu.nook.data.clean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class HAState(
    val entity_id: String,
    val state: String = "",
    val attributes: Map<String, JsonElement> = emptyMap(),
) {
    val domain get() = entity_id.substringBefore('.')
    val name get() = (attributes["friendly_name"] as? JsonPrimitive)?.contentOrNull ?: entity_id
    val unit get() = (attributes["unit_of_measurement"] as? JsonPrimitive)?.contentOrNull
    fun number(key: String) = (attributes[key] as? JsonPrimitive)?.doubleOrNull
    val isOn get() = state in setOf("on", "open", "opening", "playing", "unlocked", "home", "cleaning",
        "heat", "cool", "auto", "heat_cool", "dry", "fan_only")
    val isUnavailable get() = state == "unavailable" || state == "unknown"
}

/** What the app can do with an entity, by domain (same table as iOS). */
enum class HAControl {
    Toggle, Cover, Lock, Climate, Media, Activate, Press, Vacuum, ReadOnly;
    companion object {
        fun of(domain: String) = when (domain) {
            "light", "switch", "fan", "input_boolean", "automation", "humidifier", "siren", "water_heater" -> Toggle
            "cover", "valve" -> Cover
            "lock" -> Lock
            "climate" -> Climate
            "media_player" -> Media
            "scene", "script" -> Activate
            "button", "input_button" -> Press
            "vacuum", "lawn_mower" -> Vacuum
            else -> ReadOnly
        }
    }
}

object HADomain {
    fun title(domain: String) = when (domain) {
        "light" -> "灯"; "switch" -> "开关"; "fan" -> "风扇"; "cover" -> "窗帘/卷帘"; "lock" -> "门锁"
        "climate" -> "空调/温控"; "media_player" -> "影音"; "sensor" -> "传感器"; "binary_sensor" -> "状态传感器"
        "camera" -> "摄像头"; "vacuum" -> "扫地机"; "scene" -> "场景"; "script" -> "脚本"; "automation" -> "自动化"
        "button", "input_button" -> "按钮"; "humidifier" -> "加湿器"; "input_boolean" -> "辅助开关"; "water_heater" -> "热水器"
        else -> domain
    }

    val mappable = setOf("light", "switch", "fan", "cover", "lock", "climate", "media_player", "sensor", "binary_sensor",
        "camera", "vacuum", "scene", "script", "button", "humidifier", "water_heater", "valve", "input_boolean",
        "lawn_mower", "siren")

    fun stateText(s: HAState): String {
        if (s.isUnavailable) return "不可用"
        return when (s.domain) {
            "sensor" -> listOfNotNull(s.state, s.unit).joinToString(" ")
            "climate" -> {
                val modes = mapOf("off" to "关闭", "cool" to "制冷", "heat" to "制热", "auto" to "自动", "dry" to "除湿",
                    "fan_only" to "送风", "heat_cool" to "冷暖")
                listOfNotNull(modes[s.state] ?: s.state, s.number("current_temperature")?.let { "${it.clean()}°" },
                    s.number("temperature")?.let { "目标 ${it.clean()}°" }).joinToString(" · ")
            }
            "cover" -> mapOf("open" to "已打开", "closed" to "已关闭", "opening" to "打开中", "closing" to "关闭中")[s.state] ?: s.state
            "lock" -> mapOf("locked" to "已上锁", "unlocked" to "未上锁")[s.state] ?: s.state
            "media_player" -> mapOf("playing" to "播放中", "paused" to "已暂停", "off" to "已关闭", "idle" to "待机", "on" to "待机")[s.state] ?: s.state
            "binary_sensor" -> if (s.state == "on") "触发" else "正常"
            "scene", "script", "button", "input_button" -> ""
            else -> mapOf("on" to "开", "off" to "关")[s.state] ?: s.state
        }
    }
}

class HAClient(private val baseUrl: String, private val token: String) {
    private val http: OkHttpClient = ApiClient.http.newBuilder().readTimeout(10, TimeUnit.SECONDS).build()

    private suspend fun send(req: Request): String = withContext(Dispatchers.IO) {
        val r = try {
            http.newCall(req).execute()
        } catch (e: IOException) {
            throw ApiException("连接不上 Home Assistant，请确认手机和 HA 在同一网络")
        }
        r.use {
            when (it.code) {
                in 200..299 -> it.body?.string() ?: ""
                401 -> throw UnauthorizedException("Home Assistant 令牌无效")
                404 -> throw ApiException("找不到这个 Home Assistant 接口，请检查地址", 404)
                else -> throw ApiException("Home Assistant 返回错误（${it.code}）", it.code)
            }
        }
    }

    private fun req(path: String) = Request.Builder().url("$baseUrl/$path").header("Authorization", "Bearer $token")

    suspend fun check() { send(req("api/").get().build()) }

    suspend fun states(): List<HAState> {
        val body = send(req("api/states").get().build())
        return runCatching { NookJson.decodeFromString(ListSerializer(HAState.serializer()), body) }
            .getOrElse { throw ApiException("Home Assistant 返回的数据无法识别") }
    }

    suspend fun call(domain: String, service: String, entity: String, extra: Map<String, JsonElement> = emptyMap()) {
        val payload = JsonObject(extra + ("entity_id" to JsonPrimitive(entity))).toString()
        send(req("api/services/$domain/$service").post(payload.toRequestBody("application/json".toMediaType())).build())
    }
}

/** Live device states for the furniture they're mapped onto. */
class HomeAssistantManager(private val store: HomeStore, private val scope: CoroutineScope) {
    var states by mutableStateOf<Map<String, HAState>>(emptyMap())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var lastUpdated by mutableStateOf<Long?>(null)
        private set
    private var poller: Job? = null

    val client: HAClient?
        get() {
            val url = ApiClient.normalize(store.settings.haURL) ?: return null
            if (store.haToken.isBlank()) return null
            return HAClient(url, store.haToken)
        }

    val isConfigured get() = client != null

    val sortedStates get() = states.values.sortedWith(compareBy({ it.domain }, { it.name }))

    suspend fun refresh() {
        val c = client ?: run { states = emptyMap(); error = null; return }
        isLoading = true
        try {
            states = c.states().associateBy { it.entity_id }
            error = null
            lastUpdated = System.currentTimeMillis()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        } finally {
            isLoading = false
        }
    }

    fun startPolling() {
        if (poller != null) return
        poller = scope.launch {
            while (isActive) {
                refresh()
                delay(8000)
            }
        }
    }

    fun stopPolling() { poller?.cancel(); poller = null }

    suspend fun perform(domain: String, service: String, entity: String, extra: Map<String, JsonElement> = emptyMap()) {
        val c = client ?: return
        try {
            c.call(domain, service, entity, extra)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        }
        delay(450)
        refresh()
    }

    suspend fun toggle(s: HAState) = perform(s.domain, if (s.isOn) "turn_off" else "turn_on", s.entity_id)

    fun furnitureFor(entityId: String): Furniture? = store.furniture.values.firstOrNull { entityId in it.haEntities }

    fun isPowered(f: Furniture) = f.haEntities.any { id ->
        val s = states[id] ?: return@any false
        s.domain in setOf("light", "switch", "fan", "media_player", "climate", "humidifier") && s.isOn
    }
}
