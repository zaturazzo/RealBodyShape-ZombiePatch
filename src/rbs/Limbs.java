package rbs;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Dismembered zombies (mod "Real Body Shape - Zombie Dismemberment", id RealBodyShapeLimbs).
 * Hides a hand / forearm / whole arm / leg by collapsing the skinning matrices of those bones onto the joint
 * they hang from: every vertex weighted to them lands on one point (zero-area triangles = invisible), the few
 * blended vertices at the joint pinch into a stump. Because this happens on the matrix palette, the body mesh
 * and every 3D clothing piece skinned to the same bones (sleeves, gloves, trousers, shoes) vanish together,
 * and corpses rendered by DeadBodyAtlas get the same treatment through AnimatedModel.
 * Which zombie misses what is a stable seed of its looks (same formula as the Lua side, which makes legless
 * zombies crawl and paints blood on the stumps). Config: ~/Zomboid/Lua/RealBodyShape_limbs.txt (Lua writes it).
 */
final class Limbs {
    static final String CONFIG = "RealBodyShape_limbs.txt";
    // mask bits; group index = bit number
    static final int L_HAND = 1, L_FORE = 2, L_UPPER = 4, R_HAND = 8, R_FORE = 16, R_UPPER = 32, L_LEG = 64, R_LEG = 128;

    private static volatile boolean enabled = false;
    private static volatile double pHand = 2.0, pFore = 1.0, pArm = 1.5, pLegs = 0.8, pLeg1 = 1.2;   // percent of all zombies
    private static long cfgMtime = -1, cfgNextCheck;
    private static volatile boolean failed, ready;

    private static Method apCharacter, mStore, mLoad, mInvert, amVisual;
    private static Field fBoneOffset, fBoneIndices;
    private static Constructor<?> matrixCtor;

    /** AnimationPlayer -> HumanVisual for AnimatedModel (corpse atlas, where the player has no character) */
    private static final Map<Object, Object> animated = Collections.synchronizedMap(new WeakHashMap<>());
    /** character -> {mask, valid-until nanos} */
    private static final Map<Object, long[]> byChar = Collections.synchronizedMap(new WeakHashMap<>());
    /** SkinningData -> bone groups of that mesh */
    private static final Map<Object, Bones> bones = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<FloatBuffer> BUF = ThreadLocal.withInitial(() -> FloatBuffer.allocate(16));

    private Limbs() {}

    static final class Bones {
        final int[][] groups = new int[8][];
        final int[] joint = new int[8];
        final float[][] bindPos = new float[8][];
        boolean any;
    }

    // ---------------- hooks (called from RBSHook) ----------------

    /** AnimationPlayer.getSkinTransforms(data) returned t: collapse the bones of the character's missing limbs. */
    static void apply(Object player, Object data, Object t) throws Exception {
        if (failed || data == null || t == null) return;
        loadConfig();
        if (!enabled) return;
        if (!ready) init();
        if (failed) return;
        int mask = maskFor(player);
        summary();
        if (mask == 0) return;
        Bones b = bonesFor(data);
        if (!b.any) return;
        Object[] tr = (Object[]) t;
        if ((mask & L_UPPER) != 0) collapse(tr, b, 2); else if ((mask & L_FORE) != 0) collapse(tr, b, 1); else if ((mask & L_HAND) != 0) collapse(tr, b, 0);
        if ((mask & R_UPPER) != 0) collapse(tr, b, 5); else if ((mask & R_FORE) != 0) collapse(tr, b, 4); else if ((mask & R_HAND) != 0) collapse(tr, b, 3);
        if ((mask & L_LEG) != 0) collapse(tr, b, 6);
        if ((mask & R_LEG) != 0) collapse(tr, b, 7);
    }

    /**
     * AnimatedModel.getAnimationPlayer() returned p: remember which visual that player animates. Also done with
     * dismemberment off, because Fit needs it for the clothes of corpses.
     */
    static void remember(Object player, Object model) throws Exception {
        if (failed || player == null || model == null) return;
        if (!ready) init();
        if (failed) return;
        Method m = amVisual;
        if (m == null || m.getDeclaringClass() != model.getClass()) {
            m = model.getClass().getMethod("getHumanVisual");
            amVisual = m;
        }
        Object visual = m.invoke(model);
        if (visual != null) animated.put(player, visual);
    }

