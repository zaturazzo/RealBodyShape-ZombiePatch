package rbs;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The head each NPC of another mod wears (user, 2026-11-19: "each NPC its own head"). The HD add-on's local heads
 * (media/rbs_npc_heads.txt, written by hd_mod/zombie_hd.py with the women's variant bodies that wear them: model
 * scripts RBS_ZombieFemale_&lt;Variant&gt;_HD_&lt;head&gt;) - a woman NPC gets one of them, picked by its looks (the same
 * companion always the same head, in every session); its mod can choose one with ModData RBS_HeadSet (a head's id, or
 * "game" for the game's own head). Men keep the game's head (the heads are women's). No list = nobody gets one.
 * What each live NPC wears is noted by its looks too, so a UI preview of it (its info window) can be drawn the same
 * (PreviewNpc) and the face layer follows each one's head (FaceLayer).
 */
final class NpcHeads {
    static final String LIST = "media/rbs_npc_heads.txt";
    static final String SET_KEY = "RBS_HeadSet";
    static final String NONE = "";

    private static volatile String[] heads;
    private static volatile long nextRead;
    private static Method fsGetString;
    private static Object fileSystem;

    /** live NPC -> the head it wears ("" the game's) */
    private static final Map<Object, String> byChr = Collections.synchronizedMap(new WeakHashMap<>());
    /** npcHash of the looks -> { female (Boolean), variant (Integer), head (String) } of a live NPC seen with them */
    private static final Map<Integer, Object[]> seen = Collections.synchronizedMap(new java.util.LinkedHashMap<Integer, Object[]>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Object[]> eldest) {
            return size() > 512;
        }
    });

    private NpcHeads() {}

    /** the local heads (ids), read once (again every 30 s while there is no list) */
    static String[] heads() {
        String[] h = heads;
        long now = System.nanoTime();
        if (h != null && (h.length > 0 || now < nextRead)) return h;
        synchronized (NpcHeads.class) {
            if (heads != null && (heads.length > 0 || now < nextRead)) return heads;
            List<String> out = new ArrayList<>();
            try {
                if (fileSystem == null) {
                    Class<?> zfs = Class.forName("zombie.ZomboidFileSystem", false, NpcHeads.class.getClassLoader());
                    fileSystem = zfs.getField("instance").get(null);
                    fsGetString = zfs.getMethod("getString", String.class);
                }
                Object p = fsGetString.invoke(fileSystem, LIST);
                if (p instanceof String && new File((String) p).isFile()) {
                    for (String line : Files.readAllLines(new File((String) p).toPath(), StandardCharsets.UTF_8)) {
                        String t = line.trim();
                        if (!t.isEmpty() && t.matches("[A-Za-z0-9_-]+")) out.add(t);
                    }
                    if (!out.isEmpty()) RBSHook.log("NPC heads: other mods' NPC women wear one of " + out + " (by their looks)");
                }
            } catch (Throwable t) {
                RBSHook.warn("NPC heads: list not read: " + t);
            }
            heads = out.toArray(new String[0]);
            nextRead = now + 30_000_000_000L;
            return heads;
        }
    }

    /** the head of an NPC with these looks: its mod's choice (RBS_HeadSet), else one by its looks; "" the game's */
    static String headOf(Object chr, Object visual, boolean female) {
        if (!female || visual == null) return NONE;
        String[] h = heads();
        if (h.length == 0) return NONE;
        Object set = chr == null ? null : RBSHook.modData(chr, SET_KEY);
        if (set != null) {
            String s = set.toString().trim();
            if (s.equalsIgnoreCase("game")) return NONE;
            for (String x : h) if (x.equalsIgnoreCase(s)) return x;
        }
        try {
            return h[Math.floorMod(mix(RBSHook.npcHash(visual)), h.length)];
        } catch (Throwable t) {
            return NONE;
        }
    }

    /** spreads a looks hash (close looks would otherwise pick the same head) */
    static int mix(int k) {
        k ^= k >>> 16;
        k *= 0x45d9f3b;
        k ^= k >>> 16;
        return k;
    }

    /** npcHash of the looks -> the height a live NPC with them has (for its previews) */
    private static final Map<Integer, Double> heights = Collections.synchronizedMap(new java.util.LinkedHashMap<Integer, Double>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Double> eldest) {
            return size() > 512;
        }
    });

    static void noteHeight(Object visual, double k) {
        try {
            heights.put(RBSHook.npcHash(visual), k);
        } catch (Throwable ignored) {
        }
    }

    /** the height of the live NPC with these looks, or null */
    static Double heightOf(Object visual) {
        try {
            return heights.isEmpty() ? null : heights.get(RBSHook.npcHash(visual));
        } catch (Throwable t) {
            return null;
        }
    }

    /** what a live NPC wears, noted for its face layer and its previews */
    static void note(Object chr, Object visual, boolean female, int variant, String head) {
        byChr.put(chr, head);
        try {
            seen.put(RBSHook.npcHash(visual), new Object[]{ female, variant, head });
        } catch (Throwable ignored) {
        }
    }

    /** any NPC of another mod seen at all (none without an NPC mod: every check here stops at once) */
    static boolean anyNoted() {
        return !byChr.isEmpty();
    }

    /** the head a live NPC wears (null: not noted yet) */
    static String headOfChr(Object chr) {
        return chr == null ? null : byChr.get(chr);
    }

    // ---------------- a UI preview of an NPC (its info window: Stay With Me's) ----------------
    // The preview draws a survivor (not a zombie) from the NPC's looks with the game's shared body - the player's own
    // reshaped file, its head and its clothes. A preview whose looks are those of a live NPC seen this session is drawn
    // as that NPC instead: its body and head (RBSHook.corpseModel), the untouched clothes (Clean) fitted to its body (Fit),
    // its face (FaceLayer). The player's own previews (character creation, the info window) keep the player's.
    private static Method amAnimate, amIsometric, amZombie;
    private static int previewLogs;

    /** { female, variant, head } when this visual is a UI preview's (not a zombie's, animating or flat) with the looks
     *  of a live NPC seen this session, else null */
    static Object[] previewOf(Object visual) {
        if (visual == null || seen.isEmpty()) return null;
        try {
            if (RBSHook.isZombieVisual(visual)) return null;
            Object am = RBSHook.visualOwner(visual);
            if (am == null || !"AnimatedModel".equals(am.getClass().getSimpleName())) return null;
            if (amAnimate == null || amAnimate.getDeclaringClass() != am.getClass()) {
                Class<?> c = am.getClass();
                amAnimate = c.getMethod("isAnimate");
                amIsometric = c.getMethod("isIsometric");
                amZombie = c.getMethod("isZombie");
            }
            if ((Boolean) amZombie.invoke(am)) return null;
            // the corpse atlas and mannequins never animate and keep the isometric view: not previews
            if (!(Boolean) amAnimate.invoke(am) && (Boolean) amIsometric.invoke(am)) return null;
            Object[] s = seen.get(RBSHook.npcHash(visual));
            if (s != null && previewLogs++ < 3)
                RBSHook.log("NPC heads: a preview with the looks of another mod's NPC (its info window) - drawn as that NPC: "
                        + RBSHook.NAMES[(Integer) s[1]] + (((String) s[2]).isEmpty() ? "" : ", head '" + s[2] + "'"));
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    /** { female, variant, head } of the live NPC with these looks seen this session, or null */
    static Object[] seenWith(Object visual) {
        if (visual == null || seen.isEmpty()) return null;
        try {
            return seen.get(RBSHook.npcHash(visual));
        } catch (Throwable t) {
            return null;
        }
    }
}
