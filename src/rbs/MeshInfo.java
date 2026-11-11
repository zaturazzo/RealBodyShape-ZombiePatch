package rbs;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Which model a skinning hook call is drawing: the skinning hook only gets the mesh's SkinningData, so the model path
 * (and the mesh, for its bounding box) is found once among a character's model instances and kept per SkinningData.
 * A clothing model's SkinningData is shared by everyone wearing it, so a corpse finds what its zombie (or anyone) wore.
 * Used by the rough fit (player) and the zombie clothes fit to know a piece by name: the mod's own data, the skip list.
 */
final class MeshInfo {
    private static volatile boolean failed, ready;
    private static Method apCharacter, chrGetModel, assetGetPath, pathGetPath;
    private static Field fInstModel, fInstSub, fLegs, fSpriteSlot, fSlotSub, fModelMesh, fMeshSkinning, fMeshFullPath;
    private static Field fMin, fMax, fX, fY, fZ;
    /** SkinningData -> {String path or null, WeakReference mesh, Long retry at (not found yet)} */
    private static final Map<Object, Object[]> byData = Collections.synchronizedMap(new WeakHashMap<>());

    private MeshInfo() {}

    /** the normalised model path of this mesh (the character's pieces are searched when not known yet), or null */
    static String path(Object player, Object data) throws Exception {
        Object[] e = entry(player, data);
        return e == null ? null : (String) e[0];
    }

    /** the mesh's bounding box in its own space {minX, minY, minZ, maxX, maxY, maxZ}, or null */
    static float[] box(Object player, Object data) throws Exception {
        Object[] e = entry(player, data);
        Object mesh = e == null ? null : ((WeakReference<?>) e[1]).get();
        if (mesh == null || fMin == null) return null;
        Object lo = fMin.get(mesh), hi = fMax.get(mesh);
        if (lo == null || hi == null) return null;
        float[] b = { fX.getFloat(lo), fY.getFloat(lo), fZ.getFloat(lo), fX.getFloat(hi), fY.getFloat(hi), fZ.getFloat(hi) };
        return b[3] >= b[0] && b[4] >= b[1] && b[5] >= b[2] ? b : null;
    }

    private static Object[] entry(Object player, Object data) throws Exception {
        if (!ready) init();
        if (failed || data == null) return null;
        long now = System.nanoTime();
        Object[] e = byData.get(data);
        if (e != null && (e[0] != null || now < (Long) e[2])) return e[0] == null ? null : e;
        Object chr = player == null ? null : apCharacter.invoke(player);
        Object mesh = chr == null ? null : find(chr, data);
        if (mesh == null) {
            byData.put(data, new Object[]{ null, new WeakReference<>(null), now + 3_000_000_000L });
            return null;
        }
        String p = null;
        try {
            Object ap = assetGetPath.invoke(mesh);
            if (ap != null) p = (String) pathGetPath.invoke(ap);
        } catch (Throwable ignored) {}
        if (p == null || p.isEmpty()) p = (String) fMeshFullPath.get(mesh);
        e = new Object[]{ p == null ? null : norm(p), new WeakReference<>(mesh), now + 3_000_000_000L };
        byData.put(data, e);
        return e[0] == null ? null : e;
    }

    /** the ModelMesh among the character's model instances (and theirs) drawn with this skinning data */
    private static Object find(Object chr, Object data) throws Exception {
        Object legs = fLegs.get(chr);
        Object slot = legs == null ? null : fSpriteSlot.get(legs);
        Object found = slot == null ? null : search((List<?>) fSlotSub.get(slot), data, 1);
        if (found == null) {
            Object inst = chrGetModel.invoke(chr);
            if (inst != null) found = search((List<?>) fInstSub.get(inst), data, 1);
        }
        return found;
    }

    private static Object search(List<?> list, Object data, int depth) throws Exception {
        if (list == null) return null;
        Object[] items;
        try {
            items = list.toArray();
        } catch (RuntimeException e) {
            return null;                                       // changed while copying: next time
        }
        for (Object mi : items) {
            if (mi == null) continue;
            Object m = fInstModel.get(mi);
            Object mesh = m == null ? null : fModelMesh.get(m);
            if (mesh != null && fMeshSkinning.get(mesh) == data) return mesh;
            if (depth > 0) {
                Object r = search((List<?>) fInstSub.get(mi), data, depth - 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** lower case, forward slashes, no "media/models_X/" (or anything before models_x/), no extension: as the mod's normModel */
    static String norm(String p) {
        p = p.toLowerCase(Locale.ROOT).replace('\\', '/');
        if (p.startsWith("x:")) p = p.substring(2);
        int i = p.lastIndexOf("models_x/");
        if (i >= 0) p = p.substring(i + 9);
        if (p.startsWith("media/")) p = p.substring(6);
        int dot = p.lastIndexOf('.');
        if (dot > p.lastIndexOf('/')) {
            String ext = p.substring(dot + 1);
            if (ext.equals("x") || ext.equals("fbx") || ext.equals("glb") || ext.equals("gltf")) p = p.substring(0, dot);
        }
        return p;
    }

    private static synchronized void init() {
        if (ready || failed) return;
        try {
            ClassLoader cl = MeshInfo.class.getClassLoader();
            Class<?> ap = Class.forName("zombie.core.skinnedmodel.animation.AnimationPlayer", false, cl);
            Class<?> igc = Class.forName("zombie.characters.IsoGameCharacter", false, cl);
            Class<?> sprite = Class.forName("zombie.iso.sprite.IsoSprite", false, cl);
            Class<?> slot = Class.forName("zombie.core.skinnedmodel.ModelManager$ModelSlot", false, cl);
            Class<?> inst = Class.forName("zombie.core.skinnedmodel.model.ModelInstance", false, cl);
            Class<?> model = Class.forName("zombie.core.skinnedmodel.model.Model", false, cl);
            Class<?> mesh = Class.forName("zombie.core.skinnedmodel.model.ModelMesh", false, cl);
            Class<?> asset = Class.forName("zombie.asset.Asset", false, cl);
            Class<?> assetPath = Class.forName("zombie.asset.AssetPath", false, cl);
            apCharacter = ap.getMethod("getIsoGameCharacter");
            chrGetModel = igc.getMethod("getModel");
            fLegs = igc.getField("legsSprite");
            fSpriteSlot = sprite.getField("modelSlot");
            fSlotSub = slot.getField("sub");
            fInstModel = inst.getField("model");
            fInstSub = inst.getField("sub");
            fModelMesh = model.getField("mesh");
            fMeshSkinning = mesh.getField("skinningData");
            fMeshFullPath = mesh.getField("fullPath");
            assetGetPath = asset.getMethod("getPath");
            pathGetPath = assetPath.getMethod("getPath");
            try {
                fMin = mesh.getField("minXyz");
                fMax = mesh.getField("maxXyz");
                Class<?> v3 = fMin.getType();
                fX = v3.getField("x");
                fY = v3.getField("y");
                fZ = v3.getField("z");
            } catch (Throwable t) {
                fMin = fMax = null;                             // no boxes: the size check is skipped
                RBSHook.log("mesh info: no bounding boxes (" + t + ") - the size check of other mods' clothes is off");
            }
            ready = true;
        } catch (Throwable e) {
            failed = true;
            RBSHook.warn("mesh info init failed: " + e);
        }
    }
}
