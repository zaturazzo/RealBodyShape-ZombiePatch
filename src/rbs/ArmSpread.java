package rbs;

import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Arms hanging at the sides of a heavier body swing out from it instead of sinking into its waist and hips (user
 * 2026-11-24: "การวางแขนข้างลำตัวให้ไม่จมได้ไหม").
 *
 * The game's animations are made for its 68 kg body. Here, after the pose (and the zombie height), each upper arm -
 * with the forearm, the hand and the fingers under it - is turned out about its shoulder, in the body's frontal plane,
 * by the angle the body grew under a hanging arm (ArmSpreadTable, water/arm_spread.py: the trunk's side out plus the
 * arm's own thickening, from the elbow down to the wrist, less the idle's own gap). Only while the arm hangs: in full
 * when the upper arm points within FULL of straight down, nothing beyond NONE (raised, aiming, swinging, carrying in
 * front are left as they are). Who: the local player (its shape from PlayerFit: the amount and the keys that widen
 * the trunk or thicken the arm) and zombies by their body (Fat / Obese). Model-space matrices, like GripIK.
 */
final class ArmSpread {

    /** the upper arm this close to straight down: the whole angle; this far: none (degrees) */
    static final float FULL = 30f, NONE = 70f;
    static final float MAX_DEG = 45f;
    /** a zombie's body (RBSHook.NAMES: Thin, Normal, Fat, Obese) as an amount (52 / 68 / 95 / 125 kg) */
    static final float[] VARIANT_AMOUNT = { -0.0164f, 0f, 0.022f, 0.0517f };

    private static volatile boolean failed, ready;
    private static Method apCharacter, apSkinning, apCount, apModelAt, sdNumBones, sdBoneAt, sdParent;
    private static Method mStore, mLoad, isLocal, plFemale;
    private static java.lang.reflect.Field fBoneName;
    private static Class<?> playerClass, zombieClass;
    private static final Map<Object, Skel> skels = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<FloatBuffer> BUF = ThreadLocal.withInitial(() -> FloatBuffer.allocate(16));
    private static final ThreadLocal<float[]> M = ThreadLocal.withInitial(() -> new float[16]);
    private static int diag, warnings;
    private static long turns, summaryAt;
    private static float largest;

    private ArmSpread() {}

    static final class Skel {
        int n;
        int[] upper = { -1, -1 }, fore = { -1, -1 };
        int[][] tree = new int[2][];
        boolean ok;
    }

