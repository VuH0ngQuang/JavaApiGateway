package com.vuhongquang.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class GatewayStateStore<T> {
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault());
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(GatewayStateStore.class);

    private final Pattern stateFile;
    private final Path stateDir;
    private final int retentionCount;
    private final Class<T> itemClass;
    private final String filePrefix;

    public GatewayStateStore (
            String stateDir,
            int retentionCount,
            String filePrefix,
            Class<T> itemClass
    ) throws IOException {
        if (retentionCount < 1) {
            throw new IllegalArgumentException("retentionCount must be >= 1, was " + retentionCount);
        }
        this.stateDir = Paths.get(stateDir);
        this.retentionCount = retentionCount;
        this.stateFile = Pattern.compile(filePrefix+"-\\d{8}-\\d{6}-\\d{3}\\.json");
        this.itemClass = itemClass;
        this.filePrefix = filePrefix;
        Files.createDirectories(this.stateDir);
    }

    public synchronized void save(Collection<? extends T> snapshot) throws IOException {
        String fileName = filePrefix + "-" + TS_FORMAT.format(Instant.now()) + ".json";
        Path target = stateDir.resolve(fileName);
        Path tmp = stateDir.resolve(fileName+".tmp");

        objectMapper.writeValue(tmp.toFile(), snapshot);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        pruneOldFiles();
    }

    public synchronized List<T> load() throws IOException {
        List<Path> stateFiles = listStateFilesDescending();
        CollectionType listType = objectMapper.getTypeFactory().constructCollectionType(List.class, itemClass);

        for (Path file : stateFiles) {
            try {
                return objectMapper.readValue(file.toFile(), listType);
            } catch (IOException e) {
                log.warn("Failed to parse state file {}, falling back to next-oldest: {}", file.getFileName(), e.toString());
            }
        }
        return new ArrayList<>();
    }

    public synchronized List<T> loadManual (String filePath) throws IOException {
        try {
            Path path = Paths.get(filePath);
            CollectionType listType = objectMapper.getTypeFactory().constructCollectionType(List.class, itemClass);
            return objectMapper.readValue(path.toFile(), listType);
        } catch (Exception e) {
            log.warn("Failed to load state file {}: {}", filePath, e.toString());
        }
        return new ArrayList<>();
    }

    private void pruneOldFiles() throws IOException {
        List<Path> stateFiles = listStateFilesDescending();
        for (int i = retentionCount; i < stateFiles.size(); i++) {
            Files.deleteIfExists(stateFiles.get(i));
        }
    }

    private  List<Path> listStateFilesDescending() {
        try (Stream<Path> files = Files.list(stateDir)) {
            return files
                    .filter(p -> stateFile.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