    /** the visual an AnimatedModel's player draws (corpses in DeadBodyAtlas), null if unknown */
    static Object animatedVisual(Object player) {
        return player == null ? null : animated.get(player);
    }

    // ---------------- who misses what ----------------

    static int maskFor(Object player) throws Exception {
        Object chr = apCharacter.invoke(player);
        if (chr != null) {
            long now = System.nanoTime();
            long[] e = byChar.get(chr);
            if (e != null && now < e[1]) return (int) e[0];
            int m = 0;
            if (RBSHook.isLiveZombie(chr)) {
                Object visual = RBSHook.visualOf(chr);
                if (visual != null) {
                    long seed = RBSHook.seedOf(visual);
                    m = maskOf(seed);
                    if (m != 0 && (e == null || e[0] != m)) {
                        hidden++;
                        if (diag++ < 12) RBSHook.log("limbs: zombie seed=" + seed + " mask=" + describe(m));
                    }
                }
            }
            byChar.put(chr, new long[]{ m, now + 2_000_000_000L });
            return m;
        }
        Object visual = animated.get(player);
        if (visual == null || !RBSHook.isZombieVisual(visual)) return 0;
        return maskOf(RBSHook.seedOf(visual));
    }

    /** Same arithmetic as RealBodyShapeLimbs.lua (maskOf): the two sides must agree on every zombie. */
    static int maskOf(long h) {
        int mask = 0;
        double armRoll = (h % 10000) / 100.0;                 // 0 .. 99.99 (percent)
        int side = (int) ((h / 10000) % 2);                   // 0 = left, 1 = right
        int level = armRoll < pHand ? 1 : armRoll < pHand + pFore ? 2 : armRoll < pHand + pFore + pArm ? 3 : 0;
        if (level >= 1) mask |= side == 0 ? L_HAND : R_HAND;
        if (level >= 2) mask |= side == 0 ? L_FORE : R_FORE;
        if (level >= 3) mask |= side == 0 ? L_UPPER : R_UPPER;
        double legRoll = ((h / 20000) % 10000) / 100.0;
        int legSide = (int) ((h / 200000000L) % 2);
        if (legRoll < pLegs) mask |= L_LEG | R_LEG;
        else if (legRoll < pLegs + pLeg1) mask |= legSide == 0 ? L_LEG : R_LEG;
        return mask;
    }

    // ---------------- bones ----------------

    @SuppressWarnings("unchecked")
    static Bones bonesFor(Object data) throws Exception {
        Bones b = bones.get(data);
        if (b != null) return b;
        b = new Bones();
        Arrays.fill(b.joint, -1);
        Map<String, Integer> names = (Map<String, Integer>) fBoneIndices.get(data);
        List<Object> offsets = (List<Object>) fBoneOffset.get(data);
        if (names != null && offsets != null) {
            List<Integer>[] hand = lists(), fore = lists(), upper = lists(), leg = lists();
            int[] jHand = {-1, -1}, jFore = {-1, -1}, jUpper = {-1, -1}, jLeg = {-1, -1};
            for (Map.Entry<String, Integer> e : names.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                String n = e.getKey().toLowerCase(Locale.ROOT);
                int i = e.getValue();
                int s = n.contains("_l_") ? 0 : n.contains("_r_") ? 1 : -1;
                if (s < 0) continue;
                if (n.contains("upperarm")) { upper[s].add(i); jUpper[s] = i; }
                else if (n.contains("forearm")) { fore[s].add(i); jFore[s] = i; }
                else if (n.contains("finger")) hand[s].add(i);
                else if (n.contains("hand")) { hand[s].add(i); jHand[s] = i; }
                else if (n.contains("thigh")) { leg[s].add(i); jLeg[s] = i; }
                else if (n.contains("calf") || n.contains("foot") || n.contains("toe")) leg[s].add(i);
            }
            for (int s = 0; s < 2; s++) {
                int g = s * 3;
                b.groups[g] = ints(hand[s]);
                b.groups[g + 1] = ints(fore[s], hand[s]);
                b.groups[g + 2] = ints(upper[s], fore[s], hand[s]);
                b.joint[g] = first(jHand[s], jFore[s], jUpper[s]);
                b.joint[g + 1] = first(jFore[s], jUpper[s], jHand[s]);
                b.joint[g + 2] = first(jUpper[s], jFore[s], jHand[s]);
                b.groups[6 + s] = ints(leg[s]);
                b.joint[6 + s] = jLeg[s];
            }
            for (int g = 0; g < 8; g++) {
                if (b.groups[g].length == 0) continue;
                b.any = true;
                b.bindPos[g] = b.joint[g] >= 0 && b.joint[g] < offsets.size() ? jointBindPos(offsets.get(b.joint[g])) : new float[3];
            }
            if (diag++ < 12) RBSHook.log("limbs: mesh with " + names.size() + " bones - L arm " + b.groups[2].length + " R arm " + b.groups[5].length
                    + " L leg " + b.groups[6].length + " R leg " + b.groups[7].length + (b.any ? "" : " (nothing to hide)"));
        }
        bones.put(data, b);
        return b;
    }

