package rbs;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** ~/Zomboid/Lua/RealBodyShape_agent.txt: written while the patch is live, deleted when the JVM exits (read by the mod's Lua). */
final class Marker {
    static final String FILE = "RealBodyShape_agent.txt";
    private static boolean hooked;

    private Marker() {}

    static Path path() {
        return Paths.get(System.getProperty("user.home"), "Zomboid", "Lua", FILE);
    }

    static synchronized void write(boolean patched, String mode, String note) {
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            String text = "version=" + RBSAgent.VERSION + "\npatched=" + (patched ? 1 : 0) + "\nmode=" + mode
                    + "\ntime=" + System.currentTimeMillis() + "\nnote=" + (note == null ? "" : note) + "\n";
            Files.write(p, text.getBytes(StandardCharsets.UTF_8));
            if (!hooked) {
                hooked = true;
                Runtime.getRuntime().addShutdownHook(new Thread(Marker::delete, "rbs-marker"));
            }
        } catch (Exception e) {
            System.out.println("[RBS] marker file: " + e);
        }
    }

    static void delete() {
        try { Files.deleteIfExists(path()); } catch (Exception ignored) {}
    }
}
