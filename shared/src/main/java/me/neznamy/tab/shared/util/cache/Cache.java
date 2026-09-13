package me.neznamy.tab.shared.util.cache;

import lombok.RequiredArgsConstructor;
import me.neznamy.tab.shared.TAB;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Cache to save resources when converting the same values over and over.
 *
 * @param   <K>
 *          Source to convert from
 * @param   <V>
 *          Target to convert to
 */
@RequiredArgsConstructor
public abstract class Cache<K, V> {

    private int accessCount; // Only used for debug message, races are fine
    private final String name;
    private final int cacheSize;
    private final Map<K, V> cache = new ConcurrentHashMap<>();

    /**
     * Gets value from cache. If not present, it is created using given function, inserted
     * into the cache and then returned.
     *
     * @param   key
     *          Source to convert
     * @return  Converted value
     */
    @NotNull
    public V get(@NotNull K key) {
        accessCount++;
        V value = cache.get(key);
        if (value != null) return value;
        if (cache.size() > cacheSize) {
            float efficiency = (float) (accessCount-cacheSize) / accessCount;
            TAB.getInstance().debug("Clearing " + name + " cache due to limit (efficiency " + efficiency*100 + "% with " + accessCount + " accesses)");
            accessCount = 0;
            cache.clear();
        }
        // Not using computeIfAbsent, conversion may use caches recursively and should not block other threads
        value = convert(key);
        V previous = cache.putIfAbsent(key, value);
        return previous != null ? previous : value;
    }

    /**
     * Converts source to target type.
     *
     * @param   key
     *          Source to convert
     * @return  Converted value
     */
    @NotNull
    public abstract V convert(@NotNull K key);
}
