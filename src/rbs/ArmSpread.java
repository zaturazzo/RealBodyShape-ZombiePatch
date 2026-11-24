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
 * with the forearm, the hand and the fingers under it, and the prop bone of what that hand holds - is turned out about
 * its shoulder, in the body's frontal plane, by what this pose needs (needed: the arm's points against the trunk's
 * outline at that weight, ArmSpreadProfile / water/arm_profile.py). Only while the arm hangs: in full when the upper
 * arm points within FULL of straight down, nothing beyond NONE (raised, aiming, swinging, carrying in front are left
 * as they are); never with both hands together. Who: the local player (its amount from PlayerFit, the clothes' gap
 * from armroom=) and zombies by their body (Fat / Obese). Model-space matrices, like GripIK.
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

    /** hands closer than this x the upper arm: held together; a prop this close to a hand: in it */
    static final float HANDS_JOINED = 0.8f, PROP_NEAR = 0.9f;

    private static float dist(float[] a, float[] b) {
        float dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static final class Skel {
        int n;
        int[] upper = { -1, -1 }, fore = { -1, -1 }, hand = { -1, -1 }, prop = { -1, -1 }, finger1 = { -1, -1 };
        int pelvis = -1, spine = -1;
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
            boolean female, isPl = playerClass.isInstance(chr);
            if (isPl) {
                if (!(Boolean) isLocal.invoke(chr)) return;
                amount = PlayerFit.amountNow();
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
            if (amount <= 0f) return;                          // a slim body's arms hang as the game made them
            Object data = apSkinning.invoke(player);
            if (data == null) return;
            Skel k = skelFor(data);
            if (!k.ok || (Integer) apCount.invoke(player) != k.n) return;
            float[] m = M.get();
            float arm = dist(pos(player, k.upper[0], m), pos(player, k.fore[0], m));
            if (arm < 1e-5f) return;
            float[][] hands = { pos(player, k.hand[0], m), pos(player, k.hand[1], m) };
            // both hands on one thing (a two-handed weapon or a box held low): neither arm turns, the grip stays whole
            if (dist(hands[0], hands[1]) < HANDS_JOINED * arm) return;
            // what a hand holds hangs on a prop bone (Bip01_Prop1 / Prop2), a child of the root, not of the hand: it
            // turns with the arm whose hand it is at, the nearer one (user 2026-11-24: "กางแล้วมือหลุดจาก item"; the
            // right hand's Prop1 sits 8-17 cm from its hand bone in the walks with a weapon)
            int[] propSide = new int[k.prop.length];
            for (int i = 0; i < propSide.length; i++) {
                propSide[i] = -1;
                if (k.prop[i] < 0) continue;
                float[] pp = pos(player, k.prop[i], m);
                float d0 = dist(pp, hands[0]), d1 = dist(pp, hands[1]);
                int s = d0 <= d1 ? 0 : 1;
                if (Math.min(d0, d1) < PROP_NEAR * arm) propSide[i] = s;
            }
            // the trunk's bones as posed: the arm's points are looked at in the rest pose through them
            float[] rP = new float[9], tP = new float[3], rS = new float[9], tS = new float[3];
            rt(player, k.pelvis, m, rP, tP);
            rt(player, k.spine, m, rS, tS);
            for (int side = 0; side < 2; side++) {
                float[] p = pos(player, k.upper[side], m), e = pos(player, k.fore[side], m);
                float dx = e[0] - p[0], dy = e[1] - p[1], dz = e[2] - p[2];
                float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 1e-5f) continue;
                float fromDown = (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, -dy / len))));
                float f = fromDown <= FULL ? 1f : fromDown >= NONE ? 0f : 1f - (fromDown - FULL) / (NONE - FULL);
                f = f * f * (3f - 2f * f);
                if (f <= 0f) continue;
                float deg = needed(body, sex, side, amount, clear, p, e, hands[side],
                        k.finger1[side] >= 0 ? pos(player, k.finger1[side], m) : null, rP, tP, rS, tS);
                if (deg < 0.3f) continue;
                // out = away from the body's middle: the shoulder's side (the game's .x: the character's left at +x)
                float a = (float) Math.toRadians(deg * f) * (p[0] >= 0f ? 1f : -1f);
                float c = (float) Math.cos(a), s = (float) Math.sin(a);
                float[] q = { c, -s, 0f, s, c, 0f, 0f, 0f, 1f };          // about the body's front-back axis (z)
                rotate(player, k.tree[side], q, p, m);
                for (int i = 0; i < propSide.length; i++) {
                    if (propSide[i] == side) rotate(player, new int[] { k.prop[i] }, q, p, m);
                }
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
     * One arm's angle by the pose (user 2026-11-24: one angle per weight, measured in Kate_Idle, sent the arms far out
     * holding a bag, a flashlight, an umbrella, walking - "กางตามท่าจริง"): the elbow, mid forearm, wrist and mid hand,
     * each brought into the rest pose through the trunk bone beside it (the Pelvis, or the Spine above the Spine's rest
     * origin), against the trunk's outline as the body stands at that weight (ArmSpreadProfile, water/arm_profile.py):
     * how far the outline reaches out there, plus the arm's thickness there, EXTRA and the clothes' gap, less how far
     * out the point is - the angle about the shoulder that moves the point out by that much. The largest of the four;
     * 0 when the arm is clear (held out, in front, behind). Checked against the true skinned arm in 26 of the game's
     * poses (arm_profile.py --check).
     */
    static float needed(int body, int sex, int side, float amount, float clear, float[] p, float[] e, float[] h,
                        float[] f1, float[] rP, float[] tP, float[] rS, float[] tS) {
        float[] am = ArmSpreadProfile.AMOUNTS;
        int i1;
        float w;
        if (amount >= am[am.length - 1]) {
            i1 = am.length - 1;
            w = 1f;
        } else {
            i1 = 1;
            while (i1 < am.length - 1 && amount > am[i1]) i1++;
            w = (amount - am[i1 - 1]) / (am[i1] - am[i1 - 1]);
        }
        int i0 = Math.max(0, i1 - 1);
        float[][][][] o = ArmSpreadProfile.outline(body, sex);
        float[] r0 = ArmSpreadProfile.RADIUS[body][sex][i0][side], r1 = ArmSpreadProfile.RADIUS[body][sex][i1][side];
        float cm = ArmSpreadProfile.CM[body][sex], y0 = ArmSpreadProfile.Y0[body][sex], sx = side == 0 ? 1f : -1f;
        int nb = ArmSpreadProfile.NB, zs = ArmSpreadProfile.ZS;
        float[][] pts = { e, mid(e, h), h, f1 != null ? mid(h, f1) : h };
        float best = 0f;
        for (int j = 0; j < 4; j++) {
            float[] q = pts[j];
            float r = r0[j] + (r1[j] - r0[j]) * w;
            float[] c = toRest(q, sex, rP, tP, rS, tS);
            int kb = (int) Math.floor((c[1] - y0) * cm / ArmSpreadProfile.BIN);
            if (kb < 0 || kb >= nb) continue;
            float reach = 0f;
            for (int b = kb - 1; b <= kb + 1; b++) {
                if (b < 0 || b >= nb) continue;
                float[] a0 = o[i0][side][b], a1 = o[i1][side][b];
                float z0 = a0[0] + (a1[0] - a0[0]) * w, z1 = a0[1] + (a1[1] - a0[1]) * w;
                if (!(z1 > z0) || c[2] < z0 - r / cm || c[2] > z1 + r / cm) continue;
                float zz = Math.max(0f, Math.min(zs - 1f, (c[2] - z0) / (z1 - z0) * (zs - 1)));
                int a = Math.min((int) zz, zs - 2);
                float t = zz - a;
                float v0 = a0[2 + a] + (a1[2 + a] - a0[2 + a]) * w, v1 = a0[3 + a] + (a1[3 + a] - a0[3 + a]) * w;
                reach = Math.max(reach, v0 + (v1 - v0) * t);
            }
            if (reach <= 0f) continue;
            float push = reach + (r + ArmSpreadProfile.EXTRA[body][sex] + clear) / cm - sx * c[0];
            if (push <= 0f) continue;
            float dx = q[0] - p[0], dy = q[1] - p[1];
            if (gain(dx, dy, sx, MAX_DEG) < push) {
                best = MAX_DEG;
                continue;
            }
            float lo = 0f, hi = MAX_DEG;
            for (int it = 0; it < 14; it++) {
                float md = (lo + hi) * 0.5f;
                if (gain(dx, dy, sx, md) < push) lo = md;
                else hi = md;
            }
            best = Math.max(best, hi);
        }
        return Math.min(best, MAX_DEG);
    }

    /** how far out (model x, away from the middle) a point dx, dy from the shoulder goes turning the arm deg out */
    private static float gain(float dx, float dy, float sx, float deg) {
        double a = Math.toRadians(deg) * sx;
        return (float) (sx * ((dx * Math.cos(a) - dy * Math.sin(a)) - dx));
    }

    private static float[] mid(float[] a, float[] b) {
        return new float[] { (a[0] + b[0]) * 0.5f, (a[1] + b[1]) * 0.5f, (a[2] + b[2]) * 0.5f };
    }

    /** a posed point into the rest pose: through the Pelvis, or the Spine when that lands above the Spine's rest origin */
    private static float[] toRest(float[] q, int sex, float[] rP, float[] tP, float[] rS, float[] tS) {
        float[] c = map(q, rP, tP, ArmSpreadProfile.PELVIS_R[sex], ArmSpreadProfile.PELVIS_O[sex]);
        if (c[1] > ArmSpreadProfile.SPINE_O[sex][1]) c = map(q, rS, tS, ArmSpreadProfile.SPINE_R[sex], ArmSpreadProfile.SPINE_O[sex]);
        return c;
    }

    /** r, t: the bone as posed (column form, v' = r v + t); R, o: as at rest (row form c = l R + o) */
    private static float[] map(float[] q, float[] r, float[] t, float[] R, float[] o) {
        float d0 = q[0] - t[0], d1 = q[1] - t[1], d2 = q[2] - t[2];
        float[] l = new float[3], c = new float[3];
        for (int i = 0; i < 3; i++) l[i] = r[i] * d0 + r[3 + i] * d1 + r[6 + i] * d2;
        for (int j = 0; j < 3; j++) c[j] = l[0] * R[j] + l[1] * R[3 + j] + l[2] * R[6 + j] + o[j];
        return c;
    }

    /** a bone's posed rotation (column form, row-major 3x3) and position */
    private static void rt(Object player, int i, float[] m, float[] r, float[] t) throws Exception {
        read(apModelAt.invoke(player, i), m);
        boolean cv = colVec(m);
        for (int a = 0; a < 3; a++) {
            for (int b = 0; b < 3; b++) r[a * 3 + b] = cv ? m[b * 4 + a] : m[a * 4 + b];
            t[a] = cv ? m[12 + a] : m[a * 4 + 3];
        }
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
            else if (x.endsWith("_l_hand")) k.hand[0] = i;
            else if (x.endsWith("_r_hand")) k.hand[1] = i;
            else if (x.endsWith("_prop1")) k.prop[0] = i;
            else if (x.endsWith("_prop2")) k.prop[1] = i;
            else if (x.endsWith("_l_finger1")) k.finger1[0] = i;
            else if (x.endsWith("_r_finger1")) k.finger1[1] = i;
            else if (x.endsWith("_pelvis")) k.pelvis = i;
            else if (x.endsWith("_spine")) k.spine = i;
        }
        k.ok = k.pelvis >= 0 && k.spine >= 0 && k.hand[0] >= 0 && k.hand[1] >= 0;
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
