package rbs;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Clothes that follow the zombie body variants (part of "Real Body Shape - Zombie Bodies").
 * A zombie with a thin / fat / obese body still wears the game's vanilla 3D clothes, and the game never hides the
 * pelvis or torso of the body under them, so a fat zombie's hips and belly stuck out of its trousers and jumper.
 * For every mesh of such a zombie that is not its body (clothes, bags, skirts), each bone's skinning matrix is
 * multiplied by a bind-space affine map taken from FitTable (fitted offline by fit_clothes.py: Normal body ->
 * variant body around that bone), so the clothes are pushed out or in like the body under them.
 * The game caches skin matrices and only recomputes them when its pose changed, so the matrices written here are
 * remembered and a call that finds them unchanged leaves them alone (the map must never be applied twice).
 * Corpses drawn by DeadBodyAtlas are handled the same way, through the visual Limbs remembers for AnimatedModel.
 * An NPC drawn part of the way between two bodies (RBSHook.blendStep, 2026-11-22) wears the variant below; its clothes
 * get the two variants' maps mixed that far (each affine), and its body the map from the variant below to that mix -
 * 14 NPC bodies from the 4 files.
 */
final class Fit {
    private static volatile boolean failed, ready;
    private static Method apCharacter, mStore, mLoad;
    private static Field fModelMesh, fMeshSkinning, fBoneIndices;

    /** a key: sex*4*Q + variant*Q + step toward the next variant (0..Q-1, 0 = the variant itself); the body's own maps
     *  under BODY + key */
    private static final int Q = RBSHook.BLEND_STEPS, KEYS = 2 * 4 * Q, BODY = KEYS;

    /** character -> {variant model, key} with an expiry, like Limbs */
    private static final Map<Object, Object[]> byChar = Collections.synchronizedMap(new WeakHashMap<>());
    /** SkinningData -> per key (clothes 0..KEYS-1, a blended body KEYS..): matrix index -> affine (12 floats) or null */
    private static final Map<Object, float[][][]> perMesh = Collections.synchronizedMap(new WeakHashMap<>());
    /** the transforms array of a mesh -> the matrices last written into it (arrays have identity equality) */
    private static final Map<Object, float[][]> written = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<FloatBuffer> BUF = ThreadLocal.withInitial(() -> FloatBuffer.allocate(16));
    private static final Object NONE = new Object();
    /** 0 = not known yet, 1 = translation in m30..m32 (column vectors), 2 = translation in m03..m23 */
    private static volatile int layout;
    private static int diag, fitted, errors, pieceErrors;
    private static volatile boolean bodySeen;
    private static long summaryAt;

    private Fit() {}

    // ---------------- hook (called from RBSHook.skinTransforms before Limbs) ----------------

    /** errors are caught here: one piece that fails is left as the game made it (the others go on), and errors that
     *  keep coming (not one piece's) switch the zombie clothes off for this session instead of failing every frame */
    static void apply(Object player, Object data, Object t) {
        if (failed || data == null || t == null) return;
        try {
            fit(player, data, t);
        } catch (Throwable e) {
            if (++errors >= 30) {
                failed = true;
                RBSHook.warn("zombie clothes switched off after repeated errors, they stay as the game made them: " + e);
            } else if (errors == 1) RBSHook.warn("zombie clothes: " + e);
        }
    }

