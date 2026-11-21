package gitviz.service

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class UpdatesTest {
    private val mac = UpdatePlatform("mac", "arm64")
    private val windows = UpdatePlatform("windows", "x64")
    private fun manifest(version: String = "1.0.10", url: String = "https://git.vambda.com/downloads/Preflight.dmg", sha: String = "a".repeat(64)) =
        """{"schema":1,"product":"Preflight","releases":[
            {"platform":"mac","arch":"arm64","version":"$version","url":"$url","sha256":"$sha"},
            {"platform":"windows","arch":"x64","version":"1.0.2","url":"https://git.vambda.com/downloads/Preflight.exe","sha256":"${"b".repeat(64)}"}]}"""

    @Test fun `compares numeric versions and matches each platform independently`() {
        assertEquals("1.0.10", availableUpdate(manifest(), "1.0.9", mac)?.version)
        assertEquals("1.0.2", availableUpdate(manifest(), "1.0.1", windows)?.version)
        assertNull(availableUpdate(manifest(), "1.0.10", mac))
        assertNull(availableUpdate(manifest(), "2.0.0", mac))
        assertNull(availableUpdate(manifest(), "1.0.9", windows))
        assertNull(availableUpdate(manifest(), "1.0.0", UpdatePlatform("mac", "x64")))
        assertEquals("2.0.0", availableUpdate(manifest("2.0.0"), "1.99.99", mac)?.version)
    }

    @Test fun `mac architectures never select each others release`() {
        val intel = UpdatePlatform("mac", "x64")
        val json = manifest().replace("\"releases\":[", """"releases":[
            {"platform":"mac","arch":"x64","version":"1.0.3","url":"https://git.vambda.com/downloads/Preflight-x64.dmg","sha256":"${"c".repeat(64)}"},""")
        assertEquals("1.0.10", availableUpdate(json, "1.0.2", mac)?.version)
        assertEquals(URI("https://git.vambda.com/downloads/Preflight-x64.dmg"), availableUpdate(json, "1.0.2", intel)?.download)
        assertNull(availableUpdate(json, "1.0.3", intel))
        assertNull(availableUpdate(json, "1.0.2", windows))
    }

    @Test fun `ignores malformed versions manifests checksums and foreign downloads`() {
        for (version in listOf("1.2", "1.0.10-beta", "01.0.10", "99999999999999.0.0", "latest")) {
            assertNull(availableUpdate(manifest(version), "1.0.0", mac), version)
            assertNull(availableUpdate(manifest(), version, mac), version)
        }
        for (json in listOf("<html>offline</html>", "{}", "[]", "null",
            manifest().replace("Preflight\",\"releases", "Other\",\"releases"),
            manifest().replace("\"schema\":1", "\"schema\":2"), manifest(sha = "invalid"))) {
            assertNull(availableUpdate(json, "1.0.0", mac))
        }
        for (url in listOf("http://git.vambda.com/downloads/app.dmg", "https://other.test/downloads/app.dmg",
            "https://git.vambda.com:8443/downloads/app.dmg", "https://user@git.vambda.com/downloads/app.dmg",
            "https://git.vambda.com/downloads/../app.dmg", "https://git.vambda.com/downloads/%2e%2e/app.dmg",
            "https://git.vambda.com/downloads/app.dmg?next=evil", "https://git.vambda.com/downloads/app.exe")) {
            assertNull(availableUpdate(manifest(url = url), "1.0.0", mac), url)
        }
    }

    @Test fun `supports packaged OS aliases but skips unsupported architectures`() {
        assertEquals(mac, updatePlatform("Mac OS X", "aarch64"))
        assertEquals(windows, updatePlatform("Windows 11", "amd64"))
        assertNull(updatePlatform("Windows 11", "aarch64"))
        assertEquals(UpdatePlatform("mac", "x64"), updatePlatform("Mac OS X", "x86_64"))
        assertNull(updatePlatform("Mac OS X", "unknown"))
        assertNull(updatePlatform("Linux", "amd64"))
    }

    @Test fun `checks once and dismissal survives repeated startup calls`() = runBlocking {
        var requests = 0
        val check = StartupUpdateCheck("1.0.0", mac, false) {
            requests++
            ServiceReply(200, emptyMap(), manifest())
        }
        check.check()
        assertEquals(URI("https://git.vambda.com/downloads/Preflight.dmg"), check.available.value?.download)
        check.dismiss()
        check.check()
        assertEquals(1, requests)
        assertNull(check.available.value)
    }

    @Test fun `development or unknown version never sends a request`() = runBlocking {
        var requests = 0
        for (check in listOf(
            StartupUpdateCheck("1.0.0", mac, true) { requests++; error("unexpected") },
            StartupUpdateCheck(null, mac, false) { requests++; error("unexpected") },
            StartupUpdateCheck("dev", mac, false) { requests++; error("unexpected") },
            StartupUpdateCheck("1.0.0", null, false) { requests++; error("unexpected") },
        )) check.check()
        assertEquals(0, requests)
    }

    @Test fun `network failures HTTP errors and invalid JSON are silent and do not retry`() = runBlocking {
        for (status in listOf(200, 404, 500)) {
            var requests = 0
            val check = StartupUpdateCheck("1.0.0", mac, false) {
                requests++; ServiceReply(status, emptyMap(), if (status == 200) "broken" else manifest())
            }
            check.check(); check.check()
            assertNull(check.available.value)
            assertEquals(1, requests)
        }
        val offline = StartupUpdateCheck("1.0.0", mac, false) { throw java.io.IOException("offline") }
        offline.check()
        assertNull(offline.available.value)
        val cancelled = StartupUpdateCheck("1.0.0", mac, false) { throw CancellationException() }
        assertFailsWith<CancellationException> { cancelled.check() }
        Unit // JUnit 只发现返回 void 的测试方法。
    }
}
