package dev.kifranei.ampp.monet

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor

/** Seed of the current cover plus memoised per-theme schemes. Reads are lock-free and allocation-free. */
internal class MonetState(initialSeed: Int?) {
    @Volatile private var seed: Int? = initialSeed
    private val schemes = ConcurrentHashMap<Long, MonetScheme>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun currentSeed() = seed

    /** The scheme for the current seed, or null while no cover colour is known (native colours stay). */
    fun scheme(night: Boolean): MonetScheme? {
        val s = seed ?: return null
        val key = (s.toLong() shl 1) or (if (night) 1L else 0L)
        schemes[key]?.let { return it }
        val built = MonetCoverPalette.scheme(s, night) ?: return null
        if (schemes.size > 16) schemes.clear()
        schemes[key] = built
        return built
    }

    /** Returns true only when the seed really changed, so equal republishes cause no UI work. */
    fun publish(next: Int?): Boolean {
        if (next == null || next == seed) return false
        seed = next
        listeners.forEach { it() }
        return true
    }

    fun addListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
}

/** Small LRU of seed-by-cover-key; a cover with no usable hue is cached as such so it is not rescanned. */
internal class SeedCache(private val limit: Int = 64) {
    private val map = object : LinkedHashMap<String, Int?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int?>) = size > limit
    }

    @Synchronized fun contains(key: String) = map.containsKey(key)
    @Synchronized fun get(key: String): Int? = map[key]
    @Synchronized fun put(key: String, seed: Int?) { map[key] = seed }
}

/**
 * Off-main-thread cover pipeline: the caller (main thread) only passes a key and an opaque artwork handle;
 * pixel sampling and Oklab quantisation run on [executor]. Stale results from rapid track skipping are dropped.
 */
internal class CoverColorPipeline(
    private val executor: Executor,
    private val cache: SeedCache,
    private val state: MonetState,
    private val sample: (Any) -> IntArray?,
    private val persist: (Int) -> Unit,
) {
    @Volatile private var latestKey: String? = null
    private val ticket = java.util.concurrent.atomic.AtomicLong()

    /** Cheap and non-blocking; safe on the main thread. */
    fun submit(key: String, artwork: Any?) {
        if (key == latestKey) return
        latestKey = key
        val mine = ticket.incrementAndGet()
        executor.execute {
            val seed = if (cache.contains(key)) cache.get(key) else {
                val pixels = artwork?.let { runCatching { sample(it) }.getOrNull() }
                // A failed sample (null pixels) is not cached: the next submit may succeed.
                val computed = pixels?.let { MonetCoverPalette.seedFromPixels(it) }
                if (pixels != null) cache.put(key, computed) else if (latestKey == key) latestKey = null
                computed
            }
            if (mine != ticket.get()) return@execute
            if (state.publish(seed) && seed != null) persist(seed)
        }
    }
}