    private static void fit(Object player, Object data, Object t) throws Exception {
        if (!RBSHook.zombiesEnabled() && !RBSHook.othersEnabled()) return;
        if (!ready) init();
        if (failed) return;
        Object[] z = zombieOf(player);
        if (z == null) return;
        Object body = fModelMesh.get(z[0]);
        Object bodyData = body == null ? null : fMeshSkinning.get(body);
        int key = (Integer) z[1];
        boolean isBody = bodyData == data;
        if (isBody && key % Q == 0) {                          // the body itself already has the shape
            if (!bodySeen) {
                bodySeen = true;
                RBSHook.log("clothes: body mesh recognised - only the meshes on top of it are reshaped");
            }
            return;
        }
        if (bodyData == null) return;                          // body still loading: cannot tell it from clothes yet
        if (!ensureLayout(t)) return;
        PlayerFit.refresh();                                   // the mod's skip list and the models it has data for
        String path = MeshInfo.path(player, data);
        if (!isBody && (PlayerFit.skipped(path) || PlayerFit.skipped(plain(path)))) return;
        int slot = isBody ? BODY + key : key;                  // a blended body: moved from the variant below
        float[][] maps;
        try {
            maps = mapsFor(player, data, slot, path);
        } catch (Throwable e) {
            leave(data, slot);                                 // this piece stays as the game made it, the others go on
            if (pieceErrors++ < 10) RBSHook.warn("clothes: " + path + ": " + e + " - left as it is");
            return;
        }
        if (maps == null) return;
        if (applyMaps(t, maps)) summary();
    }

    /** a piece whose maps could not be worked out: never tried again for this body */
    private static void leave(Object data, int slot) {
        float[][][] all = perMesh.get(data);
        if (all == null) {
            all = new float[2 * KEYS][][];
            perMesh.put(data, all);
        }
        all[slot] = new float[0][];
    }

    /** the skin matrix layout, found from this transforms array if not known yet (MeshSpace needs it before any map) */
    static boolean ensureLayout(Object t) throws Exception {
        if (layout != 0) return true;
        if (!ready) init();
        if (failed) return false;
        for (Object m : (Object[]) t) {
            if (m != null && layoutOf(read(m)) != 0) return true;
        }
        return false;
    }

    /** multiplies maps[i] (bind-space affine, 12 floats) into skin matrix i of the transforms array t, once per pose:
     *  matrices still holding what was written last time are left alone. Also used for the player (PlayerFit) */
    static boolean applyMaps(Object t, float[][] maps) throws Exception {
        Object[] tr = (Object[]) t;
        int n = Math.min(tr.length, maps.length);
        boolean wrote = false;
        synchronized (tr) {
            float[][] last = written.get(tr);
            if (last == null || last.length != tr.length) {
                last = new float[tr.length][];
                written.put(tr, last);
            }
            for (int i = 0; i < n; i++) {
                if (maps[i] == null || tr[i] == null) continue;
                float[] cur = read(tr[i]);
                if (last[i] != null && Arrays.equals(cur, last[i])) continue;    // still the one we wrote: cache not refreshed
                float[] out = compose(cur, maps[i]);
                if (out == null) continue;
                write(tr[i], out);
                last[i] = out;
                wrote = true;
            }
        }
        return wrote;
    }

    /** {variant body model, sex*4+variant} of a zombie (live or corpse in the atlas) whose body is not Normal, else null */
    private static Object[] zombieOf(Object player) throws Exception {
        Object chr = apCharacter.invoke(player);
        if (chr != null) {
            long now = System.nanoTime();
            Object[] e = byChar.get(chr);
            if (e != null && now < (Long) e[2]) return e[0] == NONE ? null : e;
            Object[] r = null;
            if (RBSHook.isLiveZombie(chr)) {
                if (RBSHook.zombiesEnabled() && RBSHook.wearsVariant(chr)) r = describeLive(chr);
            } else if (Others.wearsVariant(chr)) {
                r = describe(Others.isFemale(chr), Others.variant(chr), 0, null, "player");
            }
            byChar.put(chr, r == null ? new Object[]{ NONE, 0, now + 2_000_000_000L } : new Object[]{ r[0], r[1], now + 2_000_000_000L });
            return r;
        }
        if (!RBSHook.zombiesEnabled()) return null;
        Object visual = Limbs.animatedVisual(player);
        if (visual == null) return null;
        if (!RBSHook.isZombieVisual(visual)) {
            Object[] p = NpcHeads.previewOf(visual);            // a UI preview of another mod's NPC: its body's fit
            return p == null ? null : describe((Boolean) p[0], (Integer) p[1], 0, null, "NPC preview");
        }
        if (!RBSHook.wearsVariant(visual)) return null;
        return describe(visual);
    }

