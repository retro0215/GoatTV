package tv.own.owntv.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * ExtraTrustAnchors (upstream OwnTV_Core 0b9f64bb, extended with ISRG Root X1 for API 24).
 *
 * Fixtures in src/test/resources/tls are Let's Encrypt's public intermediates and cross-signs as served
 * in a Gen-Y chain (no server certificate), plus a throwaway self-signed root nobody trusts.
 */
class ExtraTrustAnchorsTest {

    private val pem = File("src/main/res/raw/isrg_extra_roots.pem")

    /**
     * A "device" whose platform store lacks every ISRG root: it rejects each of these chains the way a
     * platform trust manager does, with a CertificateException. (A JDK trust manager over a truly empty
     * KeyStore throws a RuntimeException instead, which no real platform store does.)
     */
    private val emptySystem: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("no client trust")
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("Trust anchor for certification path not found.")
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun anchors(system: X509TrustManager = emptySystem) = ExtraTrustAnchors(pem.inputStream(), system)

    private fun fixture(name: String): X509Certificate =
        javaClass.classLoader!!.getResourceAsStream("tls/$name")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }

    @Test
    fun `bundled roots are exactly the published ISRG roots`() {
        // https://letsencrypt.org/certs/{isrgrootx1,isrg-root-x2,gen-y/root-ye,gen-y/root-yr}.pem
        assertEquals(
            listOf(
                "96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6", // ISRG Root X1
                "69729B8E15A86EFC177A57AFB7171DFC64ADD28C2FCA8CF1507E34453CCB1470", // ISRG Root X2
                "E14FFCAD5B0025731006CAA43A121A22D8E9700F4FB9CF852F02A708AA5D5666", // Root YE
                "E57B7E6F150C419102E8D5C055729FF967B9D1A829BF00CEC89CA604EBF4A86F", // Root YR
            ),
            anchors().certificates.map { sha256(it.encoded) },
        )
    }

    @Test
    fun `every bundled root is self-signed`() {
        anchors().certificates.forEach { c ->
            assertEquals(c.subjectX500Principal, c.issuerX500Principal)
            c.verify(c.publicKey)
        }
    }

    @Test
    fun `the cross-signed Root YE has the bundled Root YE's key`() {
        val ye = anchors().certificates[2]
        val cross = fixture("le_root_ye_cross_x2.pem")
        assertEquals(ye.subjectX500Principal, cross.subjectX500Principal)
        assertTrue(ye.publicKey.encoded.contentEquals(cross.publicKey.encoded))
        cross.verify(anchors().certificates[1].publicKey) // signed by ISRG Root X2
    }

    @Test
    fun `a Gen-Y chain ending at Root YE is accepted when the platform knows no ISRG root`() {
        anchors().trustManager.checkServerTrusted(arrayOf(fixture("le_ye1_intermediate.pem")), "ECDHE_ECDSA")
    }

    @Test
    fun `the full cross-signed chain is accepted on an empty platform store (API 24 case)`() {
        val chain = arrayOf(
            fixture("le_ye1_intermediate.pem"),
            fixture("le_root_ye_cross_x2.pem"),
            fixture("le_root_x2_cross_x1.pem"),
        )
        anchors().trustManager.checkServerTrusted(chain, "ECDHE_ECDSA")
    }

    @Test
    fun `a chain ending at Root YR is accepted`() {
        anchors().trustManager.checkServerTrusted(arrayOf(anchors().certificates[3]), "RSA")
    }

    @Test
    fun `an untrusted self-signed chain is rejected with the platform's own error`() {
        val platformError = CertificateException("platform rejected")
        val rejecting = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = throw platformError
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val thrown = assertThrows(CertificateException::class.java) {
            anchors(rejecting).trustManager.checkServerTrusted(arrayOf(fixture("untrusted_self_signed.pem")), "RSA")
        }
        assertSame(platformError, thrown)
    }

    @Test
    fun `an untrusted chain is rejected on the socket-aware path too`() {
        assertThrows(CertificateException::class.java) {
            anchors().trustManager.checkServerTrusted(
                arrayOf(fixture("untrusted_self_signed.pem")), "RSA", null as java.net.Socket?,
            )
        }
    }

    @Test
    fun `the platform decides first and the bundle is never asked when it accepts`() {
        var platformCalls = 0
        val accepting = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) { platformCalls++ }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        // Even an untrusted chain passes when the platform says so: this class only ever adds trust on a
        // platform rejection, never removes or overrides a platform decision.
        anchors(accepting).trustManager.checkServerTrusted(arrayOf(fixture("untrusted_self_signed.pem")), "RSA")
        assertEquals(1, platformCalls)
    }

    @Test
    fun `accepted issuers include the platform's and the bundle's`() {
        val issuers = anchors().trustManager.acceptedIssuers
        assertEquals(4, issuers.size)
    }
}
