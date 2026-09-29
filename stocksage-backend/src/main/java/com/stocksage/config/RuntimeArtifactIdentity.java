package com.stocksage.config;

import com.stocksage.StockSageApplication;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Component;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;

/** Startup code-source fingerprint, not Git HEAD or instrumented in-memory bytecode. */
@Component
public class RuntimeArtifactIdentity {
    private final Map<String, Object> identity;

    public RuntimeArtifactIdentity() {
        Map<String, Object> captured;
        try {
            var location = StockSageApplication.class.getProtectionDomain().getCodeSource().getLocation();
            var packagedSource = "file".equals(location.getProtocol()) ? Path.of(location.toURI())
                    : new ApplicationHome(StockSageApplication.class).getSource().toPath();
            captured = capture(packagedSource);
        } catch (Exception unavailable) {
            captured = Map.of("status", "UNKNOWN", "reason", "CODE_SOURCE_UNAVAILABLE");
        }
        this.identity = captured;
    }

    public Map<String, Object> snapshot() { return identity; }

    static Map<String, Object> capture(Path source) throws Exception {
        if (Files.isRegularFile(source)) {
            return Map.of("status", "KNOWN", "scope", "CODE_SOURCE_FILE",
                    "sha256", HexFormat.of().formatHex(digest(source)),
                    "dependencyScope", "ONLY_BYTES_IN_SOURCE_FILE",
                    "javaRuntime", Runtime.version().toString());
        }
        if (!Files.isDirectory(source)) throw new IllegalStateException("Code source does not exist");
        try (var paths = Files.walk(source)) {
            var classes = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .sorted(Comparator.comparing(path -> relative(source, path))).toList();
            if (classes.isEmpty()) throw new IllegalStateException("Code source has no classes");
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (var stream = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), hash))) {
                for (Path file : classes) {
                    byte[] name = relative(source, file).getBytes(StandardCharsets.UTF_8);
                    stream.writeInt(name.length);
                    stream.write(name);
                    stream.write(digest(file));
                }
            }
            return Map.of("status", "KNOWN", "scope", "CODE_SOURCE_CLASS_TREE",
                    "sha256", HexFormat.of().formatHex(hash.digest()), "classCount", classes.size(),
                    "dependencyScope", "EXTERNAL_CLASSPATH_NOT_INCLUDED",
                    "javaRuntime", Runtime.version().toString());
        }
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static byte[] digest(Path path) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path);
             var output = new DigestOutputStream(OutputStream.nullOutputStream(), hash)) {
            input.transferTo(output);
        }
        return hash.digest();
    }
}
