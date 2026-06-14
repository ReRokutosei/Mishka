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
        userAgent: String,
        ageSecretKey: String,
        onProgress: suspend (CoreFetchProgress) -> Unit,
    ): CoreFetchResult

    /** 生成 age 密钥对（hybrid=true 为 mlkem768-x25519 抗量子类型）；不支持的平台返回 null。 */
    fun generateAgeKeyPair(hybrid: Boolean = false): AgeKeyPair?
}

/** age x25519 密钥对：secretKey 用于解密订阅，publicKey 供加密方使用。 */
data class AgeKeyPair(val secretKey: String, val publicKey: String)

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
)

class MishkaCoreError(message: String) : RuntimeException(message)
