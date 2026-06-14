package top.yukonga.mishka.data.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger

actual object MishkaCoreBridge {

    private val tokenSeq = AtomicInteger(1)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        // mishka_jni 链接依赖 libmihomo.so 的导出符号，必须先加载。
        System.loadLibrary("mihomo")
        System.loadLibrary("mishka_jni")
    }

    actual fun init(homeDir: String, userAgent: String) {
        nativeCoreInit(homeDir, userAgent)
    }

    actual suspend fun fetchAndValid(
        workDir: String,
        url: String,
        force: Boolean,
        httpProxy: String?,
        userAgent: String,
        ageSecretKey: String,
        onProgress: suspend (CoreFetchProgress) -> Unit,
    ): CoreFetchResult = coroutineScope {
        val token = tokenSeq.getAndIncrement()
        val pollerJob = launchProgressPoller(this, token, onProgress)
        try {
            // age 全局密钥进程级共享：fetchAndValid 由 processLock 串行，fetch 前设置、后清空。
            val raw = withContext(Dispatchers.IO) {
                nativeSetAgeSecretKey(ageSecretKey)
                try {
                    nativeFetchAndValid(workDir, url, force, httpProxy, userAgent, token)
                } finally {
                    nativeSetAgeSecretKey("")
                }
            }
            pollerJob.cancel()
            interpretResult(raw)
        } catch (t: Throwable) {
            // 让 Go ctx 立即 Done，native 函数尽快返回
            runCatching { nativeCancel(token) }
            pollerJob.cancel()
            throw t
        }
    }

    private fun launchProgressPoller(
        scope: CoroutineScope,
        token: Int,
        onProgress: suspend (CoreFetchProgress) -> Unit,
    ) = scope.launch(Dispatchers.IO) {
        var last: String? = null
        while (isActive) {
            val current = nativeQueryProgress(token)
            if (current != null && current != last) {
                last = current
                runCatching { json.decodeFromString(CoreFetchProgress.serializer(), current) }
                    .onSuccess { onProgress(it) }
            }
            delay(PROGRESS_POLL_INTERVAL_MS)
        }
    }

    private fun interpretResult(raw: String?): CoreFetchResult {
        if (raw.isNullOrEmpty()) throw MishkaCoreError("native returned empty result")
        if (raw.startsWith("error:")) {
            throw MishkaCoreError(raw.removePrefix("error:").trim())
        }
        return runCatching { json.decodeFromString(CoreFetchResult.serializer(), raw) }
            .getOrElse { throw MishkaCoreError("invalid native payload: $raw") }
    }

    @JvmStatic
    private external fun nativeCoreInit(homeDir: String, userAgent: String)

    @JvmStatic
    private external fun nativeFetchAndValid(
        workDir: String,
        url: String,
        force: Boolean,
        httpProxy: String?,
        userAgent: String,
        token: Int,
    ): String?

    @JvmStatic
    private external fun nativeCancel(token: Int)

    @JvmStatic
    private external fun nativeQueryProgress(token: Int): String?

    actual fun generateAgeKeyPair(): AgeKeyPair? {
        val raw = nativeGenAgeKeyPair() ?: return null
        if (raw.startsWith("error:")) return null
        val lines = raw.split("\n")
        if (lines.size < 2) return null
        val secret = lines[0].trim()
        val public = lines[1].trim()
        if (secret.isEmpty() || public.isEmpty()) return null
        return AgeKeyPair(secretKey = secret, publicKey = public)
    }

    @JvmStatic
    private external fun nativeSetAgeSecretKey(key: String)

    @JvmStatic
    private external fun nativeGenAgeKeyPair(): String?

    private const val PROGRESS_POLL_INTERVAL_MS = 150L
}
