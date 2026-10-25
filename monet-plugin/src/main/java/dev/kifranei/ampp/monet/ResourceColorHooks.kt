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

    private fun onResolved(call: PluginCall) {
        if (call.throwable != null) return
        val resources = call.receiver as? Resources ?: return
        val args = call.arguments
        val id = (if (call.method.name == "loadColorStateList") args.getOrNull(1) else args.getOrNull(0)) as? Int ?: return
        val role = roles[id] ?: return
        val result = call.result
        val native = when (result) {
            is Int -> result
            is ColorStateList -> if (result.isStateful) return else result.defaultColor
            else -> return
        }
        book.rememberNative(native, role)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val scheme = state.scheme(night) ?: return
        val replaced = when (role) {
            is ColorRole.Accent -> MonetColors.accent(role.role, scheme, native)
            ColorRole.Player -> MonetColors.player(scheme, native)
        }
        if (replaced == native) return
        book.rememberApplied(replaced, role)
        call.returnResult(if (result is Int) replaced else ColorStateList.valueOf(replaced))
    }
}
