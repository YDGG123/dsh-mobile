package com.clarklevis.dsh.android.update

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clarklevis.dsh.android.localization.appText
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class AndroidRelease(
    val version: String,
    val url: String,
    /** 体积（字节）。**未知 = null** —— 不填 -1 这类看起来像真值的假值；
     *  下载时未知则回落 Content-Length 校验。manifest 路为精确值。 */
    val size: Long?,
    val digest: String?,
    val versionCode: Long? = null,
    val releaseNotes: String? = null
)
internal data class UpdateState(
    val busy: Boolean = false,
    val status: String? = null,
    val release: AndroidRelease? = null,
    val apk: File? = null
)

internal fun isSkippedVersion(version: String, skipped: String?): Boolean =
    skipped != null && version.removePrefix("v") == skipped.removePrefix("v")

internal fun compareVersions(first: String, second: String): Int {
    fun parts(value: String) = value.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(first)
    val b = parts(second)
    return (0 until maxOf(a.size, b.size)).firstNotNullOfOrNull { index ->
        (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 }).takeIf { it != 0 }
    } ?: 0
}

/**
 * P0-AT-05（D-05/SEC-07/SEC-12，2026-11-11 分家派单）：更新源**只允许**用户自己配置的
 * HTTPS manifest 地址。归一化失败（空、非 https、带凭据）一律按「未配置」处理——
 * 不得回退任何内置源（原作者旧站 download.whaleness.cc 与原作者 GitHub
 * Clarklevis1995/dsh-mobile 的全部运行路径已删除，P0-AT-15 要求旧源代码无运行路径）。
 */
internal fun normalizeUpdateSourceUrl(raw: String?): String? {
    val url = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val http = runCatching { url.toHttpUrl() }.getOrNull() ?: return null
    return if (http.isHttps && http.username.isEmpty() && http.password.isEmpty()) url else null
}