    /** after the pose (both paths: a fresh pose each time) */
    static void apply(Object player) {
        if (failed || player == null) return;
        try {
            if (!ready) init();
            if (failed) return;
            Object chr = apCharacter.invoke(player);
            if (chr == null) return;
            float amount;
            float[] keys = null;
            boolean female, isPl = playerClass.isInstance(chr);
            if (isPl) {
                if (!(Boolean) isLocal.invoke(chr)) return;
                amount = PlayerFit.amountNow();
                keys = PlayerFit.values(ArmSpreadTable.KEYS);
                // (RBSHook.visualOf is the zombie's getHumanVisual: on the player it threw, and no player's arm turned)
                female = (Boolean) plFemale.invoke(chr);
            } else if (zombieClass.isInstance(chr)) {
                Object v = RBSHook.visualOf(chr);
                if (v == null) return;
                int var = RBSHook.variantOf(v);
                if (var < 0 || var >= VARIANT_AMOUNT.length) return;
                amount = VARIANT_AMOUNT[var];
                female = RBSHook.isFemaleVisual(v);
            } else {
                return;
            }
            int body = PlayerFit.tombNow() ? 1 : 0, sex = female ? 0 : 1;
            float clear = isPl ? PlayerFit.armRoomNow() : ZOMBIE_CLEAR;
            if (clear <= 0f) clear = DEFAULT_CLEAR;
            float[] degs = { degrees(body, sex, 0, amount, clear, keys), degrees(body, sex, 1, amount, clear, keys) };
            if (degs[0] < 0.3f && degs[1] < 0.3f) return;
            Object data = apSkinning.invoke(player);
            if (data == null) return;
            Skel k = skelFor(data);
            if (!k.ok || (Integer) apCount.invoke(player) != k.n) return;
            float[] m = M.get();
            for (int side = 0; side < 2; side++) {
                float deg = degs[side];
                if (deg < 0.3f) continue;
                float[] p = pos(player, k.upper[side], m), e = pos(player, k.fore[side], m);
                float dx = e[0] - p[0], dy = e[1] - p[1], dz = e[2] - p[2];
                float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 1e-5f) continue;
                float fromDown = (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, -dy / len))));
                float f = fromDown <= FULL ? 1f : fromDown >= NONE ? 0f : 1f - (fromDown - FULL) / (NONE - FULL);
                f = f * f * (3f - 2f * f);
                if (f <= 0f) continue;
                // out = away from the body's middle: the shoulder's side (the game's .x: the character's left at +x)
                float a = (float) Math.toRadians(deg * f) * (p[0] >= 0f ? 1f : -1f);
                float c = (float) Math.cos(a), s = (float) Math.sin(a);
                float[] q = { c, -s, 0f, s, c, 0f, 0f, 0f, 1f };          // about the body's front-back axis (z)
                rotate(player, k.tree[side], q, p, m);
                turns++;
                if (deg * f > largest) largest = deg * f;
            }
            long now = System.nanoTime();
            if (turns > 0 && now > summaryAt) {
                summaryAt = now + 600_000_000_000L;
                RBSHook.log(String.format(Locale.ROOT, "arm spread: %d arm pose(s) turned out so far, largest %.1f deg", turns, largest));
            }
        } catch (Throwable t) {
            if (warnings++ < 5) RBSHook.warn("arm spread skipped a pose: " + t);
        }
    }

    /**
     * The clothes under a hanging arm (user 2026-11-24, 130 kg: "คำนวนจากความหนาชุดที่ใส่ก็ได้ +เผื่อไปนิดนึง"): the mod
     * writes armroom= (cm, RealBodyShapeStyle.lua armRoom: how far the clothes at the trunk's side reach off the skin,
     * their room in clothes and a margin) - the gap the forearm and the hand must keep off the skin. Zombies: ZOMBIE_CLEAR;
     * an older mod without the line: DEFAULT_CLEAR.
     */
    static final float ZOMBIE_CLEAR = 1.5f, DEFAULT_CLEAR = 1.5f;

    /**
     * One arm's angle (user 2026-11-24: the first table, a guessed 2 cm gap in the idle and the trunk's widest growth
     * for both arms, sent them up far too high once clothes were added): ArmSpreadTable from the game's own idle
     * (water/arm_idle.py: the body built for each weight, posed over the idle loop, the arm turned out until the forearm
     * and the hand keep that gap off the trunk), per body (the game's / Tomb's), sex and arm, by the amount (none at 0
     * and below, straight to the first knot) and the gap; plus the keys that widen the trunk or thicken the arm.
     */
    static float degrees(int body, int sex, int arm, float amount, float clear, float[] keys) {
        float[] am = ArmSpreadTable.AMOUNTS;
        float[][] dg = ArmSpreadTable.DEG[body][sex][arm];
        float d = 0f;
        if (amount > 0f && am.length > 0) {
            if (amount <= am[0]) d = byClear(dg[0], clear) * amount / am[0];
            else if (amount >= am[am.length - 1]) d = byClear(dg[am.length - 1], clear);
            else {
                for (int i = 1; i < am.length; i++) {
                    if (amount <= am[i]) {
                        float a0 = byClear(dg[i - 1], clear), a1 = byClear(dg[i], clear);
                        d = a0 + (a1 - a0) * (amount - am[i - 1]) / (am[i] - am[i - 1]);
                        break;
                    }
                }
            }
        }
        if (keys != null && d > 0f) {
            float[] per = ArmSpreadTable.DEG_PER_UNIT[sex];
            for (int i = 0; i < keys.length && i < per.length; i++) if (keys[i] > 0f) d += keys[i] * per[i];
        }
        return Math.min(d, MAX_DEG);
    }

    private static float byClear(float[] row, float c) {
        float[] cl = ArmSpreadTable.CLEAR;
        if (c <= cl[0]) return row[0];
        for (int i = 1; i < cl.length; i++) {
            if (c <= cl[i]) return row[i - 1] + (row[i] - row[i - 1]) * (c - cl[i - 1]) / (cl[i] - cl[i - 1]);
        }
        return row[cl.length - 1];
    }

    // ---------------- bones ----------------

    private static void rotate(Object player, int[] tree, float[] q, float[] p, float[] m) throws Exception {
        for (int i : tree) {
            Object mt = apModelAt.invoke(player, i);
            if (mt == null) continue;
            read(mt, m);
            boolean cv = colVec(m);
            float[] r = new float[9], t = new float[3];
            for (int a = 0; a < 3; a++) {
                for (int b = 0; b < 3; b++) r[a * 3 + b] = cv ? m[b * 4 + a] : m[a * 4 + b];
                t[a] = cv ? m[12 + a] : m[a * 4 + 3];
            }
            float[] r2 = new float[9], t2 = new float[3];
            for (int a = 0; a < 3; a++) {
                for (int b = 0; b < 3; b++) {
                    float v = 0f;
                    for (int c = 0; c < 3; c++) v += q[a * 3 + c] * r[c * 3 + b];
                    r2[a * 3 + b] = v;
                }
                float v = 0f;
                for (int c = 0; c < 3; c++) v += q[a * 3 + c] * (t[c] - p[c]);
                t2[a] = v + p[a];
            }
            for (int a = 0; a < 3; a++) {
                for (int b = 0; b < 3; b++) {
                    if (cv) m[b * 4 + a] = r2[a * 3 + b];
                    else m[a * 4 + b] = r2[a * 3 + b];
                }
                if (cv) m[12 + a] = t2[a];
                else m[a * 4 + 3] = t2[a];
            }
            write(mt, m);
        }
    }

    private static float[] pos(Object player, int i, float[] m) throws Exception {
        read(apModelAt.invoke(player, i), m);
        return colVec(m) ? new float[]{ m[12], m[13], m[14] } : new float[]{ m[3], m[7], m[11] };
    }

    private static Skel skelFor(Object data) throws Exception {
        Skel k = skels.get(data);
        if (k != null) return k;
        k = new Skel();
        int n = (Integer) sdNumBones.invoke(data);
        k.n = n;
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            Object b = sdBoneAt.invoke(data, i);
            Object nm = b == null ? null : fBoneName.get(b);
            String x = nm == null ? "" : nm.toString().toLowerCase(Locale.ROOT);
            parent[i] = (Integer) sdParent.invoke(data, i);
            if (x.endsWith("_l_upperarm")) k.upper[0] = i;
            else if (x.endsWith("_r_upperarm")) k.upper[1] = i;
            else if (x.endsWith("_l_forearm")) k.fore[0] = i;
            else if (x.endsWith("_r_forearm")) k.fore[1] = i;
        }
        k.ok = true;
        for (int s = 0; s < 2; s++) {
            if (k.upper[s] < 0 || k.fore[s] < 0) { k.ok = false; continue; }
            k.tree[s] = tree(parent, k.upper[s]);
            if (k.tree[s].length < 2) k.ok = false;
        }
        if (diag++ < 4) RBSHook.log("arm spread: skeleton with " + n + " bones" + (k.ok ? ", both arms found" : " - skipped (no arms)"));
        skels.put(data, k);
        return k;
    }

    private static int[] tree(int[] parent, int root) {
        List<Integer> out = new ArrayList<>();
        out.add(root);
        for (int i = 0; i < out.size(); i++) {
            int b = out.get(i);
            for (int j = 0; j < parent.length; j++) if (parent[j] == b && !out.contains(j)) out.add(j);
        }
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

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

    private static synchronized void init() {
        if (ready || failed) return;
        try {
            if (!RBSHook.ensureInit()) throw new IllegalStateException("RBSHook not ready");
            ClassLoader cl = ArmSpread.class.getClassLoader();
            Class<?> ap = Class.forName("zombie.core.skinnedmodel.animation.AnimationPlayer", false, cl);
            Class<?> sd = Class.forName("zombie.core.skinnedmodel.model.SkinningData", false, cl);
            Class<?> sb = Class.forName("zombie.core.skinnedmodel.model.SkinningBone", false, cl);
            Class<?> m4 = Class.forName("org.lwjgl.util.vector.Matrix4f", false, cl);
            playerClass = Class.forName("zombie.characters.IsoPlayer", false, cl);
            zombieClass = Class.forName("zombie.characters.IsoZombie", false, cl);
            apCharacter = ap.getMethod("getIsoGameCharacter");
            apSkinning = ap.getMethod("getSkinningData");
            apCount = ap.getMethod("getModelTransformsCount");
            apModelAt = ap.getMethod("getModelTransformAt", int.class);
            sdNumBones = sd.getMethod("numBones");
            sdBoneAt = sd.getMethod("getBoneAt", int.class);
            sdParent = sd.getMethod("getParentBoneIdx", int.class);
            fBoneName = sb.getField("name");
            mStore = m4.getMethod("store", FloatBuffer.class);
            mLoad = m4.getMethod("load", FloatBuffer.class);
            isLocal = playerClass.getMethod("isLocalPlayer");
            plFemale = playerClass.getMethod("isFemale");
            ready = true;
            RBSHook.log("arm spread ready - arms hanging at the sides of a heavier body (the player, fat and obese zombies) swing out from it");
        } catch (Throwable t) {
            failed = true;
            RBSHook.warn("arm spread init failed, the arms are left alone: " + t);
        }
    }
}
