package rbs;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Which head the HD body wears (RealBodyShape_HD's "Face" option, kept per character; 2026-11-16, a second local
 * face): ~/Zomboid/Lua/RealBodyShape_head.txt, written by that Lua, one line "head=&lt;id&gt;".
 *   no file / no line / "head=" : the head files at the top of the folders (media/textures/RBSHeadFace/...,
 *                                  RBSHeadMakeUp/...: one head, as before)
 *   "head=game"                  : none - the game's own face (our RBSFace layer, the game's makeup, no eyeballs)
 *   "head=&lt;id&gt;"            : that head's own folders (RBSHeadFace/&lt;id&gt;/..., RBSHeadMakeUp/&lt;id&gt;/...)
 * The file is looked at no more than twice a second. generation() goes up whenever the choice changes, so FaceLayer
 * and Eyes drop what they worked out for the old head.
 */
final class Head {
    static final String CONFIG = "RealBodyShape_head.txt";
    static final String NONE = "game";

    private static volatile String id = "";
    private static volatile int generation;
    private static volatile long nextCheck;
    private static long mtime = Long.MIN_VALUE;
    private static boolean logged;

    private Head() {}

    private static void poll() {
        long now = System.nanoTime();
        if (now < nextCheck) return;
        synchronized (Head.class) {
            if (now < nextCheck) return;
            nextCheck = now + 500_000_000L;
            String v = "";
            try {
                Path p = Paths.get(System.getProperty("user.home"), "Zomboid", "Lua", CONFIG);
                long mt = Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : Long.MIN_VALUE + 1;
                if (mt == mtime) return;
                mtime = mt;
                if (mt != Long.MIN_VALUE + 1) {
                    for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                        String t = line.trim();
                        if (t.startsWith("head=")) v = clean(t.substring(5).trim());
                    }
                }
            } catch (Exception e) {
                RBSHook.warn("head choice: " + e);
                return;
            }
            if (!v.equals(id) || !logged) {
                if (!v.equals(id)) generation++;
                id = v;
                logged = true;
                RBSHook.log("head: " + (v.isEmpty() ? "the head files at the top of the folders (if any)"
                        : NONE.equals(v) ? "none - the game's own face" : "'" + v + "' (its own folders)"));
            }
        }
    }

    /** a folder name: letters, digits, '_' and '-' only (anything else: as if there were no choice) */
    private static String clean(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-')) return "";
        }
        return s;
    }

    static int generation() { poll(); return generation; }

    /** the game's own face: no head layer, makeup or eyeballs */
    static boolean none() { poll(); return NONE.equals(id); }

    /** the chosen head's sub-folder ("&lt;head&gt;/"), or "" for the files at the top */
    static String sub() {
        poll();
        String i = id;
        return i.isEmpty() || NONE.equals(i) ? "" : i + "/";
    }
}
