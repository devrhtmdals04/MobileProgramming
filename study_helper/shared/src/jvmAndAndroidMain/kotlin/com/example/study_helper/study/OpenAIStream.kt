package com.example.study_helper.study

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*

/** Native transport shared by desktop and Android; never logs request bodies or credentials. */
class OpenAIStream(
    private val scope: CoroutineScope,
    private val connectionFactory: () -> HttpURLConnection = {
        URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection
    },
) {
    private var job: Job? = null
    @Volatile private var connection: HttpURLConnection? = null
    fun cancel() { job?.cancel(); connection?.disconnect(); connection = null }
    fun generate(key: String, model: String, body: String, event: (String) -> Unit, completion: (String) -> Unit) {
        cancel()
        job = scope.launch {
            val failure = try {
                withContext(Dispatchers.IO) {
                    val c = connectionFactory()
                    connection = c
                    try {
                        c.requestMethod = "POST"; c.doOutput = true
                        c.connectTimeout = 30_000; c.readTimeout = 180_000
                        c.setRequestProperty("Content-Type", "application/json")
                        c.setRequestProperty("Authorization", "Bearer $key")
                        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                        require(c.responseCode in 200..299) { when (c.responseCode) {
                            400 -> "요청을 처리하지 못했어요. PDF와 모델 설정을 확인해 주세요."
                            401, 403 -> "API 키와 사용 권한을 확인해 주세요."
                            404 -> "모델을 찾지 못했어요. API 설정의 모델 이름을 확인해 주세요."
                            429 -> "API 사용 한도에 도달했어요. 잠시 후 다시 시도해 주세요."
                            else -> "AI 서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요."
                        } }
                        c.inputStream.bufferedReader().use { reader ->
                            var data = StringBuilder()
                            while (true) {
                                ensureActive()
                                val line = reader.readLine() ?: break
                                if (line.isEmpty() && data.isNotEmpty()) {
                                    val value = data.toString(); data = StringBuilder()
                                    withContext(Dispatchers.Main) { event(value) }
                                } else if (line.startsWith("data:")) {
                                    if (data.isNotEmpty()) data.append('\n')
                                    data.append(line.removePrefix("data:").trimStart())
                                }
                            }
                            if (data.isNotEmpty()) withContext(Dispatchers.Main) { event(data.toString()) }
                        }
                    } finally { c.disconnect(); if (connection === c) connection = null }
                }
                ""
            } catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { e.message ?: "AI 요청에 실패했어요." }
            catch (_: Exception) { "연결이 끊겼거나 응답 시간이 초과됐어요. 다시 시도해 주세요." }
            completion(failure)
        }
    }
}
