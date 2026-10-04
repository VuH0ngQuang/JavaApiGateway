package com.vuhongquang.cache.striped;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.TreeMap;

public class LfuStore<V> implements EvictionStore<V> {
    private static final Logger log = LoggerFactory.getLogger(LfuStore.class);

    private final Map<String, V> keyToValue;
    private final Map<String, Integer> keyToFreq;
    private final TreeMap<Integer, LinkedHashSet<String>> freqToKeys;

    private int minFreq;

    public LfuStore() {
        this.keyToValue = new HashMap<>();
        this.keyToFreq = new HashMap<>();
        this.freqToKeys = new TreeMap<>();
        this.minFreq = 0;
    }

    public V get(String key) {
        V value = keyToValue.get(key);
        if (value == null) return null;
        bump(key);
        return value;
    }

    @Override
    public V put(String key, V value) {
        if (keyToValue.containsKey(key)) {
            V old = keyToValue.put(key, value);
            bump(key);
            return old;
        }
        keyToValue.put(key, value);
        keyToFreq.put(key, 1);
        freqToKeys.computeIfAbsent(1, k -> new LinkedHashSet<>()).add(key);
        minFreq = 1;
        return null;
    }

    @Override
    public V evictOne() {
        if (keyToValue.isEmpty()) return null;

        LinkedHashSet<String> bucket = freqToKeys.get(minFreq);
        String key = bucket.getFirst();
        bucket.removeFirst();
        if (bucket.isEmpty()) freqToKeys.remove(minFreq);
        minFreq = freqToKeys.isEmpty() ? 0 : freqToKeys.firstKey();
        keyToFreq.remove(key);
        return keyToValue.remove(key);
    }

    @Override
    public V remove(String key) {
        if (keyToValue.isEmpty()) return null;
        if (!keyToFreq.containsKey(key)) return null;
        int freq = keyToFreq.get(key);
        LinkedHashSet<String> bucket = freqToKeys.get(freq);
        bucket.remove(key);
        if (bucket.isEmpty()) freqToKeys.remove(freq);
        keyToFreq.remove(key);
        return keyToValue.remove(key);
    }

    @Override
    public int size() {
        return keyToValue.size();
    }

    @Override
    public void clear() {
        keyToValue.clear();
        keyToFreq.clear();
        freqToKeys.clear();
    }

    private void bump(String key) {
        int freq = keyToFreq.get(key);
        LinkedHashSet<String> bucket = freqToKeys.get(freq);
        bucket.remove(key);
        if (bucket.isEmpty()) {
            freqToKeys.remove(freq);
            if (freq == minFreq) minFreq = freq + 1;
        }
        keyToFreq.put(key, freq + 1);
        freqToKeys.computeIfAbsent(freq + 1, k -> new LinkedHashSet<>()).add(key);
    }
}