    /** a corpse's (or a copy's): the variant it keeps */
    private static Object[] describe(Object visual) throws Exception {
        if (visual == null) return null;
        return describe(RBSHook.isFemaleVisual(visual), RBSHook.variantOf(visual), 0, null, "zombie");
    }

    /** a live zombie: its variant, and the step toward the next one when it is an NPC set so (its body then needs a map
     *  too: the model it really wears is looked up - another mod's NPC may wear the HD add-on's) */
    private static Object[] describeLive(Object chr) throws Exception {
        Object visual = RBSHook.visualOf(chr);
        if (visual == null) return null;
        boolean female = RBSHook.isFemaleVisual(visual);
        int v = RBSHook.variantOf(visual);
        int q = v < RBSHook.NAMES.length - 1 ? RBSHook.blendStep(visual) : 0;
        return describe(female, v, q, q > 0 ? RBSHook.zombieVariantModel(chr, female, v) : null, q > 0 ? "NPC" : "zombie");
    }

    private static Object[] describe(boolean female, int v, int q, Object worn, String who) throws Exception {
        if (v == 1 && q == 0) return null;                    // Normal: vanilla clothes fit already
        Object model = worn != null ? worn : RBSHook.variantModelOf(female, v);
        if (model == null) return null;                       // Zombie Bodies mod off: the game's body
        int key = (female ? 0 : 4 * Q) + v * Q + q;
        if (diag < 8) {
            diag++;
            RBSHook.log("clothes: " + (female ? "female " : "male ") + RBSHook.NAMES[v] + (q > 0 ? " " + q + "/" + Q + " of the way to " + RBSHook.NAMES[v + 1] : "")
                    + " " + who + " - its clothes follow the body" + (q > 0 ? ", and the body is moved that far" : ""));
        }
        return new Object[]{ model, key };
    }

    // ---------------- per mesh: which matrix gets which map ----------------

    @SuppressWarnings("unchecked")
    /** a zombie's clean copy of a game model (RBSClean/...) under the game's own name */
    private static String plain(String path) {
        return path != null && path.startsWith("rbsclean/") ? path.substring(9) : path;
    }

