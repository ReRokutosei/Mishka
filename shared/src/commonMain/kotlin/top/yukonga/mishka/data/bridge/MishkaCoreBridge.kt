package top.yukonga.mishka.data.bridge

import kotlinx.serialization.Serializable

expect object MishkaCoreBridge {
    /** 设置 mihomo 全局 homeDir 与 HTTP UA；进程内只生效一次。 */
    fun init(homeDir: String, userAgent: String)

    /**
     * fetch + provider prefetch + Parse 三步合一。
     * 协程取消时 bridge 内部调 nativeCancel，让 Go ctx 立即返回。
     */
    suspend fun fetchAndValid(
        workDir: String,
        url: String,
        force: Boolean,
        httpProxy: String?,
        onProgress: suspend (CoreFetchProgress) -> Unit,
    ): CoreFetchResult
}

/** action: FetchConfiguration / FetchProviders / Verifying；args 视 action 而定。 */
@Serializable
data class CoreFetchProgress(
    val action: String,
    val args: List<String> = emptyList(),
    val progress: Int = -1,
    val max: Int = -1,
)

@Serializable
data class CoreFetchResult(
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0,
    val updateInterval: Long = 0,
    val hasUserinfo: Boolean = false,
)

class MishkaCoreError(message: String) : RuntimeException(message)
