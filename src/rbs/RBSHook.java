package rbs;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.Objects;

/**
 * Targets of the two patched game methods (see RBSAgent.SPECS).
 * Zombies (not skeletons, not animals) get one of the body variants defined by the RealBodyShapeZombies mod
 * (model scripts RBS_Zombie{Female,Male}_{Thin,Normal,Fat,Obese}). The variant is a stable hash of the zombie's
 * HumanVisual (skin, hair, beard, hair colour, outfit) so a corpse keeps the body it had while "alive":
 * DeadBodyAtlas renders corpses from a copy of that visual, where no character id is available.
 * Weights come from ~/Zomboid/Lua/RealBodyShape_zombies.txt (written by the mod's Lua).
 * Everything else, and every failure, falls through to the original methods.
 * Only public game members are used, through reflection, so nothing here depends on game internals.
 */
public final class RBSHook {
    static final String[] NAMES = { "Thin", "Normal", "Fat", "Obese" };
    static final String CONFIG = "RealBodyShape_zombies.txt";

    private static volatile boolean ready, failed;
    private static Method isAnimal, isZombie, isSkeleton, chrVisual, getLoadedModel, getModelScript;
    private static Method vIsZombie, vIsSkeleton, vIsFemale, vSkin, vBodyHair, vHairModel, vBeardModel, vHairColor, vOutfit, outfitName;
    private static Field fR, fG, fB, fFemaleModel, fMaleModel;
    private static Object scriptManager, modelManager;
    private static Class<?> zombieClass, visualClass;
    private static Method origBody, origVisual;

    private static volatile boolean enabled = true;
    private static volatile int[] weights = { 15, 55, 22, 8 };
    private static long cfgMtime = -1, cfgNextCheck;
    private static int logged;

    private RBSHook() {}

    // ---------------- ModelManager.getBodyModel(chr) ----------------
    public static Object getBodyModel(Object mgr, Object chr) {
        Object m = null;
        try {
            m = zombieModel(chr);
        } catch (Throwable t) {
            warn("zombie body failed: " + t);
        }
        if (m != null) return m;
        return call(origBody(mgr), mgr, chr);
    }

    // ---------------- HumanVisual.getModel() ----------------
    public static Object visualModel(Object visual) {
        Object orig = call(origVisual(visual), visual);
        try {
            Object m = corpseModel(visual, orig);
            if (m != null) return m;
        } catch (Throwable t) {
            warn("corpse body failed: " + t);
        }
        return orig;
    }

    private static Object call(Method m, Object target, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw RBSHook.<RuntimeException>sneaky(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("RBS hook: cannot call " + m, e);
        }
    }

    private static Method origBody(Object mgr) {
        Method o = origBody;
        if (o == null) {
            try {
                Class<?> igc = Class.forName("zombie.characters.IsoGameCharacter", false, mgr.getClass().getClassLoader());
                o = mgr.getClass().getMethod(RBSAgent.RENAMED_BODY, igc);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("RBS hook: original getBodyModel missing", e);
            }
            origBody = o;
        }
        return o;
    }

    private static Method origVisual(Object visual) {
        Method o = origVisual;
        if (o == null) {
            try {
                o = visual.getClass().getMethod(RBSAgent.RENAMED_VISUAL);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("RBS hook: original HumanVisual.getModel missing", e);
            }
            origVisual = o;
        }
        return o;
    }

    private static Object zombieModel(Object chr) throws Exception {
        if (failed) return null;
        if (!ready) init();
        if (failed) return null;
        loadConfig();
        if (!enabled) return null;
        if (chr == null || !zombieClass.isInstance(chr)) return null;
        if ((Boolean) isAnimal.invoke(chr)) return null;
        if (!(Boolean) isZombie.invoke(chr)) return null;
        if ((Boolean) isSkeleton.invoke(chr)) return null;
        Object visual = chrVisual.invoke(chr);
        if (visual == null) return null;
        return variantModel((Boolean) vIsFemale.invoke(visual), visualHash(visual));
    }

