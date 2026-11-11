package rbs;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Clothes Real Body Shape has no data for, on the player: another mod's own 3D clothes (clothing mods' pieces) kept
 * their original size on a fat or thin character. The mod reshapes the game's clothes (and those it has data for)
 * piece by piece; for the rest this does what Fit does for zombie clothes, roughly: each bone's skinning matrix gets
 * the bind-space affine map that takes the vanilla body to the player's body around that bone, mixed from
 * PlayerFitTable (one table per shape parameter, fitted offline by rough_clothes.py) with the player's parameters.
 * The shape is the one the mod last built the body files for (~/Zomboid/Lua/RealBodyShape_built.txt); the mod writes
 * ~/Zomboid/Lua/RealBodyShape_fit.txt when a game starts: rough=1/0, female=0 when women keep another mod's body,
 * and known=<model> for every model it has data for (those are left alone, they already have the shape).
 * A fit file older than this game session means the mod is off: nothing is done then.
 * Only players wearing the game's shared body (not another mod's body, e.g. a child's), not hair or beards.
 */
final class PlayerFit {
    static final String CONFIG = "RealBodyShape_fit.txt", STAMP = "RealBodyShape_built.txt";
    private static final float[] IDENTITY = { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0 };

    private static volatile boolean failed, ready;
    private static Method apCharacter, chrFemale, chrAnimal, chrGetModel;
    private static Class<?> playerClass;
    private static Field fInstModel, fModelMesh, fMeshSkinning;
    private static Field fBoneIndices, fFemaleBody, fMaleBody;
    private static Object modelManager;

    // what the files say
    private static volatile boolean on, femaleOn = true;
    private static volatile Set<String> known = Collections.emptySet();
    /** models never fitted, roughly or (zombies) by Fit: RealBodyShape.ROUGH.skip, a whole path or its last parts */
    private static volatile Set<String> skip = Collections.emptySet();
    private static volatile float[] params = new float[PlayerFitTable.KEYS.length];
    private static volatile int version;
    private static volatile long nextCheck;
    private static long cfgMtime = -1, stampMtime = -1, liveSince;

    /** SkinningData -> {Integer 1 = fit roughly / 0 = leave / -1 = not found yet, Long retry at} */
    private static final Map<Object, Object[]> pieces = Collections.synchronizedMap(new WeakHashMap<>());
    /** SkinningData -> {Integer version*2+sex, float[][] maps by matrix index or null} */
    private static final Map<Object, Object[]> mapsCache = Collections.synchronizedMap(new WeakHashMap<>());
    private static int logs, fitted, errors;
    private static long summaryAt;

    private PlayerFit() {}

    // ---------------- hook (called from RBSHook.afterSkinTransforms after Fit) ----------------

    static void apply(Object player, Object data, Object t) {
        if (failed || data == null || t == null) return;
        try {
            fit(player, data, t);
        } catch (Throwable e) {
            if (++errors >= 30) {
                failed = true;
                RBSHook.warn("rough fit switched off after repeated errors: " + e);
            } else if (errors == 1) RBSHook.warn("rough fit: " + e);
        }
    }

    private static void fit(Object player, Object data, Object t) throws Exception {
        if (!ready) init();
        if (failed) return;
        if (System.nanoTime() >= nextCheck) load();
        if (!on) return;
        Object chr = apCharacter.invoke(player);
        if (chr == null || !playerClass.isInstance(chr) || (Boolean) chrAnimal.invoke(chr)) return;
        boolean female = (Boolean) chrFemale.invoke(chr);
        if (female && !femaleOn) return;
        Object inst = chrGetModel.invoke(chr);
        if (inst == null) return;
        Object body = fInstModel.get(inst);
        if (body == null || body != (female ? fFemaleBody : fMaleBody).get(modelManager)) return;   // another mod's body
        Object bodyMesh = fModelMesh.get(body);
        Object bodyData = bodyMesh == null ? null : fMeshSkinning.get(bodyMesh);
        if (bodyData == null || bodyData == data) return;      // still loading, or the body itself (it has the shape)
        if (!rough(player, data)) return;
        if (!Fit.ensureReady() || !Fit.ensureLayout(t)) return;
        float[][] maps = mapsFor(player, data, female ? 0 : 1);
        if (maps == null) return;
        if (Fit.applyMaps(t, maps)) summary();
    }

    // ---------------- which pieces ----------------

