package com.vuhongquang.cache.striped;

public interface EvictionStore<V> {
    V get(String key);
    V put(String key, V value);
    V evictOne();
    V remove(String key);
    int size();
    void clear();
}
