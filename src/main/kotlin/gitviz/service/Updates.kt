package gitviz.service

import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*

internal const val RELEASE_URL = "https://git.vambda.com/release.json"
internal const val DOWNLOAD_PAGE = "https://git.vambda.com/#get"

/** 安装包提供自身版本；源码运行和 Dev 包不会自动请求更新。 */
internal object AppRelease {
    val version: String? = System.getProperty("jpackage.app-version")
    val isDevelopment = System.getProperty("preflight.dev")?.toBoolean() ?: (version == null)
    val updates = StartupUpdateCheck(version, updatePlatform(System.getProperty("os.name"), System.getProperty("os.arch")), isDevelopment)
}

internal data class UpdatePlatform(val os: String, val arch: String)
internal data class AvailableUpdate(val version: String, val download: URI, val sha256: String)

internal fun updatePlatform(os: String, arch: String): UpdatePlatform? = when {
    os.startsWith("Mac", ignoreCase = true) && arch.lowercase() in setOf("aarch64", "arm64") -> UpdatePlatform("mac", "arm64")
    os.startsWith("Mac", ignoreCase = true) && arch.lowercase() in setOf("amd64", "x86_64", "x64") -> UpdatePlatform("mac", "x64")
    os.startsWith("Windows", ignoreCase = true) && arch.lowercase() in setOf("amd64", "x86_64", "x64") -> UpdatePlatform("windows", "x64")
    else -> null
}

/** 只比较当前发布流程使用的三段稳定版本号，避免字符串排序把 1.0.10 当作旧版。 */
internal fun releaseVersion(value: String): List<Int>? {
    if (!value.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) return null
    return value.split('.').map { it.toIntOrNull() ?: return null }
}

internal fun availableUpdate(json: String, local: String, platform: UpdatePlatform): AvailableUpdate? = runCatching {
    val current = releaseVersion(local) ?: return null
    val root = Json.parseToJsonElement(json).jsonObject
    if (root["schema"]?.jsonPrimitive?.intOrNull != 1 || root["product"]?.jsonPrimitive?.content != "Preflight") return null
    val matches = root["releases"]!!.jsonArray.map { it.jsonObject }.filter {
        it["platform"]?.jsonPrimitive?.content == platform.os && it["arch"]?.jsonPrimitive?.content == platform.arch
    }
    // 一个目标只能有一个最新版，含糊或不完整的清单静默忽略。
    val release = matches.singleOrNull() ?: return null
    val version = release["version"]!!.jsonPrimitive.content
    val latest = releaseVersion(version) ?: return null
    val difference = latest.zip(current).firstOrNull { (a, b) -> a != b } ?: return null
    if (difference.first < difference.second) return null
    val uri = URI(release["url"]!!.jsonPrimitive.content)
    if (uri.scheme != "https" || uri.host != "git.vambda.com" || uri.port != -1 || uri.rawUserInfo != null ||
        uri.rawQuery != null || uri.rawFragment != null || uri.rawPath != uri.path ||
        !uri.path.startsWith("/downloads/") || uri.path.split('/').any { it == "." || it == ".." }) return null
    val suffix = if (platform.os == "mac") ".dmg" else ".exe"
    if (!uri.path.endsWith(suffix)) return null
    val sha = release["sha256"]!!.jsonPrimitive.content
    if (!sha.matches(Regex("[0-9a-f]{64}"))) return null
    AvailableUpdate(version, uri, sha)
}.getOrNull()

/** 生命周期属于进程：切换仓库、重建窗口、关闭提示都不会再请求。 */
internal class StartupUpdateCheck(
    private val localVersion: String?,
    private val platform: UpdatePlatform?,
    private val development: Boolean,
    private val fetch: () -> ServiceReply = {
        ServiceHttp.request(URI(RELEASE_URL), mapOf("Cache-Control" to "no-cache"), timeoutSeconds = 8)
    },
) {
    private val started = AtomicBoolean()
    private val notice = MutableStateFlow<AvailableUpdate?>(null)
    val available = notice.asStateFlow()

    suspend fun check() {
        val local = localVersion?.takeIf { releaseVersion(it) != null } ?: return
        val target = platform ?: return
        if (development || !started.compareAndSet(false, true)) return
        try {
            val response = runInterruptible(Dispatchers.IO) { fetch() }
            if (response.status == 200) notice.value = availableUpdate(response.body, local, target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 更新检查不影响启动，也不将断网等错误显示为仓库操作失败。
        }
    }

    fun dismiss() { notice.value = null }
}
