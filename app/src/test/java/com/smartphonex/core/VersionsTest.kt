package com.smartphonex.core

import org.junit.Assert.*
import org.junit.Test

class VersionsTest {
    @Test fun `versions compare by number, and a pre-release comes first`() {
        assertTrue(Versions.compare("0.7.0", "0.6")!! > 0)
        assertTrue(Versions.compare("0.7.0-dev", "0.7.0")!! < 0)
        assertTrue(Versions.compare("v0.10.0", "0.9.9")!! > 0)
        assertEquals(0, Versions.compare("v1.2", "1.2.0"))
        assertTrue(Versions.compare("1.0.0-beta2", "1.0.0-beta1")!! > 0)
        assertNull(Versions.compare("banana", "1.0"))
        assertNull(Versions.compare("1.x", "1.0"))
    }

    @Test fun `reads a GitHub release`() {
        val r = Versions.parseFeed("""{"tag_name":"v0.7.1","html_url":"https://github.com/x/y/releases/tag/v0.7.1","draft":false,
            "assets":[{"browser_download_url":"https://github.com/x/y/releases/download/v0.7.1/SmartPhoneX-v0.7.1.apk.sha256"},
                      {"browser_download_url":"https://github.com/x/y/releases/download/v0.7.1/SmartPhoneX-v0.7.1.apk"}]}""")!!
        assertEquals("0.7.1", r.version)
        assertEquals("https://github.com/x/y/releases/download/v0.7.1/SmartPhoneX-v0.7.1.apk", r.apk)
    }

    @Test fun `reads a plain feed, and refuses unsafe or broken ones`() {
        assertEquals("0.8.0", Versions.parseFeed("""{"version":"0.8.0","url":"https://example.org/get"}""")!!.version)
        assertNull(Versions.parseFeed("""{"version":"0.8.0","url":"http://example.org/get"}"""))
        assertNull(Versions.parseFeed("""{"tag_name":"v1","html_url":"https://x","draft":true}"""))
        assertNull(Versions.parseFeed("""{"version":"latest","url":"https://x"}"""))
        assertNull(Versions.parseFeed("not json"))
        assertNull(Versions.parseFeed("""{"tag_name":"v1.0","html_url":"https://x","assets":[{"browser_download_url":"http://x/a.apk"}]}""")!!.apk)
    }
}