    private static boolean rough(Object player, Object data) throws Exception {
        long now = System.nanoTime();
        Object[] e = pieces.get(data);
        if (e != null && ((Integer) e[0] >= 0 || now < (Long) e[1])) return (Integer) e[0] == 1;
        String path = MeshInfo.path(player, data);
        int kind;
        String why;
        if (path == null) {
            kind = -1;
            why = null;
        } else if (path.contains("hair") || path.contains("beard")) {
            kind = 0;
            why = "hair, left as it is";
        } else if (path.endsWith("_lhand") || path.endsWith("_rhand")) {
            kind = 0;
            why = "a bag carried in the hand, left as it is";
        } else if (skipped(path)) {
            kind = 0;
            why = "on the skip list (RealBodyShape.ROUGH.skip), left as it is";
        } else if (known.contains(path)) {
            kind = 0;
            why = "the mod has its data";
        } else {
            kind = 1;
            why = "no data - fitted roughly";
        }
        pieces.put(data, new Object[]{ kind, now + 3_000_000_000L });
        if (why != null && logs < 24) {
            logs++;
            RBSHook.log("rough fit: " + path + ": " + why);
        }
        return kind == 1;
    }

    /** on the mod's skip list: the whole normalised path, or its last parts ("skirt_long_f", "skirts_long/skirt_long_f") */
    static boolean skipped(String path) {
        if (path == null) return false;
        for (String s : skip) {
            if (path.equals(s) || path.endsWith("/" + s)) return true;
        }
        return false;
    }

    /** the mod has its own data for this model (its files already have the shape) */
    static boolean known(String path) {
        return path != null && known.contains(path);
    }

    /** the files read again when due (for Fit: the skip list and the known models also hold for zombies) */
    static void refresh() {
        if (!ready) init();
        if (!failed && System.nanoTime() >= nextCheck) load();
    }

    // ---------------- maps ----------------

    @SuppressWarnings("unchecked")
    private static float[][] mapsFor(Object player, Object data, int sex) throws Exception {
        int key = version * 2 + sex;
        Object[] c = mapsCache.get(data);
        if (c != null && (Integer) c[0] == key) return (float[][]) c[1];
        float[][][] table = PlayerFitTable.DELTA[sex];
        float[] p = params;
        Map<String, float[]> byName = new HashMap<>();
        for (int b = 0; b < FitTable.BONES.length; b++) {
            float[] a = IDENTITY.clone();
            boolean any = false;
            for (int k = 0; k < p.length && k < table.length; k++) {
                if (p[k] == 0f || table[k] == null || b >= table[k].length || table[k][b] == null) continue;
                float[] d = table[k][b];
                for (int j = 0; j < 12; j++) a[j] += p[k] * d[j];
                any = true;
            }
            if (any && changes(a)) byName.put(FitTable.BONES[b], a);
        }
        for (int i = 0; i + 1 < FitTable.ALIAS.length; i += 2) {
            float[] src = byName.get(FitTable.ALIAS[i + 1]);
            if (src != null) byName.put(FitTable.ALIAS[i], src);
        }
        float[][] m = null;
        Map<String, Integer> names = (Map<String, Integer>) fBoneIndices.get(data);
        if (!byName.isEmpty() && names != null) {
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
            if (!any) m = null;
        }
        if (m != null) {
            // other mods' meshes in a space of their own (FBX x100...), and the guards (an odd rig, a piece far off the body)
            m = MeshSpace.convert(player, data, m, "rough fit: " + MeshInfo.path(player, data), MeshInfo.box(player, data));
            if (m == null) return null;                          // not yet: try again next time
            if (m.length == 0) m = null;
        }
        mapsCache.put(data, new Object[]{ key, m });
        return m;
    }

    /** not the identity (a change of at least 0.1 mm / 0.1 %) */
    private static boolean changes(float[] a) {
        for (int j = 0; j < 12; j++) if (Math.abs(a[j] - IDENTITY[j]) > 1e-4f) return true;
        return false;
    }

    // ---------------- files ----------------

