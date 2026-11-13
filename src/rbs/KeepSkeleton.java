package rbs;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Animations go on when a model file is reloaded during play.
 *
 * Real Body Shape rewrites the body model as the character's weight changes, and the game reloads a model file it sees
 * change (about 2 s after the write in single player, or our own reload). The reload builds a new SkinningData for the
 * mesh and empties the old one in place (ProcessedAiScene.applyToMesh); the model then carries the new one, and the
 * character's AnimationPlayer, finding a SkinningData that is not its own (AnimationPlayer.initSkinningData), drops
 * every animation track. Whatever was playing starts over from nothing: a short twitch of the whole body, and a state
 * that ends only when its anim finishes (sitting down, getting up) never ends (user's report and a player's comment,
 * 2026-11-13: "the character's animations glitch out and then go back to normal right away").
 *
 * Our files keep the skeleton of the game's: only the vertices move. So around ModelMesh.onLoadedX, when the mesh
 * already had a SkinningData (a reload) and the new one has the same bones, parents and bind poses, the old one stays on
 * the mesh with its own lists (the game empties stand-ins instead): the model gets back the object the AnimationPlayer
 * already uses and nothing is reset. A file whose skeleton differs goes the game's way.
 */
final class KeepSkeleton {
    private KeepSkeleton() {}

    /** the old SkinningData of a mesh being reloaded and its real lists, between before() and after() */
    private static final class Stash {
        Object mesh, data, bindPose, inverseBindPose, boneOffset, hierarchy, indices;
    }

    private static final ThreadLocal<Stash> STASH = new ThreadLocal<>();
    private static final float EPS = 1e-4f;

    private static volatile boolean ready, failed;
    private static Field fMeshSkin, fClips, fBind, fInv, fOff, fHier, fIdx;
    private static Field[] fMat;
    private static int kept, differed;

    private static synchronized void init(Object mesh) throws Exception {
        if (ready) return;
        ClassLoader cl = mesh.getClass().getClassLoader();
        Class<?> mm = Class.forName("zombie.core.skinnedmodel.model.ModelMesh", false, cl);
        Class<?> sd = Class.forName("zombie.core.skinnedmodel.model.SkinningData", false, cl);
        Class<?> m4 = Class.forName("org.lwjgl.util.vector.Matrix4f", false, cl);
        fMeshSkin = mm.getField("skinningData");
        fClips = sd.getField("animationClips");
        fBind = sd.getField("bindPose");
        fInv = sd.getField("inverseBindPose");
        fOff = sd.getField("boneOffset");
        fHier = sd.getField("skeletonHierarchy");
        fIdx = sd.getField("boneIndices");
        Field[] m = new Field[16];
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) m[r * 4 + c] = m4.getField("m" + r + c);
        }
        fMat = m;
        ready = true;
    }

    private static void fail(Throwable t) {
        failed = true;
        STASH.remove();
        RBSHook.warn("keeping the skeleton of reloaded models is off, a reload restarts the animations as in the game: " + t);
    }

    /** before the game applies a loaded file to the mesh */
    static void before(Object mesh) {
        STASH.remove();
        if (failed || mesh == null) return;
        try {
            init(mesh);
            Object data = fMeshSkin.get(mesh);
            if (data == null) return;                       // first load of this mesh
            Stash s = new Stash();
            s.mesh = mesh;
            s.data = data;
            s.bindPose = fBind.get(data);
            s.inverseBindPose = fInv.get(data);
            s.boneOffset = fOff.get(data);
            s.hierarchy = fHier.get(data);
            s.indices = fIdx.get(data);
            // the game empties these lists of the old data: it gets empty stand-ins, the real ones are kept aside.
            // If the skeleton turns out different, the old data is left with them, as empty as the game makes it.
            fBind.set(data, new ArrayList<>());
            fInv.set(data, new ArrayList<>());
            fOff.set(data, new ArrayList<>());
            fHier.set(data, new ArrayList<>());
            fIdx.set(data, new HashMap<>());
            STASH.set(s);
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** after the game applied it: put the old data back on the mesh when its skeleton is the same */
    static void after(Object mesh) {
        Stash s = STASH.get();
        STASH.remove();
        if (s == null || s.mesh != mesh || failed) return;
        try {
            Object fresh = fMeshSkin.get(mesh);
            if (fresh == null || fresh == s.data) return;
            if (!sameSkeleton(s, fresh)) {
                if (differed++ < 5) RBSHook.log("a reloaded model has a different skeleton - animations restart, as in the game");
                return;
            }
            fBind.set(s.data, s.bindPose);
            fInv.set(s.data, s.inverseBindPose);
            fOff.set(s.data, s.boneOffset);
            fHier.set(s.data, s.hierarchy);
            fIdx.set(s.data, s.indices);
            // the clips: the body shares its map with the new data (not emptied); a mesh with clips of its own gets
            // the ones just loaded, as the new data has them
            Object clips = fClips.get(fresh);
            if (fClips.get(s.data) != clips) fClips.set(s.data, clips);
            fMeshSkin.set(mesh, s.data);                     // Model.onBeforeReady copies it to the model's tag
            if (kept++ < 5) RBSHook.log("model reloaded with the same skeleton - animations go on");
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static boolean sameSkeleton(Stash s, Object fresh) throws IllegalAccessException {
        if (!s.hierarchy.equals(fHier.get(fresh))) return false;
        if (!s.indices.equals(fIdx.get(fresh))) return false;
        return sameMatrices(s.bindPose, fBind.get(fresh))
            && sameMatrices(s.inverseBindPose, fInv.get(fresh))
            && sameMatrices(s.boneOffset, fOff.get(fresh));
    }

    private static boolean sameMatrices(Object a, Object b) throws IllegalAccessException {
        if (!(a instanceof List) || !(b instanceof List)) return a == b;
        List<?> la = (List<?>) a, lb = (List<?>) b;
        if (la.size() != lb.size()) return false;
        for (int i = 0; i < la.size(); i++) {
            Object ma = la.get(i), mb = lb.get(i);
            if (ma == mb) continue;
            if (ma == null || mb == null) return false;
            for (Field f : fMat) {
                float x = f.getFloat(ma), y = f.getFloat(mb);
                if (Math.abs(x - y) > EPS * Math.max(1f, Math.abs(x))) return false;
            }
        }
        return true;
    }

    /** for the log / tests */
    static Map<String, Integer> counts() {
        Map<String, Integer> m = new HashMap<>();
        m.put("kept", kept);
        m.put("differed", differed);
        return m;
    }
}
