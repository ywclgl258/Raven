package com.raven.app.core.sitespi

/**
 * Thread-safe bounded LRU cache with optional per-entry TTL expiration.
 * Replaces ad-hoc `LinkedHashMap` caches previously duplicated across site adapters.
 */
class LruCache<K : Any, V : Any>(
    private val maxEntries: Int,
    private val ttlMs: Long? = null,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private data class Entry<V>(
        val value: V,
        val expiresAtMs: Long?,
    )

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(ttlMs == null || ttlMs > 0L) { "ttlMs must be positive when specified" }
    }

    private val lock = Any()
    private val map = object : LinkedHashMap<K, Entry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?): Boolean =
            size > maxEntries
    }

    fun get(key: K): V? = synchronized(lock) {
        val entry = map[key] ?: return null
        val expiresAt = entry.expiresAtMs
        if (expiresAt != null && clockMs() >= expiresAt) {
            map.remove(key)
            return null
        }
        entry.value
    }

    fun put(key: K, value: V, customTtlMs: Long? = ttlMs): V? = synchronized(lock) {
        val expiresAt = customTtlMs?.let { clockMs() + it }
        map.put(key, Entry(value, expiresAt))?.value
    }

    fun getOrPut(key: K, compute: () -> V): V {
        get(key)?.let { return it }
        val computed = compute()
        put(key, computed)
        return computed
    }

    suspend fun getOrPutSuspend(key: K, compute: suspend () -> V): V {
        get(key)?.let { return it }
        val computed = compute()
        put(key, computed)
        return computed
    }

    fun remove(key: K): V? = synchronized(lock) {
        map.remove(key)?.value
    }

    fun clear() = synchronized(lock) {
        map.clear()
    }

    fun evictExpired(): Int = synchronized(lock) {
        val now = clockMs()
        var removed = 0
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            val exp = iterator.next().value.expiresAtMs
            if (exp != null && now >= exp) {
                iterator.remove()
                removed++
            }
        }
        removed
    }

    fun size(): Int = synchronized(lock) { map.size }
}
