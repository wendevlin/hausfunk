package dev.wendelin.hausfunk.identity

import java.security.cert.CertificateException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TrustTest {
    private val parent = TestCerts.certificate("parent")
    private val stranger = TestCerts.certificate("stranger")

    @Test
    fun deviceIdIsTheCertificateFingerprint() {
        assertEquals(TestCerts.PARENT_ID, TestCerts.identity("parent").deviceId)
        assertEquals(TestCerts.BABY_ID, TestCerts.identity("baby").deviceId)
    }

    @Test
    fun acceptsExactlyThePinnedFingerprints() {
        val manager = PinningTrustManager { it == TestCerts.PARENT_ID }
        manager.checkClientTrusted(arrayOf(parent), "EC")
        manager.checkServerTrusted(arrayOf(parent), "EC")
        assertFailsWith<CertificateException> { manager.checkClientTrusted(arrayOf(stranger), "EC") }
        assertFailsWith<CertificateException> { manager.checkServerTrusted(arrayOf(stranger), "EC") }
        assertFailsWith<CertificateException> { manager.checkClientTrusted(emptyArray(), "EC") }
        assertEquals(0, manager.acceptedIssuers.size)
    }

    @Test
    fun anUnknownCertificateIsNeverTrusted() {
        val trusted = setOf(TestCerts.PARENT_ID)
        assertEquals(PeerClass.TRUSTED, classifyPeer(TestCerts.PARENT_ID, trusted, pairingWindowOpen = false))
        assertEquals(PeerClass.TRUSTED, classifyPeer(TestCerts.PARENT_ID, trusted, pairingWindowOpen = true))
        assertEquals(PeerClass.REJECTED, classifyPeer(TestCerts.STRANGER_ID, trusted, pairingWindowOpen = false))
        assertEquals(PeerClass.PAIRING, classifyPeer(TestCerts.STRANGER_ID, trusted, pairingWindowOpen = true))
    }

    @Test
    fun serverTrustManagerFollowsTheWindow() {
        var windowOpen = false
        val manager = PinningTrustManager { classifyPeer(it, setOf(TestCerts.PARENT_ID), windowOpen) != PeerClass.REJECTED }
        assertFailsWith<CertificateException> { manager.checkClientTrusted(arrayOf(stranger), "EC") }
        windowOpen = true
        manager.checkClientTrusted(arrayOf(stranger), "EC")
    }
}
