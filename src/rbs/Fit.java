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
 */
final class Fit {
    private static volatile boolean failed, ready;
    private static Method apCharacter, mStore, mLoad;
    private static Field fModelMesh, fMeshSkinning, fBoneIndices;

    /** character -> {variant model, sex*4+variant} with an expiry, like Limbs */
    private static final Map<Object, Object[]> byChar = Collections.synchronizedMap(new WeakHashMap<>());
    /** SkinningData -> per sex/variant: matrix index -> affine (12 floats) or null */
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
        if (bodyData == data) {                                // the body itself already has the shape
            if (!bodySeen) {
                bodySeen = true;
                RBSHook.log("clothes: body mesh recognised - only the meshes on top of it are reshaped");
            }
            return;
        }
        if (bodyData == null) return;                          // body still loading: cannot tell it from clothes yet
        int key = (Integer) z[1];
        if (!ensureLayout(t)) return;
        PlayerFit.refresh();                                   // the mod's skip list and the models it has data for
        String path = MeshInfo.path(player, data);
        if (PlayerFit.skipped(path) || PlayerFit.skipped(plain(path))) return;
        float[][] maps;
        try {
            maps = mapsFor(player, data, key, path);
        } catch (Throwable e) {
            leave(data, key);                                  // this piece stays as the game made it, the others go on
            if (pieceErrors++ < 10) RBSHook.warn("clothes: " + path + ": " + e + " - left as it is");
            return;
        }
        if (maps == null) return;
        if (applyMaps(t, maps)) summary();
    }

    /** a piece whose maps could not be worked out: never tried again for this body */
    private static void leave(Object data, int key) {
        float[][][] all = perMesh.get(data);
        if (all == null) {
            all = new float[8][][];
            perMesh.put(data, all);
        }
        all[key] = new float[0][];
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
                if (RBSHook.zombiesEnabled() && RBSHook.wearsVariant(chr)) r = describe(RBSHook.visualOf(chr));
            } else if (Others.wearsVariant(chr)) {
                r = describe(Others.isFemale(chr), Others.variant(chr), "player");
            }
            byChar.put(chr, r == null ? new Object[]{ NONE, 0, now + 2_000_000_000L } : new Object[]{ r[0], r[1], now + 2_000_000_000L });
            return r;
        }
        if (!RBSHook.zombiesEnabled()) return null;
        Object visual = Limbs.animatedVisual(player);
        if (visual == null || !RBSHook.isZombieVisual(visual) || !RBSHook.wearsVariant(visual)) return null;
        return describe(visual);
    }

    private static Object[] describe(Object visual) throws Exception {
        if (visual == null) return null;
        return describe(RBSHook.isFemaleVisual(visual), RBSHook.variantOf(visual), "zombie");
    }

    private static Object[] describe(boolean female, int v, String who) throws Exception {
        if (v == 1) return null;                              // Normal: vanilla clothes fit already
        Object model = RBSHook.variantModelOf(female, v);
        if (model == null) return null;                       // Zombie Bodies mod off: the game's body
        int key = (female ? 0 : 4) + v;
        if (diag < 8) {
            diag++;
            RBSHook.log("clothes: " + (female ? "female " : "male ") + RBSHook.NAMES[v] + " " + who + " - its clothes follow the body");
        }
        return new Object[]{ model, key };
    }

    // ---------------- per mesh: which matrix gets which map ----------------

    @SuppressWarnings("unchecked")
    /** a zombie's clean copy of a game model (RBSClean/...) under the game's own name */
    private static String plain(String path) {
        return path != null && path.startsWith("rbsclean/") ? path.substring(9) : path;
    }

    private static float[][] mapsFor(Object player, Object data, int key, String path) throws Exception {
        float[][][] all = perMesh.get(data);
        if (all == null) {
            all = new float[8][][];
            perMesh.put(data, all);
        }
        float[][] m = all[key];
        if (m != null) return m.length == 0 ? null : m;
        float[][][] table = FitTable.MAPS[key / 4];
        float[][] byBone = table == null ? null : table[key % 4];
        Map<String, Integer> names = (Map<String, Integer>) fBoneIndices.get(data);
        if (byBone == null || names == null) {
            all[key] = new float[0][];
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
            all[key] = new float[0][];
            return null;
        }
        // a mod's mesh in a space of its own (FBX scaled x100...); the size guard only for pieces without the mod's data
        // (some of the game's pads have boxes reaching far off the body and always fitted fine)
        boolean ours = path == null || PlayerFit.known(plain(path));
        m = MeshSpace.convert(player, data, m, "clothes: " + path, ours ? null : MeshInfo.box(player, data));
        if (m == null) return null;                              // not yet: try again next time
        all[key] = m;
        return m.length == 0 ? null : m;
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