    private static Object corpseModel(Object visual, Object orig) throws Exception {
        if (failed) return null;
        if (!ready) init();
        if (failed) return null;
        loadConfig();
        if (!enabled || visual == null || !visualClass.isInstance(visual)) return null;
        // only replace the shared body (forceModel / skeleton / animal results are returned by the original as-is)
        if (orig == null || (orig != fFemaleModel.get(modelManager) && orig != fMaleModel.get(modelManager))) return null;
        if (!(Boolean) vIsZombie.invoke(visual)) return null;
        if ((Boolean) vIsSkeleton.invoke(visual)) return null;
        return variantModel((Boolean) vIsFemale.invoke(visual), visualHash(visual));
    }

    private static Object variantModel(boolean female, int hash) throws Exception {
        String base = "RBS_Zombie" + (female ? "Female" : "Male") + "_" + NAMES[variant(hash)];
        String name = base;
        Object script = getModelScript.invoke(scriptManager, name);
        if (script == null) {
            name = "Base." + base;
            script = getModelScript.invoke(scriptManager, name);
        }
        if (script == null) {
            warn("model script " + base + " not found - enable the mod 'Real Body Shape - Zombie Bodies' (it comes with the Real Body Shape Workshop item)");
            return null;
        }
        return getLoadedModel.invoke(modelManager, name);
    }

    /** stable identity of a zombie that survives death: what it looks like */
    static int visualHash(Object visual) throws Exception {
        int h = 17;
        h = h * 31 + (Integer) vSkin.invoke(visual);
        h = h * 31 + (Integer) vBodyHair.invoke(visual);
        h = h * 31 + Objects.hashCode(vHairModel.invoke(visual));
        h = h * 31 + Objects.hashCode(vBeardModel.invoke(visual));
        Object col = vHairColor.invoke(visual);
        if (col != null) {
            h = h * 31 + Math.round(fR.getFloat(col) * 255);
            h = h * 31 + Math.round(fG.getFloat(col) * 255);
            h = h * 31 + Math.round(fB.getFloat(col) * 255);
        }
        Object outfit = vOutfit.invoke(visual);
        if (outfit != null) h = h * 31 + Objects.hashCode(outfitName.invoke(outfit));
        return h;
    }

    private static synchronized void init() {
        if (ready || failed) return;
        String mode = RBSAgent.agentActive ? "javaagent" : "classpath";
        try {
            ClassLoader cl = RBSHook.class.getClassLoader();
            Class<?> igc = Class.forName("zombie.characters.IsoGameCharacter", false, cl);
            zombieClass = Class.forName("zombie.characters.IsoZombie", false, cl);
            visualClass = Class.forName("zombie.core.skinnedmodel.visual.HumanVisual", false, cl);
            Class<?> mm = Class.forName("zombie.core.skinnedmodel.ModelManager", false, cl);
            Class<?> sm = Class.forName("zombie.scripting.ScriptManager", false, cl);
            Class<?> color = Class.forName("zombie.core.ImmutableColor", false, cl);
            Class<?> outfit = Class.forName("zombie.core.skinnedmodel.population.Outfit", false, cl);
            isAnimal = igc.getMethod("isAnimal");
            isZombie = igc.getMethod("isZombie");
            isSkeleton = zombieClass.getMethod("isSkeleton");
            chrVisual = zombieClass.getMethod("getHumanVisual");
            vIsZombie = visualClass.getMethod("isZombie");
            vIsSkeleton = visualClass.getMethod("isSkeleton");
            vIsFemale = visualClass.getMethod("isFemale");
            vSkin = visualClass.getMethod("getSkinTextureIndex");
            vBodyHair = visualClass.getMethod("getBodyHairIndex");
            vHairModel = visualClass.getMethod("getHairModel");
            vBeardModel = visualClass.getMethod("getBeardModel");
            vHairColor = visualClass.getMethod("getHairColor");
            vOutfit = visualClass.getMethod("getOutfit");
            outfitName = outfit.getMethod("getName");
            fR = color.getField("r");
            fG = color.getField("g");
            fB = color.getField("b");
            modelManager = mm.getField("instance").get(null);
            fFemaleModel = mm.getField("femaleModel");
            fMaleModel = mm.getField("maleModel");
            getLoadedModel = mm.getMethod("getLoadedModel", String.class);
            scriptManager = sm.getField("instance").get(null);
            getModelScript = sm.getMethod("getModelScript", String.class);
            ready = true;
            String note = checkSource(cl);
            log("ready (" + mode + ") - zombies and corpses use RBS_Zombie*_{Thin,Normal,Fat,Obese} model scripts; " + note);
            Marker.write(true, mode, note);
        } catch (Throwable t) {
            failed = true;
            warn("init failed, zombies keep the shared body: " + t);
            Marker.write(false, mode, String.valueOf(t));
        }
    }

