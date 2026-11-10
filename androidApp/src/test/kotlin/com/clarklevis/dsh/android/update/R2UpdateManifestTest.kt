package com.clarklevis.dsh.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-AT-05（2026-11-11 分家）：manifest 是唯一更新配置入口。
 * 旧作者源（download.whaleness.cc / Clarklevis1995/dsh-mobile）与静默备用下载地址
 * （githubDownloadUrl）已删除；下载域白名单收紧为「与 manifest 同源或 GitHub 资产域」。
 */
class R2UpdateManifestTest {
    private fun manifest(
        build: Int = 22,
        url: String = "https://updates.own.example/builds/Orcarness-android-v1.0.0.apk",
        hash: String = "a".repeat(64)
    ) = """{"schemaVersion":1,"packageName":"com.clarklevis.dsh.android","version":"1.0.0",
        "versionCode":$build,"size":37627206,"sha256":"$hash","downloadUrl":"$url",
        "releaseNotes":"新功能说明"}"""

    private fun parse(source: String, installed: String = "0.9.9", build: Long = 21) =
        parseUpdateManifest(source, "com.clarklevis.dsh.android", installed, build,
            manifestUrl = "https://updates.own.example/latest.json")

    @Test fun selectsManifestReleaseWithNotes() {
        val release = requireNotNull(parse(manifest()))
        assertEquals(22L, release.versionCode)
        assertEquals("sha256:${"a".repeat(64)}", release.digest)
        assertEquals("新功能说明", release.releaseNotes)
        assertEquals("https://updates.own.example/builds/Orcarness-android-v1.0.0.apk", release.url)
    }

    @Test fun selectsPreferredCompatibleAbiAndUsesItsOwnChecksum() {
        val variant = """"abiApks":{"arm64-v8a":{"size":123,"sha256":"${"b".repeat(64)}",
            "downloadUrl":"https://updates.own.example/builds/arm64.apk"},
            "armeabi-v7a":{"size":456,"sha256":"${"c".repeat(64)}",
            "downloadUrl":"https://updates.own.example/builds/arm.apk"}},"""
        val source = manifest().replace("\"schemaVersion\":1,", "\"schemaVersion\":1,$variant")
        val release = requireNotNull(parseUpdateManifest(source, "com.clarklevis.dsh.android", "0.9.9", 21,
            listOf("arm64-v8a", "armeabi-v7a"), "https://updates.own.example/latest.json"))
        assertEquals("sha256:${"b".repeat(64)}", release.digest)
        assertEquals("sha256:${"a".repeat(64)}", requireNotNull(parse(source)).digest)
        assertEquals("sha256:${"a".repeat(64)}", requireNotNull(parseUpdateManifest(source,
            "com.clarklevis.dsh.android", "0.9.9", 21, listOf("unknown"),
            "https://updates.own.example/latest.json")).digest)
        assertTrue("cross-host download outside asset domains must be rejected", runCatching {
            parseUpdateManifest(source.replace("https://updates.own.example/builds/arm64.apk",
                "https://evil.example/arm64.apk"), "com.clarklevis.dsh.android", "0.9.9", 21,
                listOf("arm64-v8a"), "https://updates.own.example/latest.json")
        }.isFailure)
    }

    @Test fun handlesSameVersionRebuildAndDoesNotDowngrade() {
        assertNotNull(parse(manifest(), "1.0.0", 21))
        assertNull(parse(manifest(), "1.0.0", 22))
        assertNull(parse(manifest(build = 50), "1.1.0", 21))
        assertNull(parse(manifest(build = 20)))
    }

    @Test fun rejectsInvalidHashPackageSchemaAndUrls() {
        val invalid = listOf(
            manifest(hash = "broken"),
            manifest(url = "https://updates.own.example.evil.example/app.apk"),
            manifest(url = "http://updates.own.example/app.apk"),
            manifest().replace("com.clarklevis.dsh.android", "another.package"),
            manifest().replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        )
        invalid.forEach { source ->
            assertTrue(runCatching { parse(source) }.isFailure)
        }
    }

    @Test fun githubAssetHostsAreAllowedButOtherCrossHostIsNot() {
        assertNotNull(parse(manifest(
            url = "https://release-assets.githubusercontent.com/private/assets/app.apk")))
        assertNotNull(parse(manifest(url = "https://objects.githubusercontent.com/assets/app.apk")))
        assertTrue("github.com releases path is no longer whitelisted", runCatching {
            parse(manifest(url = "https://github.com/someone/other/releases/download/v1.0.0/app.apk"))
        }.isFailure)
    }
}