internal class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private companion object { const val TAG = "DshUpdate" }
    private val context = application
    private val cacheStorage = com.clarklevis.dsh.android.AndroidCacheStorage.shared(application)
    private var updateLease: java.io.Closeable? = null

    private fun releaseUpdateLease() {
        updateLease?.close()
        updateLease = null
    }
    override fun onCleared() {
        releaseUpdateLease()
        super.onCleared()
    }
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).build()
    private val metadataClient = client.newBuilder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()
    @Suppress("DEPRECATION")
    private val installed = context.packageManager.getPackageInfo(context.packageName, 0)
    val currentVersion: String = installed.versionName ?: appText("未知")

    /** versionCode（API 28+ 读 longVersionCode，24–27 回落已废弃的 versionCode）。 */
    @Suppress("DEPRECATION")
    private val installedVersionCode: Long =
        if (android.os.Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()

    /**
     * **界面自证用**：「1.7.26 (63)」—— 装的是哪个 versionCode 一眼可见
     * （缺陷登记表 #12；此前 69/70/71 三版界面全显示 `1.7.12`，两次靠问用户才分清）。
     *
     * ⚠️ 只有「关于」的显示行（`SettingsScreen.kt`）允许用它。
     * **两处比较必须保持 [currentVersion] 原值**：`compareVersions(...)` 与
     * `releaseInfo.version == currentVersion`。
     * 把 versionCode 拼进 currentVersion 会同时弄坏「检查更新」和「本版本新功能」
     * —— 与 iOS 是同一坑（iOS 拆成 appVersionRaw / appVersionDisplay）。
     */
    val displayVersion: String =
        formatDisplayVersion(installed.versionName, installedVersionCode, appText("未知"))

    fun dismiss() {
        if (!mutableState.value.busy) {
            releaseUpdateLease()
            mutableState.value = UpdateState()
        }
    }
    fun installationHandled() {
        val retained = runCatching { mutableState.value.apk?.let { cacheStorage.retainExternal(it) } }.isSuccess
        if (retained) releaseUpdateLease()
        mutableState.value = UpdateState()
    }
    fun reportError(message: String) { mutableState.value = UpdateState(status = message) }

    private val preferences = context.getSharedPreferences("app_updates", Application.MODE_PRIVATE)

    /** 用户在设置里配置的自有发布 manifest；未配置 = null（不得联网，不得回退旧源）。 */
    private fun configuredUpdateSource(): String? =
        normalizeUpdateSourceUrl(preferences.getString("update_source_url", null))

    /**
     * 更新源存取（Chief 2026-11-11 派单：设置项必须有地方填）。
     * 保存动作本身**零联网**——只做归一化校验并落盘；联网只发生在手动「检查更新」时。
     * 返回 null = 输入非法（空/非 https/带凭据/不是 URL），调用方展示错误、不落盘。
     */
    fun saveUpdateSource(raw: String): String? {
        val normalized = normalizeUpdateSourceUrl(raw)
            ?: return appText("更新源必须是 https 地址且不带登录凭据")
        preferences.edit().putString("update_source_url", normalized).apply()
        return null
    }

    /** 清空更新源；此后「检查更新」提示未配置且零联网。 */
    fun clearUpdateSource() {
        preferences.edit().remove("update_source_url").apply()
    }

    /** 供设置页显示当前值（未配置 = 空串，不给占位假值）。 */
    fun currentUpdateSource(): String =
        preferences.getString("update_source_url", null) ?: ""

    fun skipVersion() {
        val release = mutableState.value.release ?: return
        preferences.edit().putString("skipped_version", release.version.removePrefix("v")).apply()
        dismiss()
    }

    fun check() = performCheck(automatic = false)

    private fun performCheck(automatic: Boolean) = runOperation(silent = automatic) {
        // 分家纪律（2026-11-11）：更新检查唯一路径 = 用户自己配置的 HTTPS manifest。
        // 未配置 → 明确提示并**零网络请求**；没有任何内置 URL、没有静默 fallback。
        val manifestUrl = configuredUpdateSource()
            ?: return@runOperation UpdateState(
                status = if (automatic) null else appText("尚未配置更新源：请在设置中填写你自己的发布清单地址")
            )
        val json = metadataClient.newCall(Request.Builder().url(manifestUrl)
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "Orcarness-Android").build()).execute().use { response ->
            check(response.isSuccessful) { appText("检查更新失败（%1\$s）", response.code) }
            check((response.body?.contentLength() ?: -1) <= 256 * 1024) { appText("更新配置过大") }
            response.body?.byteStream()?.use { input ->
                val buffer = ByteArray(256 * 1024 + 1)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                check(count <= 256 * 1024) { appText("更新配置过大") }
                String(buffer, 0, count, Charsets.UTF_8)
            } ?: error(appText("更新配置为空"))
        }
        val release = parseUpdateManifest(json, context.packageName, currentVersion,
            PackageInfoCompat.getLongVersionCode(installed), Build.SUPPORTED_ABIS.toList(), manifestUrl)
        val visibleRelease = release?.takeUnless {
            automatic && isSkippedVersion(it.version, preferences.getString("skipped_version", null))
        }
        UpdateState(
            release = visibleRelease,
            status = if (!automatic && release == null) appText("当前已是最新版本") else null
        )
    }

    fun download(release: AndroidRelease) = runOperation {
        val directory = File(context.cacheDir, "updates")
        releaseUpdateLease()
        val lease = cacheStorage.access {
            check(cacheStorage.safeManagedDirectory(directory) && cacheStorage.canRemove(directory)) { appText("安装器仍在使用缓存安装包，请稍后再试") }
            cacheStorage.lease(directory)
        }
        var ready = false
        directory.mkdirs()
        val target = File(directory, "update.apk")
        val partial = File(directory, "update.part")
        try {
            // 无静默备用下载地址（分家纪律）：manifest 里的 downloadUrl 是唯一来源，
            // 失败即失败并展示结果，不切任何「加速源」。
            partial.delete()
            client.newCall(Request.Builder().url(release.url).build()).execute().use { response ->
                check(response.isSuccessful) { appText("下载失败（%1\$s）", response.code) }
                val body = response.body ?: error(appText("下载内容为空"))
                val expectedSize = release.size?.takeIf { it > 0 } ?: body.contentLength()
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(expectedSize <= 0 || total <= expectedSize) { appText("安装包大小异常") }
                            check(cacheStorage.canWriteTemporary(count.toLong())) { appText("可用存储空间不足") }
                            output.write(buffer, 0, count)
                            mutableState.value = UpdateState(busy = true, status = if (expectedSize > 0) appText("正在下载 %1\$s%", total * 100 / expectedSize) else appText("正在下载 %1\$s MB", total / 1024 / 1024))
                        }
                        check((expectedSize <= 0 || total == expectedSize) && total > 0) { appText("安装包下载不完整") }
                    }
                }
            }
            if (release.digest == null) {
                // 显式记录而非静默跳过；该路仍受 体积校验 + 包名/版本递增/签名一致性 三重约束。
                Log.w(TAG, "manifest 无摘要校验（digest=null）：已按体积 + 包名 + 版本递增 + 签名校验")
            }
            release.digest?.takeIf { it.startsWith("sha256:") }?.let { expected ->
                val hash = MessageDigest.getInstance("SHA-256")
                partial.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
                }
                check(hash.digest().joinToString("") { "%02x".format(it) } == expected.removePrefix("sha256:")) { appText("安装包校验失败") }
            }
            validateApk(partial, release)
            target.delete()
            check(partial.renameTo(target)) { appText("无法保存安装包") }
            updateLease = lease
            ready = true
            UpdateState(apk = target)
        } finally {
            partial.delete()
            if (!ready) lease.close()
        }
    }

    @Suppress("DEPRECATION")
    private fun validateApk(file: File, release: AndroidRelease) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.path, flags) ?: error(appText("无效的 APK 安装包"))
        check(archive.packageName == context.packageName) { appText("安装包不属于当前应用") }
        check(PackageInfoCompat.getLongVersionCode(archive) > PackageInfoCompat.getLongVersionCode(installed)) { appText("安装包构建号未高于当前版本") }
        release.versionCode?.let { expected ->
            check(PackageInfoCompat.getLongVersionCode(archive) == expected && archive.versionName == release.version) {
                appText("安装包版本与更新配置不一致")
            }
        }
        val current = context.packageManager.getPackageInfo(context.packageName, flags)
        val archiveSignatures = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
        val currentSignatures = if (Build.VERSION.SDK_INT >= 28) current.signingInfo?.apkContentsSigners else current.signatures
        check(!archiveSignatures.isNullOrEmpty() && !currentSignatures.isNullOrEmpty() &&
            archiveSignatures.toSet() == currentSignatures.toSet()) { appText("安装包签名不一致，无法覆盖安装") }
    }

    private fun runOperation(silent: Boolean = false, operation: suspend () -> UpdateState) {
        if (mutableState.value.busy) return
        mutableState.value = UpdateState(busy = true, status = if (silent) null else appText("正在检查更新…"))
        viewModelScope.launch {
            try { mutableState.value = withContext(Dispatchers.IO) { operation() } }
            catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { mutableState.value = UpdateState(status = if (silent) null else exception.message ?: appText("更新失败，请重试")) }
        }
    }
}