    private static synchronized void load() {
        long now = System.nanoTime();
        if (now < nextCheck) return;
        nextCheck = now + 2_000_000_000L;
        try {
            Path dir = Paths.get(System.getProperty("user.home"), "Zomboid", "Lua");
            Path cfg = dir.resolve(CONFIG), stamp = dir.resolve(STAMP);
            long cm = Files.exists(cfg) ? Files.getLastModifiedTime(cfg).toMillis() : 0;
            long sm = Files.exists(stamp) ? Files.getLastModifiedTime(stamp).toMillis() : 0;
            if (cm == cfgMtime && sm == stampMtime) return;
            boolean cfgChanged = cm != cfgMtime;
            cfgMtime = cm;
            stampMtime = sm;
            boolean wasOn = on;
            if (cfgChanged) {
                boolean r = false, f = true;
                Set<String> k = new HashSet<>(), sk = new HashSet<>();
                if (cm > 0 && cm + 5000 >= liveSince) {           // written in this session: the mod is on
                    for (String line : Files.readAllLines(cfg, StandardCharsets.UTF_8)) {
                        int eq = line.indexOf('=');
                        if (eq < 0 || line.trim().startsWith("#")) continue;
                        String key = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                        if (key.equals("rough")) r = v.equals("1") || v.equalsIgnoreCase("true");
                        else if (key.equals("female")) f = !(v.equals("0") || v.equalsIgnoreCase("false"));
                        else if (key.equals("known") && !v.isEmpty()) k.add(MeshInfo.norm(v));
                        else if (key.equals("skip") && !v.isEmpty()) sk.add(MeshInfo.norm(v));
                    }
                }
                known = k;
                skip = sk;
                femaleOn = f;
                on = r;
                pieces.clear();
                if (on) RBSHook.log("rough fit: on - " + k.size() + " model(s) have the mod's own data, other 3D clothes on the player are fitted roughly"
                        + (f ? "" : " (men only: women keep another mod's body)") + (sk.isEmpty() ? "" : ", skip list: " + sk));
                else if (wasOn || cm > 0) RBSHook.log("rough fit: off" + (cm > 0 && cm + 5000 < liveSince ? " (the mod did not start this session)" : ""));
            }
            float[] p = new float[PlayerFitTable.KEYS.length];
            if (sm > 0) {
                Map<String, Float> v = new HashMap<>();
                for (String line : Files.readAllLines(stamp, StandardCharsets.UTF_8)) {
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    try {
                        v.put(line.substring(0, eq).trim(), Float.parseFloat(line.substring(eq + 1).trim()));
                    } catch (NumberFormatException ignored) {}
                }
                for (int i = 0; i < p.length; i++) {
                    String key = PlayerFitTable.KEYS[i];
                    float amount = v.getOrDefault("amount", 0f);
                    if (key.equals("fat")) p[i] = Math.max(amount, 0f);
                    else if (key.equals("thin")) p[i] = Math.max(-amount, 0f);
                    else p[i] = v.getOrDefault(key, 0f);
                }
                if (on) RBSHook.log(String.format(Locale.ROOT, "rough fit: shape %.1f kg (amount %.4f, belly %.4f, chest %.4f, arms %.4f, legs %.4f, bust %.4f, hips %.4f, waist %.4f)",
                        v.getOrDefault("weight", 0f), v.getOrDefault("amount", 0f), p[2], p[3], p[4], p[5], p[6], p[7], p[8]));
            }
            params = p;
            version++;
        } catch (Throwable e) {
            RBSHook.warn("rough fit: could not read the mod's files: " + e);
        }
    }

    private static void summary() {
        fitted++;
        long now = System.nanoTime();
        if (now < summaryAt) return;
        summaryAt = now + 60_000_000_000L;
        RBSHook.log("rough fit: " + fitted + " clothing pose(s) fitted on players so far");
    }

    // ---------------- setup ----------------

    private static synchronized void init() {
        if (ready || failed) return;
        try {
            if (!RBSHook.ensureInit()) throw new IllegalStateException("RBSHook not ready");
            ClassLoader cl = PlayerFit.class.getClassLoader();
            Class<?> ap = Class.forName("zombie.core.skinnedmodel.animation.AnimationPlayer", false, cl);
            Class<?> igc = Class.forName("zombie.characters.IsoGameCharacter", false, cl);
            Class<?> inst = Class.forName("zombie.core.skinnedmodel.model.ModelInstance", false, cl);
            Class<?> model = Class.forName("zombie.core.skinnedmodel.model.Model", false, cl);
            Class<?> mesh = Class.forName("zombie.core.skinnedmodel.model.ModelMesh", false, cl);
            Class<?> sd = Class.forName("zombie.core.skinnedmodel.model.SkinningData", false, cl);
            Class<?> mm = Class.forName("zombie.core.skinnedmodel.ModelManager", false, cl);
            playerClass = Class.forName("zombie.characters.IsoPlayer", false, cl);
            apCharacter = ap.getMethod("getIsoGameCharacter");
            chrFemale = igc.getMethod("isFemale");
            chrAnimal = igc.getMethod("isAnimal");
            chrGetModel = igc.getMethod("getModel");
            fInstModel = inst.getField("model");
            fModelMesh = model.getField("mesh");
            fMeshSkinning = mesh.getField("skinningData");
            fBoneIndices = sd.getField("boneIndices");
            modelManager = mm.getField("instance").get(null);
            fFemaleBody = mm.getField("femaleModel");
            fMaleBody = mm.getField("maleModel");
            long start = System.currentTimeMillis();
            try {
                start = java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
            } catch (Throwable ignored) {}
            liveSince = start;
            ready = true;
            RBSHook.log("rough fit ready - the player's 3D clothes without the mod's data follow the body roughly");
        } catch (Throwable e) {
            failed = true;
            RBSHook.warn("rough fit init failed, clothes without data stay as they are: " + e);
        }
    }
}
