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
 * The left hand stays on a two-handed melee weapon during the swing.
 *
 * The game draws a held weapon at Bip01_Prop1, which the animation moves on its own (a child of Bip01, not of the
 * hands), and it has no IK: the hands go where the animation puts them. Two-handed weapons share the bat's swings, and
 * in those the left hand leaves the handle - measured on Bob_AttackBat01/02/03_Hit: the hands' distance changes by up
 * to 23 cm during one swing (user 2026-11-17: "ขวานสองมือ มือซ้ายหลุด" while swinging, the grip fine otherwise).
 *
 * So, while the character holds one melee weapon in both hands: in the guard stance (aiming, not swinging) - the pose
 * the swings start from and the one with both hands on the handle (Bob_IdleAimBat: the left hand still against Prop1;
 * the idle and the walk carry a bat in one hand) - where the left hand is against Prop1 (position and rotation) is
 * learnt; while the attack animation plays, the left arm is bent so the hand goes back there - a two-bone IK (upper
 * arm about the shoulder, forearm about the elbow, the elbow bending the way the animation bends it), then the hand
 * turned to the learnt rotation. It blends in and out over a few hundredths of a second. Where the grip is out of the
 * arm's reach (parts of the wide swings, where the animation lets go) the pull fades out instead of pointing a
 * straight arm at it. Offline on the game's swings: Bat01 / Bat03 back on the grip at every key (they were up to 11 /
 * 15 cm off), arm lengths kept, the elbow on its side. Only the left arm's bones move; nothing for guns.
 *
 * Model-space matrices, like HeadLook (called right after it, and again after transformRootChildBones).
 */
final class GripIK {

    /** how fast the learnt grip follows the left hand when not swinging, and the pull blends in / out (s) */
    static final float TAU_LEARN = 0.4f, TAU_IN = 0.06f, TAU_OUT = 0.10f;
    /** a learnt grip further than this from Prop1 (model units) is not a grip */
    static final float MAX_GRIP = 0.45f;

    private static volatile boolean failed, ready;
    private static Method apCharacter, apSkinning, apCount, apModelAt, sdNumBones, sdBoneAt, sdParent;
    private static Method mStore, mLoad, primaryItem, secondaryItem, isRanged, attacking, aiming;
    private static java.lang.reflect.Field fBoneName;
    private static Class<?> playerClass, weaponClass;

    private static final Map<Object, Skel> skels = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, State> states = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<FloatBuffer> BUF = ThreadLocal.withInitial(() -> FloatBuffer.allocate(16));
    private static final ThreadLocal<float[]> M = ThreadLocal.withInitial(() -> new float[16]);
    private static int diag, warnings;
    private static long pulls, summaryAt;
    private static float largestPull;

    private GripIK() {}

    static final class Skel {
        int n, upper = -1, fore = -1, hand = -1, prop = -1;
        int[] upperTree, foreTree, handTree;
        boolean ok;
    }

    static final class State {
        Object item;                  // the weapon the grip was learnt for
        float[] refR, refT;           // the left hand against Prop1: rotation (3x3, row-major) and position
        boolean has;
        float w;                      // how much the pull is on now (0..1)
        long t;
    }

    // ---------------- hook ----------------

    /** after the pose (and the head look): learn the grip, or pull the left hand back onto the weapon */
    static void update(Object player) {
        apply(player, true);
    }

    /** the turn-around event rebuilt the matrices: pull again by what it is at now (no time passes) */
    static void reapply(Object player) {
        apply(player, false);
    }

