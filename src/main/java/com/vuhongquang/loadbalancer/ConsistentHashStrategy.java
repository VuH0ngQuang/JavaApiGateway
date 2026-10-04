package com.vuhongquang.loadbalancer;

import com.vuhongquang.GatewayConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;

public class ConsistentHashStrategy extends LoadBalancingStrategy{
    private static final Logger log = LoggerFactory.getLogger(ConsistentHashStrategy.class);

    private final GatewayConfig config;
    private final ConcurrentSkipListMap<Long, Backend> ring;
    public enum KeyType {CLIENT_IP, URI};
    private final KeyType keyType;

    public ConsistentHashStrategy(KeyType keyType, GatewayConfig config) {
        this.keyType = keyType;
        this.config = config;
        ring = new ConcurrentSkipListMap<>();
    }

    @Override
    protected Backend doSelect(List<Backend> backends, String clientIp, String uri) {
        String key = keyType == KeyType.CLIENT_IP ? clientIp : uri;
        if (ring.isEmpty()) return null;

        long h = hash(key);

        Map.Entry<Long, Backend> entry = ring.ceilingEntry(h);

        if (entry == null) {
            entry = ring.firstEntry();
        }

        int attempts = 0;

        while (attempts < ring.size()) {
            if (backends.contains(entry.getValue())) {
                return entry.getValue();
            }
            Map.Entry<Long, Backend> next = ring.higherEntry(entry.getKey());
            entry = (next != null) ? next : ring.firstEntry();
            attempts++;
        }

        return null;
    }

    @Override
    void onBackendAdded(Backend be) {
        super.onBackendAdded(be);
        for (int i = 0; i < config.virtualNode(); i++) {
            ring.put(hash(be.address().toString() + "#" + i), be);
        }
    }

    @Override
    void onBackendRemoved(Backend be) {
        super.onBackendRemoved(be);
        for (int i = 0; i < config.virtualNode(); i++) {
            ring.remove(hash(be.address().toString() + "#" + i), be);
        }
    }

    private long hash(String key)  {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException e) {
            log.error("MD5 not available", e);
            return 0;
        }
    }

    public KeyType keyType() {
        return keyType;
    }
}
