package com.wanwu.nook.net

import com.wanwu.nook.data.NookJson
import com.wanwu.nook.data.SyncChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

open class ApiException(message: String, val status: Int = 0) : IOException(message)
class UnauthorizedException(message: String) : ApiException(message, 401)

@Serializable
data class ServerInfo(val name: String = "", val version: String = "", val registrationOpen: Boolean = true, val features: Features = Features()) {
    @Serializable data class Features(val icons: Boolean = false, val cutout: Boolean = false)
}

@Serializable
data class AuthResponse(val token: String, val user: User) {
    @Serializable data class User(val id: String, val username: String)
}

@Serializable
data class SyncResponse(val cursor: Long, val changes: List<SyncChange> = emptyList(), val hasMore: Boolean = false,
                        val accepted: Int = 0, val reset: Boolean? = null)

/** Thin client for the self-hosted server (same API the iOS app uses). */
class ApiClient(val baseUrl: String, private val token: String? = null) {

    companion object {
        private val json = "application/json".toMediaType()
        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
        private val slow: OkHttpClient = http.newBuilder().readTimeout(180, TimeUnit.SECONDS).build()

        /** Accepts "192.168.1.5:5858", "nook.example.com" or a full URL. */
        fun normalize(raw: String): String? {
            var s = raw.trim()
            if (s.isEmpty()) return null
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            s = s.trimEnd('/')
            return s.toHttpUrlOrNull()?.let { s }
        }
    }

    private fun request(path: String) = Request.Builder().url("$baseUrl/$path").apply {
        token?.let { header("Authorization", "Bearer $it") }
    }

    private suspend fun execute(req: Request, client: OkHttpClient = http): ByteArray = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(req).execute()
        } catch (e: SocketTimeoutException) {
            throw ApiException("连接服务器超时")
        } catch (e: UnknownHostException) {
            throw ApiException("连接不上服务器，请检查地址和网络")
        } catch (e: ConnectException) {
            throw ApiException("连接不上服务器，请检查地址和网络")
        } catch (e: IOException) {
            throw ApiException("网络错误：${e.message}")
        }
        response.use { r ->
            val body = r.body?.bytes() ?: ByteArray(0)
            if (!r.isSuccessful) {
                val detail = runCatching {
                    NookJson.parseToJsonElement(body.decodeToString()).jsonObject["detail"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                val message = detail ?: "服务器错误（${r.code}）"
                if (r.code == 401) throw UnauthorizedException(message)
                throw ApiException(message, r.code)
            }
            body
        }
    }

    private suspend fun <T> call(method: String, path: String, body: RequestBody?, out: KSerializer<T>, client: OkHttpClient = http): T {
        val bytes = execute(request(path).method(method, body).build(), client)
        return try {
            NookJson.decodeFromString(out, bytes.decodeToString())
        } catch (e: Exception) {
            throw ApiException("服务器返回的数据无法识别，请确认地址指向万物收纳服务端")
        }
    }

    private fun jsonBody(obj: JsonObject) = obj.toString().toRequestBody(json)

    private fun body(vararg pairs: Pair<String, Any>): RequestBody {
        val map = pairs.associate { (k, v) ->
            k to when (v) {
                is String -> kotlinx.serialization.json.JsonPrimitive(v)
                is Number -> kotlinx.serialization.json.JsonPrimitive(v)
                is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
                is kotlinx.serialization.json.JsonElement -> v
                else -> error("unsupported")
            }
        }
        return jsonBody(JsonObject(map))
    }

    suspend fun info(): ServerInfo = call("GET", "api/info", null, ServerInfo.serializer())

    suspend fun login(username: String, password: String, device: String, register: Boolean): AuthResponse =
        call("POST", if (register) "api/auth/register" else "api/auth/login",
            body("username" to username, "password" to password, "deviceName" to device), AuthResponse.serializer())

    suspend fun logout() { execute(request("api/auth/logout").post(body()).build()) }

    suspend fun changePassword(old: String, new: String) {
        execute(request("api/auth/password").post(body("oldPassword" to old, "newPassword" to new)).build())
    }

    suspend fun deleteAccount(password: String) {
        execute(request("api/me/delete").post(body("password" to password)).build())
    }

    @Serializable private data class SyncBody(val cursor: Long, val changes: List<SyncChange>, val limit: Int)

    suspend fun sync(cursor: Long, changes: List<SyncChange>, limit: Int = 500): SyncResponse {
        val payload = NookJson.encodeToString(SyncBody.serializer(), SyncBody(cursor, changes, limit)).toRequestBody(json)
        return call("POST", "api/sync", payload, SyncResponse.serializer())
    }

    suspend fun upload(fileId: String, bytes: ByteArray) {
        val type = (if (fileId.endsWith(".png")) "image/png" else "image/jpeg").toMediaType()
        execute(request("api/files/$fileId").put(bytes.toRequestBody(type)).build())
    }

    suspend fun download(fileId: String): ByteArray = execute(request("api/files/$fileId").get().build())

    @Serializable private data class SvgResponse(val svg: String)

    suspend fun generateIcon(prompt: String): String =
        call("POST", "api/icons/generate", body("prompt" to prompt), SvgResponse.serializer(), slow).svg

    suspend fun cutout(jpeg: ByteArray): ByteArray =
        execute(request("api/cutout").post(jpeg.toRequestBody("image/jpeg".toMediaType())).build(), slow)
}
