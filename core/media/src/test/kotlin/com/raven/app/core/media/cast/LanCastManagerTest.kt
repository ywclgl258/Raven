package com.raven.app.core.media.cast

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanCastManagerTest {

    @Test
    fun `lan cast rejects loopback, link-local metadata, and public hosts while allowing RFC1918 subnets`() {
        assertTrue(LanCastManager.isRfc1918Ipv4("192.168.1.50"))
        assertTrue(LanCastManager.isRfc1918Ipv4("10.0.0.12"))
        assertTrue(LanCastManager.isRfc1918Ipv4("172.16.0.1"))
        assertTrue(LanCastManager.isRfc1918Ipv4("172.31.255.254"))

        assertFalse(LanCastManager.isRfc1918Ipv4("127.0.0.1"))
        assertFalse(LanCastManager.isRfc1918Ipv4("169.254.169.254"))
        assertFalse(LanCastManager.isRfc1918Ipv4("172.15.0.1"))
        assertFalse(LanCastManager.isRfc1918Ipv4("172.32.0.1"))
        assertFalse(LanCastManager.isRfc1918Ipv4("8.8.8.8"))

        assertTrue(LanCastManager.isAllowedLanUrl("http://192.168.1.8:8200/desc.xml"))
        assertFalse(LanCastManager.isAllowedLanUrl("http://169.254.169.254/latest/meta-data"))
        assertFalse(LanCastManager.isAllowedLanUrl("file:///etc/passwd"))
        assertFalse(LanCastManager.isAllowedLanUrl("http://example.com/desc.xml"))
    }

    @Test
    fun `parses SSDP location, AVTransport controlURL, and escapes XML SOAP payload`() {
        val ssdp = """
            HTTP/1.1 200 OK
            CACHE-CONTROL: max-age=1800
            LOCATION: http://192.168.50.10:49152/description.xml
            ST: urn:schemas-upnp-org:service:AVTransport:1
        """.trimIndent()
        assertEquals(
            "http://192.168.50.10:49152/description.xml",
            LanCastManager.parseSsdpLocation(ssdp),
        )

        val xml = """
            <root>
              <device>
                <friendlyName>Living Room TV &amp; Bar</friendlyName>
                <UDN>uuid:1234-5678</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <controlURL>/upnp/control/AVTransport1</controlURL>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()
        val parsed = LanCastManager.parseDeviceDescriptionXml(xml, "http://192.168.50.10:49152/description.xml")
        assertNotNull(parsed)
        assertEquals("Living Room TV & Bar", parsed!!.friendlyName)
        assertEquals("http://192.168.50.10:49152/upnp/control/AVTransport1", parsed.controlUrl)

        // Malicious public redirect in controlURL must be rejected
        val evilXml = xml.replace("/upnp/control/AVTransport1", "http://evil.example/steal")
        assertNull(LanCastManager.parseDeviceDescriptionXml(evilXml, "http://192.168.50.10:49152/description.xml"))

        val soap = LanCastManager.buildSetAvTransportUriSoap(
            streamUrl = "https://cdn.example/v.mp4?a=1&b=2",
            title = "A & B <Video>",
        )
        val parser = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val envelope = parser.parse(soap.byteInputStream())
        assertEquals("https://cdn.example/v.mp4?a=1&b=2", envelope.getElementsByTagName("CurrentURI").item(0).textContent)
        val metadata = envelope.getElementsByTagName("CurrentURIMetaData").item(0).textContent
        val didl = parser.parse(metadata.byteInputStream())
        assertEquals("A & B <Video>", didl.getElementsByTagName("dc:title").item(0).textContent)
    }

    @Test
    fun `fetchXml ignores redirects and rejects oversized XML bodies without OOM`() = runTest {
        val server = MockWebServer()
        val redirectTarget = MockWebServer()
        server.start()
        redirectTarget.start()
        try {
            val manager = LanCastManager(
                context = null,
                lanUrlValidator = { true },
            )

            // Redirect response must NOT be followed
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", redirectTarget.url("/secret").toString()),
            )
            assertNull(manager.fetchXml(server.url("/desc.xml").toString()))
            assertEquals(0, redirectTarget.requestCount)

            // Oversized XML body (> 512 KiB) must be rejected cleanly
            val oversized = "A".repeat(LanCastManager.MAX_DESCRIPTION_XML_BYTES + 128)
            server.enqueue(MockResponse().setResponseCode(200).setBody(oversized))
            assertNull(manager.fetchXml(server.url("/huge.xml").toString()))
            server.enqueue(MockResponse().setChunkedBody(oversized, 8192))
            assertNull(manager.fetchXml(server.url("/chunked.xml").toString()))
            server.enqueue(MockResponse().setChunkedBody("<root/>", 4))
            assertEquals("<root/>", manager.fetchXml(server.url("/small.xml").toString()))
        } finally {
            server.shutdown()
            redirectTarget.shutdown()
        }
    }
}