    private static int diag, hidden;
    private static long summaryAt;

    /** called from apply(): once a minute say how many zombies had limbs hidden so far */
    private static void summary() {
        long now = System.nanoTime();
        if (now < summaryAt) return;
        summaryAt = now + 60_000_000_000L;
        if (hidden > 0) RBSHook.log("limbs: " + hidden + " zombie(s) drawn with hidden limbs so far");
    }

    static String describe(int m) {
        StringBuilder s = new StringBuilder();
        if ((m & L_UPPER) != 0) s.append(" L-arm"); else if ((m & L_FORE) != 0) s.append(" L-forearm"); else if ((m & L_HAND) != 0) s.append(" L-hand");
        if ((m & R_UPPER) != 0) s.append(" R-arm"); else if ((m & R_FORE) != 0) s.append(" R-forearm"); else if ((m & R_HAND) != 0) s.append(" R-hand");
        if ((m & L_LEG) != 0) s.append(" L-leg");
        if ((m & R_LEG) != 0) s.append(" R-leg");
        return s.length() == 0 ? "none" : s.substring(1);
    }

    /** bind-space position of a joint = translation of the inverse of its offset (inverse bind pose) matrix */
    private static float[] jointBindPos(Object offset) throws Exception {
        if (offset == null) return new float[3];
        Object inv = matrixCtor.newInstance();
        if (mInvert.invoke(null, offset, inv) == null) return new float[3];
        float[] m = read(inv);
        return colVec(m) ? new float[]{ m[12], m[13], m[14] } : new float[]{ m[3], m[7], m[11] };
    }

