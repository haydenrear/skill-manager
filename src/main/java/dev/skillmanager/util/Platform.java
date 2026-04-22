package dev.skillmanager.util;

import java.util.Locale;

public final class Platform {

    public enum Os { DARWIN, LINUX, WINDOWS, UNKNOWN }

    public enum Arch { X64, ARM64, UNKNOWN }

    private Platform() {}

    public static Os currentOs() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("mac") || name.contains("darwin")) return Os.DARWIN;
        if (name.contains("linux")) return Os.LINUX;
        if (name.contains("win")) return Os.WINDOWS;
        return Os.UNKNOWN;
    }

    public static Arch currentArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) return Arch.ARM64;
        if (arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x64")) return Arch.X64;
        return Arch.UNKNOWN;
    }

    public static String currentKey() {
        return osKey(currentOs()) + "-" + archKey(currentArch());
    }

    public static String osKey(Os os) {
        return switch (os) {
            case DARWIN -> "darwin";
            case LINUX -> "linux";
            case WINDOWS -> "windows";
            case UNKNOWN -> "unknown";
        };
    }

    public static String archKey(Arch arch) {
        return switch (arch) {
            case X64 -> "x64";
            case ARM64 -> "arm64";
            case UNKNOWN -> "unknown";
        };
    }

    public static boolean matches(String key) {
        if (key == null || key.isBlank()) return false;
        String cur = currentKey();
        if (key.equalsIgnoreCase(cur)) return true;
        String[] parts = key.toLowerCase(Locale.ROOT).split("-");
        if (parts.length == 1) return parts[0].equals(osKey(currentOs()));
        return parts[0].equals(osKey(currentOs())) && (parts[1].equals(archKey(currentArch())) || parts[1].equals("any"));
    }
}
