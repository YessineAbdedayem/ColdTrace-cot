package tn.supcom.coldtrace.ingestion;

import org.bson.Document;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;

/** Single-writer append/force/rename inbox. No MongoDB or network dependency. */
public final class DurableInbox {
    public record Entry(Path file, String topic, String payload, Instant ingestedAt) { }
    private final Path directory;
    private final int maxFiles;
    private final long maxBytes;
    public DurableInbox(Path directory, int maxFiles, long maxBytes) throws IOException {
        this.directory = directory;
        this.maxFiles = maxFiles;
        this.maxBytes = maxBytes;
        Files.createDirectories(directory.resolve("quarantine"));
    }
    public synchronized Path append(String topic, String payload, Instant received) throws IOException {
        byte[] bytes = new Document("topic", topic).append("payload", payload).append("ingestedAt", received.toString())
                .toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        List<Path> all = allFiles();
        long size = 0;
        for (Path p : all) size += Files.size(p);
        if (all.size() >= maxFiles || size + bytes.length > maxBytes) throw new IOException("durable inbox capacity exceeded");
        Path target = directory.resolve(String.format("%019d-%s.evt", received.toEpochMilli(), UUID.randomUUID()));
        write(target, bytes);
        return target;
    }
    private void write(Path target, byte[] bytes) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, CREATE_NEW, WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        forceDirectory(target.getParent());
    }
    private void forceDirectory(Path dir) throws IOException {
        // Linux Docker volumes support directory fsync; Windows direct unit tests may not.
        try (FileChannel channel = FileChannel.open(dir, READ)) { channel.force(true); }
        catch (AccessDeniedException | UnsupportedOperationException e) {
            if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) throw e;
        }
    }
    public synchronized List<Path> pending(int limit) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".evt")).sorted().limit(limit).toList();
        }
    }
    public Entry read(Path file) throws IOException {
        Document d = Document.parse(Files.readString(file));
        return new Entry(file, d.getString("topic"), d.getString("payload"), Instant.parse(d.getString("ingestedAt")));
    }
    public synchronized void complete(Path file) throws IOException { Files.delete(file); forceDirectory(directory); }
    public synchronized void quarantine(Entry entry, String reason) throws IOException {
        Document raw = Document.parse(Files.readString(entry.file())).append("reason", reason);
        write(directory.resolve("quarantine").resolve(entry.file().getFileName()), raw.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        complete(entry.file());
    }
    private List<Path> allFiles() throws IOException {
        try (var paths = Files.walk(directory)) { return paths.filter(Files::isRegularFile).toList(); }
    }
    public synchronized Map<String, Object> status() throws IOException {
        List<Path> all = allFiles(); long bytes = 0;
        for (Path p : all) bytes += Files.size(p);
        return Map.of("pending", pending(Integer.MAX_VALUE).size(), "quarantined", all.stream()
                        .filter(p -> p.getParent().endsWith("quarantine")).count(),
                "bytes", bytes, "maxFiles", maxFiles, "maxBytes", maxBytes);
    }
    public synchronized List<Map<String, Object>> rejected(int limit) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (var paths = Files.list(directory.resolve("quarantine"))) {
            for (Path p : paths.sorted().limit(limit).toList()) {
                Document d = Document.parse(Files.readString(p));
                result.add(Map.of("id", p.getFileName().toString(), "topic", d.getString("topic"),
                        "reason", d.getString("reason"), "ingestedAt", d.getString("ingestedAt")));
            }
        }
        return result;
    }
}
