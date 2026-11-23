package rbs;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Animations go on when a model file is reloaded during play, and the skeleton the animations share stays whole.
 *
 * Real Body Shape rewrites the body model as the character's weight changes, and the game reloads a model file it sees
 * change (about 2 s after the write in single player, or our own reload). The reload builds a new SkinningData for the
 * mesh and empties the old one in place (ProcessedAiScene.applyToMesh); the model then carries the new one, and the
 * character's AnimationPlayer, finding a SkinningData that is not its own (AnimationPlayer.initSkinningData), drops
 * every animation track. Whatever was playing starts over from nothing: a short twitch of the whole body, and a state
 * that ends only when its anim finishes (sitting down, getting up) never ends (user's report and a player's comment,
 * 2026-11-13: "the character's animations glitch out and then go back to normal right away").
 *
 * The man's body is also the game's animations mesh (scripts: animationsMesh Human, meshFile = Skinned/MaleBody).
 * Every animation keeps the SkinningData it had when the animations were queued (ModelManager.loadAnim), and an
 * animation file still loading copies its bones and hierarchy from it (ImportedSkeleton.processAiScene). Emptied, it
 * leaves the T-pose the ragdoll setup reads at the first world load without the bones (RagdollBuilder: no "Dummy01",
 * a NullPointerException, the world never finishes loading after a man's body was built on the character screen -
 * our logs 2026-11-05 / 11-08, wjtime 2026-11-23 with a mod that adds hundreds of falls), and an animation loading at
 * that moment without a skeleton.
 *
 * So around ModelMesh.onLoadedX the game gets a stand-in on the mesh to empty: a SkinningData with empty lists and the
 * old one's clips map (the game decides from that map, as it would for the old one, whether to clear it and what the
 * new one shares). The old SkinningData is never touched, not even for a moment. After the load, when the new one has
 * the same bones, parents and bind poses, the old one goes back on the mesh: the model gets back the object the
 * AnimationPlayer already uses and nothing is reset. A file whose skeleton differs keeps the new one, as in the game,
 * and the old one stays whole for the animations still holding it.
 */
final class KeepSkeleton {
    private KeepSkeleton() {}

    /** the old SkinningData of a mesh being reloaded and the stand-in the game empties, between before() and after() */
    private static final class Stash {
        Object mesh, data, standIn;
    }

    private static final ThreadLocal<Stash> STASH = new ThreadLocal<>();
    private static final float EPS = 1e-4f;

    private static volatile boolean ready, failed;
    private static Field fMeshSkin, fClips, fBind, fInv, fOff, fHier, fIdx;
    private static Field[] fMat;
    private static Constructor<?> newData;
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
        newData = sd.getConstructor(HashMap.class, List.class, List.class, List.class, List.class, HashMap.class);
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

    /** before the game applies a loaded file to the mesh: it gets a stand-in to empty, the old data is kept aside */
    static void before(Object mesh) {
        STASH.remove();
        if (failed || mesh == null) return;
        try {
            init(mesh);
            Object data = fMeshSkin.get(mesh);
            if (data == null) return;                       // first load of this mesh
            Object standIn = newData.newInstance(fClips.get(data), new ArrayList<>(), new ArrayList<>(),
                    new ArrayList<>(), new ArrayList<>(), new HashMap<>());
            Stash s = new Stash();
            s.mesh = mesh;
            s.data = data;
            s.standIn = standIn;
            STASH.set(s);
            fMeshSkin.set(mesh, standIn);                   // last: nothing above changed the mesh if it threw
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** after the game applied it (or threw): the old data back on the mesh when its skeleton is the same */
    static void after(Object mesh) {
        Stash s = STASH.get();
        STASH.remove();
        if (s == null || s.mesh != mesh) return;
        try {
            Object fresh = fMeshSkin.get(mesh);
            if (fresh == s.standIn) {                       // the load stopped before the game took it: as it was
                fMeshSkin.set(mesh, s.data);
                return;
            }
            if (fresh == null || fresh == s.data) return;  // a file without a skeleton: the game's way
            if (!sameSkeleton(s.data, fresh)) {
                if (differed++ < 5) RBSHook.log("a reloaded model has a different skeleton - animations restart, as in the game; the old skeleton stays whole for the animations");
                return;
            }
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

    private static boolean sameSkeleton(Object old, Object fresh) throws IllegalAccessException {
        if (!fHier.get(old).equals(fHier.get(fresh))) return false;
        if (!fIdx.get(old).equals(fIdx.get(fresh))) return false;
        return sameMatrices(fBind.get(old), fBind.get(fresh))
            && sameMatrices(fInv.get(old), fInv.get(fresh))
            && sameMatrices(fOff.get(old), fOff.get(fresh));
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
