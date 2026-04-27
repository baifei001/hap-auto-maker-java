package com.hap.automaker.config;

import java.nio.file.Files;
import java.nio.file.Path;

public record RepoPaths(Path repoRoot, Path javaProjectRoot) {

    public static RepoPaths detect(Path start) {
        Path current = start.toAbsolutePath().normalize();
        while (current != null) {
            boolean looksLikeRepo = Files.isDirectory(current.resolve("config"))
                    && Files.isDirectory(current.resolve("data"))
                    && Files.isDirectory(current.resolve("scripts"));
            if (looksLikeRepo) {
                return new RepoPaths(current, current.resolve("hap-auto-maker-java"));
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to detect repo root from " + start);
    }
}
