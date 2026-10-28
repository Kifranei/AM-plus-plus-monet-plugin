package dev.kifranei.ampp.monet

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import dev.amenhancer.plugin.api.AmppPlugin
import dev.amenhancer.plugin.api.PluginContext
import dev.amenhancer.plugin.api.PluginSettingsSession
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Shown in diagnostics; kept in sync with monet-plugin/plugin.json by MonetProfileTest. */
internal const val PLUGIN_BUILD = "1.0.1/2"

/** SDK v1 entry. Mode Off installs nothing at all. */
class MonetCoverColorPlugin : AmppPlugin() {
    private lateinit var runtime: PluginContext
    private lateinit var store: MonetSettingsStore
    private lateinit var seeds: SeedStore
    private lateinit var profile: MonetProfile
    private lateinit var settings: MonetSettings
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AM monet cover colour").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    override fun onLoad(context: PluginContext) {
        runtime = context
        // Unsupported builds throw PluginUnsupportedException here, before any hook exists.
        MonetDiag.context = context
        profile = try { MonetProfile.load(context) } catch (error: Throwable) {
            MonetDiag.start(context, "none")
            MonetDiag.event("startup", "no usable profile; plugin refuses this host", error, key = null)
            throw error
        }
        MonetDiag.start(context, "applemusic-${context.hostVersionCode}.json")
        store = MonetSettingsStore(context.dataDirectory)
        seeds = SeedStore(context.dataDirectory)
        settings = store.read()
        context.onClose { io.shutdown() }
        context.log("Monet cover colour prepared for ${context.hostVersionName} (${context.hostVersionCode}), mode ${settings.mode}", null)
    }

    override fun onStart() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val mode = settings.mode
        if (mode == MonetMode.OFF) { runtime.log("Monet cover colour is off; no hooks installed", null); return }
        val app = runtime.application
        val resource = runtime.claimResource("shared:applemusic-monet-cover-color", true)
        try {
            val state = MonetState(seeds.read()) // tiny file; lets the first inflate after a cold start already match
            val book = ColorRoleBook()
            val pipeline = CoverColorPipeline(io, SeedCache(), state, ArtworkSampler::pixels, seeds::write)
            val status = ResourceColorHooks(runtime, mode, profile, state, book).install(app.resources)
            status.forEach { runtime.log("Monet: $it", null) }
            CoverMetadataSource(pipeline).install(runtime).let { source -> runtime.onClose { source.close() } }
            val playerRoot = app.resources.getIdentifier(profile.playerRootId, "id", runtime.hostPackageName)
            LiveRecolor(app, mode, state, book, playerRoot).also { it.start(); runtime.onClose(it::close) }
            runtime.log("Monet cover colour active: $mode", null)
            MonetDiag.event("capability", "Monet $mode -> ACTIVE (${status.size} note(s): ${status.joinToString("; ").ifEmpty { "none" }})", key = null)
        } catch (error: Throwable) {
            MonetDiag.event("capability", "Monet $mode -> FAILED at start; plugin stops, Apple Music keeps native colours", error, key = null)
            resource.close()
            throw error
        }
    }

    override fun createSettings(context: Context): PluginSettingsSession {
        val closed = AtomicBoolean()
        val main = Handler(Looper.getMainLooper())
        val density = context.resources.displayMetrics.density
        val text = MonetText.forLanguage(context.resources.configuration.locales[0].language)
        fun dp(n: Int) = (n * density).toInt()
        var draft = store.read()
        val ids = HashMap<Int, MonetMode>()
        val next = AtomicInteger(1000)
        val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(20)) }
        layout.addView(TextView(context).apply { this.text = "${text.name}\n${text.introduction}"; textSize = 16f })
        val group = RadioGroup(context)
        MonetMode.values().forEach { mode ->
            val id = next.getAndIncrement()
            ids[id] = mode
            group.addView(RadioButton(context).apply {
                this.id = id; this.text = text.label(mode); minHeight = dp(56); isChecked = mode == draft.mode
            })
        }
        group.setOnCheckedChangeListener { _, id -> ids[id]?.let { draft = MonetSettings(it) } }
        layout.addView(group)
        layout.addView(TextView(context).apply { this.text = text.restartHint; textSize = 13f; setPadding(0, dp(8), 0, dp(8)) })
        val save = Button(context).apply { this.text = text.save }
        save.setOnClickListener {
            val value = draft
            save.isEnabled = false
            io.execute {
                val result = runCatching { store.write(value) }
                main.post {
                    if (closed.get()) return@post
                    save.isEnabled = true
                    Toast.makeText(context, if (result.isSuccess) text.saved else text.saveFailed, Toast.LENGTH_SHORT).show()
                    result.exceptionOrNull()?.let { runtime.log("Monet settings write failed", it) }
                }
            }
        }
        layout.addView(save)
        return object : PluginSettingsSession {
            override fun getView(): View = layout
            override fun close() { closed.set(true) }
        }
    }
}