    /**
     * classpath mode: our jar shadows the game's classes. rbs/source.sha256 holds "<class>=<sha256>" lines of the
     * game classes the patch was made from; if a game copy changed (game update) the patch must be rebuilt.
     */
    private static String checkSource(ClassLoader cl) {
        try (InputStream in = RBSHook.class.getResourceAsStream("/rbs/source.sha256")) {
            if (in == null) return "no source hash (javaagent build)";
            String stale = "";
            int checked = 0;
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String res = line.substring(0, eq).trim(), expect = line.substring(eq + 1).trim();
                Enumeration<URL> urls = cl.getResources(res);
                int n = 0;
                while (urls.hasMoreElements()) {
                    URL u = urls.nextElement();
                    n++;
                    if (n == 1) continue;            // first = our patched copy
                    try (InputStream s = u.openStream()) {
                        checked++;
                        if (!sha256(s.readAllBytes()).equals(expect)) stale += " " + res;
                    }
                }
            }
            if (!stale.isEmpty()) {
                warn("the game's classes changed (game update?) but the stale patched copies are in use - run tools/bodyshape/zombie_patch.py --patch --install again:" + stale);
                return "STALE: game classes changed, rebuild the patch";
            }
            return checked == 0 ? "single copies on classpath" : "game classes match the patch source";
        } catch (Exception e) {
            return "source check failed: " + e;
        }
    }

    static String sha256(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Stable variant per zombie: mix of the visual hash, split by the configured weights. */
    static int variant(int id) {
        int[] w = weights;
        int h = id * 0x9E3779B1;
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        int sum = 0;
        for (int x : w) sum += Math.max(x, 0);
        if (sum <= 0) return 1;
        int r = Math.floorMod(h, sum);
        for (int i = 0; i < w.length; i++) {
            r -= Math.max(w[i], 0);
            if (r < 0) return i;
        }
        return 1;
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
            int[] w = weights.clone();
            boolean en = enabled;
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                try {
                    switch (k) {
                        case "enabled": en = !(v.equals("0") || v.equalsIgnoreCase("false")); break;
                        case "thin": w[0] = Integer.parseInt(v); break;
                        case "normal": w[1] = Integer.parseInt(v); break;
                        case "fat": w[2] = Integer.parseInt(v); break;
                        case "obese": w[3] = Integer.parseInt(v); break;
                        default: break;
                    }
                } catch (NumberFormatException ignored) {}
            }
            weights = w;
            enabled = en;
            log("config: enabled=" + en + " thin/normal/fat/obese=" + w[0] + "/" + w[1] + "/" + w[2] + "/" + w[3]);
        } catch (Exception e) {
            warn("config: " + e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable t) throws T {
        throw (T) t;
    }

    static void log(String s) {
        System.out.println("[RBS hook] " + s);
    }

    static void warn(String s) {
        if (logged++ < 20) System.out.println("[RBS hook] WARNING " + s);
    }
}
