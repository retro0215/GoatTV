package tv.own.owntv.core.network

import java.io.InputStream
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * Let's Encrypt roots that many devices do not trust yet, read from `res/raw/isrg_extra_roots.pem`
 * (ported from upstream OwnTV_Core 0b9f64bb, OwnTV #208).
 *
 * - ISRG Root YE / YR (2025) are in no platform trust store at all. The provider's panel and stream
 *   hosts already chain to Root YE; today they validate only because the server also sends the
 *   YE→X2→X1 cross-signs, and a server that stops sending them would fail on every device.
 * - ISRG Root X2 is in the platform store only from Android 14.
 * - ISRG Root X1 is missing on Android 7.0 (API 24, added in 7.1.1); GoatTV still supports API 24.
 *
 * The platform decides first, with the hostname-aware checks it would have used anyway; these roots
 * are consulted only when it rejects a server chain, so nothing that worked before changes and nothing
 * the bundled roots did not sign is ever accepted. Sources: https://letsencrypt.org/certificates/
 * (X1/X2 also match the Mozilla root store; YE was checked against its cross-sign by X2 — same key,
 * valid signature).
 */
class ExtraTrustAnchors(
    pems: InputStream,
    private val system: X509TrustManager = trustManagerFor(null),
) {

    val certificates: List<X509Certificate> = pems.use {
        CertificateFactory.getInstance("X.509").generateCertificates(it).map { c -> c as X509Certificate }
    }

    private val bundled: X509TrustManager = trustManagerFor(
        KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            certificates.forEachIndexed { i, cert -> setCertificateEntry("isrg-$i", cert) }
        },
    )

    /** The platform trust manager, falling back to [certificates] when it rejects a server chain. */
    val trustManager: X509ExtendedTrustManager = object : X509ExtendedTrustManager() {
        private val systemExt = system as? X509ExtendedTrustManager

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            system.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
            if (systemExt != null) systemExt.checkClientTrusted(chain, authType, socket)
            else system.checkClientTrusted(chain, authType)
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) {
            if (systemExt != null) systemExt.checkClientTrusted(chain, authType, engine)
            else system.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
            orBundled(chain, authType) { system.checkServerTrusted(chain, authType) }

        // The socket / engine forms carry the host name, so the platform keeps applying its
        // hostname-aware checks (network security config, CT) exactly as without this class.
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
            orBundled(chain, authType) {
                if (systemExt != null) systemExt.checkServerTrusted(chain, authType, socket)
                else system.checkServerTrusted(chain, authType)
            }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
            orBundled(chain, authType) {
                if (systemExt != null) systemExt.checkServerTrusted(chain, authType, engine)
                else system.checkServerTrusted(chain, authType)
            }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            system.acceptedIssuers + bundled.acceptedIssuers
    }

    val sslSocketFactory: SSLSocketFactory =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory

    /** [platform] first; only its rejection is retried against the bundled roots, else rethrown. */
    private inline fun orBundled(chain: Array<X509Certificate>, authType: String, platform: () -> Unit) {
        try {
            platform()
        } catch (e: CertificateException) {
            try {
                bundled.checkServerTrusted(chain, authType)
            } catch (_: CertificateException) {
                throw e
            } catch (_: IllegalArgumentException) {
                throw e
            }
        }
    }

    companion object {
        /** The default [X509TrustManager] over [keyStore]; `null` = the platform's own trust store. */
        fun trustManagerFor(keyStore: KeyStore?): X509TrustManager =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(keyStore) }
                .trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
