import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

// go 工具链交叉编译 Android 产物。BuildMode.CShared 走 cgo + NDK clang，Default 走纯 Go。
abstract class GoBuildTask : DefaultTask() {

    enum class BuildMode { Default, CShared }

    @get:Inject
    abstract val execOps: ExecOperations

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val goSourceDir: DirectoryProperty

    @get:Input
    abstract val abi: Property<String>

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val buildTags: ListProperty<String>

    @get:Input
    abstract val cgoEnabled: Property<Boolean>

    @get:Input
    abstract val buildMode: Property<BuildMode>

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val ndkDirectory: DirectoryProperty

    @get:Input
    @get:Optional
    abstract val minSdk: Property<Int>

    @get:Input
    abstract val moduleVersionPath: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun build() {
        val outFile = outputFile.get().asFile
        outFile.parentFile.mkdirs()

        val goArch = ABI_TO_GOARCH[abi.get()]
            ?: error("Unsupported ABI: ${abi.get()}; expected one of ${ABI_TO_GOARCH.keys}")

        val args = mutableListOf<String>("go", "build")
        buildTags.get().takeIf { it.isNotEmpty() }?.let {
            args += "-tags"
            args += it.joinToString(",")
        }
        args += "-trimpath"
        // 不设 SONAME，消费方链接器会把构建期绝对路径烙进 DT_NEEDED，运行时 dlopen 必失败
        val ldflagsBuilder = StringBuilder("-s -w -X ${moduleVersionPath.get()}=${versionName.get()}")
        if (buildMode.get() == BuildMode.CShared) {
            ldflagsBuilder.append(" -extldflags=-Wl,-soname,").append(outFile.name)
        }
        args += "-ldflags"
        args += ldflagsBuilder.toString()
        if (buildMode.get() == BuildMode.CShared) {
            args += "-buildmode=c-shared"
        }
        args += "-o"
        args += outFile.absolutePath
        args += "."

        execOps.exec {
            workingDir = goSourceDir.get().asFile
            commandLine = args

            environment("GOOS", "android")
            environment("GOARCH", goArch)
            environment("CGO_ENABLED", if (cgoEnabled.get()) "1" else "0")

            if (cgoEnabled.get()) {
                val ndk = ndkDirectory.orNull?.asFile
                    ?: error("ndkDirectory is required for cgo builds")
                val sdkLevel = minSdk.orNull
                    ?: error("minSdk is required for cgo builds")
                environment("CC", resolveClang(ndk, abi.get(), sdkLevel))
            }
        }
    }

    private fun resolveClang(ndkRoot: java.io.File, abi: String, sdkLevel: Int): String {
        val osName = System.getProperty("os.name").lowercase()
        val isWindows = osName.startsWith("windows")
        val hostTag = when {
            isWindows -> "windows-x86_64"
            osName.startsWith("mac") || osName.contains("darwin") -> "darwin-x86_64"
            else -> "linux-x86_64"
        }
        val toolPrefix = when (abi) {
            "arm64-v8a" -> "aarch64-linux-android"
            "armeabi-v7a" -> "armv7a-linux-androideabi"
            "x86" -> "i686-linux-android"
            "x86_64" -> "x86_64-linux-android"
            else -> error("Unsupported ABI for cgo: $abi")
        }
        val bin = ndkRoot.resolve("toolchains/llvm/prebuilt/$hostTag/bin")
        val wrapperName = "$toolPrefix$sdkLevel-clang" + if (isWindows) ".cmd" else ""
        val wrapper = bin.resolve(wrapperName)
        if (!wrapper.exists()) {
            error(
                "NDK clang wrapper not found at ${wrapper.absolutePath}. " +
                    "Check that NDK is installed and minSdk=$sdkLevel is supported.",
            )
        }
        return wrapper.absolutePath
    }

    companion object {
        private val ABI_TO_GOARCH = mapOf(
            "arm64-v8a" to "arm64",
            "armeabi-v7a" to "arm",
            "x86" to "386",
            "x86_64" to "amd64",
        )
    }
}
