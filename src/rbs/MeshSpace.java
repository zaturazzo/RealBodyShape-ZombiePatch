package rbs;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-bone maps of FitTable / PlayerFitTable were fitted in the game's own model space (the vanilla .x bodies,
 * rigged like the Human skeleton the animations run on). A clothing mesh from another mod can be in a space of its
 * own: a clothing mod's FBX files can hold vertices a hundred times smaller, with a scale in their bone offsets, so a map
 * used as it is moved a skirt a hundred times too far (a huge triangle above the player, user's screenshot 2026-11-11).
 * The game skins a mesh with its own bone offset (mesh -> bone) times the animation skeleton's pose, so for each bone
 * C = (Human offset)^-1 * (mesh offset) takes this mesh's space to the Human's, and the map for this mesh is
 * C^-1 * map * C. For the game's own clothes C is the identity.
 * Matrices are read the way Fit.compose reads the skin matrices (layout found from them).
 * Guards (user, 2026-11-11: "something we did not expect: leave it"): a piece whose bones do not agree on C (a rig
 * unlike the game's) and, for pieces the mod has no data for, one that reaches far off the body (wings, capes, big
 * props: stretching pulls their far parts a long way) is left as it is, with a line in the console.
 */
final class MeshSpace {
    private static volatile boolean failed, ready;
    private static Method apSkinning, mStore;
    private static Field fBoneOffset, fBoneIndices;
    private static final ThreadLocal<FloatBuffer> BUF = ThreadLocal.withInitial(() -> FloatBuffer.allocate(16));
    private static int logs, rejected;
    /** C of the bones may differ by this much (linear part, relative) / this far (model units) and still be one rig */
    static final float RIG_LINEAR = 0.02f, RIG_SHIFT = 0.01f;
    /** a piece without data reaching further than this beyond the body's box (model units, ~55 cm) is left alone;
     *  the game's own pieces reach 0.28 at most (the knapsack sprayer; rough_limit.py / the bounding boxes, 2026-11-11) */
    static final float REACH = 0.30f;
    /** the vanilla bodies' box (both sexes, T pose): x, y, z min then max */
    private static final float[] BODY = { -0.50f, -0.01f, -0.07f, 0.50f, 0.99f, 0.13f };

    private MeshSpace() {}

    /** maps[i] (12 floats, model space, matrix index i of this mesh) for this mesh's own space; null when it cannot
     *  be worked out yet (skin matrix layout unknown, no animation skeleton): try again later */
    @SuppressWarnings("unchecked")
    static float[][] convert(Object player, Object data, float[][] maps, String what, float[] box) throws Exception {
        if (maps == null) return null;
        if (!ready) init();
        if (failed) return null;
        int lay = Fit.layout();
        if (lay == 0) return null;
        Object anim = apSkinning.invoke(player);
        if (anim == null) return null;
        if (anim == data) return maps;
        Map<String, Integer> meshNames = (Map<String, Integer>) fBoneIndices.get(data);
        Map<String, Integer> animNames = (Map<String, Integer>) fBoneIndices.get(anim);
        List<Object> meshOff = (List<Object>) fBoneOffset.get(data);
        List<Object> animOff = (List<Object>) fBoneOffset.get(anim);
        if (meshNames == null || animNames == null || meshOff == null || animOff == null) return null;
        Map<Integer, String> byIndex = new HashMap<>();
        for (Map.Entry<String, Integer> e : meshNames.entrySet()) if (e.getValue() != null) byIndex.put(e.getValue(), e.getKey());
        float[][] out = new float[maps.length][];
        float[] global = null;
        for (String b : new String[]{ "Bip01_Pelvis", "Bip01_Spine", "Bip01_Spine1", "Bip01_L_Thigh", "Bip01_R_Thigh" }) {
            global = spaceOf(b, meshNames, animNames, meshOff, animOff, lay);
            if (global != null) break;
        }
        if (global != null && box != null) {
            float reach = reach(global, box);
            if (reach > REACH) return reject(what, String.format(java.util.Locale.ROOT, "reaches %.0f cm beyond the body", reach * 183));
        }
        float maxScale = 0f;
        boolean any = false;
        for (int i = 0; i < maps.length; i++) {
            if (maps[i] == null) continue;
            String name = byIndex.get(i);
            float[] c = name == null ? null : spaceOf(name, meshNames, animNames, meshOff, animOff, lay);
            if (c != null && global != null && !oneRig(c, global)) return reject(what, "its bone " + name + " is rigged unlike the game's skeleton");
            if (c == null) c = global;
            if (c == null) continue;                            // no way to tell this mesh's space: leave the bone alone
            float[] ci = inverse(c);
            if (ci == null) continue;
            out[i] = mul(ci, mul(maps[i], c));
            if (!finite(out[i])) return reject(what, "its bone " + name + " gives no usable numbers");
            maxScale = Math.max(maxScale, scaleOf(c));
            any = true;
        }
        if (logs < 12 && global != null && Math.abs(scaleOf(global) - 1f) > 0.02f) {
            logs++;
            RBSHook.log(String.format(java.util.Locale.ROOT, "%s: a mesh in its own space (scale %.3f to the game's) - its fit converted", what, scaleOf(global)));
        }
        return any ? out : new float[0][];
    }

    private static final java.util.Map<Object, float[]> spaceOfMesh = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final float[] NONE = new float[0];
    private static int jiggleLogs;

    /**
     * C (this mesh's space -> the game's) when the mesh is in a space of its own, null when it is in the game's (or that
     * cannot be told yet). Cached per mesh. Jiggle converts the shader's rows with it (jiggleRows59 / jiggleRows58).
     */
    @SuppressWarnings("unchecked")
    static float[] meshToGame(Object player, Object data) {
        if (data == null || player == null) return null;
        float[] c = spaceOfMesh.get(data);
        if (c != null) return c.length == 0 ? null : c;
        try {
            if (!ready) init();
            if (failed) return null;
            int lay = Fit.layout();
            if (lay == 0) return null;
            Object anim = apSkinning.invoke(player);
            if (anim == null) return null;
            if (anim == data) {
                spaceOfMesh.put(data, NONE);
                return null;
            }
            Map<String, Integer> meshNames = (Map<String, Integer>) fBoneIndices.get(data);
            Map<String, Integer> animNames = (Map<String, Integer>) fBoneIndices.get(anim);
            List<Object> meshOff = (List<Object>) fBoneOffset.get(data);
            List<Object> animOff = (List<Object>) fBoneOffset.get(anim);
            if (meshNames == null || animNames == null || meshOff == null || animOff == null) return null;
            float[] g = null;
            for (String bone : new String[]{ "Bip01_Pelvis", "Bip01_Spine", "Bip01_Spine1", "Bip01_L_Thigh", "Bip01_R_Thigh",
                    "Bip01_L_UpperArm", "Bip01_R_UpperArm", "Bip01_Neck" }) {
                g = spaceOf(bone, meshNames, animNames, meshOff, animOff, lay);
                if (g != null) break;
            }
            boolean game = g == null || !finite(g) || sameRig(g, new float[]{ 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0 });
            spaceOfMesh.put(data, game ? NONE : g);
            if (!game && jiggleLogs++ < 12) {
                RBSHook.log(String.format(java.util.Locale.ROOT, "jiggle: %s is in a space of its own (scale %.3f) - its bounce converted",
                        MeshInfo.path(player, data), scaleOf(g)));
            }
            return game ? null : g;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * The shader's slot 59 rows (game space, see RBSJiggle.vert) for a mesh whose space C takes to the game's:
     * row 0 xyz = displacement (vector), w = on (kept); row 1 xyz = pelvis (point), w = torso length (/ scale);
     * rows 2, 3 xyz = forward, up (unit directions: rotation only), w = buttocks lag up / forward (/ scale).
     * The shader's regions are in torso lengths along those axes, so they come out the same in any similar space.
     */
    static float[] jiggleRows59(float[] rows, float[] c) {
        float[] ci = inverse(c);
        if (ci == null) return rows;
        float s = Math.max(1e-6f, scaleOf(c));
        float[] o = rows.clone();
        vec(ci, rows, 0, o, 1f);
        point(ci, rows, 4, o);
        o[7] = rows[7] / s;
        vec(ci, rows, 8, o, s);
        o[11] = rows[11] / s;
        vec(ci, rows, 12, o, s);
        o[15] = rows[15] / s;
        return o;
    }

    /** slot 58: rows 0 - 2 xyz = bust, bag, buttocks / thighs / arms displacement (vectors); row 2 w = breathing push
     *  (/ scale); row 0 w = 58, row 1 w and row 3 = this mesh's own bone numbers (kept) */
    static float[] jiggleRows58(float[] rows, float[] c) {
        float[] ci = inverse(c);
        if (ci == null) return rows;
        float s = Math.max(1e-6f, scaleOf(c));
        float[] o = rows.clone();
        vec(ci, rows, 0, o, 1f);
        vec(ci, rows, 4, o, 1f);
        vec(ci, rows, 8, o, 1f);
        o[11] = rows[11] / s;
        return o;
    }

    /** o[at..at+2] = k * (linear part of m) * v[at..at+2] (a vector: no shift; k = scale for unit directions) */
    private static void vec(float[] m, float[] v, int at, float[] o, float k) {
        float x = v[at], y = v[at + 1], z = v[at + 2];
        for (int r = 0; r < 3; r++) o[at + r] = k * (m[r * 4] * x + m[r * 4 + 1] * y + m[r * 4 + 2] * z);
    }

    /** o[at..at+2] = m * v[at..at+2] (a point) */
    private static void point(float[] m, float[] v, int at, float[] o) {
        float x = v[at], y = v[at + 1], z = v[at + 2];
        for (int r = 0; r < 3; r++) o[at + r] = m[r * 4] * x + m[r * 4 + 1] * y + m[r * 4 + 2] * z + m[r * 4 + 3];
    }

    private static float[][] reject(String what, String why) {
        if (rejected++ < 20) RBSHook.log(what + ": " + why + " - left as it is");
        return new float[0][];
    }

    /** two bones' C agree (one rig): linear parts within RIG_LINEAR of the scale, shifts within RIG_SHIFT */
    /**
     * One rig: a bone's C may differ from the pelvis's as the game's own skeletons do (each bone is converted with its
     * own C anyway): the women's arms against the Human (men's) skeleton the animations run on are turned 2.9 degrees and
     * shifted 4.8 cm, so pieces rigged on the men's arms worn by a woman (a clothing mod's jackets) were left out at first
     * (2026-11-11). Not one rig: another scale (+/-25 %), turned more than 30 degrees or shifted more than ~27 cm.
     */
    static boolean oneRig(float[] c, float[] g) {
        float sg = Math.max(1e-6f, scaleOf(g)), sc = scaleOf(c);
        float ratio = sc / sg;
        if (ratio < 0.8f || ratio > 1.25f) return false;
        float[] gi = inverse(g);
        if (gi == null) return false;
        float[] rel = mul(gi, c);                               // c against g, in the mesh's own space
        float tr = (rel[0] + rel[5] + rel[10]) / Math.max(1e-6f, ratio);
        if ((tr - 1f) / 2f < (float) Math.cos(Math.toRadians(30))) return false;
        float dx = c[3] - g[3], dy = c[7] - g[7], dz = c[11] - g[11];   // where they put the mesh's origin (game units)
        return dx * dx + dy * dy + dz * dz <= 0.15f * 0.15f;
    }

    static boolean sameRig(float[] a, float[] b) {
        float s = Math.max(1e-6f, scaleOf(b));
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) if (Math.abs(a[r * 4 + c] - b[r * 4 + c]) > RIG_LINEAR * s) return false;
            if (Math.abs(a[r * 4 + 3] - b[r * 4 + 3]) > RIG_SHIFT) return false;
        }
        return true;
    }

    /** how far the piece's box (its own space, taken to the game's by C) reaches beyond the body's box */
    static float reach(float[] c, float[] box) {
        float out = 0f;
        for (int k = 0; k < 8; k++) {
            float x = box[(k & 1) == 0 ? 0 : 3], y = box[(k & 2) == 0 ? 1 : 4], z = box[(k & 4) == 0 ? 2 : 5];
            float[] p = { c[0] * x + c[1] * y + c[2] * z + c[3], c[4] * x + c[5] * y + c[6] * z + c[7], c[8] * x + c[9] * y + c[10] * z + c[11] };
            for (int a = 0; a < 3; a++) out = Math.max(out, Math.max(BODY[a] - p[a], p[a] - BODY[3 + a]));
        }
        return out;
    }

    /** C for one bone: Human offset^-1 * mesh offset, or null when either is missing / the identity (bone not in the mesh) */
    private static float[] spaceOf(String bone, Map<String, Integer> meshNames, Map<String, Integer> animNames,
                                   List<Object> meshOff, List<Object> animOff, int lay) throws Exception {
        Integer mi = meshNames.get(bone), ai = animNames.get(bone);
        if (mi == null || ai == null || mi < 0 || ai < 0 || mi >= meshOff.size() || ai >= animOff.size()) return null;
        Object om = meshOff.get(mi), oa = animOff.get(ai);
        if (om == null || oa == null) return null;
        float[] m = affine(read(om), lay), a = affine(read(oa), lay);
        if (isIdentity(m) || isIdentity(a)) return null;
        float[] ai2 = inverse(a);
        return ai2 == null ? null : mul(ai2, m);
    }

    // ---------------- 3x4 affine maps { a00, a01, a02, t0, a10, a11, a12, t1, a20, a21, a22, t2 } (y = A x + t) ----------------

    private static float[] affine(float[] m, int lay) {
        float[] o = new float[12];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) o[r * 4 + c] = lay == 1 ? m[c * 4 + r] : m[r * 4 + c];
            o[r * 4 + 3] = lay == 1 ? m[12 + r] : m[r * 4 + 3];
        }
        return o;
    }

    /** p * q: the map that applies q first, then p */
    static float[] mul(float[] p, float[] q) {
        float[] o = new float[12];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) o[r * 4 + c] = p[r * 4] * q[c] + p[r * 4 + 1] * q[4 + c] + p[r * 4 + 2] * q[8 + c];
            o[r * 4 + 3] = p[r * 4] * q[3] + p[r * 4 + 1] * q[7] + p[r * 4 + 2] * q[11] + p[r * 4 + 3];
        }
        return o;
    }

    static float[] inverse(float[] m) {
        float a = m[0], b = m[1], c = m[2], d = m[4], e = m[5], f = m[6], g = m[8], h = m[9], i = m[10];
        float A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        float det = a * A + b * B + c * C;
        if (Math.abs(det) < 1e-12f) return null;
        float inv = 1f / det;
        float[] o = new float[12];
        o[0] = A * inv; o[1] = -(b * i - c * h) * inv; o[2] = (b * f - c * e) * inv;
        o[4] = B * inv; o[5] = (a * i - c * g) * inv; o[6] = -(a * f - c * d) * inv;
        o[8] = C * inv; o[9] = -(a * h - b * g) * inv; o[10] = (a * e - b * d) * inv;
        for (int r = 0; r < 3; r++) o[r * 4 + 3] = -(o[r * 4] * m[3] + o[r * 4 + 1] * m[7] + o[r * 4 + 2] * m[11]);
        return o;
    }

    /** every number is a real one (no NaN, no infinity): a broken bone offset must not reach the game's matrices */
    static boolean finite(float[] m) {
        if (m == null) return false;
        for (float v : m) if (!Float.isFinite(v)) return false;
        return true;
    }

    private static boolean isIdentity(float[] m) {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 4; c++) if (Math.abs(m[r * 4 + c] - (r == c ? 1f : 0f)) > 1e-6f) return false;
        }
        return true;
    }

    private static float scaleOf(float[] m) {
        float det = m[0] * (m[5] * m[10] - m[6] * m[9]) - m[1] * (m[4] * m[10] - m[6] * m[8]) + m[2] * (m[4] * m[9] - m[5] * m[8]);
        return (float) Math.cbrt(Math.abs(det));
    }

    private static float[] read(Object matrix) throws Exception {
        FloatBuffer buf = BUF.get();
        buf.clear();
        mStore.invoke(matrix, buf);
        buf.rewind();
        float[] m = new float[16];
        buf.get(m);
        return m;
    }

    private static synchronized void init() {
        if (ready || failed) return;
        try {
            ClassLoader cl = MeshSpace.class.getClassLoader();
            Class<?> ap = Class.forName("zombie.core.skinnedmodel.animation.AnimationPlayer", false, cl);
            Class<?> sd = Class.forName("zombie.core.skinnedmodel.model.SkinningData", false, cl);
            Class<?> m4 = Class.forName("org.lwjgl.util.vector.Matrix4f", false, cl);
            apSkinning = ap.getMethod("getSkinningData");
            fBoneOffset = sd.getField("boneOffset");
            fBoneIndices = sd.getField("boneIndices");
            mStore = m4.getMethod("store", FloatBuffer.class);
            ready = true;
        } catch (Throwable e) {
            failed = true;
            RBSHook.warn("mesh space init failed, clothes of other mods' formats are not fitted: " + e);
        }
    }
}
