package com.clarklevis.dsh.android.update

import com.clarklevis.dsh.android.localization.appText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * P0-AT-05（2026-11-11 分家）：更新清单只能来自**用户自己配置**的地址
 * （[AppUpdateViewModel.configuredUpdateSource]）；这里不再有任何内置 URL——
 * 原作者旧站 `download.whaleness.cc` 常量已删除，manifest 的 `githubDownloadUrl`
 * （静默备用下载源）也不再被解析。
 */
internal fun parseUpdateManifest(
    source: String,
    packageName: String,
    installedVersion: String,
    installedBuild: Long,
    supportedAbis: List<String> = emptyList(),
    manifestUrl: String
): AndroidRelease? {
    val manifest = Json.parseToJsonElement(source).jsonObject
    require(manifest.getValue("schemaVersion").jsonPrimitive.int == 1) { appText("不支持的更新配置格式") }
    require(manifest.getValue("packageName").jsonPrimitive.content == packageName) { appText("更新配置包名不一致") }
    val version = manifest.getValue("version").jsonPrimitive.content
    require(Regex("\\d+\\.\\d+\\.\\d+").matches(version)) { appText("无效的更新版本") }
    val build = manifest.getValue("versionCode").jsonPrimitive.long
    val variants = manifest["abiApks"]?.jsonObject
    val selected = supportedAbis.firstNotNullOfOrNull { variants?.get(it)?.jsonObject } ?: manifest
    val size = selected.getValue("size").jsonPrimitive.long
    val hash = selected.getValue("sha256").jsonPrimitive.content.lowercase()
    require(build > 0 && size > 0 && Regex("[0-9a-f]{64}").matches(hash)) { appText("无效的安装包校验信息") }
    val url = selected.getValue("downloadUrl").jsonPrimitive.content.toHttpUrl()
    // 下载域白名单（fail-closed）：**与 manifest 同源**（自有服务器场景）或 GitHub 官方资产域
    // （manifest 托管在 GitHub 的场景），仍须 https + 无凭据 + .apk 结尾。
    // 原作者域名与按原作者仓库写死的路径已随分家删除；不再存在任何「备用域」。
    val manifestHost = manifestUrl.toHttpUrl().host
    require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty() &&
        url.encodedPath.endsWith(".apk") &&
        (url.host == manifestHost ||
            url.host == "objects.githubusercontent.com" ||
            url.host == "release-assets.githubusercontent.com")) { appText("不可信的 APK 下载地址") }
    // 同一版本重新打包时也以构建号判断；禁止降级和无法覆盖安装的构建。
    if (build <= installedBuild || compareVersions(version, installedVersion) < 0) return null
    return AndroidRelease(version, url.toString(), size, "sha256:$hash", build,
        manifest["releaseNotes"]?.jsonPrimitive?.content)
}
