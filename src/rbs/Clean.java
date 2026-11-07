package rbs;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zombies wear untouched copies of the clothes (part of "Real Body Shape - Zombie Bodies").
 * The main mod reshapes the player's 3D clothes by rewriting the model files in its own folder, and every character
 * wearing the same piece loads that same file: zombies of the player's sex wore the player's shape, and Fit then
 * pushed it out once more for a fat zombie. The Zombie Bodies mod ships the untouched files a second time under
 * media/models_X/RBSClean/ (another data mod can do the same for its own); when a zombie - alive, or a
 * corpse drawn by DeadBodyAtlas - gets a clothing model that has such a copy, the copy is loaded instead.
 * The player and everyone else keep the normal path. A copy is used only when it is the same file type the game
 * would load for the original name (a mod's .fbx must not turn into the vanilla .x).
 */
final class Clean {
    static final String DIR = "rbsclean/";
    private static final String[] EXTS = { ".fbx", ".glb", ".x" };     // the game's own order
    private static volatile boolean failed, ready;
    private static Object fileSystem;
    private static Method getString, amVisual;
    /** mesh name -> the copy's name, "" = no copy (or not usable) */
    private static final Map<String, String> cache = new ConcurrentHashMap<>();
    /** set while an AnimatedModel builds the clothes of a zombie corpse (no character is passed then) */
    private static final ThreadLocal<Boolean> CORPSE = new ThreadLocal<>();
    private static int diag;
    private static long used, summaryAt;

    private Clean() {}

    static Boolean enter(boolean zombieCorpse) {
        Boolean prev = CORPSE.get();
        CORPSE.set(zombieCorpse);
        return prev;
    }

    static void exit(Boolean prev) {
        if (prev == null) CORPSE.remove(); else CORPSE.set(prev);
    }

    static boolean isZombieModel(Object model) throws Exception {
        if (model == null || !RBSHook.ensureInit()) return false;
        Method m = amVisual;
        if (m == null || m.getDeclaringClass() != model.getClass()) {
            m = model.getClass().getMethod("getHumanVisual");
            amVisual = m;
        }
        return RBSHook.isZombieVisual(m.invoke(model));
    }

    /** a live zombie, or (no character passed) the corpse of one whose clothes an AnimatedModel builds right now */
    static boolean isZombieWearer(Object chr) throws Exception {
        return chr != null ? RBSHook.isLiveZombie(chr) : Boolean.TRUE.equals(CORPSE.get());
    }

    /** the mesh name to load for this character: the untouched copy for a zombie when there is one, else as given */
    static Object meshFor(Object mesh, Object chr) throws Exception {
        if (failed || !(mesh instanceof String)) return mesh;
        if (!RBSHook.zombiesEnabled()) return mesh;
        boolean zombie = isZombieWearer(chr);
        if (!zombie) return mesh;
        if (!ready) init();
        if (failed) return mesh;
        String name = (String) mesh;
        String c = cache.get(name);
        if (c == null) {
            c = resolve(name);
            cache.put(name, c);
            if (!c.isEmpty() && diag < 6) {
                diag++;
                RBSHook.log("clothes: zombies wear the untouched copy " + c + " (the player keeps " + name + ")");
            }
        }
        if (c.isEmpty()) return mesh;
        summary();
        return c;
    }

    // ---------------- where the game would find a model file ----------------

    /** same rules as FileTask_AbstractLoadModel: a name with "media/" or an extension is a file, else try the types */
    private static String resolve(String name) throws Exception {
        String n = name.replace('\\', '/');
        String lower = n.toLowerCase(Locale.ROOT);
        if (lower.startsWith("x:")) {
            n = n.substring(2);
            lower = lower.substring(2);
        }
        if (lower.contains(DIR)) return "";                    // already a copy
        String clean, full, cleanFull;
        if (lower.contains("media/")) {
            int mx = lower.indexOf("models_x/");
            if (mx < 0) return "";
            clean = n.substring(0, mx + 9) + DIR + n.substring(mx + 9);
            full = n;
            cleanFull = clean;
        } else {
            clean = DIR + n;
            full = "media/models_X/" + n;
            cleanFull = "media/models_X/" + clean;
        }
        String last = lower.substring(lower.lastIndexOf('/') + 1);
        if (last.contains(".")) return exists(cleanFull) ? clean : "";
        String ext = firstExisting(full);
        return ext != null && ext.equals(firstExisting(cleanFull)) ? clean : "";
    }

    private static String firstExisting(String base) throws Exception {
        for (String e : EXTS) if (exists(base + e)) return e;
        return null;
    }

    private static boolean exists(String path) throws Exception {
        Object p = getString.invoke(fileSystem, path);
        return p != null && new File(p.toString()).isFile();
    }

    private static void summary() {
        used++;
        long now = System.nanoTime();
        if (now < summaryAt) return;
        summaryAt = now + 60_000_000_000L;
        RBSHook.log("clothes: " + used + " zombie clothing piece(s) loaded from untouched copies so far (" + cache.size() + " model name(s) checked)");
    }

    private static synchronized void init() {
        if (ready || failed) return;
        try {
            Class<?> zfs = Class.forName("zombie.ZomboidFileSystem", false, Clean.class.getClassLoader());
            fileSystem = zfs.getField("instance").get(null);
            getString = zfs.getMethod("getString", String.class);
            ready = true;
            RBSHook.log("clothes: zombies use untouched clothing copies from media/models_X/RBSClean when there are any");
        } catch (Throwable e) {
            failed = true;
            RBSHook.warn("clothes copies init failed, zombies share the player's clothing files: " + e);
        }
    }
}
