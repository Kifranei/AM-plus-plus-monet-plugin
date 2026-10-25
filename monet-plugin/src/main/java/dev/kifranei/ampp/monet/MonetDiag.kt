package dev.kifranei.ampp.monet

import dev.amenhancer.plugin.api.PluginContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tester-facing diagnostics. Every line starts with `monet-diag:` and goes to the AM++ plugin log (LSPosed),
 * logcat tag `AMPP-monet` and `<plugin data>/monet-diag.log` (bounded). Lines name the stage (resource hook,
 * metadata observer, pipeline, live recolour) and the host build. Diagnostics never throw.
 */
internal object MonetDiag {
    const val TAG = "AMPP-monet"
    const val FILE = "monet-diag.log"
    const val MAX_FAILURES = 3
    private const val MAX_FILE_BYTES = 256 * 1024L
    private const val MAX_REPEATS = 3
    private val repeats = ConcurrentHashMap<String, AtomicInteger>()
    @Volatile var context: PluginContext? = null
    @Volatile private var file: File? = null
    @Volatile private var build = "?"

    fun start(context: PluginContext, profile: String) {
        this.context = context
        build = runCatching { "${context.hostVersionName}/${context.hostVersionCode}" }.getOrDefault("?")
        file = runCatching { File(context.dataDirectory, FILE) }.getOrNull()
        runCatching { file?.takeIf { it.length() > MAX_FILE_BYTES }?.delete() }
        event("startup", "host=$build profile=$profile plugin=$PLUGIN_BUILD", key = null)
    }

    fun event(area: String, message: String, error: Throwable? = null, key: String? = "$area|$message") {
        if (key != null && repeats.getOrPut(key) { AtomicInteger() }.incrementAndGet() > MAX_REPEATS) return
        val line = "monet-diag: [$area] $message (host $build)" + (error?.let { " ! ${it.javaClass.simpleName}: ${it.message?.take(200)}" } ?: "")
        runCatching { context?.log(line, error) }
        runCatching { android.util.Log.w(TAG, line, error) }
        runCatching {
            val target = file ?: return@runCatching
            if (target.length() > MAX_FILE_BYTES) target.writeText("")
            target.appendText(SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date()) + " $line\n")
        }
    }

    internal fun resetForTest() { repeats.clear(); context = null; file = null; build = "?" }
}

/**
 * Fail-open guard for one plugin stage: a failure is reported once per kind and swallowed, so the host keeps its
 * native behaviour; after [MonetDiag.MAX_FAILURES] failures the stage is switched off through [disable].
 */
internal class FailOpen(private val stage: String, private val disable: () -> Unit = {}) {
    private val failures = AtomicInteger()
    @Volatile var disabled = false
        private set
    inline fun <T> run(fallback: T, block: () -> T): T {
        if (disabled) return fallback
        return try { block() } catch (error: Throwable) { failed(error); fallback }
    }
    fun failed(error: Throwable) {
        val count = failures.incrementAndGet()
        MonetDiag.event("failed", "$stage count=$count; host value left unchanged", error, key = "failed|$stage|${error.javaClass.name}")
        if (count >= MonetDiag.MAX_FAILURES && !disabled) {
            disabled = true
            runCatching(disable).onFailure { MonetDiag.event("failed", "$stage disable failed", it, key = null) }
            MonetDiag.event("disabled", "$stage switched off after $count failures; Apple Music keeps native colours here", key = null)
        }
    }
}
