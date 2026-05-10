package top.yukonga.mishka.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 使用 mihomo -t 进行完整配置校验。
 * 与 MihomoRunner 不同，不需要 TUN fd 继承，可安全使用 ProcessBuilder。
 *
 * 注意：mihomo -t 在解析含 GEOIP/GEOSITE/IP-ASN 规则时会触发对应数据库 init，
 * 缺失则按 mihomo 内部 90s timeout 走 HTTP 下载，因此必须支持 proxyUrl 注入，
 * 与 MihomoPrefetcher 对称。
 */
object MihomoValidator {

    private const val TAG = "MihomoValidator"
    private const val TIMEOUT_DIRECT_MS = 120_000L
    private const val TIMEOUT_PROXY_MS = 60_000L
    private const val POLL_INTERVAL_MS = 200L

    /**
     * 对指定工作目录运行 mihomo -t 校验配置。
     *
     * @param context Android 上下文
     * @param workDir 工作目录（包含配置和 providers/）
     * @param configFileName 相对 workDir 的配置文件名，调用方可指定 override 合并后的校验专用配置
     * @param proxyUrl 非空时设置 `HTTPS_PROXY` / `HTTP_PROXY` 环境变量（mihomo 内 GeoIP/GeoSite
     *                 下载会经此代理走当前运行的 mihomo 主进程）；走代理时超时缩短至 60s。
     * @return null 表示校验通过，否则返回错误信息
     */
    suspend fun validate(
        context: Context,
        workDir: String,
        configFileName: String = "config.yaml",
        proxyUrl: String? = null,
        onProgress: ((String) -> Unit)? = null,
    ): String? = withContext(Dispatchers.IO) {
        val binary = getMihomoBinary(context)
        if (binary == null) {
            Log.e(TAG, "mihomo binary not found")
            return@withContext "mihomo binary not found"
        }

        binary.setExecutable(true)

        val configFile = File(workDir, configFileName)
        if (!configFile.exists()) {
            return@withContext "Configuration file does not exist: ${configFile.absolutePath}"
        }

        val pb = ProcessBuilder(
            binary.absolutePath,
            "-t",
            "-d", workDir,
            "-f", configFile.absolutePath,
        )
            .directory(File(workDir))
            .redirectErrorStream(true)

        if (proxyUrl != null) {
            // Go 的 net/http.DefaultTransport 读取这两个环境变量作为 HTTP proxy。
            pb.environment()["HTTPS_PROXY"] = proxyUrl
            pb.environment()["HTTP_PROXY"] = proxyUrl
        }

        val process = try {
            pb.start()
        } catch (e: Exception) {
            Log.e(TAG, "mihomo -t failed to start", e)
            return@withContext "mihomo -t failed: ${e.message ?: e.javaClass.simpleName}"
        }

        val timeoutMs = if (proxyUrl != null) TIMEOUT_PROXY_MS else TIMEOUT_DIRECT_MS

        // 独立线程消费 stdout：读在主协程会阻塞到 EOF，让后面的轮询循环形同虚设
        val outputBuilder = StringBuilder()
        val readerThread = Thread({
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        synchronized(outputBuilder) { outputBuilder.appendLine(line) }
                        if (onProgress != null) {
                            parseProgressLine(line)?.let { onProgress(it) }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }, "mihomo-validate-reader").apply {
            isDaemon = true
            start()
        }

        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                coroutineContext.ensureActive()
                if (!process.isAlive) break
                if (System.currentTimeMillis() > deadline) {
                    Log.e(
                        TAG,
                        "mihomo -t timed out after ${timeoutMs}ms (proxy=${proxyUrl != null}), output so far:\n${
                            snapshotOutput(
                                outputBuilder
                            )
                        }",
                    )
                    return@withContext "mihomo -t timed out"
                }
                delay(POLL_INTERVAL_MS)
            }
            readerThread.join(1000)
            val output = snapshotOutput(outputBuilder)
            val exitCode = process.exitValue()
            Log.i(TAG, "mihomo -t exit=$exitCode (proxy=${proxyUrl != null}), output:\n$output")
            return@withContext if (exitCode == 0) null else parseErrorMessage(output)
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                readerThread.join(500)
            }
        }
    }

    private fun snapshotOutput(sb: StringBuilder): String = synchronized(sb) { sb.toString() }

    /**
     * 从 mihomo 输出中提取错误信息。
     * mihomo 日志格式: time="..." level=error msg="..."
     */
    private fun parseErrorMessage(output: String): String {
        // 检查 GeoIP 下载超时
        if (output.contains("can't download MMDB") || output.contains("can't download GeoIP") ||
            output.contains("context deadline exceeded") && output.contains("GeoIP")
        ) {
            return "can't download GeoIP"
        }

        // 查找 level=error 或 level=fatal 的行
        val errorLines = output.lines().filter { line ->
            line.contains("level=error") || line.contains("level=fatal")
        }

        if (errorLines.isNotEmpty()) {
            // 提取 msg="..." 内容
            val msgRegex = Regex("""msg="(.+?)"""")
            val messages = errorLines.mapNotNull { line ->
                msgRegex.find(line)?.groupValues?.get(1)
            }
            if (messages.isNotEmpty()) {
                return "Invalid configuration: ${messages.joinToString("; ")}"
            }
        }

        // 如果没有标准格式的错误信息，返回最后几行
        val lastLines = output.lines().filter { it.isNotBlank() }.takeLast(3)
        return if (lastLines.isNotEmpty()) {
            "Invalid configuration: ${lastLines.joinToString(" ")}"
        } else {
            "Configuration verification failed (unknown error)"
        }
    }

    private val providerRegex = Regex("""msg=".*?(?:provider|Provider)\s+(.+?)[\s"]""")

    /**
     * 从 mihomo 日志行中提取进度信息。
     * 例如: time="..." level=info msg="Start initial provider xxx"
     */
    private fun parseProgressLine(line: String): String? {
        if (line.contains("level=error") || line.contains("level=fatal")) return null
        val match = providerRegex.find(line)
        if (match != null) return match.groupValues[1]
        return null
    }

    internal fun getMihomoBinary(context: Context): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val binary = File(nativeDir, "libmihomo.so")
        return if (binary.exists()) binary else null
    }
}
