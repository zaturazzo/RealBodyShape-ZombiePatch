package rbs;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finer eyes, brows and mouth for player skins (RealBodyShape: media/textures/RBSFace/&lt;skin&gt;.png, 1024 x 1024,
 * transparent except the face). The game builds a character's texture from layers at the size of its biggest layer,
 * so the face layer makes that texture 1024 (the 256 skin is drawn into it filtered, looking as before):
 *   ModelInstanceTextureCreator.init(HumanVisual, ...) : the layer of the skin is loaded, and the texture waits for
 *                                                        it like for the game's own layers (on the first try too)
 *   SmartTexture.calculate()                           : a character texture starting with a player skin gets the
 *                                                        layer right after the skin (blood, dirt, makeup and
 *                                                        clothes still go over it)
 * Zombie skins (ZedBody) have no layer. No layer file (the mod off) -> nothing happens.
 * A skin texture another mod replaces (a body mod that ships its own FemaleBody01..05 with another layout, skin
 * retextures...) gets no layer either: it is painted for the game's own skins (2026-11-07).
 */
final class FaceLayer {
    static final String DIR = "media/textures/RBSFace/";
    private static final Pattern SKIN = Pattern.compile("(?i)(?:^|[/\\\\])((?:Female|Male)Body0[1-5])a?(?:\\.png)?$");

    private static volatile boolean failed, ready;
    private static Field fChrData, fBaseTexture, fNotReady, fCommands, fCatMap, fCmdTex;
    private static Method getShared, texName, texReady, texFailure, smartAdd, fsGetString, fsAbsolute, mmFlags;
    private static Object fileSystem, modelManager;
    private static Class<?> charSmartClass;
    private static final Map<String, Object> textures = new HashMap<>();   // layer path -> Texture, or MISSING
    private static final Map<String, Boolean> vanillaSkin = new HashMap<>(); // layer path -> its skin is the game's own
    private static final Object MISSING = new Object();
    private static int logged;

    private FaceLayer() {}

    /** the layer for a skin texture path / name ("media/textures/Body/FemaleBody01.png"), or null */
    static String layerFor(String skin) {
        if (skin == null) return null;
        Matcher m = SKIN.matcher(skin.trim());
        if (!m.find()) return null;
        String base = m.group(1);
        base = Character.toUpperCase(base.charAt(0)) + base.substring(1).toLowerCase().replace("body", "Body");
        return DIR + base + ".png";
    }

    private static boolean init(ClassLoader cl) {
        if (ready) return true;
        if (failed) return false;
        synchronized (FaceLayer.class) {
            if (ready) return true;
            try {
                Class<?> creator = Class.forName("zombie.core.skinnedmodel.model.ModelInstanceTextureCreator", false, cl);
                Class<?> chrData = Class.forName("zombie.core.skinnedmodel.model.ModelInstanceTextureCreator$CharacterData", false, cl);
                Class<?> tex = Class.forName("zombie.core.textures.Texture", false, cl);
                Class<?> smart = Class.forName("zombie.core.textures.SmartTexture", false, cl);
                Class<?> cmd = Class.forName("zombie.core.textures.TextureCombinerCommand", false, cl);
                Class<?> zfs = Class.forName("zombie.ZomboidFileSystem", false, cl);
                Class<?> mm = Class.forName("zombie.core.skinnedmodel.ModelManager", false, cl);
                charSmartClass = Class.forName("zombie.characterTextures.CharacterSmartTexture", false, cl);
                fChrData = creator.getDeclaredField("chrData");
                fChrData.setAccessible(true);
                fBaseTexture = chrData.getDeclaredField("baseTexture");
                fBaseTexture.setAccessible(true);
                fNotReady = creator.getDeclaredField("texturesNotReady");
                fNotReady.setAccessible(true);
                fCommands = smart.getField("commands");
                fCatMap = smart.getDeclaredField("categoryMap");
                fCatMap.setAccessible(true);
                fCmdTex = cmd.getField("tex");
                getShared = tex.getMethod("getSharedTexture", String.class, int.class);
                texName = tex.getMethod("getName");
                texReady = tex.getMethod("isReady");
                texFailure = tex.getMethod("isFailure");
                smartAdd = smart.getMethod("add", tex);
                fileSystem = zfs.getField("instance").get(null);
                fsGetString = zfs.getMethod("getString", String.class);
                fsAbsolute = zfs.getMethod("getAbsolutePath", String.class);
                modelManager = mm.getField("instance").get(null);
                mmFlags = mm.getMethod("getTextureFlags");
                ready = true;
                RBSHook.log("face layer: ready (" + DIR + ")");
                return true;
            } catch (Throwable t) {
                failed = true;
                RBSHook.log("face layer: off, the game's classes are not as expected: " + t);
                return false;
            }
        }
    }