    private static void apply(Object player, boolean step) {
        if (failed || player == null) return;
        try {
            if (!ready) init();
            if (failed) return;
            Object chr = apCharacter.invoke(player);
            if (chr == null || !playerClass.isInstance(chr)) return;
            Object item = primaryItem.invoke(chr);
            boolean twoHanded = item != null && item == secondaryItem.invoke(chr) && weaponClass.isInstance(item)
                    && !(Boolean) isRanged.invoke(item);
            State s = states.get(player);
            if (!twoHanded) {
                if (s != null) states.remove(player);
                return;
            }
            Object data = apSkinning.invoke(player);
            if (data == null) return;
            Skel k = skelFor(data);
            if (!k.ok || (Integer) apCount.invoke(player) != k.n) return;
            if (s == null || s.item != item) {
                s = new State();
                s.item = item;
                s.t = System.nanoTime();
                states.put(player, s);
            }
            float[] m = M.get();
            float[][] prop = rt(player, k.prop, m), hand = rt(player, k.hand, m);
            float[] pR = prop[0], pT = prop[1], hR = hand[0], hT = hand[1];
            boolean swing = (Boolean) attacking.invoke(chr);
            if (step) {
                long now = System.nanoTime();
                float dt = Math.min(Math.max((now - s.t) / 1e9f, 0f), 0.1f);
                s.t = now;
                if (swing && s.has) s.w += (1f - s.w) * (1f - (float) Math.exp(-dt / TAU_IN));
                else s.w -= s.w * (1f - (float) Math.exp(-dt / TAU_OUT));
                if (s.w < 1e-3f) s.w = 0f;
                if (!swing && s.w == 0f && (Boolean) aiming.invoke(chr)) learn(s, pR, pT, hR, hT, dt);
            }
            if (s.w > 0f && s.has) pull(player, k, s, pR, pT, hR, hT, m);
        } catch (Throwable t) {
            if (warnings++ < 5) RBSHook.warn("grip skipped a pose: " + t);
        }
    }

    /** the left hand against Prop1 now, eased into what was learnt */
    private static void learn(State s, float[] pR, float[] pT, float[] hR, float[] hT, float dt) {
        float[] pRt = tr(pR);
        float[] relR = mat3(pRt, hR), relT = apply(pRt, sub(hT, pT));
        if (Math.sqrt(dot(relT, relT)) > MAX_GRIP) return;
        if (!s.has) {
            s.refR = relR;
            s.refT = relT;
            s.has = true;
            return;
        }
        float a = 1f - (float) Math.exp(-dt / TAU_LEARN);
        s.refT = add(s.refT, mul(sub(relT, s.refT), a));
        s.refR = mat3(scaled(mat3(relR, tr(s.refR)), a), s.refR);
    }

    /** bend the left arm so the hand reaches the learnt grip (by s.w) */
    private static void pull(Object player, Skel k, State s, float[] pR, float[] pT, float[] hR, float[] hT, float[] m)
            throws Exception {
        float[] goalT = add(pT, apply(pR, s.refT)), goalR = mat3(pR, s.refR);
        float[] S = pos(player, k.upper, m), E = pos(player, k.fore, m), W = hT;
        float a = len(sub(E, S)), b = len(sub(W, E));
        // out of reach (the animation lets go there): fade the pull out rather than point a straight arm at it
        float far = len(sub(goalT, S)) / Math.max(a + b, 1e-5f);
        float w = s.w * (1f - smoothstep(0.98f, 1.10f, far));
        if (w < 1e-3f) return;
        float[] target = add(hT, mul(sub(goalT, hT), w));
        float[] wantR = mat3(scaled(mat3(goalR, tr(hR)), w), hR);
        float[] dv = sub(target, S);
        float d0 = len(dv);
        if (a < 1e-5f || b < 1e-5f || d0 < 1e-5f) return;
        float[] n = mul(dv, 1f / d0);
        float d = Math.min(Math.max(d0, Math.abs(a - b) + 1e-4f), a + b - 1e-4f);
        float cosA = Math.min(Math.max((a * a + d * d - b * b) / (2f * a * d), -1f), 1f);
        float sinA = (float) Math.sqrt(1f - cosA * cosA);
        float[] pole = sub(E, S);
        float[] bend = norm(sub(pole, mul(n, dot(pole, n))));          // the way the animation bends the elbow
        if (bend == null) bend = perpendicular(n);
        float[] E2 = add(S, add(mul(n, a * cosA), mul(bend, a * sinA)));
        rotate(player, k.upperTree, fromTo(norm(sub(E, S)), norm(sub(E2, S))), S, m);
        float[] E1 = pos(player, k.fore, m), W1 = pos(player, k.hand, m);
        float[] reach = add(S, mul(n, d));
        float[] dirW = norm(sub(W1, E1)), dirT = norm(sub(reach, E1));
        if (dirW != null && dirT != null) rotate(player, k.foreTree, fromTo(dirW, dirT), E1, m);
        float[][] h2 = rt(player, k.hand, m);
        rotate(player, k.handTree, mat3(wantR, tr(h2[0])), h2[1], m);
        pulls++;
        float pullMm = len(sub(goalT, hT)) * w;
        if (pullMm > largestPull) largestPull = pullMm;
        long now = System.nanoTime();
        if (now >= summaryAt) {
            summaryAt = now + 30_000_000_000L;
            RBSHook.log(String.format(Locale.ROOT, "grip: %d pose(s) kept the left hand on a two-handed weapon so far, largest pull %.3f model units",
                    pulls, largestPull));
        }
    }

