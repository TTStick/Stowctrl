package com.wanwu.nook.net

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException as ClaudeUnauthorized
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.wanwu.nook.image.SvgDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

/**
 * Draws icons with Claude, either through the user's sync server (the key
 * lives there) or straight from this device with the user's own key (kept
 * encrypted on this device, never synced) — the latter needs no server.
 */
class AIService(private val prefs: SharedPreferences, private val secrets: SecretStore?) {
    enum class Source { server, direct }
    enum class Availability { Ready, NeedsKey, Offline, NeedsLogin, ServerWithoutLLM }

    var source by mutableStateOf(runCatching { Source.valueOf(prefs.getString("ai.source", "server")!!) }.getOrDefault(Source.server))
        private set
    var baseUrl by mutableStateOf(prefs.getString("ai.baseURL", "") ?: "")
        private set
    var model by mutableStateOf(prefs.getString("ai.model", DEFAULT_MODEL) ?: DEFAULT_MODEL)
        private set
    var hasKey by mutableStateOf(!secrets?.get(KEY).isNullOrEmpty())
        private set

    fun updateSource(v: Source) { source = v; prefs.edit().putString("ai.source", v.name).apply() }
    fun updateBaseUrl(v: String) { baseUrl = v; prefs.edit().putString("ai.baseURL", v).apply() }
    fun updateModel(v: String) { model = v; prefs.edit().putString("ai.model", v).apply() }
    fun updateKey(v: String?) { secrets?.put(KEY, v?.trim()?.ifEmpty { null }); hasKey = !v.isNullOrBlank() }
    fun savedKey(): String = secrets?.get(KEY).orEmpty()

    fun availability(sync: SyncManager): Availability = when (source) {
        Source.direct -> if (hasKey) Availability.Ready else Availability.NeedsKey
        Source.server -> when {
            sync.offline -> Availability.Offline
            !sync.isLoggedIn -> Availability.NeedsLogin
            sync.canGenerateIcons -> Availability.Ready
            else -> Availability.ServerWithoutLLM
        }
    }

    suspend fun generate(prompt: String, sync: SyncManager): String {
        val svg = when (source) {
            Source.server -> (sync.client ?: throw ApiException("服务器地址不正确")).generateIcon(prompt)
            Source.direct -> DirectClaude(savedKey().ifEmpty { throw ApiException("请先填写 API Key") }, baseUrl, model.ifBlank { DEFAULT_MODEL })
                .drawIcon(prompt)
        }
        if (SvgDocument.parse(svg) == null) throw ApiException("这次画出来的图标无法显示，再试一次吧")
        return svg
    }

    suspend fun checkKey(key: String) = DirectClaude(key, baseUrl, model).checkKey()

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        private const val KEY = "ai.apiKey"
    }
}

/** Calls the Claude Messages API with the official Java SDK. */
class DirectClaude(private val key: String, private val baseUrl: String, private val model: String) {
    private fun client() = AnthropicOkHttpClient.builder()
        .apiKey(key)
        .apply { if (baseUrl.isNotBlank()) baseUrl(baseUrl.trim().trimEnd('/')) }
        .timeout(Duration.ofSeconds(180))
        .maxRetries(2)
        .build()

    private fun params(prompt: String, withFallbacks: Boolean) = MessageCreateParams.builder()
        .model(model)
        .maxTokens(16000)
        .system(SYSTEM_PROMPT)
        .addUserMessage("要画的物品：$prompt")
        .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
        .apply {
            // "default" lets the API pick a fallback model if this one declines.
            if (withFallbacks) {
                putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }
        }
        .build()

    suspend fun drawIcon(prompt: String): String = withContext(Dispatchers.IO) {
        val client = client()
        try {
            val message = try {
                client.messages().create(params(prompt, true))
            } catch (e: BadRequestException) {
                // Gateways that don't know the fallback beta reject it; retry plainly.
                client.messages().create(params(prompt, false))
            }
            if (message.stopReason().orElse(null) == StopReason.REFUSAL) throw ApiException("大模型拒绝绘制这个图标，换个描述试试")
            val text = message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
            Regex("<svg[\\s\\S]*?</svg>", RegexOption.IGNORE_CASE).find(text)?.value
                ?: throw ApiException("大模型没有返回有效的图标，请重试")
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(describe(e))
        } finally {
            client.close()
        }
    }

    /** Validates the key without spending tokens (lists models). */
    suspend fun checkKey() = withContext(Dispatchers.IO) {
        val client = client()
        try {
            client.models().list()
            Unit
        } catch (e: Exception) {
            throw ApiException(describe(e))
        } finally {
            client.close()
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is ClaudeUnauthorized -> "API Key 无效"
        is PermissionDeniedException -> "这个 API Key 没有调用权限"
        is NotFoundException -> "找不到模型 $model，或 API 地址不对"
        is RateLimitException -> "调用太频繁，请稍后再试"
        is BadRequestException -> "请求被拒绝：${e.message}"
        is AnthropicServiceException -> "大模型服务出错（${e.statusCode()}），请稍后再试"
        is AnthropicIoException -> "连接不上大模型服务，请检查网络或 API 地址"
        else -> e.message ?: "调用大模型失败"
    }

    companion object {
        /** Same instructions the server uses (server/app/icons.py). */
        val SYSTEM_PROMPT = """
            You draw small icons for a home-inventory app. The user names a household object (usually in Chinese); you reply with exactly one SVG document and nothing else.

            Style:
            - Simple, instantly recognisable outline icon in the spirit of SF Symbols or Lucide.
            - Monochrome. Every shape uses stroke="currentColor" and fill="none". fill="currentColor" is allowed only for tiny solid details such as dots or buttons.
            - stroke-width="2.5", stroke-linecap="round", stroke-linejoin="round".
            - Show the object alone from its most recognisable angle, centred, with about 4 units of padding.
            - At most 14 shapes. No background, no frame, no shadow, no text or letters.

            Format:
            - <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48">.
            - Only these elements: g, path, circle, ellipse, rect, line, polyline, polygon.
            - No transform, style, class, id, defs, gradients, filters, masks, clipPath, image, text or script.
            - Absolute coordinates inside the 48x48 viewBox.
        """.trimIndent()
    }
}
