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
 * and known=<model> for every model it has data for (those are left alone, they already have the shape); while
 * Tomb's Player Body is worn, tomb= / tombgain= lines carry the Tomb add-on's own table (in the game's frame), used
 * instead of PlayerFitTable for every piece then.
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
    /** RealBodyShape_HD draws the body (hd=1): add what its rounder skin needs on top (PlayerFitTableHD) */
    private static volatile boolean hdOn;
    /**
     * Tomb's Player Body is worn: it grows otherwise than the game's body PlayerFitTable is fitted to (a woman's calves
     * and thighs with its fill morph), so the Tomb add-on brings a table of its own, fitted to Tomb's body and its skin
     * weights (rough_tomb.py), passed on by the mod in the fit file as tomb=Female|Male key bone 12 numbers and
     * tombgain=amount,gain,... (the fill morph's gain). Used for every piece while Tomb's body is worn. [sex][TOMB_KEYS][bone],
     * a sex without rows keeps PlayerFitTable.
     */
    private static volatile float[][][][] tomb = new float[2][][][];
    private static volatile float[] tombGain;
    private static final String[] TOMB_KEYS = tombKeys();
    private static volatile Set<String> known = Collections.emptySet();
    /** models never fitted, roughly or (zombies) by Fit: RealBodyShape.ROUGH.skip, a whole path or its last parts */
    private static volatile Set<String> skip = Collections.emptySet();
    private static volatile float[] params = new float[PlayerFitTable.KEYS.length];
    /**
     * Room in clothes (2026-11-20, Options > Mods > Real Body Shape): cm over each part (Allowance.REGIONS), the set of
     * the body worn as the mod writes it: allow= numbers. Every piece the player wears takes it - the ones the mod has
     * its data for too, and other mods' pieces even with the rough fit off. Asked after skin of Tomb's body showed
     * through clothes made for the game's body.
     */
    private static volatile float[] allowCm = new float[Allowance.REGIONS.length];
    /**
     * Tops over trousers or a skirt (user, 2026-11-20): the outer tops worn over a bottom (sweaters, jackets, coats,
     * vests: after it in the game's order of body locations) take the bottom's room over the lower parts on top of their
     * own, so they stay over the bottom it moved out. The mod writes allowover= (cm: the lower parts' room while trousers,
     * shorts, a skirt, a dress or a suit is worn; with underwear only or nothing below, not written) and over= the model
     * of each such top. Shirts under the bottom (tucked in) are not on the list.
     */
    private static volatile float[] overCm = new float[Allowance.REGIONS.length];
    private static volatile Set<String> over = Collections.emptySet();
    /**
     * Rigid pieces the mod knows (rigid=model|x,y,z|bone, user 2026-11-20: "กระติกไม่ขยาย", like the holsters): the room
     * moves the whole piece by the move of one point (the canteen's bottle, bind space) under its main bone - the
     * same move for every bone, so the bottle and its strap keep their size. {x, y, z, bone index}
     */
    private static volatile Map<String, float[]> anchors = Collections.emptyMap();
    /** the bottoms worn (bottom=): the extra is added only when one of them takes room itself */
    private static volatile Set<String> bottoms = Collections.emptySet();
    /**
     * Clothes made for the body worn take no room (user, 2026-11-20: the Tomb clothes already fitted to Tomb's body):
     * fits= the Tomb add-ons' models (while Tomb's body is worn), and the files of Tomb's own mods (tombMade) while
     * Tomb's table is in use. A top over a bottom that the room moved out still takes its room and the extra.
     */
    private static volatile Set<String> fits = Collections.emptySet();

    /** this piece keeps its fit: made for the body worn */
    private static boolean madeForBody(String path, int sex) {
        if (path == null) return false;
        if (fits.contains(path)) return true;
        return tomb[sex] != null && tombMade(path);
    }

    /** some bottom worn takes room (one not made for the body worn): the tops over it need the extra */
    private static boolean bottomMoved(int sex) {
        for (String b : bottoms) if (!madeForBody(b, sex)) return true;
        return false;
    }
    private static volatile int version;
    private static volatile long nextCheck;
    private static long cfgMtime = -1, stampMtime = -1, liveSince;

    /** SkinningData -> {Integer 1 = fit roughly / 0 = leave / -1 = not found yet, Long retry at} */
    private static final Map<Object, Object[]> pieces = Collections.synchronizedMap(new WeakHashMap<>());
    /** SkinningData -> {Integer version*2+sex, float[][] maps by matrix index or null} */
    private static final Map<Object, Object[]> mapsCache = Collections.synchronizedMap(new WeakHashMap<>());
    private static int logs, fitted, errors, pieceErrors;
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
        if (!on && !Allowance.any(allowCm)) return;
        Object chr = apCharacter.invoke(player);
        boolean female;
        Object inst;
        if (chr == null) {
            // a UI preview of the player (the character info window): an AnimatedModel showing that character
            Object am = previews.get(player);
            if (am == null) return;
            Object who = amCharacter.invoke(am);
            if (who == null || !playerClass.isInstance(who) || (Boolean) chrAnimal.invoke(who)) return;
            female = (Boolean) amFemale.invoke(am);
            inst = fAmInstance.get(am);
        } else {
            if (!playerClass.isInstance(chr) || (Boolean) chrAnimal.invoke(chr)) return;
            female = (Boolean) chrFemale.invoke(chr);
            inst = chrGetModel.invoke(chr);
        }
        if (female && !femaleOn) return;
        if (inst == null) return;
        Object body = fInstModel.get(inst);
        if (body == null || body != (female ? fFemaleBody : fMaleBody).get(modelManager)) return;   // another mod's body
        Object bodyMesh = fModelMesh.get(body);
        Object bodyData = bodyMesh == null ? null : fMeshSkinning.get(bodyMesh);
        if (bodyData == null || bodyData == data) return;      // still loading, or the body itself (it has the shape)
        int kind = kind(player, data);
        if (kind == 0) return;
        boolean table = kind == 1 && on;                       // the rough fit: other mods' pieces, with its mod on
        if (!table && !Allowance.any(allowCm)) return;         // else only the room in clothes, when there is any
        if (!Fit.ensureReady() || !Fit.ensureLayout(t)) return;
        float[][] maps;
        try {
            maps = mapsFor(player, data, female ? 0 : 1, table);
        } catch (Throwable e) {
            // this piece stays as it is (until the mod's files change), the others go on: one odd piece of another
            // mod must not count towards switching the whole rough fit off
            pieces.put(data, new Object[]{ 0, Long.MAX_VALUE });
            if (pieceErrors++ < 10) {
                String what;
                try {
                    what = MeshInfo.path(player, data);
                } catch (Throwable ignored) {
                    what = "a piece";
                }
                RBSHook.warn("rough fit: " + what + ": " + e + " - left as it is");
            }
            return;
        }
        if (maps == null) return;
        if (Fit.applyMaps(t, maps)) summary();
    }

    // ---------------- which pieces ----------------

    /** 1 = fitted roughly (+ the room in clothes), 2 = the mod has its data (the room in clothes only), 0 = left as it is */
    private static int kind(Object player, Object data) throws Exception {
        long now = System.nanoTime();
        Object[] e = pieces.get(data);
        if (e != null && ((Integer) e[0] >= 0 || now < (Long) e[1])) return Math.max((Integer) e[0], 0);
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
        } else if (known.contains(path) && otherModel(path) == null) {
            kind = 2;
            why = "the mod has its data";
        } else if (known.contains(path)) {
            // the game loads <name>.fbx / .glb before <name>.x: another mod's file of that name (Tomb's Player Body -
            // Compatibility's trousers, shorts and skirt for Tomb's body) is what is worn, never the .x the mod reshapes
            kind = 1;
            why = "another mod's file is worn instead of the one the mod reshapes (" + otherModel(path) + ") - fitted roughly";
        } else {
            kind = 1;
            why = "no data - fitted roughly";
        }
        if (kind == 1 && tombMade(path)) why += " (made for Tomb's body)";
        if (kind == 1 && tight(path)) why += ", worn tight: fitted closer (x" + TIGHT + ")";
        pieces.put(data, new Object[]{ kind, now + 3_000_000_000L });
        if (why != null && logs < 24) {
            logs++;
            RBSHook.log("rough fit: " + path + ": " + why);
        }
        return Math.max(kind, 0);
    }

    /** on the mod's skip list: the whole normalised path, or its last parts ("skirt_long_f", "skirts_long/skirt_long_f") */
    static boolean skipped(String path) {
        if (path == null) return false;
        for (String s : skip) {
            if (path.equals(s) || path.endsWith("/" + s)) return true;
        }
        return false;
    }

    /** path -> the file the game loads for it (forward slashes), "" when it cannot be told */
    private static final Map<String, String> files = new java.util.concurrent.ConcurrentHashMap<>();
    private static Object fileSystem;
    private static Method fsGetString;

    /**
     * The file the game loads for this model: it tries .fbx, then .glb, then .x (FileTask_AbstractLoadModel
     * .checkExtensionType), each through the mods' files first (ZomboidFileSystem.getString). Null when none is found or
     * the game cannot be asked.
     */
    static String modelFile(String path) {
        if (path == null || fsGetString == null) return null;
        String v = files.get(path);
        if (v == null) {
            v = "";
            for (String ext : new String[]{ ".fbx", ".glb", ".x" }) {
                try {
                    Object r = fsGetString.invoke(fileSystem, "media/models_X/" + path + ext);
                    if (!(r instanceof String)) continue;
                    java.io.File f = new java.io.File((String) r);
                    if (!f.isFile()) continue;
                    v = f.getAbsolutePath().replace('\\', '/');
                    break;
                } catch (Throwable ignored) {
                    // cannot tell: treated as before (the mod's own file, the game body's table)
                }
            }
            files.put(path, v);
        }
        return v.isEmpty() ? null : v;
    }

    /**
     * Another mod's .fbx / .glb of this model, which the game loads instead of the .x the mod reshapes, or null. A file
     * in one of this mod's own folders (RealBodyShape...) is ours: the Tomb add-ons write their .fbx pieces there.
     */
    static String otherModel(String path) {
        String f = modelFile(path);
        if (f == null) return null;
        String low = f.toLowerCase(Locale.ROOT);
        if (low.endsWith(".x") || low.matches(".*/realbodyshape[^/]*/.*")) return null;
        return f;
    }

    /**
     * A piece made for Tomb's Player Body: its file is in one of Tomb's mods (Tomb's Player Body - Compatability, its
     * Boots Expanded parts...): said in the log (every piece follows Tomb's own table while Tomb's body is worn).
     */
    static boolean tombMade(String path) {
        String f = modelFile(path);
        return f != null && f.toLowerCase(Locale.ROOT).contains("/tomb's player body");
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
    private static float[][] mapsFor(Object player, Object data, int sex, boolean useTable) throws Exception {
        int key = version * 4 + sex * 2 + (useTable ? 1 : 0);
        Object[] c = mapsCache.get(data);
        if (c != null && (Integer) c[0] == key && System.nanoTime() < (Long) c[2]) return (float[][]) c[1];
        // Tomb's body worn (the mod sends its table only then): Tomb's own table for every piece - the ones made for
        // Tomb's body and the ones made on the game's body alike follow how Tomb's body grows (offline, a 115 kg
        // woman: the game's trousers 104 skin points out with the game body's table, 26 with Tomb's; Compatibility's
        // long shorts 93 / 10 - rough_tomb.py, 2026-11-18)
        float[][][] tombTable = tomb[sex];
        float[][][] table = tombTable != null ? tombTable : PlayerFitTable.DELTA[sex];
        float[] p = params;
        if (tombTable != null) {                                  // its keys: PlayerFitTable.KEYS, then fill
            float[] q = new float[TOMB_KEYS.length];
            System.arraycopy(p, 0, q, 0, Math.min(p.length, q.length));
            q[TOMB_KEYS.length - 1] = gain(tombGain, p[0] - p[1]);
            p = q;
        }
        // a small piece worn tight (a belt, a holster...) gets less of the tables' room
        String path = MeshInfo.path(player, data);
        float f = tight(path) ? TIGHT : 1f;
        Map<String, float[]> byName = new HashMap<>();
        float[] room = allowCm;
        if (path != null && over.contains(path) && Allowance.any(overCm) && bottomMoved(sex)) {   // a top over a moved bottom
            room = room.clone();
            for (int i = 0; i < room.length && i < overCm.length; i++) room[i] += overCm[i];
        } else if (madeForBody(path, sex)) {
            room = null;                                          // made for this body: as it is
        }
        // a rigid piece (a canteen): its middle moves out, the piece is not stretched (only for a mesh in the game's own
        // frame - another mod's in a space of its own would get the move of the wrong point)
        float[] mid = null, whole = null;
        float[] anc = path == null ? null : anchors.get(path);
        if (room != null && anc != null && Allowance.any(room)) {
            whole = Allowance.moveAt(sex, (int) anc[3], room, new float[]{ anc[0], anc[1], anc[2] });
            if (whole == null) room = null;                       // nothing moves that point: as it is
        } else if (room != null && rigid(path) && Allowance.any(room)) {
            float[] bx = MeshInfo.box(player, data);
            String file = modelFile(path);
            if (bx != null && (file == null || file.toLowerCase(Locale.ROOT).endsWith(".x")))
                mid = new float[]{ (bx[0] + bx[3]) / 2, (bx[1] + bx[4]) / 2, (bx[2] + bx[5]) / 2 };
        }
        for (int b = 0; b < FitTable.BONES.length; b++) {
            float[] a = IDENTITY.clone();
            boolean any = false;
            if (useTable) {
                for (int k = 0; k < p.length && k < table.length; k++) {
                    if (p[k] == 0f || table[k] == null || b >= table[k].length || table[k][b] == null) continue;
                    float[] d = table[k][b];
                    for (int j = 0; j < 12; j++) a[j] += f * p[k] * d[j];
                    any = true;
                }
                if (hdOn && tombTable == null && addHD(a, sex, b, p)) any = true;
            }
            // the room in clothes, on top, whole (a belt over trousers moves out with them, tight or not)
            if (whole != null) {                                  // a rigid piece: the same move for every bone
                a[3] += whole[0];
                a[7] += whole[1];
                a[11] += whole[2];
                any = true;
            } else if (room != null && (mid != null ? Allowance.addAt(a, sex, b, room, mid) : Allowance.add(a, sex, b, room))) any = true;
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
        // the model's name not found yet: asked again in a second (not every frame, should it never be found)
        mapsCache.put(data, new Object[]{ key, m, path != null ? Long.MAX_VALUE : System.nanoTime() + 1_000_000_000L });
        return m;
    }

    /**
     * Small pieces worn tight on the body, by the model's file name: belts, chokers and necklaces, holsters, fanny
     * packs and pouches, straps, harnesses, webbing, bandoliers, suspenders, garters, bracelets. The tables' room
     * (boost 1.2, for clothes over the skin) put them too far out: another mod's belt (Frockin Splendor Vol.6
     * Belt_Deco_F) sat 1.9 cm off the waist at 85 kg where it was made 0.8 cm off - "a purple belt looks a bit thick"
     * (user, 2026-11-19; Silver Chariot's bags and holsters floated, another mod's). x0.8: 1.2 cm at 85 kg, 1.1 at 110
     * (was 2.05), no skin out up to 110 kg, at 130 kg no worse than before (scratchpad belt_scale.py). The HD body's
     * extra room stays whole (its skin is rounder).
     */
    static final float TIGHT = 0.8f;
    static final String[] TIGHT_WORDS = { "belt", "choker", "necklace", "pendant", "holster", "fanny", "pouch", "strap",
            "harness", "webbing", "bandolier", "suspender", "garter", "bracelet" };
    /** names with those words that are not small tight pieces (a belted coat, a strapless dress) */
    static final String[] NOT_TIGHT = { "belted", "strapless" };

    /** one rigid= line (model|x,y,z|bone name) into an; a line that does not read is left out */
    private static void rigidRow(String v, Map<String, float[]> an) {
        try {
            String[] f = v.split("\\|");
            if (f.length < 3) return;
            String[] c = f[1].split(",");
            int bone = indexOf(FitTable.BONES, f[2].trim());
            if (c.length < 3 || bone < 0) return;
            an.put(MeshInfo.norm(f[0].trim()), new float[]{ Float.parseFloat(c[0].trim()), Float.parseFloat(c[1].trim()),
                    Float.parseFloat(c[2].trim()), bone });
        } catch (NumberFormatException ignored) {}
    }

    /** rigid pieces that the room in clothes moves whole (as export_datasets' RIGID_ITEMS: the canteen) */
    static final String[] RIGID_WORDS = { "canteen", "flask", "thermos", "bottle" };

    static boolean rigid(String path) {
        if (path == null) return false;
        String n = path.toLowerCase(Locale.ROOT);
        int s = n.lastIndexOf('/');
        if (s >= 0) n = n.substring(s + 1);
        for (String w : RIGID_WORDS) if (n.contains(w)) return true;
        return false;
    }

    static boolean tight(String path) {
        if (path == null) return false;
        String n = path.toLowerCase(Locale.ROOT).replace('\\', '/');
        int s = n.lastIndexOf('/');
        if (s >= 0) n = n.substring(s + 1);
        for (String w : NOT_TIGHT) if (n.contains(w)) return false;
        for (String w : TIGHT_WORDS) if (n.contains(w)) return true;
        return false;
    }

    /**
     * The HD body stands out of the low-poly one the tables above fit to (facets rounded out, round legs, a heavy
     * woman's inner thighs filled): its extra maps, base once, a shape parameter by its value, fill by the fill gain of
     * the amount. True when anything was added to a.
     */
    private static boolean addHD(float[] a, int sex, int bone, float[] p) {
        float[][][] table = PlayerFitTableHD.DELTA[sex];
        float amount = p[0] - p[1];                               // PlayerFitTable.KEYS: fat, thin, ...
        boolean any = false;
        for (int k = 0; k < PlayerFitTableHD.KEYS.length && k < table.length; k++) {
            String key = PlayerFitTableHD.KEYS[k];
            float c;
            if (key.equals("base")) c = 1f;
            else if (key.equals("fill")) c = fillGain(amount);
            else {
                int i = indexOf(PlayerFitTable.KEYS, key);
                c = i < 0 || i >= p.length ? 0f : p[i];
            }
            if (c == 0f || table[k] == null || bone >= table[k].length || table[k][bone] == null) continue;
            float[] d = table[k][bone];
            for (int j = 0; j < 12; j++) a[j] += c * d[j];
            any = true;
        }
        return any;
    }

    /** the HD add-on's inner thigh fill: none below the first amount, all from the last, straight lines between */
    static float fillGain(float amount) {
        return gain(PlayerFitTableHD.FILL_GAIN, amount);
    }

    /** a gain by the amount from pairs amount, gain, ... (straight lines between, flat outside); 0 without pairs */
    static float gain(float[] g, float amount) {
        if (g == null || g.length < 2) return 0f;
        if (amount <= g[0]) return g[1];
        for (int i = 2; i + 1 < g.length; i += 2) {
            if (amount <= g[i]) return g[i - 1] + (g[i + 1] - g[i - 1]) * (amount - g[i - 2]) / (g[i] - g[i - 2]);
        }
        return g[g.length - 1];
    }

    private static String[] tombKeys() {
        String[] k = java.util.Arrays.copyOf(PlayerFitTable.KEYS, PlayerFitTable.KEYS.length + 1);
        k[k.length - 1] = "fill";
        return k;
    }

    /** one tomb= row of the fit file into t[sex][key][bone]; false when it does not read */
    private static boolean tombRow(String v, float[][][][] t) {
        String[] f = v.trim().split("\\s+");
        if (f.length != 15) return false;
        int sex = f[0].equals("Female") ? 0 : f[0].equals("Male") ? 1 : -1;
        int key = indexOf(TOMB_KEYS, f[1]), bone = indexOf(FitTable.BONES, f[2]);
        if (sex < 0 || key < 0 || bone < 0) return false;
        float[] d = new float[12];
        try {
            for (int j = 0; j < 12; j++) d[j] = Float.parseFloat(f[3 + j]);
        } catch (NumberFormatException e) {
            return false;
        }
        for (float x : d) if (Float.isNaN(x) || Float.isInfinite(x) || Math.abs(x) > 1000f) return false;
        if (t[sex] == null) t[sex] = new float[TOMB_KEYS.length][FitTable.BONES.length][];
        t[sex][key][bone] = d;
        return true;
    }

    private static float[] gainPairs(String v) {
        String[] f = v.split(",");
        if (f.length < 2 || f.length % 2 != 0) return null;
        float[] g = new float[f.length];
        try {
            for (int i = 0; i < f.length; i++) g[i] = Float.parseFloat(f[i].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        for (int i = 2; i < g.length; i += 2) if (!(g[i] > g[i - 2])) return null;     // amounts must rise
        return g;
    }

    /** allow=belly,waist,hips,butt,thighs,calves (cm, 0 .. 5 each; a bad number counts as 0) */
    private static float[] allowValues(String v) {
        float[] out = new float[Allowance.REGIONS.length];
        String[] parts = v.split(",");
        for (int i = 0; i < parts.length && i < out.length; i++) {
            try {
                float x = Float.parseFloat(parts[i].trim());
                out[i] = Float.isFinite(x) ? Math.max(0f, Math.min(5f, x)) : 0f;
            } catch (NumberFormatException ignored) {
                out[i] = 0f;
            }
        }
        return out;
    }

    private static int indexOf(String[] keys, String key) {
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(key)) return i;
        return -1;
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
        // two file dates twice a second: other mods' clothes follow a new body within 0.5 s (2 s before; the
        // test weight key, Ctrl+Shift+F7, rebuilds and reloads the body at once - 2026-11-18)
        nextCheck = now + 500_000_000L;
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
                boolean r = false, f = true, h = false;
                Set<String> k = new HashSet<>(), sk = new HashSet<>();
                float[][][][] tt = new float[2][][][];
                float[] tg = null;
                float[] al = new float[Allowance.REGIONS.length], ao = new float[Allowance.REGIONS.length];
                Set<String> ov = new HashSet<>(), bo = new HashSet<>(), fi = new HashSet<>();
                Map<String, float[]> an = new HashMap<>();
                int tRows = 0, tBad = 0;
                if (cm > 0 && cm + 5000 >= liveSince) {           // written in this session: the mod is on
                    for (String line : Files.readAllLines(cfg, StandardCharsets.UTF_8)) {
                        int eq = line.indexOf('=');
                        if (eq < 0 || line.trim().startsWith("#")) continue;
                        String key = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                        if (key.equals("rough")) r = v.equals("1") || v.equalsIgnoreCase("true");
                        else if (key.equals("female")) f = !(v.equals("0") || v.equalsIgnoreCase("false"));
                        else if (key.equals("hd")) h = v.equals("1") || v.equalsIgnoreCase("true");
                        else if (key.equals("known") && !v.isEmpty()) k.add(MeshInfo.norm(v));
                        else if (key.equals("skip") && !v.isEmpty()) sk.add(MeshInfo.norm(v));
                        else if (key.equals("tomb")) {
                            if (tombRow(v, tt)) tRows++;
                            else tBad++;
                        } else if (key.equals("tombgain")) tg = gainPairs(v);
                        else if (key.equals("allow")) al = allowValues(v);
                        else if (key.equals("allowover")) ao = allowValues(v);
                        else if (key.equals("over") && !v.isEmpty()) ov.add(MeshInfo.norm(v));
                        else if (key.equals("bottom") && !v.isEmpty()) bo.add(MeshInfo.norm(v));
                        else if (key.equals("fits") && !v.isEmpty()) fi.add(MeshInfo.norm(v));
                        else if (key.equals("rigid")) rigidRow(v, an);
                    }
                }
                anchors = an;
                allowCm = al;
                overCm = ao;
                over = ov;
                bottoms = bo;
                fits = fi;
                if (Allowance.any(al)) {
                    StringBuilder sb = new StringBuilder("room in clothes:");
                    for (int i = 0; i < al.length; i++) sb.append(String.format(Locale.ROOT, " %s %.1f cm", Allowance.REGIONS[i], al[i]));
                    if (!ov.isEmpty() && Allowance.any(ao)) sb.append(", tops over the trousers / skirt get its room too: ").append(ov)
                            .append(" (bottoms ").append(bo).append(")");
                    if (!fi.isEmpty()) sb.append(", ").append(fi.size()).append(" model(s) made for the body worn keep their fit");
                    RBSHook.log(sb.toString());
                }
                known = k;
                skip = sk;
                femaleOn = f;
                hdOn = h;
                tomb = tt;
                tombGain = tg;
                on = r;
                pieces.clear();
                if (on) RBSHook.log("rough fit: on - " + k.size() + " model(s) have the mod's own data, other 3D clothes on the player are fitted roughly"
                        + (f ? "" : " (men only: women keep another mod's body)") + (sk.isEmpty() ? "" : ", skip list: " + sk)
                        + (h ? ", HD body: a little more room for its rounder skin" : "")
                        + (tRows > 0 ? ", Tomb's body: its own table (" + (tt[0] != null ? "women" : "") + (tt[0] != null && tt[1] != null ? " and " : "")
                            + (tt[1] != null ? "men" : "") + ", " + tRows + " rows" + (tg != null ? ", fill by the amount" : "") + ")" : "")
                        + (tBad > 0 ? " - " + tBad + " Tomb table row(s) did not read and were left out" : ""));
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

    // ---------------- UI previews ----------------
    // The character info window draws the player with an AnimatedModel of its own: its AnimationPlayer has no
    // character, so without this the clothes fitted roughly in the world kept their own size there (user, 2026-11-14).
    // RBSHook.afterAnimatedPlayer tells which AnimatedModel an AnimationPlayer belongs to; only previews whose
    // character is a player are fitted (the corpse atlas's have none).

    /** AnimationPlayer -> the AnimatedModel it animates */
    private static final Map<Object, Object> previews = Collections.synchronizedMap(new WeakHashMap<>());
    private static Method amCharacter, amFemale;
    private static Field fAmInstance;

    static void rememberPreview(Object player, Object model) {
        if (player == null || model == null || failed) return;
        try {
            if (amCharacter == null) {
                Class<?> c = model.getClass();
                amCharacter = c.getMethod("getCharacter");
                amFemale = c.getMethod("isFemale");
                Field f = c.getDeclaredField("modelInstance");
                f.setAccessible(true);
                fAmInstance = f;
            }
            if (previews.get(player) != model) previews.put(player, model);
        } catch (Throwable e) {
            RBSHook.warn("rough fit: UI previews left as they are: " + e);
            amCharacter = null;
        }
    }

    /** the AnimatedModel this AnimationPlayer animates, or null (for Hair: the previews of character creation...) */
    static Object previewModel(Object player) {
        return player == null ? null : previews.get(player);
    }

    /** the model instance of the player's UI preview this AnimationPlayer animates, or null (for MeshInfo) */
    static Object previewInstance(Object player) {
        Object am = player == null ? null : previews.get(player);
        if (am == null || fAmInstance == null) return null;
        try {
            Object who = amCharacter.invoke(am);
            return who != null && playerClass != null && playerClass.isInstance(who) ? fAmInstance.get(am) : null;
        } catch (Throwable e) {
            return null;
        }
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
            try {
                Class<?> zfs = Class.forName("zombie.ZomboidFileSystem", false, cl);
                fileSystem = zfs.getField("instance").get(null);
                fsGetString = zfs.getMethod("getString", String.class);
            } catch (Throwable e) {
                RBSHook.warn("rough fit: cannot see which file the game loads for a model, the mod's own data is trusted: " + e);
            }
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