    // ---------------- bones ----------------

    /** M' = T(p) Q T(-p) M for each bone of the tree, in model space (as HeadLook.rotate) */
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
            float[] r2 = mat3(q, r), t2 = add(apply(q, sub(t, p)), p);
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

    /** {rotation 3x3 row-major (acting on column vectors), translation} of a bone in model space */
    private static float[][] rt(Object player, int i, float[] m) throws Exception {
        read(apModelAt.invoke(player, i), m);
        boolean cv = colVec(m);
        float[] r = new float[9], t = new float[3];
        for (int a = 0; a < 3; a++) {
            for (int b = 0; b < 3; b++) r[a * 3 + b] = cv ? m[b * 4 + a] : m[a * 4 + b];
            t[a] = cv ? m[12 + a] : m[a * 4 + 3];
        }
        return new float[][]{ r, t };
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
            if (x.endsWith("_l_upperarm")) k.upper = i;
            else if (x.endsWith("_l_forearm")) k.fore = i;
            else if (x.endsWith("_l_hand")) k.hand = i;
            else if (x.endsWith("_prop1")) k.prop = i;
        }
        if (k.upper >= 0 && k.fore >= 0 && k.hand >= 0 && k.prop >= 0) {
            k.upperTree = tree(parent, k.upper);
            k.foreTree = tree(parent, k.fore);
            k.handTree = tree(parent, k.hand);
            // the weapon must not hang under the arm (it would move with it): Prop1 off the arm's tree
            boolean propInArm = false;
            for (int i : k.upperTree) if (i == k.prop) propInArm = true;
            k.ok = !propInArm && k.handTree.length > 0 && k.foreTree.length > k.handTree.length
                    && k.upperTree.length > k.foreTree.length;
        }
        if (diag++ < 4) RBSHook.log("grip: skeleton with " + n + " bones" + (k.ok
                ? ", left arm " + k.upperTree.length + " bone(s), Prop1 apart from it" : " - skipped (no left arm / Prop1)"));
        skels.put(data, k);
        return k;
    }

    private static int[] tree(int[] parent, int root) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < parent.length; i++) {
            int j = i;
            for (int guard = 0; j >= 0 && guard < 64; guard++) {
                if (j == root) {
                    out.add(i);
                    break;
                }
                j = parent[j];
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    // ---------------- small vector maths (3x3 row-major, acting on column vectors) ----------------

    static float[] rot(float[] k, float a) {
        float c = (float) Math.cos(a), s = (float) Math.sin(a), v = 1 - c;
        float x = k[0], y = k[1], z = k[2];
        return new float[]{
                c + x * x * v, x * y * v - z * s, x * z * v + y * s,
                y * x * v + z * s, c + y * y * v, y * z * v - x * s,
                z * x * v - y * s, z * y * v + x * s, c + z * z * v };
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = Math.min(Math.max((x - e0) / (e1 - e0), 0f), 1f);
        return t * t * (3f - 2f * t);
    }

    /** the rotation by the fraction f of R's angle about its axis */
    static float[] scaled(float[] r, float f) {
        float c = Math.min(Math.max((r[0] + r[4] + r[8] - 1f) * 0.5f, -1f), 1f);
        float ang = (float) Math.acos(c);
        if (ang < 1e-5f) return new float[]{ 1, 0, 0, 0, 1, 0, 0, 0, 1 };
        float[] ax = norm(new float[]{ r[7] - r[5], r[2] - r[6], r[3] - r[1] });
        if (ax == null) {                                   // about half a turn: the axis from the diagonal
            int i = r[0] >= r[4] && r[0] >= r[8] ? 0 : r[4] >= r[8] ? 1 : 2;
            float[] col = { r[i] + (i == 0 ? 1 : 0), r[3 + i] + (i == 1 ? 1 : 0), r[6 + i] + (i == 2 ? 1 : 0) };
            ax = norm(col);
            if (ax == null) return new float[]{ 1, 0, 0, 0, 1, 0, 0, 0, 1 };
        }
        return rot(ax, ang * f);
    }

    /** the shortest turn taking the unit vector u to v */
    static float[] fromTo(float[] u, float[] v) {
        if (u == null || v == null) return new float[]{ 1, 0, 0, 0, 1, 0, 0, 0, 1 };
        float c = Math.min(Math.max(dot(u, v), -1f), 1f);
        float[] ax = norm(cross(u, v));
        if (ax == null) {
            if (c > 0f) return new float[]{ 1, 0, 0, 0, 1, 0, 0, 0, 1 };
            ax = perpendicular(u);
        }
        return rot(ax, (float) Math.acos(c));
    }

    static float[] perpendicular(float[] n) {
        float[] p = Math.abs(n[1]) < 0.9f ? new float[]{ 0, 1, 0 } : new float[]{ 1, 0, 0 };
        float[] q = norm(cross(n, p));
        return q == null ? new float[]{ 1, 0, 0 } : q;
    }

    static float[] mat3(float[] a, float[] b) {
        float[] o = new float[9];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                o[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
        return o;
    }

    static float[] tr(float[] a) {
        return new float[]{ a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8] };
    }

    static float[] apply(float[] q, float[] v) {
        return new float[]{ q[0] * v[0] + q[1] * v[1] + q[2] * v[2], q[3] * v[0] + q[4] * v[1] + q[5] * v[2],
                q[6] * v[0] + q[7] * v[1] + q[8] * v[2] };
    }

    static float[] cross(float[] a, float[] b) {
        return new float[]{ a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
    }

    static float[] sub(float[] a, float[] b) {
        return new float[]{ a[0] - b[0], a[1] - b[1], a[2] - b[2] };
    }

    static float[] add(float[] a, float[] b) {
        return new float[]{ a[0] + b[0], a[1] + b[1], a[2] + b[2] };
    }

    static float[] mul(float[] a, float f) {
        return new float[]{ a[0] * f, a[1] * f, a[2] * f };
    }

    static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static float len(float[] a) {
        return (float) Math.sqrt(dot(a, a));
    }

    static float[] norm(float[] v) {
        float l = len(v);
        return l < 1e-6f ? null : mul(v, 1f / l);
    }

    // ---------------- matrices ----------------

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

    private static synchronized void init() {
        if (ready || failed) return;
        try {
            if (!RBSHook.ensureInit()) throw new IllegalStateException("RBSHook not ready");
            ClassLoader cl = GripIK.class.getClassLoader();
            Class<?> ap = Class.forName("zombie.core.skinnedmodel.animation.AnimationPlayer", false, cl);
            Class<?> sd = Class.forName("zombie.core.skinnedmodel.model.SkinningData", false, cl);
            Class<?> sb = Class.forName("zombie.core.skinnedmodel.model.SkinningBone", false, cl);
            Class<?> m4 = Class.forName("org.lwjgl.util.vector.Matrix4f", false, cl);
            Class<?> igc = Class.forName("zombie.characters.IsoGameCharacter", false, cl);
            playerClass = Class.forName("zombie.characters.IsoPlayer", false, cl);
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
            primaryItem = igc.getMethod("getPrimaryHandItem");
            secondaryItem = igc.getMethod("getSecondaryHandItem");
            attacking = igc.getMethod("isPerformingAttackAnimation");
            aiming = playerClass.getMethod("isAiming");
            weaponClass = Class.forName("zombie.inventory.types.HandWeapon", false, igc.getClassLoader());
            isRanged = weaponClass.getMethod("isRanged");
            ready = true;
            RBSHook.log("grip ready - a two-handed melee weapon keeps the left hand on it while swinging (the grip learnt in the guard stance)");
        } catch (Throwable t) {
            failed = true;
            RBSHook.warn("grip init failed, the hands are left alone: " + t);
        }
    }
}