    private static float[][] mapsFor(Object player, Object data, int slot, String path) throws Exception {
        float[][][] all = perMesh.get(data);
        if (all == null) {
            all = new float[2 * KEYS][][];
            perMesh.put(data, all);
        }
        float[][] m = all[slot];
        if (m != null) return m.length == 0 ? null : m;
        boolean body = slot >= BODY;
        int key = slot % KEYS;
        float[][][] table = FitTable.MAPS[key / (4 * Q)];
        int v = key % (4 * Q) / Q, q = key % Q;
        float[][] byBone = table == null || v >= table.length ? null
                : q == 0 ? (body ? null : table[v])
                : blend(table[v], v + 1 < table.length ? table[v + 1] : null, q / (float) Q, body);
        Map<String, Integer> names = (Map<String, Integer>) fBoneIndices.get(data);
        if (byBone == null || names == null) {
            all[slot] = new float[0][];
            return null;
        }
        Map<String, float[]> byName = new HashMap<>();
        for (int i = 0; i < FitTable.BONES.length && i < byBone.length; i++) {
            if (byBone[i] != null) byName.put(FitTable.BONES[i], byBone[i]);
        }
        for (int i = 0; i + 1 < FitTable.ALIAS.length; i += 2) {
            float[] src = byName.get(FitTable.ALIAS[i + 1]);
            if (src != null) byName.put(FitTable.ALIAS[i], src);
        }
        int max = -1;
        for (Integer idx : names.values()) if (idx != null) max = Math.max(max, idx);
        m = new float[max + 1][];
        boolean any = false;
        for (Map.Entry<String, Integer> e : names.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() < 0) continue;
            float[] a = byName.get(e.getKey());
            if (a != null) {
                m[e.getValue()] = a;
                any = true;
            }
        }
        if (!any) {
            all[slot] = new float[0][];
            return null;
        }
        // a mod's mesh in a space of its own (FBX scaled x100...); the size guard only for pieces without the mod's data
        // (some of the game's pads have boxes reaching far off the body and always fitted fine; never the body itself)
        boolean ours = body || path == null || PlayerFit.known(plain(path));
        m = MeshSpace.convert(player, data, m, (body ? "blended body: " : "clothes: ") + path, ours ? null : MeshInfo.box(player, data));
        if (m == null) return null;                              // not yet: try again next time
        all[slot] = m;
        return m.length == 0 ? null : m;
    }

    /**
     * The maps of the body the share t of the way from one variant to the next (per bone, bind space): each affine mixed
     * (1-t)*lo + t*hi - for clothes made for the Normal body; for the body of the variant below (body) that mix after
     * the inverse of its own map, which takes the variant below to the blended one. A missing map is the identity (the
     * Normal body's own); a mix equal to it stays missing (nothing to move).
     */
    static float[][] blend(float[][] lo, float[][] hi, float t, boolean body) {
        int n = Math.max(lo == null ? 0 : lo.length, hi == null ? 0 : hi.length);
        float[][] out = new float[n][];
        boolean any = false;
        for (int i = 0; i < n; i++) {
            float[] a = lo != null && i < lo.length && lo[i] != null ? lo[i] : IDENTITY;
            float[] b = hi != null && i < hi.length && hi[i] != null ? hi[i] : IDENTITY;
            float[] h = new float[12];
            for (int k = 0; k < 12; k++) h[k] = (1 - t) * a[k] + t * b[k];
            if (body) {
                float[] inv = inverse(a);
                if (inv == null) continue;
                h = then(inv, h);
            }
            if (isIdentity(h)) continue;
            out[i] = h;
            any = true;
        }
        return any ? out : null;
    }

    private static final float[] IDENTITY = { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0 };

    /** an affine (rows of 3x3 | translation) inverted, or null when it cannot be */
    static float[] inverse(float[] a) {
        float a00 = a[0], a01 = a[1], a02 = a[2], a10 = a[4], a11 = a[5], a12 = a[6], a20 = a[8], a21 = a[9], a22 = a[10];
        float c00 = a11 * a22 - a12 * a21, c01 = a02 * a21 - a01 * a22, c02 = a01 * a12 - a02 * a11;
        float c10 = a12 * a20 - a10 * a22, c11 = a00 * a22 - a02 * a20, c12 = a02 * a10 - a00 * a12;
        float c20 = a10 * a21 - a11 * a20, c21 = a01 * a20 - a00 * a21, c22 = a00 * a11 - a01 * a10;
        float det = a00 * c00 + a01 * c10 + a02 * c20;
        if (!(Math.abs(det) > 1e-8f)) return null;
        float d = 1f / det;
        float[] r = { c00 * d, c01 * d, c02 * d, 0, c10 * d, c11 * d, c12 * d, 0, c20 * d, c21 * d, c22 * d, 0 };
        for (int row = 0; row < 3; row++) {
            r[row * 4 + 3] = -(r[row * 4] * a[3] + r[row * 4 + 1] * a[7] + r[row * 4 + 2] * a[11]);
        }
        return r;
    }

    /** first p, then q: (q after p)(x) = q(p(x)) */
    static float[] then(float[] p, float[] q) {
        float[] r = new float[12];
        for (int row = 0; row < 3; row++) {
            for (int c = 0; c < 3; c++) {
                r[row * 4 + c] = q[row * 4] * p[c] + q[row * 4 + 1] * p[4 + c] + q[row * 4 + 2] * p[8 + c];
            }
            r[row * 4 + 3] = q[row * 4] * p[3] + q[row * 4 + 1] * p[7] + q[row * 4 + 2] * p[11] + q[row * 4 + 3];
        }
        return r;
    }

    private static boolean isIdentity(float[] h) {
        for (int k = 0; k < 12; k++) if (Math.abs(h[k] - IDENTITY[k]) > 1e-6f) return false;
        return true;
    }

    // ---------------- matrices ----------------

    /** skin'(x) = skin(A x + b): linear part L*A, translation L*b + T, in whichever layout the game stores */
    private static float[] compose(float[] m, float[] a) {
        int lay = layoutOf(m);
        if (lay == 0) return null;
        float[][] L = new float[3][3];
        float[] T = new float[3];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) L[r][c] = lay == 1 ? m[c * 4 + r] : m[r * 4 + c];
            T[r] = lay == 1 ? m[12 + r] : m[r * 4 + 3];
        }
        float[] out = m.clone();
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                float v = L[r][0] * a[c] + L[r][1] * a[4 + c] + L[r][2] * a[8 + c];
                if (lay == 1) out[c * 4 + r] = v; else out[r * 4 + c] = v;
            }
            float tv = L[r][0] * a[3] + L[r][1] * a[7] + L[r][2] * a[11] + T[r];
            if (lay == 1) out[12 + r] = tv; else out[r * 4 + 3] = tv;
        }
        return MeshSpace.finite(out) ? out : null;             // never a NaN / infinity into the game's matrices
    }

    /** 0 = not known yet (no skin matrix seen), 1 = translation in m30..m32, 2 = in m03..m23 */
    static int layout() {
        return layout;
    }

    /** decided once from a matrix with a clear translation (the same test Limbs uses per matrix) */
    private static int layoutOf(float[] m) {
        int l = layout;
        if (l != 0) return l;
        float col = Math.abs(m[12]) + Math.abs(m[13]) + Math.abs(m[14]);
        float row = Math.abs(m[3]) + Math.abs(m[7]) + Math.abs(m[11]);
        if (Math.abs(col - row) < 0.05f) return 0;
        l = col > row ? 1 : 2;
        layout = l;
        RBSHook.log("clothes: skin matrices keep their translation in " + (l == 1 ? "m30..m32" : "m03..m23"));
        return l;
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

    private static void write(Object matrix, float[] m) throws Exception {
        FloatBuffer buf = BUF.get();
        buf.clear();
        buf.put(m);
        buf.rewind();
        mLoad.invoke(matrix, buf);
    }

    private static void summary() {
        fitted++;
        long now = System.nanoTime();
        if (now < summaryAt) return;
        summaryAt = now + 60_000_000_000L;
        RBSHook.log("clothes: " + fitted + " clothing pose(s) fitted to zombie bodies so far");
    }

    // ---------------- setup ----------------

    /** the matrix access is set up (PlayerFit uses it without the zombie side being on) */
    static boolean ensureReady() {
        if (!ready && !failed) init();
        return ready;
    }

    private static synchronized void init() {
        if (ready || failed) return;
        try {
            if (!RBSHook.ensureInit()) throw new IllegalStateException("RBSHook not ready");
            ClassLoader cl = Fit.class.getClassLoader();
            Class<?> ap = Class.forName("zombie.core.skinnedmodel.animation.AnimationPlayer", false, cl);
            Class<?> sd = Class.forName("zombie.core.skinnedmodel.model.SkinningData", false, cl);
            Class<?> model = Class.forName("zombie.core.skinnedmodel.model.Model", false, cl);
            Class<?> mesh = Class.forName("zombie.core.skinnedmodel.model.ModelMesh", false, cl);
            Class<?> m4 = Class.forName("org.lwjgl.util.vector.Matrix4f", false, cl);
            apCharacter = ap.getMethod("getIsoGameCharacter");
            fBoneIndices = sd.getField("boneIndices");
            fModelMesh = model.getField("mesh");
            fMeshSkinning = mesh.getField("skinningData");
            mStore = m4.getMethod("store", FloatBuffer.class);
            mLoad = m4.getMethod("load", FloatBuffer.class);
            ready = true;
            RBSHook.log("clothes ready - zombie clothes follow thin / fat / obese bodies");
        } catch (Throwable e) {
            failed = true;
            RBSHook.warn("clothes init failed, zombie clothes stay as they are: " + e);
        }
    }
}
