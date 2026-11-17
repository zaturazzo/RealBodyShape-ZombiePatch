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
 * A mod that gives the HD body a head of its own (RealBodyShape_HD: RBS_HEAD_MODS) ships its face layer as
 * media/textures/RBSHeadFace/&lt;skin&gt;.png - used in place of ours whatever the mods' order - and the game's
 * makeup moved onto that face as media/textures/RBSHeadMakeUp/&lt;the game's file name&gt;: a character with the
 * layer gets those in place of media/textures/Body/MakeUp/... (2026-11-16, a local face mod). Without such files
 * nothing changes. With several heads (RealBodyShape_HD's Face option) the chosen one's are in sub-folders named after
 * it, and the game's face can be chosen too (Head).
 */
final class FaceLayer {
    static final String DIR = "media/textures/RBSFace/";
    static final String HEAD_DIR = "media/textures/RBSHeadFace/";
    static final String HEAD_MAKEUP = "media/textures/RBSHeadMakeUp/";
    static final String GAME_MAKEUP = "media/textures/Body/MakeUp/";
    private static final Pattern SKIN = Pattern.compile("(?i)(?:^|[/\\\\])((?:Female|Male)Body0[1-5])a?(?:\\.png)?$");

    private static volatile boolean failed, ready;
    private static Field fChrData, fBaseTexture, fNotReady, fCommands, fCatMap, fCmdTex;
    private static Method getShared, texName, texReady, texFailure, smartAdd, fsGetString, fsAbsolute, mmFlags;
    private static Object fileSystem, modelManager;
    private static Class<?> charSmartClass;
    private static final Map<String, Object> textures = new HashMap<>();   // layer path -> Texture, or MISSING
    private static final Map<String, Boolean> vanillaSkin = new HashMap<>(); // layer path -> its skin is the game's own
    private static final Map<String, String> headLayer = new HashMap<>();   // our layer path -> the one in use
    private static final Map<String, String> makeupFor = new HashMap<>();   // game makeup file (lower case) -> head's path, or ""
    private static volatile String[] makeupNames;                            // the game's makeup files (lower case)
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

    private static volatile int headGen = -1;

    /** the head choice changed (Head): forget the layers and makeup found for the old one */
    private static void headChanged() {
        int g = Head.generation();
        if (g == headGen) return;
        synchronized (headLayer) { headLayer.clear(); }
        synchronized (makeupFor) { makeupFor.clear(); }
        headGen = g;
    }

    /** the layer to draw for our layer path: the chosen head's (RBSHeadFace[/id]) when there is one */
    private static String inUse(String layer) {
        headChanged();
        if (Head.none()) return layer;
        synchronized (headLayer) {
            String p = headLayer.get(layer);
            if (p != null) return p;
            String h = HEAD_DIR + Head.sub() + layer.substring(DIR.length());
            p = exists(h) ? h : layer;
            if (p != layer) RBSHook.log("face layer: a head mod's layer in use: " + h);
            headLayer.put(layer, p);
            return p;
        }
    }

    /** the game's makeup files (lower-case names), from its MakeUp folder */
    private static String[] makeupNames() {
        String[] n = makeupNames;
        if (n != null) return n;
        java.util.List<String> list = new java.util.ArrayList<>();
        try {
            Object s = fsGetString.invoke(fileSystem, GAME_MAKEUP + "LipStick.png");
            File dir = s instanceof String ? new File((String) s).getParentFile() : null;
            File[] fs = dir == null ? null : dir.listFiles();
            if (fs != null) for (File f : fs) {
                String nm = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (nm.endsWith(".png")) list.add(nm);
            }
        } catch (Throwable t) {
            warn("makeup list failed: " + t);
        }
        makeupNames = n = list.toArray(new String[0]);
        return n;
    }

    /** the head mod's makeup for a game makeup file name (lower case), or null */
    private static String headMakeup(String lowerName) {
        headChanged();
        if (Head.none()) return null;
        String dir = HEAD_MAKEUP + Head.sub();
        synchronized (makeupFor) {
            String p = makeupFor.get(lowerName);
            if (p == null) {
                p = "";
                for (String cand : new String[]{lowerName, original(lowerName)}) {
                    if (cand != null && exists(dir + cand)) { p = dir + cand; break; }
                }
                makeupFor.put(lowerName, p);
            }
            return p.isEmpty() ? null : p;
        }
    }

    /** the file name as the game spells it (LipStick.png), from its folder */
    private static String original(String lowerName) {
        try {
            Object s = fsGetString.invoke(fileSystem, GAME_MAKEUP + "LipStick.png");
            File dir = s instanceof String ? new File((String) s).getParentFile() : null;
            File[] fs = dir == null ? null : dir.listFiles();
            if (fs != null) for (File f : fs) if (f.getName().equalsIgnoreCase(lowerName)) return f.getName();
        } catch (Throwable t) {
            warn("makeup name failed: " + t);
        }
        return null;
    }

    /** a texture name of the game's makeup ("media/textures/Body/MakeUp/x.png", any case / slashes) -> "x.png" */
    private static String makeupFile(String name) {
        if (name == null) return null;
        String n = name.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        int i = n.lastIndexOf('/');
        if (i < 0 || !n.substring(0, i).endsWith("makeup")) return null;   // "...Body/MakeUp/x" or "MakeUp/x"
        String f = n.substring(i + 1);
        if (f.isEmpty()) return null;
        return f.endsWith(".png") ? f : f + ".png";
    }

    /** after ModelInstanceTextureCreator.init(HumanVisual, ItemVisuals, ModelInstance) */
    @SuppressWarnings("unchecked")
    static void afterInit(Object creator) {
        try {
            if (!init(creator.getClass().getClassLoader())) return;
            Object data = fChrData.get(creator);
            String path = layerFor(data == null ? null : (String) fBaseTexture.get(data));
            if (path == null || !gameSkin(path)) return;
            Object tex = texture(inUse(path));
            if (tex == null || (Boolean) texFailure.invoke(tex)) return;
            List<Object> wait = (List<Object>) fNotReady.get(creator);
            if (!(Boolean) texReady.invoke(tex)) wait.add(tex);
            // a head mod's makeup: loaded here so the texture waits for it like for the game's own (the first build
            // would otherwise draw the game's makeup and keep it)
            for (String m : makeupNames()) {
                String hp = headMakeup(m);
                if (hp == null) continue;
                Object mt = texture(hp);
                if (mt != null && !(Boolean) texFailure.invoke(mt) && !(Boolean) texReady.invoke(mt)) wait.add(mt);
            }
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
            Object tex = texture(inUse(path));
            if (!usable(tex)) return;
            swapMakeup(cmds);
            // a face layer already there: this one, or another head's (the choice changed while the character's
            // texture was kept) - that one is given this layer, so two faces are never drawn one over the other
            boolean have = false;
            for (Object c : cmds) {
                Object ct = fCmdTex.get(c);
                if (ct == tex) { have = true; continue; }
                if (ct != null && faceLayerName((String) texName.invoke(ct))) {
                    fCmdTex.set(c, tex);
                    have = true;
                    if (relayers++ < 3) RBSHook.log("face layer: " + texName.invoke(ct) + " -> " + texName.invoke(tex) + " (the head was switched)");
                }
            }
            if (have) return;
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

    /** the game's makeup layers -> the head mod's (ready ones only; a missing file keeps the game's). One already
     *  moved for another head (in its own folder: the choice changed since) -> this head's, or the game's own file
     *  when the game's face is chosen */
    private static void swapMakeup(List<Object> cmds) throws Exception {
        for (Object c : cmds) {
            Object t = fCmdTex.get(c);
            if (t == null) continue;
            String name = (String) texName.invoke(t);
            String f = makeupFile(name);
            boolean moved = false;
            if (f == null) {
                f = headMakeupFile(name);
                moved = f != null;
            }
            if (f == null) continue;
            String hp = headMakeup(f);
            if (hp == null) {
                if (!moved) continue;
                String g = original(f);
                Object gt = texture(GAME_MAKEUP + (g != null ? g : f));
                if (usable(gt)) fCmdTex.set(c, gt);
                continue;
            }
            Object ht = texture(hp);
            if (ht == t) continue;
            if (usable(ht)) {
                fCmdTex.set(c, ht);
                if (swaps++ < 3) RBSHook.log("face layer: makeup " + texName.invoke(t) + " -> " + hp);
            }
        }
    }

    private static int swaps, relayers;

    /** one of the face layers: ours (RBSFace) or a head's (RBSHeadFace[/id]) */
    private static boolean faceLayerName(String name) {
        if (name == null) return false;
        String n = name.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        return n.contains("rbsface/") || n.contains("rbsheadface/");
    }

    /** a head's moved makeup ("media/textures/RBSHeadMakeUp/<id>/x.png") -> "x.png" (lower case), else null */
    private static String headMakeupFile(String name) {
        if (name == null) return null;
        String n = name.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        if (!n.contains("rbsheadmakeup/")) return null;
        String f = n.substring(n.lastIndexOf('/') + 1);
        if (f.isEmpty()) return null;
        return f.endsWith(".png") ? f : f + ".png";
    }

    private static void warn(String s) {
        if (logged++ < 5) RBSHook.log("WARNING " + s);
    }
}