    /** the layer's Texture (loading), or null when the file is not there (mod off) */
    private static Object texture(String path) throws Exception {
        synchronized (textures) {
            Object t = textures.get(path);
            if (t == MISSING) return null;
            if (t != null) return t;
            if (!exists(path)) {
                textures.put(path, MISSING);
                RBSHook.log("face layer: " + path + " not found - the skin stays as the game draws it");
                return null;
            }
            t = getShared.invoke(null, path, mmFlags.invoke(modelManager));
            textures.put(path, t == null ? MISSING : t);
            if (t != null) RBSHook.log("face layer: loading " + path);
            return t;
        }
    }

    private static boolean exists(String path) {
        try {
            for (Method m : new Method[]{fsGetString, fsAbsolute}) {
                Object s = m.invoke(fileSystem, path);
                if (s instanceof String && new File((String) s).isFile()) return true;
            }
        } catch (Throwable t) {
            warn("file check failed: " + t);
        }
        return false;
    }

    /** the skin this layer is painted for is the game's own file, not one another mod replaced (other layout) */
    private static boolean gameSkin(String layer) {
        synchronized (vanillaSkin) {
            Boolean v = vanillaSkin.get(layer);
            if (v != null) return v;
            String skin = "media/textures/Body/" + layer.substring(DIR.length());
            String at = null;
            try {
                Object s = fsGetString.invoke(fileSystem, skin);
                if (s instanceof String) at = (String) s;
            } catch (Throwable t) {
                warn("skin file check failed: " + t);
            }
            String norm = at == null ? "" : at.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
            v = at != null && !norm.contains("/mods/") && !norm.contains("/workshop/");
            vanillaSkin.put(layer, v);
            if (!v) RBSHook.log("face layer: " + skin + " comes from another mod (" + at + ") - no face layer on it");
            return v;
        }
    }

    private static boolean usable(Object tex) throws Exception {
        return tex != null && (Boolean) texReady.invoke(tex) && !(Boolean) texFailure.invoke(tex);
    }

    /** after ModelInstanceTextureCreator.init(HumanVisual, ItemVisuals, ModelInstance) */
    @SuppressWarnings("unchecked")
    static void afterInit(Object creator) {
        try {
            if (!init(creator.getClass().getClassLoader())) return;
            Object data = fChrData.get(creator);
            String path = layerFor(data == null ? null : (String) fBaseTexture.get(data));
            if (path == null || !gameSkin(path)) return;
            Object tex = texture(path);
            if (tex == null || (Boolean) texFailure.invoke(tex)) return;
            if (!(Boolean) texReady.invoke(tex)) ((List<Object>) fNotReady.get(creator)).add(tex);
        } catch (Throwable t) {
            warn("face layer (init) failed: " + t);
        }
    }

    /** before SmartTexture.calculate() */
    @SuppressWarnings("unchecked")
    static void beforeCalculate(Object smart) {
        try {
            if (!init(smart.getClass().getClassLoader()) || !charSmartClass.isInstance(smart)) return;
            List<Object> cmds = (List<Object>) fCommands.get(smart);
            if (cmds.isEmpty()) return;
            Object skin = fCmdTex.get(cmds.get(0));
            if (skin == null) return;
            String path = layerFor((String) texName.invoke(skin));
            if (path == null || !gameSkin(path)) return;
            Object tex = texture(path);
            if (!usable(tex)) return;
            for (Object c : cmds) if (fCmdTex.get(c) == tex) return;
            smartAdd.invoke(smart, tex);                       // a plain alpha-blended layer, appended ...
            Object c = cmds.remove(cmds.size() - 1);
            cmds.add(1, c);                                    // ... moved right after the skin
            Map<Integer, List<Integer>> cat = (Map<Integer, List<Integer>>) fCatMap.get(smart);
            if (cat != null) {
                for (List<Integer> l : cat.values()) {
                    for (int i = 0; i < l.size(); i++) if (l.get(i) >= 1) l.set(i, l.get(i) + 1);
                }
            }
        } catch (Throwable t) {
            warn("face layer failed: " + t);
        }
    }

    private static void warn(String s) {
        if (logged++ < 5) RBSHook.log("WARNING " + s);
    }
}
