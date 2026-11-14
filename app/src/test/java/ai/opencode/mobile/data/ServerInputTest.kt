package ai.opencode.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerInputTest {

    @Test
    fun `bare host defaults to http and the opencode port`() {
        val target = ServerInput.parse("192.168.1.10")
        assertEquals("http://192.168.1.10:4096", target?.baseUrl)
        assertNull(target?.path)
    }

    @Test
    fun `explicit port is kept`() {
        assertEquals("http://box.local:8080", ServerInput.parse("box.local:8080")?.baseUrl)
    }

    @Test
    fun `https without a port stays on 443`() {
        assertEquals("https://oc.example.com", ServerInput.parse("https://oc.example.com")?.baseUrl)
    }

    @Test
    fun `path prefix survives for proxied servers`() {
        assertEquals("https://example.com/oc", ServerInput.parse("https://example.com/oc/")?.baseUrl)
    }

    @Test
    fun `pairing link becomes a one time connect path`() {
        val target = ServerInput.parse("http://10.0.0.2:4096/auth/connect/AbC-123_xyz")
        assertEquals("http://10.0.0.2:4096", target?.baseUrl)
        assertEquals("/auth/connect/AbC-123_xyz", target?.path)
    }

    @Test
    fun `qr payload from opencode pair is understood`() {
        val payload = """{"code":"Zx9_-Q","urls":["http://10.0.0.2:4096","http://172.20.0.5:4096"]}"""
        val target = ServerInput.parse(payload)
        assertEquals("http://10.0.0.2:4096", target?.baseUrl)
        assertEquals("/auth/connect/Zx9_-Q", target?.path)
    }

    @Test
    fun `scheme only input is rejected`() {
        assertNull(ServerInput.parse("http://"))
        assertNull(ServerInput.parse("   "))
        assertNull(ServerInput.parse("ftp://example.com"))
    }

    @Test
    fun `ipv6 literals keep their brackets`() {
        assertEquals("http://[fe80::1]:4096", ServerInput.parse("[fe80::1]:4096")?.baseUrl)
    }

    @Test
    fun `pairing link with an unreachable loopback address is still parsed`() {
        val target = ServerInput.parse("http://127.0.0.1:4096/auth/connect/token1")
        assertTrue(target?.path?.startsWith("/auth/connect/") == true)
    }
}
