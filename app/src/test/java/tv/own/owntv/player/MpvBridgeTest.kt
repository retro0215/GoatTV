package tv.own.owntv.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rebuilt libmpv JNI bridge (third_party/mpv-bridge) must not print mpv's log messages, which carry
 * stream URLs and credentials, and must still export every entry point MPVLib 1.0.0 calls.
 * (verifyMpvBridge in app/build.gradle.kts checks the packaged copy is this one.)
 */
class MpvBridgeTest {
    private val abis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
    private val jni = listOf(
        "nativeCreate", "nativeInit", "nativeDestroy", "nativeAttachSurface", "nativeDetachSurface", "nativeCommand",
        "nativeSetOptionString", "nativeGetPropertyInt", "nativeGetPropertyDouble", "nativeGetPropertyBoolean",
        "nativeGetPropertyString", "nativeSetPropertyInt", "nativeSetPropertyDouble", "nativeSetPropertyBoolean",
        "nativeSetPropertyString", "nativeObserveProperty",
    )

    private fun lib(abi: String): ByteArray {
        val f = listOf(File("src/main/jniLibs/$abi/libplayer.so"), File("app/src/main/jniLibs/$abi/libplayer.so")).first { it.isFile }
        return f.readBytes()
    }

    private fun ByteArray.has(s: String): Boolean = String(this, Charsets.ISO_8859_1).contains(s)

    @Test
    fun `the bridge no longer writes mpv log messages or event names to logcat`() {
        for (abi in abis) {
            val b = lib(abi)
            assertFalse("$abi still has the per-message log format", b.has("[%s:%s] %s"))
            assertFalse("$abi still logs event names", b.has("event: %s"))
            // Error lines (property names / error strings only) stay.
            assertTrue(abi, b.has("returned error %s"))
        }
    }

    @Test
    fun `every MPVLib 1_0_0 native entry point is exported`() {
        for (abi in abis) {
            val b = lib(abi)
            val missing = jni.filterNot { b.has("Java_dev_jdtech_mpv_MPVLib_$it") }
            assertEquals("$abi missing $missing", emptyList<String>(), missing)
            assertTrue(abi, b.has("mpv_request_log_messages")) // messages still reach MPVLib.LogObserver
        }
    }
}
