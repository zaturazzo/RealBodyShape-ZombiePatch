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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Zombie heights (part of "Real Body Shape - Zombie Bodies"): every zombie is a little taller or shorter.
 * Not a scale of the whole model - that makes the head, hands and feet grow with it, so tall zombies look like
 * giants and short ones like miniatures. Here the legs, the trunk, the neck and the arms get longer or shorter,
 * the head, hands and feet keep their size, and the whole body moves up or down so the feet stay on the ground.
 *
 * Done on the model-space bone matrices right after AnimationPlayer.UpdateSkinTransforms(): both animation paths
 * (a zombie's own tracks, and the shared tracks of crowds) call it once the pose is fresh, and the skin matrices
 * are only worked out later from these matrices. So the body and every clothing piece, held items (prop bones),
 * corpses in DeadBodyAtlas and the game's own hit points all follow. Only joint positions change, never a rotation:
 *     p'(bone) = p'(parent) + k * (p(bone) - p(parent))
 * The animation and the ragdoll read their own local bone transforms, so nothing builds up from frame to frame.
 * transformRootChildBones (a turn-around animation event) rebuilds these matrices too, so it is hooked as well.
 *
 * A zombie's height is a stable hash of its looks (the fields saved with a corpse, like the body variant), so the
 * corpse keeps it and every machine in multiplayer agrees without syncing anything.
 * Config: ~/Zomboid/Lua/RealBodyShape_height.txt (enabled=1, spread=<percent at the extremes>).
 */
final class Height {
    static final String CONFIG = "RealBodyShape_height.txt";

    private static volatile boolean enabled = true;
    /** k = 1 +/- spread at the extremes, bell-shaped (sum of three uniforms): standard deviation = spread / 3 */
    private static volatile double spread = 0.15;
    private static long cfgMtime = -1, cfgNextCheck;
    private static volatile boolean failed, ready;

    private static Method apCharacter, apSkinning, apCount, apModelAt;
    private static Method sdNumBones, sdBoneAt, sdParent;
    private static Method mStore, mLoad, mInvert;
    private static Field fBoneName, fBoneOffset;
    private static Constructor<?> matrixCtor;

    /** character -> {k bits, valid-until nanos} */
    private static final Map<Object, long[]> byChar = Collections.synchronizedMap(new WeakHashMap<>());
    /** SkinningData -> what the skeleton needs */
    private static final Map<Object, Skel> skels = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<FloatBuffer> BUF = ThreadLocal.withInitial(() -> FloatBuffer.allocate(16));
    private static final ThreadLocal<Work> WORK = ThreadLocal.withInitial(Work::new);

    private static int diag;
    private static long drawn, summaryAt;
    private static double minK = 9, maxK = 0;

    private Height() {}

    static final class Skel {
        int n;
        int[] parent;
        /** the segment parent -> bone gets longer / shorter (false: head, hands, feet, root, props, skirt bones) */
        boolean[] scaled;
        int head = -1, lFoot = -1, rFoot = -1, lHand = -1, rHand = -1;
        int[] props = new int[0];
        /** model-space up (from the bind pose: feet -> head) */
        float[] up;
        boolean ok;
        String why = "";
    }

    static final class Work {
        Object[] mats = new Object[0];
        float[][] m = new float[0][];
        float[] p = new float[0], q = new float[0];

        void size(int n) {
            if (mats.length >= n) return;
            mats = new Object[n];
            m = new float[n][16];
            p = new float[n * 3];
            q = new float[n * 3];
        }
    }

    // ---------------- hook (called from RBSHook after UpdateSkinTransforms / transformRootChildBones) ----------------

    static void apply(Object player) throws Exception {
        if (failed || player == null) return;
        loadConfig();
        if (!enabled || spread <= 0) return;
        if (!ready) init();
        if (failed) return;
        if (!RBSHook.zombiesEnabled()) return;
        double k = factorFor(player);
        if (Math.abs(k - 1) < 0.002) return;
        Object data = apSkinning.invoke(player);
        if (data == null) return;
        Skel s = skelFor(data);
        if (!s.ok) return;
        int n = (Integer) apCount.invoke(player);
        if (n != s.n) return;
        stretch(player, s, n, (float) k);
        drawn++;
        if (k < minK) minK = k;
        if (k > maxK) maxK = k;
        summary();
    }

    private static void stretch(Object player, Skel s, int n, float k) throws Exception {
        Work w = WORK.get();
        w.size(n);
        for (int i = 0; i < n; i++) {
            Object mt = apModelAt.invoke(player, i);
            if (mt == null) return;
            w.mats[i] = mt;
            read(mt, w.m[i]);
        }
        boolean cv = colVec(w.m[s.head]);
        int tx = cv ? 12 : 3, ty = cv ? 13 : 7, tz = cv ? 14 : 11;
        float[] p = w.p, q = w.q;
        for (int i = 0; i < n; i++) {
            float[] m = w.m[i];
            p[i * 3] = m[tx];
            p[i * 3 + 1] = m[ty];
            p[i * 3 + 2] = m[tz];
        }
        // joints, parents first (the game's own loop relies on that order too)
        for (int i = 0; i < n; i++) {
            int a = s.parent[i];
            if (a < 0) {
                q[i * 3] = p[i * 3];
                q[i * 3 + 1] = p[i * 3 + 1];
                q[i * 3 + 2] = p[i * 3 + 2];
                continue;
            }
            float f = s.scaled[i] ? k : 1f;
            for (int c = 0; c < 3; c++) q[i * 3 + c] = q[a * 3 + c] + f * (p[i * 3 + c] - p[a * 3 + c]);
        }
        // prop bones hang from the root, not the hand: move each with the hand it is closest to
        for (int j : s.props) {
            int h = nearest(p, j, s.lHand, s.rHand);
            if (h < 0) continue;
            for (int c = 0; c < 3; c++) q[j * 3 + c] = p[j * 3 + c] + (q[h * 3 + c] - p[h * 3 + c]);
        }
        // keep the lower foot where it was (up or down along the model's up axis)
        float[] up = s.up;
        float lowBefore = Math.min(dot(p, s.lFoot, up), dot(p, s.rFoot, up));
        float lowAfter = Math.min(dot(q, s.lFoot, up), dot(q, s.rFoot, up));
        float d = lowBefore - lowAfter;
        for (int i = 0; i < n; i++) {
            float[] m = w.m[i];
            float x = q[i * 3] + up[0] * d, y = q[i * 3 + 1] + up[1] * d, z = q[i * 3 + 2] + up[2] * d;
            if (Math.abs(x - m[tx]) + Math.abs(y - m[ty]) + Math.abs(z - m[tz]) < 1e-7f) continue;
            m[tx] = x;
            m[ty] = y;
            m[tz] = z;
            write(w.mats[i], m);
        }
    }

    private static int nearest(float[] p, int j, int a, int b) {
        if (a < 0) return b;
        if (b < 0) return a;
        return dist2(p, j, a) <= dist2(p, j, b) ? a : b;
    }

    private static float dist2(float[] p, int i, int j) {
        float x = p[i * 3] - p[j * 3], y = p[i * 3 + 1] - p[j * 3 + 1], z = p[i * 3 + 2] - p[j * 3 + 2];
        return x * x + y * y + z * z;
    }

    private static float dot(float[] p, int i, float[] u) {
        return p[i * 3] * u[0] + p[i * 3 + 1] * u[1] + p[i * 3 + 2] * u[2];
    }

    // ---------------- who gets which height ----------------

    static double factorFor(Object player) throws Exception {
        Object chr = apCharacter.invoke(player);
        if (chr != null) {
            long now = System.nanoTime();
            long[] e = byChar.get(chr);
            if (e != null && now < e[1]) return Double.longBitsToDouble(e[0]);
            double k = 1;
            if (RBSHook.isLiveZombie(chr)) {
                Object visual = RBSHook.visualOf(chr);
                if (visual != null) {
                    k = factorOf(RBSHook.visualHash(visual));
                    if (e == null && diag++ < 10) RBSHook.log(String.format(Locale.ROOT, "height: zombie x%.3f", k));
                }
            }
            byChar.put(chr, new long[]{ Double.doubleToLongBits(k), now + 2_000_000_000L });
            return k;
        }
        Object visual = Limbs.animatedVisual(player);       // corpse in DeadBodyAtlas
        if (visual == null || !RBSHook.isZombieVisual(visual)) return 1;
        return factorOf(RBSHook.visualHash(visual));
    }

    /** bell-shaped 1 +/- spread from the visual hash (mixed apart from the body variant's own use of it) */
    static double factorOf(int hash) {
        long x = (hash & 0xFFFFFFFFL) * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        x ^= x >>> 31;
        double u = ((x & 0xFFFF) + ((x >>> 16) & 0xFFFF) + ((x >>> 32) & 0xFFFF)) / 65536.0;   // 0 .. 3
        return 1 + spread * (u / 1.5 - 1);
    }

    // ---------------- skeleton ----------------

    static Skel skelFor(Object data) throws Exception {
        Skel s = skels.get(data);
        if (s != null) return s;
        s = new Skel();
        int n = (Integer) sdNumBones.invoke(data);
        s.n = n;
        s.parent = new int[n];
        s.scaled = new boolean[n];
        String[] names = new String[n];
        for (int i = 0; i < n; i++) {
            Object b = sdBoneAt.invoke(data, i);
            Object nm = b == null ? null : fBoneName.get(b);
            names[i] = nm == null ? "" : nm.toString().toLowerCase(Locale.ROOT);
            s.parent[i] = (Integer) sdParent.invoke(data, i);
        }
        boolean order = true;
        List<Integer> props = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (s.parent[i] >= i) order = false;
            String nm = names[i];
            if (nm.endsWith("_head")) s.head = i;
            else if (nm.endsWith("_l_foot")) s.lFoot = i;
            else if (nm.endsWith("_r_foot")) s.rFoot = i;
            else if (nm.endsWith("_l_hand")) s.lHand = i;
            else if (nm.endsWith("_r_hand")) s.rHand = i;
            if (nm.contains("prop")) props.add(i);
            s.scaled[i] = s.parent[i] >= 0 && s.parent[i] < n && scales(nm, names[s.parent[i]]);
        }
        s.props = props.stream().mapToInt(Integer::intValue).toArray();
        if (!order) s.why = "bones not in parent-first order";
        else if (s.head < 0 || s.lFoot < 0 || s.rFoot < 0) s.why = "no head / feet";
        else s.up = upAxis(data, s);
        s.ok = s.why.isEmpty() && s.up != null;
        if (s.up == null && s.why.isEmpty()) s.why = "no bind pose";
        if (diag++ < 10) {
            int c = 0;
            for (boolean b : s.scaled) if (b) c++;
            RBSHook.log("height: skeleton with " + n + " bones, " + c + " segments stretch, " + s.props.length + " prop bone(s)"
                    + (s.ok ? String.format(Locale.ROOT, ", up = (%.2f, %.2f, %.2f)", s.up[0], s.up[1], s.up[2]) : " - skipped: " + s.why));
        }
        skels.put(data, s);
        return s;
    }

    /** does the segment parent -> bone get longer / shorter? not inside the head, hands or feet, not from the root */
    static boolean scales(String nm, String parent) {
        if (parent.isEmpty() || parent.equals("bip01") || parent.startsWith("dummy") || parent.contains("translation")) return false;
        if (parent.endsWith("_head") || parent.endsWith("_hand") || parent.endsWith("_foot")) return false;
        for (String x : new String[]{ "finger", "toe", "prop", "dress", "translation", "dummy", "nub" })
            if (nm.contains(x) || parent.contains(x)) return false;
        return true;
    }

    /** model-space up: from between the feet to the head in the bind pose (inverse of each bone's offset matrix) */
    @SuppressWarnings("unchecked")
    private static float[] upAxis(Object data, Skel s) throws Exception {
        List<Object> offsets = (List<Object>) fBoneOffset.get(data);
        if (offsets == null) return null;
        float[] h = bindPos(offsets, s.head), l = bindPos(offsets, s.lFoot), r = bindPos(offsets, s.rFoot);
        if (h == null || l == null || r == null) return null;
        float x = h[0] - (l[0] + r[0]) / 2, y = h[1] - (l[1] + r[1]) / 2, z = h[2] - (l[2] + r[2]) / 2;
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-6f) return null;
        return new float[]{ x / len, y / len, z / len };
    }

    private static float[] bindPos(List<Object> offsets, int i) throws Exception {
        if (i < 0 || i >= offsets.size() || offsets.get(i) == null) return null;
        Object inv = matrixCtor.newInstance();
        if (mInvert.invoke(null, offsets.get(i), inv) == null) return null;
        float[] m = new float[16];
        read(inv, m);
        return colVec(m) ? new float[]{ m[12], m[13], m[14] } : new float[]{ m[3], m[7], m[11] };
    }

    // ---------------- matrices ----------------

    /** true when the translation lives in m30..m32 (LWJGL column-vector layout) rather than m03..m23 */
    private static boolean colVec(float[] m) {
        return Math.abs(m[3]) + Math.abs(m[7]) + Math.abs(m[11]) <= Math.abs(m[12]) + Math.abs(m[13]) + Math.abs(m[14]);
    }

    private static void read(Object matrix, float[] out) throws Exception {
        FloatBuffer buf = BUF.get();
        buf.clear();
        mStore.invoke(matrix, buf);
        buf.rewind();
        buf.get(out, 0, 16);
    }

    private static void write(Object matrix, float[] m) throws Exception {
        FloatBuffer buf = BUF.get();
        buf.clear();
        buf.put(m, 0, 16);
        buf.rewind();
        mLoad.invoke(matrix, buf);
    }

    // ---------------- setup ----------------

    private static void summary() {
        long now = System.nanoTime();
        if (now < summaryAt) return;
        summaryAt = now + 60_000_000_000L;
        RBSHook.log(String.format(Locale.ROOT, "height: %d zombie pose(s) stretched so far, x%.3f .. x%.3f", drawn, minK, maxK));
    }

    private static synchronized void init() {
        if (ready || failed) return;
        try {
            if (!RBSHook.ensureInit()) throw new IllegalStateException("RBSHook not ready");
            ClassLoader cl = Height.class.getClassLoader();
            Class<?> ap = Class.forName("zombie.core.skinnedmodel.animation.AnimationPlayer", false, cl);
            Class<?> sd = Class.forName("zombie.core.skinnedmodel.model.SkinningData", false, cl);
            Class<?> sb = Class.forName("zombie.core.skinnedmodel.model.SkinningBone", false, cl);
            Class<?> m4 = Class.forName("org.lwjgl.util.vector.Matrix4f", false, cl);
            apCharacter = ap.getMethod("getIsoGameCharacter");
            apSkinning = ap.getMethod("getSkinningData");
            apCount = ap.getMethod("getModelTransformsCount");
            apModelAt = ap.getMethod("getModelTransformAt", int.class);
            sdNumBones = sd.getMethod("numBones");
            sdBoneAt = sd.getMethod("getBoneAt", int.class);
            sdParent = sd.getMethod("getParentBoneIdx", int.class);
            fBoneName = sb.getField("name");
            fBoneOffset = sd.getField("boneOffset");
            mStore = m4.getMethod("store", FloatBuffer.class);
            mLoad = m4.getMethod("load", FloatBuffer.class);
            mInvert = m4.getMethod("invert", m4, m4);
            matrixCtor = m4.getConstructor();
            ready = true;
            RBSHook.log(String.format(Locale.ROOT, "height ready - zombies x%.2f .. x%.2f (legs, trunk, neck, arms; head, hands, feet keep their size)", 1 - spread, 1 + spread));
        } catch (Throwable t) {
            failed = true;
            RBSHook.warn("height init failed, zombies keep the vanilla height: " + t);
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
            double sp = spread;
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq < 0 || line.trim().startsWith("#")) continue;
                String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                try {
                    switch (k) {
                        case "enabled": en = !(v.equals("0") || v.equalsIgnoreCase("false")); break;
                        case "spread": sp = Math.max(0, Math.min(40, Double.parseDouble(v))) / 100.0; break;
                        default: break;
                    }
                } catch (NumberFormatException ignored) {}
            }
            enabled = en;
            spread = sp;
            byChar.clear();
            minK = 9;
            maxK = 0;
            RBSHook.log(String.format(Locale.ROOT, "height config: enabled=%s spread=+/-%.1f%%", en, sp * 100));
        } catch (Exception e) {
            RBSHook.warn("height config: " + e);
        }
    }
}
