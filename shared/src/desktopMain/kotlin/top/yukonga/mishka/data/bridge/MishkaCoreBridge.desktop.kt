package top.yukonga.mishka.data.bridge

actual object MishkaCoreBridge {
    actual fun init(homeDir: String, userAgent: String) {}

    actual suspend fun fetchAndValid(
        workDir: String,
        url: String,
        force: Boolean,
        httpProxy: String?,
        onProgress: suspend (CoreFetchProgress) -> Unit,
    ): CoreFetchResult {
        throw UnsupportedOperationException("MishkaCoreBridge.fetchAndValid not implemented on desktop")
    }
}
