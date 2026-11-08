package dev.kifranei.ampp.monet

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.util.TypedValue
import dev.amenhancer.plugin.api.PluginCall
import dev.amenhancer.plugin.api.PluginContext
import dev.amenhancer.plugin.api.PluginHook

/**
 * Recolours profiled colour resources as they are read. Colour ids are resolved by name at start-up, the hook
 * does one hash lookup per call and returns the host value untouched while no cover colour exists.
 */
internal class ResourceColorHooks(
    private val context: PluginContext,
    private val mode: MonetMode,
    private val profile: MonetProfile,
    private val state: MonetState,
    private val book: ColorRoleBook,
) {
    private val roles = HashMap<Int, ColorRole>()

    fun install(resources: Resources): List<String> {
        val status = ArrayList<String>()
        val pkg = context.hostPackageName
        fun id(name: String) = resources.getIdentifier(name, "color", pkg).takeIf { it != 0 }
        if (mode.affectsApp) profile.accent.forEach { r -> id(r.name)?.let { roles[it] = ColorRole.Accent(r.role) } ?: status.add("missing ${r.name}") }
        profile.player.forEach { n -> id(n)?.let { roles[it] = ColorRole.Player } ?: status.add("missing $n") }
        status.filter { it.startsWith("missing ") }.forEach { MonetDiag.event("profile", "colour resource ${it.removePrefix("missing ")} not in this host", key = null) }
        check(roles.isNotEmpty()) { "No profiled colour resources found" }
        seedNatives(resources)
        val registrations = ArrayList<AutoCloseable>()
        // Strict fail-open: a throwing callback would make the SDK stop the plugin; contain it, keep the host
        // colour, report it, and unregister these hooks after repeated failures.
        val guard = FailOpen("resource-colour hook") { registrations.forEach { runCatching(it::close) } }
        val after = object : PluginHook {
            override fun after(call: PluginCall) = guard.run(Unit) { onResolved(call) }
        }
        fun hook(owner: Class<*>, name: String, vararg types: Class<*>, required: Boolean) {
            runCatching { owner.getDeclaredMethod(name, *types) }.onSuccess {
                registrations += context.hooks.hook(it, false, after)
            }.onFailure {
                MonetDiag.event("hook", "Resources.$name(${types.joinToString(",") { t -> t.simpleName }}) unavailable (required=$required)", it, key = null)
                if (required) throw it else status.add("optional hook $name unavailable")
            }
        }
        hook(Resources::class.java, "getColor", Int::class.javaPrimitiveType!!, Resources.Theme::class.java, required = true)
        hook(Resources::class.java, "getColorStateList", Int::class.javaPrimitiveType!!, Resources.Theme::class.java, required = true)
        // XML `android:textColor="@color/..."` is loaded through this framework method.
        hook(Resources::class.java, "loadColorStateList", TypedValue::class.java, Int::class.javaPrimitiveType!!,
            Resources.Theme::class.java, required = false)
        context.onClose { registrations.forEach { runCatching(it::close) } }
        return status
    }

    /**
     * Learns the native light and dark values of every profiled colour up front (hooks are not installed yet),
     * so selectors that embed them, such as the non-glass bottom navigation, can be recognised on first read.
     */
    private fun seedNatives(resources: Resources) {
        val app = runCatching { context.application }.getOrNull()
        for (night in booleanArrayOf(false, true)) {
            val res = runCatching {
                val config = Configuration(resources.configuration)
                config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                app?.createConfigurationContext(config)?.resources
            }.getOrNull() ?: continue
            roles.forEach { (id, role) ->
                runCatching { res.getColorStateList(id, null) }.getOrNull()?.takeIf { !it.isStateful }
                    ?.let { book.rememberNative(it.defaultColor, role) }
            }
        }
    }

    private fun onResolved(call: PluginCall) {
        if (call.throwable != null) return
        val resources = call.receiver as? Resources ?: return
        val args = call.arguments
        val id = (if (call.method.name == "loadColorStateList") args.getOrNull(1) else args.getOrNull(0)) as? Int ?: return
        val role = roles[id]
        val result = call.result
        if (result is ColorStateList && result.isStateful) {
            onStateful(resources, role, result, call)
            return
        }
        role ?: return
        val native = when (result) {
            is Int -> result
            is ColorStateList -> result.defaultColor
            else -> return
        }
        book.rememberNative(native, role)
        val scheme = scheme(resources) ?: return
        val replaced = when (role) {
            is ColorRole.Accent -> MonetColors.accent(role.role, scheme, native)
            ColorRole.Player -> MonetColors.player(scheme, native)
        }
        if (replaced == native) return
        book.rememberApplied(replaced, role)
        call.returnResult(if (result is Int) replaced else ColorStateList.valueOf(replaced))
    }

    /** Selectors: swap only the state colours that are known native/applied colours (issue #1). */
    private fun onStateful(resources: Resources, role: ColorRole?, list: ColorStateList, call: PluginCall) {
        val scheme = scheme(resources) ?: return
        val parts = StatefulColors.parts(list) ?: return
        val includePlayer = role == ColorRole.Player
        val colors = StatefulColors.remap(parts) { book.replacement(it, scheme, includePlayer, mode.affectsApp) } ?: return
        parts.colors.forEachIndexed { i, from -> book.roleOf(from)?.let { book.rememberApplied(colors[i], it) } }
        call.returnResult(StatefulColors.build(parts, colors))
    }

    private fun scheme(resources: Resources): MonetScheme? {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return state.scheme(night)
    }
}
