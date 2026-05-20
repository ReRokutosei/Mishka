package top.yukonga.mishka.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.subscription_downloading
import mishka.shared.generated.resources.subscription_prefetching
import mishka.shared.generated.resources.subscription_updating_progress
import mishka.shared.generated.resources.subscription_validating
import org.jetbrains.compose.resources.getString
import top.yukonga.mishka.data.bridge.CoreFetchProgress
import top.yukonga.mishka.data.bridge.MishkaCoreBridge
import top.yukonga.mishka.data.bridge.MishkaCoreError
import top.yukonga.mishka.data.model.ProfileType
import top.yukonga.mishka.platform.ProfileFileManager

data class ImportProgress(
    val step: String,
    val current: Int = 0,
    val total: Int = 0,
)

class ConfigValidationException(message: String) : Exception(message)

/**
 * snapshot → fetchAndValid → commit-swap 三阶段。
 * processLock 串行整个流程，[SubscriptionRepository.profileLock] 守护 DB snapshot。
 * commit 阶段必须 NonCancellable，否则文件 swap 完成 / DB 未更新会撕裂。
 */
class ProfileProcessor(
    private val repo: SubscriptionRepository,
    private val fileManager: ProfileFileManager,
    private val proxyResolver: SubscriptionProxyResolver,
) {

    private val processLock = Mutex()

    suspend fun apply(uuid: String, onProgress: (ImportProgress) -> Unit = {}) {
        runProcess(uuid, isUpdate = false, onProgress)
    }

    suspend fun update(uuid: String, onProgress: (ImportProgress) -> Unit = {}) {
        runProcess(uuid, isUpdate = true, onProgress)
    }

    private suspend fun runProcess(
        uuid: String,
        isUpdate: Boolean,
        onProgress: (ImportProgress) -> Unit,
    ) = withContext(Dispatchers.Default) {
        processLock.withLock {
            val (snapshot, workDir) = repo.withProfileLock {
                if (isUpdate) {
                    val imported = repo.queryImported(uuid)
                        ?: throw IllegalArgumentException("Profile $uuid not found")
                    val snap = PendingSnapshot(imported.uuid, imported.name, imported.type, imported.source, imported.interval)
                    val dir = fileManager.prepareProcessing(uuid)
                    // File 类型需要保留旧 config.yaml 作基准；Url 类型会被 force=true 覆盖下载
                    fileManager.readImportedFile(uuid, "config.yaml")?.let {
                        fileManager.writeProcessingConfig(dir, it)
                    }
                    snap to dir
                } else {
                    val pending = repo.queryPending(uuid)
                        ?: throw IllegalArgumentException("No pending profile for $uuid")
                    pending.enforceFieldValid()
                    val dir = fileManager.prepareProcessing(uuid)
                    PendingSnapshot(pending.uuid, pending.name, pending.type, pending.source, pending.interval) to dir
                }
            }

            try {
                val proxyUrl = if (snapshot.type == ProfileType.Url) proxyResolver.resolve() else null

                val result = try {
                    MishkaCoreBridge.fetchAndValid(
                        workDir = workDir,
                        url = if (snapshot.type == ProfileType.Url) snapshot.source else "",
                        force = snapshot.type == ProfileType.Url,
                        httpProxy = proxyUrl,
                        onProgress = { p -> onProgress(mapProgress(p)) },
                    )
                } catch (e: MishkaCoreError) {
                    // "validate config:" 前缀区分 Parse 失败 vs fetch / unmarshal 失败，UI 文案不同
                    val msg = e.message ?: throw e
                    if (msg.startsWith("validate config:")) {
                        throw ConfigValidationException(msg.removePrefix("validate config:").trim())
                    }
                    throw e
                }

                withContext(NonCancellable) {
                    repo.withProfileLock {
                        if (isUpdate) {
                            val current = repo.queryImported(uuid)
                                ?: throw IllegalArgumentException("Imported profile $uuid disappeared during update")
                            check(current.uuid == snapshot.uuid)
                            fileManager.commitProcessingToImported(uuid)
                            repo.updateImported(
                                uuid = uuid,
                                upload = result.upload,
                                download = result.download,
                                total = result.total,
                                expire = result.expire,
                            )
                        } else {
                            val currentPending = repo.queryPending(uuid)
                                ?: throw IllegalArgumentException("Pending profile $uuid disappeared during commit")
                            check(currentPending.uuid == snapshot.uuid)
                            fileManager.commitProcessingToImported(uuid)
                            repo.commitPending(
                                uuid = uuid,
                                upload = result.upload,
                                download = result.download,
                                total = result.total,
                                expire = result.expire,
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                withContext(NonCancellable) { fileManager.cleanupProcessing() }
                throw t
            }
        }
    }

    private suspend fun mapProgress(p: CoreFetchProgress): ImportProgress = when (p.action) {
        "FetchConfiguration" -> ImportProgress(getString(Res.string.subscription_downloading))
        "FetchProviders" -> {
            val name = p.args.firstOrNull().orEmpty()
            val step = if (name.isNotEmpty() && p.max > 0) {
                getString(Res.string.subscription_updating_progress, name, p.progress + 1, p.max)
            } else {
                getString(Res.string.subscription_prefetching)
            }
            ImportProgress(step, current = p.progress, total = p.max)
        }
        "Verifying" -> ImportProgress(getString(Res.string.subscription_validating))
        else -> ImportProgress(p.action)
    }
}

// 与 PendingEntity 解耦：update 路径下 Pending DB 记录不存在，但仍需带字段做 commit
internal data class PendingSnapshot(
    val uuid: String,
    val name: String,
    val type: ProfileType,
    val source: String,
    val interval: Long,
)
