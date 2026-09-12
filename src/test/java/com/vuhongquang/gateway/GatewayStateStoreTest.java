package com.vuhongquang.gateway;

import com.vuhongquang.gateway.request.AddBackendRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GatewayStateStoreTest {

    @Test
    void save_thenLoad_returnsSameSnapshot(@TempDir Path tempDir) throws IOException {

        GatewayStateStore store = new GatewayStateStore(tempDir.toString(), 20);

        List<AddBackendRequest> snapshot = List.of(
                new AddBackendRequest("/api/movies",
                        "localhost",
                        8081,
                        5000,
                        0.5,
                        10,
                        20,
                        0,
                        true
                )
        );

        store.save(snapshot);
        List<AddBackendRequest> loaded = store.load();

        assertEquals(snapshot, loaded);
    }

    @Test
    void load_returnsEmptyListWhenNoFilesExist(@TempDir Path tempDir) throws IOException {
        GatewayStateStore store = new GatewayStateStore(tempDir.toString(), 20);
        List<AddBackendRequest> loaded = store.load();
        assertTrue(loaded.isEmpty());
    }

    @Test
    void save_prunesOldestFilesBeyondRetentionCount(@TempDir Path tempDir) throws IOException, InterruptedException {
        GatewayStateStore store = new GatewayStateStore(tempDir.toString(), 2);
        store.save(List.of());
        Thread.sleep(5);
        store.save(List.of());
        Thread.sleep(5);
        store.save(List.of());
        long count = Files.list(tempDir).count();
        assertEquals(2, count);
    }
}