    private static void collapse(Object[] t, Bones b, int g) throws Exception {
        int[] idx = b.groups[g];
        int j = b.joint[g];
        if (idx == null || idx.length == 0) return;
        float x = 0, y = 0, z = 0;
        boolean cv = true;
        if (j >= 0 && j < t.length && t[j] != null) {
            float[] m = read(t[j]);
            float[] p = b.bindPos[g];
            cv = colVec(m);
            if (cv) {
                x = m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12];
                y = m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13];
                z = m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14];
            } else {
                x = m[0] * p[0] + m[1] * p[1] + m[2] * p[2] + m[3];
                y = m[4] * p[0] + m[5] * p[1] + m[6] * p[2] + m[7];
                z = m[8] * p[0] + m[9] * p[1] + m[10] * p[2] + m[11];
            }
        }
        float[] c = new float[16];
        c[15] = 1f;
        if (cv) { c[12] = x; c[13] = y; c[14] = z; } else { c[3] = x; c[7] = y; c[11] = z; }
        FloatBuffer buf = BUF.get();
        for (int i : idx) {
            if (i < 0 || i >= t.length || t[i] == null) continue;
            buf.clear();
            buf.put(c);
            buf.rewind();
            mLoad.invoke(t[i], buf);
        }
    }

    /** true when the translation lives in m30..m32 (LWJGL column-vector layout) rather than m03..m23 */
    private static boolean colVec(float[] m) {
        return Math.abs(m[3]) + Math.abs(m[7]) + Math.abs(m[11]) <= Math.abs(m[12]) + Math.abs(m[13]) + Math.abs(m[14]);
    }

    private static float[] read(Object matrix) throws Exception {
        FloatBuffer buf = BUF.get();
        buf.clear();
        mStore.invoke(matrix, buf);     // m00 m01 m02 m03 m10 ... (index = column*4 + row)
        buf.rewind();
        float[] m = new float[16];
        buf.get(m);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Integer>[] lists() {
        return new List[]{ new ArrayList<Integer>(), new ArrayList<Integer>() };
    }

    @SafeVarargs
    private static int[] ints(List<Integer>... parts) {
        int n = 0;
        for (List<Integer> p : parts) n += p.size();
        int[] out = new int[n];
        int k = 0;
        for (List<Integer> p : parts) for (int i : p) out[k++] = i;
        return out;
    }

    private static int first(int... v) {
        for (int x : v) if (x >= 0) return x;
        return -1;
    }

    // ---------------- setup ----------------

    private static synchronized void init() {
        if (ready || failed) return;
        try {
            if (!RBSHook.ensureInit()) throw new IllegalStateException("RBSHook not ready");
            ClassLoader cl = Limbs.class.getClassLoader();
            Class<?> ap = Class.forName("zombie.core.skinnedmodel.animation.AnimationPlayer", false, cl);
            Class<?> sd = Class.forName("zombie.core.skinnedmodel.model.SkinningData", false, cl);
            Class<?> m4 = Class.forName("org.lwjgl.util.vector.Matrix4f", false, cl);
            apCharacter = ap.getMethod("getIsoGameCharacter");
            fBoneOffset = sd.getField("boneOffset");
            fBoneIndices = sd.getField("boneIndices");
            mStore = m4.getMethod("store", FloatBuffer.class);
            mLoad = m4.getMethod("load", FloatBuffer.class);
            mInvert = m4.getMethod("invert", m4, m4);
            matrixCtor = m4.getConstructor();
            ready = true;
            RBSHook.log("limbs ready - hand/forearm/arm/legs/one-leg = " + pHand + "/" + pFore + "/" + pArm + "/" + pLegs + "/" + pLeg1 + " %");
        } catch (Throwable t) {
            failed = true;
            RBSHook.warn("limbs init failed, zombies keep all limbs: " + t);
        }
    }

    private static void loadConfig() {
        long now = System.nanoTime();
        if (now < cfgNextCheck) return;
        cfgNextCheck = now + 5_000_000_000L;
        try {
            Path p = Paths.get(System.getProperty("user.home"), "Zomboid", "Lua", CONFIG);
            if (!Files.exists(p)) return;
            long mt = Files.getLastModifiedTime(p).toMillis();
            if (mt == cfgMtime) return;
            cfgMtime = mt;
            boolean en = enabled;
            double hand = pHand, fore = pFore, arm = pArm, legs = pLegs, leg1 = pLeg1;
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                try {
                    switch (k) {
                        case "enabled": en = !(v.equals("0") || v.equalsIgnoreCase("false")); break;
                        case "hand": hand = Double.parseDouble(v); break;
                        case "forearm": fore = Double.parseDouble(v); break;
                        case "arm": arm = Double.parseDouble(v); break;
                        case "legs": legs = Double.parseDouble(v); break;
                        case "leg1": leg1 = Double.parseDouble(v); break;
                        default: break;
                    }
                } catch (NumberFormatException ignored) {}
            }
            pHand = hand; pFore = fore; pArm = arm; pLegs = legs; pLeg1 = leg1;
            enabled = en;
            byChar.clear();
            RBSHook.log("limbs config: enabled=" + en + " hand/forearm/arm/legs/one-leg=" + hand + "/" + fore + "/" + arm + "/" + legs + "/" + leg1 + " %");
        } catch (Exception e) {
            RBSHook.warn("limbs config: " + e);
        }
    }
}
