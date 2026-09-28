package com.example.study_helper.desktop

import com.example.study_helper.sync.DrivePlatform
import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.io.File
import java.net.*
import java.net.http.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** PKCE and a loopback listener for Google's Desktop OAuth client. Tokens live only in memory. */
class GoogleDriveDesktop(private val configuration: () -> File?, private val scope: CoroutineScope) : DrivePlatform {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER).build()
    private var tokens: JsonObject? = null
    private var expires = 0L
    private var configPath = ""
    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
    private fun form(data: Map<String, String>) = data.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
    private fun random() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    private fun tokenRequest(fields: Map<String, String>): JsonObject {
        val response = client.send(HttpRequest.newBuilder(URI("https://oauth2.googleapis.com/token"))
            .timeout(Duration.ofSeconds(40)).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form(fields))).build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            val error = runCatching { Json.parseToJsonElement(response.body()).jsonObject }.getOrNull()
            val description = error?.get("error_description")?.jsonPrimitive?.contentOrNull.orEmpty()
            if (description.contains("client_secret", ignoreCase = true)) {
                error("Google이 이 PC 클라이언트의 추가 인증 설정을 요구합니다. PC 설정에서 이 클라이언트의 데스크톱 OAuth JSON을 선택해 주세요.")
            }
            error("Google 인증을 완료하지 못했어요 (HTTP ${response.statusCode()}). 다시 연결해 주세요.")
        }
        return Json.parseToJsonElement(response.body()).jsonObject
    }
    private fun authorize(): String {
        val file = configuration()
        val source = file?.absolutePath ?: "bundled"
        if (configPath != source) { tokens = null; expires = 0; configPath = source }
        if (System.currentTimeMillis() < expires && tokens != null) return tokens!!.getValue("access_token").jsonPrimitive.content
        val config = if (file != null) {
            Json.parseToJsonElement(file.readText()).jsonObject["installed"]?.jsonObject
                ?: error("‘데스크톱 앱’ 유형의 OAuth JSON을 선택해 주세요.")
        } else {
            val id = javaClass.getResourceAsStream("/google-desktop-client-id.txt")
                ?.bufferedReader()?.use { it.readText().trim() }
            check(!id.isNullOrBlank()) { "PC Google 로그인 설정이 빌드에 포함되지 않았어요." }
            buildJsonObject { put("client_id", id) }
        }
        val id = config.getValue("client_id").jsonPrimitive.content
        val fields = mutableMapOf("client_id" to id)
        config["client_secret"]?.jsonPrimitive?.contentOrNull?.let { fields["client_secret"] = it }
        val refresh = tokens?.get("refresh_token")?.jsonPrimitive?.contentOrNull
        val received = if (refresh != null) {
            runCatching { tokenRequest(fields + mapOf("grant_type" to "refresh_token", "refresh_token" to refresh)) }.getOrElse {
                tokens = null; expires = 0; return authorize()
            }
        } else {
            val verifier = random(); val state = random()
            val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val redirect = "http://127.0.0.1:${server.address.port}/oauth2callback"
            val result = CompletableFuture<JsonObject>()
            server.createContext("/oauth2callback") { exchange ->
                val params = exchange.requestURI.rawQuery.orEmpty().split('&').mapNotNull {
                    val pair = it.split('=', limit = 2)
                    if (pair.size == 2) URLDecoder.decode(pair[0], Charsets.UTF_8) to URLDecoder.decode(pair[1], Charsets.UTF_8) else null
                }.toMap()
                val valid = exchange.requestURI.path == "/oauth2callback" && params["state"] == state
                val outcome = if (valid) runCatching {
                    val code = params["code"] ?: error("Google 연결을 취소했어요.")
                    tokenRequest(fields + mapOf("code" to code, "code_verifier" to verifier,
                        "redirect_uri" to redirect, "grant_type" to "authorization_code"))
                } else null
                val text = when {
                    outcome == null -> "Invalid OAuth callback. Return to Study Helper and try again."
                    outcome.isSuccess -> "Google 인증이 완료됐습니다. Study Helper로 돌아가 Drive 폴더를 선택해 주세요."
                    else -> "Google 연결에 실패했습니다.\n${outcome.exceptionOrNull()?.message}\nStudy Helper로 돌아가 설정을 확인해 주세요."
                }
                try {
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
                    exchange.responseHeaders.set("Cache-Control", "no-store")
                    exchange.sendResponseHeaders(if (outcome?.isSuccess == true) 200 else 400, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } finally {
                    outcome?.fold(result::complete, result::completeExceptionally)
                    exchange.close()
                }
            }
            server.start()
            try {
                val query = form(mapOf("client_id" to id, "redirect_uri" to redirect, "response_type" to "code",
                    "scope" to "https://www.googleapis.com/auth/drive", "code_challenge" to challenge,
                    "code_challenge_method" to "S256", "state" to state, "access_type" to "offline", "prompt" to "consent"))
                Desktop.getDesktop().browse(URI("https://accounts.google.com/o/oauth2/v2/auth?$query"))
                try { result.get(150, TimeUnit.SECONDS) }
                catch (error: java.util.concurrent.ExecutionException) { throw (error.cause ?: error) }
            } finally { server.stop(0) }
        }
        tokens = JsonObject(tokens.orEmpty() + received)
        expires = System.currentTimeMillis() + ((received["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600) - 120) * 1000
        return received.getValue("access_token").jsonPrimitive.content
    }
    override fun authorizeDrive(completion: (String) -> Unit) {
        scope.launch {
            val response = withContext(Dispatchers.IO) { runCatching { buildJsonObject { put("accessToken", authorize()) } }
                .getOrElse { buildJsonObject { put("error", it.message ?: "Google 연결에 실패했어요.") } } }
            completion(response.toString())
        }
    }
    override fun driveRequest(request: String, completion: (String) -> Unit) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val data = Json.parseToJsonElement(request).jsonObject
                val uri = URI(data.getValue("url").jsonPrimitive.content)
                require(uri.scheme == "https" && uri.host == "www.googleapis.com")
                val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45))
                data.getValue("headers").jsonObject.forEach { (key, value) -> builder.header(key, value.jsonPrimitive.content) }
                val body = data.getValue("body").jsonPrimitive.content
                builder.method(data.getValue("method").jsonPrimitive.content, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
                val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
                val bytes = response.body().use { it.readNBytes(4_000_001) }
                require(bytes.size <= 4_000_000) { "Drive 응답이 너무 커요." }
                buildJsonObject { put("status", response.statusCode()); put("body", bytes.toString(Charsets.UTF_8)) }
            }.getOrElse { buildJsonObject { put("error", it.message ?: "네트워크 연결을 확인해 주세요.") } } }
            if (result["status"]?.jsonPrimitive?.intOrNull == 401) expires = 0
            completion(result.toString())
        }
    }
    override fun disconnectDrive() { tokens = null; expires = 0 }
}
