package com.vuhongquang.cache.striped;

import java.util.LinkedHashMap;

public class LruStore<V> implements EvictionStore<V> {

    private final LinkedHashMap<String, V> entries;

    public LruStore() {
        this.entries = new LinkedHashMap<>(16, 0.75f, true);
    }

    @Override
    public V get(String key) {
        return entries.get(key);
    }

    @Override
    public V put(String key, V value) {
        return entries.put(key, value);
    }

    @Override
    public V evictOne() {
        var it = entries.entrySet().iterator();
        if (!it.hasNext()) {
            return null;
        }
        V value = it.next().getValue();
        it.remove();
        return value;
    }

    @Override
    public V remove(String key) {
        return entries.remove(key);
    }

    @Override
    public int size() {
        return entries.size();
    }

    @Override
    public void clear() {
        entries.clear();
    }
}
