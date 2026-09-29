package com.stocksage.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.*;

class RuntimeArtifactIdentityTest {
    @TempDir Path directory;

    @Test
    void classTreeIdentityIgnoresLocationAndRuntimePropertiesButDetectsChangedClasses() throws Exception {
        Path first = Files.createDirectories(directory.resolve("first/package"));
        Path second = Files.createDirectories(directory.resolve("second/package"));
        Files.write(first.resolve("A.class"), new byte[]{1, 2});
        Files.write(first.resolve("B.class"), new byte[]{3});
        Files.write(second.resolve("B.class"), new byte[]{3});
        Files.write(second.resolve("A.class"), new byte[]{1, 2});
        var captured = RuntimeArtifactIdentity.capture(first.getParent());
        assertThat(captured).isEqualTo(RuntimeArtifactIdentity.capture(second.getParent()))
                .containsEntry("scope", "CODE_SOURCE_CLASS_TREE").containsEntry("classCount", 2);
        Files.writeString(first.getParent().resolve("application-local.properties"), "private-value");
        assertThat(RuntimeArtifactIdentity.capture(first.getParent())).isEqualTo(captured);
        Files.write(second.resolve("A.class"), new byte[]{1, 4});
        assertThat(RuntimeArtifactIdentity.capture(second.getParent()).get("sha256"))
                .isNotEqualTo(captured.get("sha256"));
        assertThatThrownBy(() -> captured.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new RuntimeArtifactIdentity().snapshot()).containsEntry("status", "KNOWN")
                .containsEntry("scope", "CODE_SOURCE_CLASS_TREE");
    }

    @Test
    void sourceFileUsesItsActualBytesAndMissingSourceCannotClaimIdentity() throws Exception {
        Path archive = directory.resolve("application.jar");
        byte[] bytes = {1, 2, 3, 4};
        Files.write(archive, bytes);
        assertThat(RuntimeArtifactIdentity.capture(archive)).containsEntry("scope", "CODE_SOURCE_FILE")
                .containsEntry("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        assertThatThrownBy(() -> RuntimeArtifactIdentity.capture(directory.resolve("absent")))
                .isInstanceOf(IllegalStateException.class);
    }
}
