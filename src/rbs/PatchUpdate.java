package rbs;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The classpath patch keeps up with the Workshop item by itself (2026-11-24, user: "load github once").
 *
 * install.bat lists two things first on the game's classpath: rbs_core.jar inside the Workshop item (our code, which
 * Steam updates with the mod) and the patch folder ~/Zomboid/RealBodyShape/patch (the game classes patched on the
 * player's computer, plus a copy of our code from install time for when the item is not there). A new version of our
 * code therefore arrives with the mod; only when it patches other game methods than the folder was made for
 * (RBSAgent.SPECS, hashed into rbs/specs.sha256) must the folder be made again. That is done here, as the game starts,
 * in the background, from the game's own projectzomboid.jar - in use from the next start (the classes this start
 * loaded stay as they were). Our code's copy in the folder is refreshed with it, so the fallback can always run what
 * the patched classes call.
 *
 * The rule that makes this safe: the hook methods RBSHook offers the patched game classes are never removed or
 * changed, only new ones added - a folder made by an older version keeps working with a newer rbs_core.jar.
 */
final class PatchUpdate {
    private static volatile boolean started;

    private PatchUpdate() {}

    /** once, from RBSHook.init: the classpath build with the patch folder on the classpath and our code newer than it */
    static void checkLater() {
        if (started) return;
        started = true;
        if (!"rbs".equals(PatchUpdate.class.getPackageName()) || RBSAgent.agentActive) return;   // the classpath copy only
        Thread t = new Thread(PatchUpdate::check, "rbs-patch-update");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    static void check() {
        try {
            Path dir = MakePatch.patchDir();
            String cp = System.getProperty("java.class.path", "");
            if (!Files.isDirectory(dir) || !onClasspath(cp, dir)) return;        // an older jar install, or not ours
            // the Workshop item's jar gone from where install.bat found it (the game moved to another drive with Steam's
            // "Move install folder", the item unsubscribed): the copy in the folder runs, without the item's updates
            for (String e : cp.split(File.pathSeparator)) {
                if (e.toLowerCase().endsWith("rbs_core.jar") && !Files.exists(Paths.get(e)))
                    RBSHook.warn("the Workshop item's rbs_core.jar is no longer at " + e + " (the game moved, or the item was"
                            + " unsubscribed): the copy in the patch folder runs - run install.bat again to get the item's updates");
            }
            Path f = dir.resolve("rbs/specs.sha256");
            String have = Files.exists(f) ? new String(Files.readAllBytes(f), StandardCharsets.UTF_8).trim() : "";
            String want = MakePatch.specsHash();
            if (want.equals(have)) {
                RBSHook.log("patch folder up to date with our code (" + codeSource() + ")");
                return;
            }
            Path gameJar = gameJar(cp);
            if (gameJar == null) {
                RBSHook.warn("patch folder: our code patches other game methods than it was made for, but projectzomboid.jar is not on the classpath - run install.bat again");
                return;
            }
            RBSHook.log("patch folder: our code (" + codeSource() + ") patches other game methods than the folder was made for - making it again from " + gameJar);
            MakePatch.buildPatchDir(gameJar, dir, MakePatch.ownClasses(), true);
            RBSHook.log("patch folder made again: the new parts of the patch work from the next start of the game");
            Marker.write(true, "classpath", "patch folder made again for the new version - restart the game to use all of it");
        } catch (Throwable t) {
            RBSHook.warn("patch folder update failed (the patch works as installed; install.bat makes it again): " + t);
        }
    }

    static boolean onClasspath(String cp, Path dir) {
        String want = norm(dir.toAbsolutePath().toString());
        for (String e : cp.split(File.pathSeparator)) if (norm(Paths.get(e).toAbsolutePath().toString()).equals(want)) return true;
        return false;
    }

    static Path gameJar(String cp) {
        for (String e : cp.split(File.pathSeparator)) {
            if (e.toLowerCase().endsWith("projectzomboid.jar")) {
                Path p = Paths.get(e);
                if (!p.isAbsolute()) p = Paths.get(System.getProperty("user.dir")).resolve(p);
                if (Files.exists(p)) return p;
            }
        }
        Path here = Paths.get(System.getProperty("user.dir"), "projectzomboid.jar");
        return Files.exists(here) ? here : null;
    }

    static String codeSource() {
        try {
            return String.valueOf(PatchUpdate.class.getProtectionDomain().getCodeSource().getLocation());
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String norm(String s) {
        String n = s.replace('\\', '/');
        while (n.endsWith("/")) n = n.substring(0, n.length() - 1);
        return n.toLowerCase();
    }
}
