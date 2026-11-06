package tv.own.owntv.core.diagnostics

import androidx.annotation.OptIn
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi

/**
 * Media3's logger with every message and throwable passed through [SensitiveText] first. Media3 logs playback
 * errors together with their cause chain, and a data-source failure can carry the request URL in that chain.
 */
@OptIn(UnstableApi::class)
internal class RedactingMedia3Logger(private val out: Log.Logger = Log.Logger.DEFAULT) : Log.Logger {
    override fun d(tag: String, message: String, throwable: Throwable?) = out.d(tag, text(message, throwable), null)
    override fun i(tag: String, message: String, throwable: Throwable?) = out.i(tag, text(message, throwable), null)
    override fun w(tag: String, message: String, throwable: Throwable?) = out.w(tag, text(message, throwable), null)
    override fun e(tag: String, message: String, throwable: Throwable?) = out.e(tag, text(message, throwable), null)

    private fun text(message: String, throwable: Throwable?): String =
        if (throwable == null) SensitiveText.redact(message)
        else SensitiveText.redact(message) + "\n" + SensitiveText.stackTrace(throwable)

    companion object {
        fun install() { Log.setLogger(RedactingMedia3Logger()) }
    }
}
