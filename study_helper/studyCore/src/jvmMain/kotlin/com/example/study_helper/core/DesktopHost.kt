package com.example.study_helper.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.*

fun main(args: Array<String>) {
    require(args.size == 2)
    val directory = File(args[1]).apply { mkdirs() }
    val service = StudyService()
    fun request(id: String, type: String, body: JsonObject = buildJsonObject {}) = buildJsonObject {
        put("version", 1); put("requestId", id); put("type", type); put("body", body)
    }.toString()
    if (args[0] == "--fixtures") {
        val session = service.exchange(request("fixture-begin", "begin"))
        directory.resolve("session.json").writeText(session)
        val body = Json.parseToJsonElement(session).jsonObject["body"]!!.jsonObject
        val question = body["questions"]!!.jsonArray.first().jsonObject
        val answer = request("fixture-answer", "answer", buildJsonObject {
            put("sessionId", body["sessionId"]!!); put("questionId", question["id"]!!); put("selectedChoice", 0)
        })
        directory.resolve("graded.json").writeText(service.exchange(answer))
        println("Wrote actual Kotlin protocol responses to $directory")
        return
    }
    require(args[0] == "--serve")
    directory.resolve("request.json").delete()
    directory.resolve("response.json").delete()
    directory.resolve("ready.json").writeText("{\"version\":1}")
    println("Kotlin study bridge listening in $directory")
    Runtime.getRuntime().addShutdownHook(Thread { directory.resolve("ready.json").delete() })
    while (true) {
        val input = directory.resolve("request.json")
        if (input.exists()) {
            val response = service.exchange(input.readText())
            input.delete()
            val temporary = directory.resolve("response.tmp")
            temporary.writeText(response)
            Files.move(temporary.toPath(), directory.resolve("response.json").toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        Thread.sleep(50)
    }
}
