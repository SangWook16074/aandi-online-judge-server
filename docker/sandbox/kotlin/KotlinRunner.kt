import org.json.JSONArray
import org.json.JSONObject
import java.io.File

fun main() {
    val raw = System.`in`.bufferedReader().readText()

    val payload = try {
        JSONObject(raw)
    } catch (e: Exception) {
        println(buildErrorJson("INTERNAL_ERROR: failed to parse input: ${e.message}", 0.0))
        return
    }

    val code = payload.optString("code", "")
    val argsJson = payload.optJSONArray("args") ?: JSONArray()
    val argsLiteral = buildArgsLiteral(argsJson)

    val tmpDir = File("/tmp/judge_${System.nanoTime()}").also { it.mkdirs() }
    val sourceFile = File(tmpDir, "Solution.kt")
    val resultFile = File(tmpDir, "result.txt")

    sourceFile.writeText(buildSolutionSource(code, argsLiteral, resultFile.absolutePath))

    // Compile user code into a JAR
    val compileProc = ProcessBuilder(
        "kotlinc", sourceFile.absolutePath,
        "-include-runtime", "-d", File(tmpDir, "solution.jar").absolutePath
    ).redirectErrorStream(true).start()

    val compileOut = compileProc.inputStream.bufferedReader().readText()
    val compileExit = compileProc.waitFor()

    if (compileExit != 0) {
        tmpDir.deleteRecursively()
        val errMsg = compileOut.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        println(buildErrorJson("COMPILE_ERROR: $errMsg", 0.0))
        return
    }

    // Run the compiled JAR (java reads JAR as data — safe with noexec tmpfs)
    ProcessBuilder("java", "-jar", File(tmpDir, "solution.jar").absolutePath)
        .redirectErrorStream(true)
        .start()
        .waitFor()

    // Read result written by the compiled solution
    val lines = if (resultFile.exists()) resultFile.readLines() else emptyList()
    tmpDir.deleteRecursively()

    val status = lines.getOrElse(0) { "RUNTIME_ERROR" }
    val value = lines.getOrElse(1) { "no output" }
    val timeMs = lines.getOrElse(2) { "0.0" }.toDoubleOrNull() ?: 0.0

    if (status == "OK") {
        println(JSONObject()
            .put("output", value)
            .put("error", JSONObject.NULL)
            .put("timeMs", timeMs)
            .put("memoryMb", 0.0)
            .toString())
    } else {
        println(buildErrorJson("$status: $value", timeMs))
    }
}

fun buildArgsLiteral(argsJson: JSONArray): String =
    (0 until argsJson.length()).joinToString(", ") { i ->
        when (val arg = argsJson.get(i)) {
            is String -> "\"${arg.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            else -> arg.toString()
        }
    }

/** Generates Solution.kt source: user code + main() that writes result to a file. */
fun buildSolutionSource(code: String, argsLiteral: String, resultFilePath: String): String {
    val safeResultPath = resultFilePath.replace("\\", "\\\\").replace("\"", "\\\"")
    return "import java.io.File\n\n" +
        code + "\n\n" +
        "fun main() {\n" +
        "    val resultFile = File(\"$safeResultPath\")\n" +
        "    val t0 = System.nanoTime()\n" +
        "    try {\n" +
        "        val result = solution($argsLiteral)\n" +
        "        val ms = (System.nanoTime() - t0) / 1_000_000.0\n" +
        "        resultFile.writeText(\"OK\\n\" + result.toString() + \"\\n\" + ms)\n" +
        "    } catch (e: Exception) {\n" +
        "        val ms = (System.nanoTime() - t0) / 1_000_000.0\n" +
        "        resultFile.writeText(\"RUNTIME_ERROR\\n\" + (e.message ?: \"unknown error\") + \"\\n\" + ms)\n" +
        "    }\n" +
        "}\n"
}

fun buildErrorJson(error: String, timeMs: Double): String =
    JSONObject()
        .put("output", JSONObject.NULL)
        .put("error", error)
        .put("timeMs", timeMs)
        .put("memoryMb", 0.0)
        .toString()
