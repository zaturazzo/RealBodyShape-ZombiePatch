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
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Targets of the patched game methods (see RBSAgent.SPECS).
 * Zombies (not skeletons, not animals) get one of the body variants defined by the RealBodyShapeZombies mod
 * (model scripts RBS_Zombie{Female,Male}_{Thin,Normal,Fat,Obese}). The variant is a stable hash of the zombie's
 * HumanVisual (skin, hair, beard, hair colour, outfit) so a corpse keeps the body it had while "alive":
 * DeadBodyAtlas renders corpses from a copy of that visual, where no character id is available.
 * Weights come from ~/Zomboid/Lua/RealBodyShape_zombies.txt (written by the mod's Lua).
 * Missing limbs (RealBodyShapeLimbs mod) are handled by {@link Limbs} through two more hooks; the same skin
 * matrix hook lets {@link Fit} push a variant zombie's clothes out or in to match its body, and {@link Clean}
 * makes zombies load untouched copies of the clothes the main mod reshapes for the player. {@link Height} makes
 * each zombie a little taller or shorter through two more hooks on AnimationPlayer, and {@link Jiggle} lets fat
 * zombies' and overweight players' bellies bounce (its own shader for their bodies and clothes, fed through the skin
 * matrix palette). In multiplayer, the other players get the variant of their weight too ({@link Others}).
 * Everything else, and every failure, falls through to the original methods.
 * Only public game members are used, through reflection, so nothing here depends on game internals.
 */
public final class RBSHook {
    static final String[] NAMES = { "Thin", "Normal", "Fat", "Obese" };
    static final String CONFIG = "RealBodyShape_zombies.txt";

    private static volatile boolean ready, failed;
    private static Method isAnimal, isZombie, isSkeleton, chrVisual, getLoadedModel, getModelScript;
    private static Method vIsZombie, vIsSkeleton, vIsFemale, vSkin, vBodyHair, vHairModel, vBeardModel, vHairColor, vOutfit, outfitName;
    private static Method vSkinName;                                     // optional: null = not there (npcHash: the index)
    private static Method cRedInt, cGreenInt, cBlueInt;
    private static Field fR, fG, fB, fFemaleModel, fMaleModel;
    private static Object scriptManager, modelManager;
    private static Class<?> zombieClass, visualClass, skinningDataClass;
    private static Method origBody, origVisual, origSkin, origAnimated, origAdditional, origClothing, origUpdateSkin, origRootChild;

    private static volatile boolean enabled = true;
    /** female zombies get variants too; 0 while Hotsco's New Body replaces the female body (it keeps its own) */
    private static volatile boolean females = true;
    private static volatile int[] weights = { 15, 55, 22, 8 };
    // patch 6.8: chances by sex and by line of work (the outfit), from the add-on's sandbox options via the config
    private static volatile int[] wFemale, wMale;
    private static volatile Map<String, int[]> groupWeights = Collections.emptyMap();
    private static volatile Map<String, String> outfitGroup = Collections.emptyMap();     // outfit (lower case) -> group
    private static volatile int cfgVersion;
    /** visual -> {variant, cfgVersion}: the outfit and a corpse's stored body do not change */
    private static final Map<Object, int[]> variantCache = Collections.synchronizedMap(new WeakHashMap<>());
    /** looks hash -> the body a zombie or corpse with those looks got this session (for copies that carry neither
     *  their line of work nor their corpse's stored body); a bounded table */
    @SuppressWarnings("serial")
    private static final Map<Integer, Integer> bodyByLooks = Collections.synchronizedMap(new java.util.LinkedHashMap<Integer, Integer>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Integer> e) { return size() > 8192; }
    });
    private static Class<?> chrClass;
    private static int copyLogs;
    private static java.lang.reflect.Field fVisualOwner;
    private static Class<?> deadBodyClass;
    private static volatile boolean ownerFailed;
    /** other players in multiplayer get the variant body of their weight (Others) */
    private static volatile boolean others = true;
    private static long cfgMtime = -1, cfgNextCheck;
    private static int logged, keptLogged;
    /**
     * zombies (live: the character, corpses: the visual) whose body another mod chose (child zombies, special
     * models...): they keep it, and the clothes fit / belly of a variant body are not applied to them
     */
    private static final Map<Object, Boolean> keptBody = Collections.synchronizedMap(new WeakHashMap<>());

    private RBSHook() {}

    // ---------------- ZombieBuddy ----------------
    // The same hooks also come as ZombieBuddy patches (package rbszb, built from these sources by zb_build.py: no
    // launcher change of ours, the jar ships in the Workshop item). Its patches mark the JVM the first time one of them
    // runs; from then on this classpath copy only passes calls through to the game, so one of the two works, never both.
    // If ZombieBuddy is missing or its jar was not allowed, nothing is marked and this copy keeps working.
    static final String ZB_FLAG = "rbs.zb";
    private static final boolean CLASSPATH_COPY = "rbs".equals(RBSHook.class.getPackageName());
    private static volatile boolean zbSeen, zbMarked;
    private static long zbNext;

    /** this classpath copy only: true once the ZombieBuddy build has taken over */
    static boolean zbActive() {
        if (zbSeen) return true;
        if (!CLASSPATH_COPY) return false;
        long now = System.nanoTime();
        if (now < zbNext) return false;
        zbNext = now + 500_000_000L;
        if ("1".equals(System.getProperty(ZB_FLAG))) {
            zbSeen = true;
            log("the ZombieBuddy build of the patch is running - this classpath copy steps aside");
        }
        return zbSeen;
    }

    /** the ZombieBuddy copy: its patches call this, the classpath copy steps aside */
    public static void zbMark() {
        if (zbMarked) return;
        zbMarked = true;
        System.setProperty(ZB_FLAG, "1");
        log("ZombieBuddy build: the first patched call ran");
    }

    // ---------------- ModelManager.getBodyModel(chr) ----------------
    // the game's answer first: only the shared vanilla body is swapped for a variant, a body another mod picked stays
    public static Object getBodyModel(Object mgr, Object chr) {
        Object body = call(origBody(mgr), mgr, chr);
        return zbActive() ? body : afterBodyModel(chr, body);
    }

    public static Object afterBodyModel(Object chr, Object body) {
        Object m = null;
        try {
            m = zombieModel(chr, body);
            if (m == null) m = otherPlayerModel(chr, body);
        } catch (Throwable t) {
            warn("zombie body failed: " + t);
        }
        if (m != null) {
            Jiggle.bodyModel(m);
            return m;
        }
        Jiggle.playerBody(chr, body);
        return body;
    }

    // ---------------- HumanVisual.getModel() ----------------
    public static Object visualModel(Object visual) {
        Object orig = call(origVisual(visual), visual);
        return zbActive() ? orig : afterVisualModel(visual, orig);
    }

    public static Object afterVisualModel(Object visual, Object orig) {
        try {
            Object m = corpseModel(visual, orig);
            if (m != null) {
                Jiggle.bodyModel(m);
                return m;
            }
            // a UI preview with the player's own body (character creation): the shader, for the eyes to blink there
            if (orig != null && Hair.previewOfVisual(visual)) Jiggle.previewBody(orig);
        } catch (Throwable t) {
            warn("corpse body failed: " + t);
        }
        return orig;
    }

    // ---------------- AnimationPlayer.getSkinTransforms(data) ----------------
    public static Object skinTransforms(Object player, Object data) {
        Object t = call(origSkin(player), player, data);
        return zbActive() ? t : afterSkinTransforms(player, data, t);
    }

    /** Project Viewpoint read one of its shader files (ZombieBuddy build only): its model.vert gets our soft body */
    public static String viewpointShader(String name, String src) {
        return ViewpointShader.source(name, src);
    }

    public static Object afterSkinTransforms(Object player, Object data, Object t) {
        try {
            Fit.apply(player, data, t);
        } catch (Throwable e) {
            warn("zombie clothes failed: " + e);
        }
        PlayerFit.apply(player, data, t);                     // catches its own errors
        try {
            Limbs.apply(player, data, t);
        } catch (Throwable e) {
            warn("limbs failed: " + e);
        }
        return Jiggle.palette(player, data, t);
    }

    // ---------------- AnimationPlayer.UpdateSkinTransforms() ----------------
    // called once a fresh pose is in the model-space bone matrices (both animation paths): zombie heights
    public static void updateSkinTransforms(Object player) {
        call(origUpdateSkin(player), player);
        if (!zbActive()) afterUpdateSkinTransforms(player);
    }

    public static void afterUpdateSkinTransforms(Object player) {
        if (!enter(player)) return;
        try {
            try {
                Height.apply(player);
            } catch (Throwable e) {
                warn("zombie height failed: " + why(e));
            }
            HeadLook.update(player);
            GripIK.update(player);                             // the left hand back on a two-handed weapon
            Jiggle.update(player);
            Hair.update(player);                               // after the head look: the tie follows the turned head
        } finally {
            leave(player);
        }
    }

    // ---------------- never inside ourselves for the same character ----------------
    // Lulu, 2026-11-17: with Project Viewpoint 0.1.7 far zombies lay as a ball on the ground and 4 of them took the game
    // from 200 to 20 fps. Viewpoint poses zombies itself and writes a pose into the bone matrices only when something
    // reads them; writing it, it calls UpdateSkinTransforms, and only after that marks the pose written. Our hook after
    // UpdateSkinTransforms reads the bones (Height, the jiggle), so Viewpoint wrote the pose again and called
    // UpdateSkinTransforms again: us again, round and round to a stack overflow, every zombie it posed, every frame
    // (6.7: each round stretched the pose once more - the ball). A call for a character we are already in on this thread
    // returns at once now: the pose it wrote is the one the outer call then stretches, once.
    private static final ThreadLocal<Object[]> INSIDE = ThreadLocal.withInitial(() -> new Object[4]);
    private static boolean turnedAwaySaid;

    /** false if we are already in our hook for this character on this thread (or four deep): leave it alone */
    private static boolean enter(Object player) {
        Object[] in = INSIDE.get();
        int free = -1;
        for (int i = 0; i < in.length; i++) {
            if (in[i] == player) return turnAway();
            if (in[i] == null && free < 0) free = i;
        }
        if (free < 0) return turnAway();
        in[free] = player;
        return true;
    }

    private static void leave(Object player) {
        Object[] in = INSIDE.get();
        for (int i = 0; i < in.length; i++) {
            if (in[i] == player) {
                in[i] = null;
                return;
            }
        }
    }

    private static boolean turnAway() {
        if (!turnedAwaySaid) {
            turnedAwaySaid = true;
            log("a pose update from inside our own one for the same character (another mod writing the pose as it is"
                    + " read): left to the outer one. Called from: " + Height.caller());
        }
        return false;
    }

    // ---------------- AnimationPlayer.transformRootChildBones(bone, rotation) ----------------
    // a turn-around animation event rebuilds the model-space bone matrices: stretch them again
    public static void rootChildBones(Object player, Object bone, Object rotation) {
        call(origRootChild(player), player, bone, rotation);
        if (!zbActive()) afterRootChildBones(player);
    }

    public static void afterRootChildBones(Object player) {
        if (!enter(player)) return;
        try {
            try {
                Height.apply(player);
            } catch (Throwable e) {
                warn("zombie height (turn) failed: " + why(e));
            }
            HeadLook.reapply(player);
            GripIK.reapply(player);
        } finally {
            leave(player);
        }
    }

    // ---------------- AnimatedModel.getAnimationPlayer() ----------------
    public static Object animatedPlayer(Object model) {
        Object p = call(origAnimated(model), model);
        if (!zbActive()) afterAnimatedPlayer(model, p);
        return p;
    }

    public static void afterAnimatedPlayer(Object model, Object p) {
        try {
            Limbs.remember(p, model);
        } catch (Throwable e) {
            warn("animated model failed: " + e);
        }
        PlayerFit.rememberPreview(p, model);
    }

    // ---------------- ModelManager.newAdditionalModelInstance(mesh, texture, chr, animPlayer, shader) ----------------
    public static Object additionalModel(Object mgr, Object mesh, Object tex, Object chr, Object ap, Object shader) {
        Object m = mesh, sh = shader;
        if (!zbActive()) {
            m = additionalMesh(mesh, chr);
            sh = additionalShader(shader, m, chr);
        }
        return call(origAdditional(mgr), mgr, m, tex, chr, ap, sh);
    }

    /** the mesh a zombie's clothing piece loads: the untouched copy of what the main mod reshapes for the player */
    public static Object additionalMesh(Object mesh, Object chr) {
        try {
            return Clean.meshFor(mesh, chr);
        } catch (Throwable t) {
            warn("zombie clothes copy failed: " + t);
            return mesh;
        }
    }

    /** the shader of a clothing piece of a zombie with a variant body / a player: ours, so the clothes move along */
    public static Object additionalShader(Object shader, Object mesh, Object chr) {
        try {
            boolean wearer = (zombiesEnabled() && Clean.isZombieWearer(chr)) || Jiggle.isPlayer(chr);
            // our hair with no character: an AnimatedModel's - the character creation preview (a new game, or after a
            // death: no character yet; user, 2026-11-19), the corpse atlas, a mannequin. It gets our hair's shader too,
            // the shaders compiled first if nothing in the world did it yet (the main menu); only a UI preview gets
            // springs (Hair.previewOf), the others' slot stays empty and the shader leaves their hair as it is
            if (!wearer && chr == null && mesh != null && mesh.toString().toLowerCase(java.util.Locale.ROOT).contains(Hair.MARK)
                    && Jiggle.ensureShaders())
                return Jiggle.clothingShader(shader, mesh, true);
            return Jiggle.clothingShader(shader, mesh, wearer);
        } catch (Throwable t) {
            warn("zombie clothes shader failed: " + t);
            return shader;
        }
    }

    // ---------------- AnimatedModel.addClothingItem(itemVisual, clothingItem) ----------------
    // corpses in DeadBodyAtlas build their clothes here with no character: tell Clean whose clothes they are
    public static void animatedClothing(Object model, Object itemVisual, Object clothingItem) {
        if (zbActive()) {
            call(origClothing(model), model, itemVisual, clothingItem);
            return;
        }
        enterClothing(model);
        try {
            call(origClothing(model), model, itemVisual, clothingItem);
        } finally {
            exitClothing();
        }
    }

    private static final Object NONE = new Object();
    private static final ThreadLocal<java.util.ArrayDeque<Object>> CLOTHING_PREV = ThreadLocal.withInitial(java.util.ArrayDeque::new);

    public static void enterClothing(Object model) {
        boolean zombie = false;
        try {
            zombie = Clean.isZombieModel(model);
        } catch (Throwable t) {
            warn("corpse clothes failed: " + t);
        }
        Boolean prev = Clean.enter(zombie);
        CLOTHING_PREV.get().push(prev == null ? NONE : prev);
    }

    public static void exitClothing() {
        Object prev = CLOTHING_PREV.get().poll();
        Clean.exit(prev == null || prev == NONE ? null : (Boolean) prev);
    }

    // ---------------- SmartTexture.calculate() ----------------
    // a player's texture: the finer face layer right after the skin (FaceLayer)
    public static void smartCalculate(Object smart) {
        if (!zbActive()) beforeSmartCalculate(smart);
        call(origCalculate(smart), smart);
    }

    public static void beforeSmartCalculate(Object smart) {
        FaceLayer.beforeCalculate(smart);
    }

    // ---------------- ModelInstanceTextureCreator.init(HumanVisual, ItemVisuals, ModelInstance) ----------------
    // the face layer is loaded with the skin, and the texture waits for it like for its other layers
    public static void textureCreatorInit(Object creator, Object visual, Object items, Object modelInstance) {
        call(origCreatorInit(creator), creator, visual, items, modelInstance);
        if (!zbActive()) afterTextureCreatorInit(creator);
    }

    public static void afterTextureCreatorInit(Object creator) {
        FaceLayer.afterInit(creator);
    }

    // ---------------- ModelMesh.onLoadedX(scene) ----------------
    // a model file reloaded during play keeps its SkinningData when the skeleton is the same, so the character's
    // animations are not all dropped and restarted (see KeepSkeleton)
    public static void meshLoadedX(Object mesh, Object scene) {
        if (!zbActive()) beforeMeshLoadedX(mesh);
        call(origLoadedX(mesh), mesh, scene);
        if (!zbActive()) afterMeshLoadedX(mesh);
    }

    public static void beforeMeshLoadedX(Object mesh) {
        KeepSkeleton.before(mesh);
    }

    public static void afterMeshLoadedX(Object mesh) {
        KeepSkeleton.after(mesh);
    }

    private static Method origLoadedX;

    private static Method origLoadedX(Object mesh) {
        Method o = origLoadedX;
        if (o == null) {
            try {
                ClassLoader cl = mesh.getClass().getClassLoader();
                Class<?> mm = Class.forName("zombie.core.skinnedmodel.model.ModelMesh", false, cl);
                o = mm.getDeclaredMethod(RBSAgent.RENAMED_LOADED_X,
                        Class.forName("zombie.core.skinnedmodel.model.jassimp.ProcessedAiScene", false, cl));
                o.setAccessible(true);                       // the original is protected
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("RBS hook: original ModelMesh.onLoadedX missing", e);
            }
            origLoadedX = o;
        }
        return o;
    }

    private static Method origCalculate, origCreatorInit;

    private static Method origCalculate(Object smart) {
        Method o = origCalculate;
        if (o == null) {
            try {
                Class<?> st = Class.forName("zombie.core.textures.SmartTexture", false, smart.getClass().getClassLoader());
                o = st.getMethod(RBSAgent.RENAMED_CALCULATE);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("RBS hook: original SmartTexture.calculate missing", e);
            }
            origCalculate = o;
        }
        return o;
    }

    private static Method origCreatorInit(Object creator) {
        Method o = origCreatorInit;
        if (o == null) {
            try {
                ClassLoader cl = creator.getClass().getClassLoader();
                o = creator.getClass().getMethod(RBSAgent.RENAMED_CREATOR_INIT,
                        Class.forName("zombie.core.skinnedmodel.visual.HumanVisual", false, cl),
                        Class.forName("zombie.core.skinnedmodel.visual.ItemVisuals", false, cl),
                        Class.forName("zombie.core.skinnedmodel.model.ModelInstance", false, cl));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("RBS hook: original ModelInstanceTextureCreator.init missing", e);
            }
            origCreatorInit = o;
        }
        return o;
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

    private static Method origSkin(Object player) {
        Method o = origSkin;
        if (o == null) {
            try {
                Class<?> sd = Class.forName("zombie.core.skinnedmodel.model.SkinningData", false, player.getClass().getClassLoader());
                o = player.getClass().getMethod(RBSAgent.RENAMED_SKIN, sd);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("RBS hook: original AnimationPlayer.getSkinTransforms missing", e);
            }
            origSkin = o;
        }
        return o;
    }

    private static Method origUpdateSkin(Object player) {
        Method o = origUpdateSkin;
        if (o == null) {
            try {
                o = player.getClass().getMethod(RBSAgent.RENAMED_UPDATE_SKIN);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("RBS hook: original AnimationPlayer.UpdateSkinTransforms missing", e);
            }
            origUpdateSkin = o;
        }
        return o;
    }

    private static Method origRootChild(Object player) {
        Method o = origRootChild;
        if (o == null) {
            try {
                Class<?> q = Class.forName("org.lwjgl.util.vector.Quaternion", false, player.getClass().getClassLoader());
                o = player.getClass().getMethod(RBSAgent.RENAMED_ROOT_CHILD, String.class, q);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("RBS hook: original AnimationPlayer.transformRootChildBones missing", e);
            }
            origRootChild = o;
        }
        return o;
    }

    private static Method origAnimated(Object model) {
        Method o = origAnimated;
        if (o == null) {
            try {
                o = model.getClass().getMethod(RBSAgent.RENAMED_ANIMATED);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("RBS hook: original AnimatedModel.getAnimationPlayer missing", e);
            }
            origAnimated = o;
        }
        return o;
    }

    private static Method origAdditional(Object mgr) {
        Method o = origAdditional;
        if (o == null) {
            try {
                ClassLoader cl = mgr.getClass().getClassLoader();
                Class<?> igc = Class.forName("zombie.characters.IsoGameCharacter", false, cl);
                Class<?> ap = Class.forName("zombie.core.skinnedmodel.animation.AnimationPlayer", false, cl);
                o = mgr.getClass().getMethod(RBSAgent.RENAMED_ADDITIONAL, String.class, String.class, igc, ap, String.class);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("RBS hook: original newAdditionalModelInstance missing", e);
            }
            origAdditional = o;
        }
        return o;
    }

    private static Method origClothing(Object model) {
        Method o = origClothing;
        if (o == null) {
            try {
                ClassLoader cl = model.getClass().getClassLoader();
                Class<?> iv = Class.forName("zombie.core.skinnedmodel.visual.ItemVisual", false, cl);
                Class<?> ci = Class.forName("zombie.core.skinnedmodel.population.ClothingItem", false, cl);
                o = model.getClass().getDeclaredMethod(RBSAgent.RENAMED_CLOTHING, iv, ci);   // the original is private
                o.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("RBS hook: original AnimatedModel.addClothingItem missing", e);
            }
            origClothing = o;
        }
        return o;
    }

    private static Object zombieModel(Object chr, Object orig) throws Exception {
        if (failed) return null;
        if (!ready) init();
        if (failed) return null;
        loadConfig();
        if (!enabled) return null;
        if (!isLiveZombie(chr)) return null;
        if (!isSharedBody(orig)) {
            kept(chr, orig);
            return null;
        }
        Object visual = chrVisual.invoke(chr);
        if (visual == null) return null;
        boolean female = (Boolean) vIsFemale.invoke(visual);
        if (female && !females) {
            kept(chr, orig);
            return null;
        }
        keptBody.remove(chr);
        int v = variantOfVisual(visual);
        Object m = zombieVariantModel(chr, female, v);
        if (m != null) tagVariant(chr, v);
        return m;
    }

    /** the variant body a zombie wears: another mod's NPC the HD add-on's (when there), every other one the usual */
    static Object zombieVariantModel(Object chr, boolean female, int v) throws Exception {
        if (isNpc(chr)) {
            Object visual = visualOf(chr);
            Object h = npcModel(chr, visual, female, v);
            if (h != null) return h;
        }
        return variantModelOf(female, v);
    }

    /** the HD body of an NPC (live, or its corpse: owner), with its head when it has one (NpcHeads); null = none */
    static Object npcModel(Object owner, Object visual, boolean female, int v) throws Exception {
        String head = NpcHeads.headOf(owner, visual, female);
        Object h = head.isEmpty() ? null : hdHeadModelOf(v, head);
        if (h == null) {
            head = NpcHeads.NONE;
            h = hdVariantModelOf(female, v);
        }
        if (visual != null && owner != null && isLiveZombie(owner)) NpcHeads.note(owner, visual, female, v, head);
        return h;
    }

    /** a woman NPC's HD body with a local head (RBS_ZombieFemale_<Variant>_HD_<head>, zombie_hd.py), or null */
    static Object hdHeadModelOf(int v, String head) throws Exception {
        return hdModel("RBS_ZombieFemale_" + NAMES[v] + "_HD_" + head);
    }

    /**
     * The HD add-on's variant body (RealBodyShape_HD, hd_mod/zombie_hd.py: model scripts RBS_Zombie<Sex>_<Variant>_HD,
     * the Zombie Bodies variants subdivided like the player's HD body) for other mods' NPCs, or null when it is not
     * there - looked for once (the scripts are loaded with the game). User, 2026-11-19: Stay With Me's companion was
     * not HD in the world, while its info window (drawn from the player's own body file) was.
     */
    static Object hdVariantModelOf(boolean female, int v) throws Exception {
        return hdModel("RBS_Zombie" + (female ? "Female" : "Male") + "_" + NAMES[v] + "_HD");
    }

    /** a model of the HD add-on by its script name (looked for once), or null */
    private static Object hdModel(String base) throws Exception {
        String name = hdScripts.get(base);
        if (name == null) {
            name = getModelScript.invoke(scriptManager, base) != null ? base
                    : getModelScript.invoke(scriptManager, "Base." + base) != null ? "Base." + base : "";
            hdScripts.put(base, name);
            if (!name.isEmpty() && hdLogs++ < 2) log("zombie body: other mods' NPCs wear the HD add-on's bodies (" + name + ")");
        }
        return name.isEmpty() ? null : getLoadedModel.invoke(modelManager, name);
    }

    private static final Map<String, String> hdScripts = new java.util.concurrent.ConcurrentHashMap<>();
    private static int hdLogs;

    /**
     * Another player in multiplayer: the variant body of their weight (Others), not the shared body this machine
     * reshapes for its own player. null = leave the game's body (single player, a body another mod chose, Zombie
     * Bodies off, switched off in the config).
     */
    private static Object otherPlayerModel(Object chr, Object orig) throws Exception {
        if (failed) return null;
        if (!ready) init();
        if (failed || !Others.isOther(chr)) return null;
        loadConfig();
        if (!others) return null;
        boolean female = Others.isFemale(chr);
        if (!isSharedBody(orig) || (female && !females)) {
            Others.keep(chr, false);
            return null;
        }
        Object m = variantModelOf(female, Others.variant(chr));
        Others.keep(chr, m != null);
        return m;
    }

    /** other players get variant bodies (config players=1, the default) and the hook works */
    static boolean othersEnabled() {
        if (!ensureInit()) return false;
        loadConfig();
        return others;
    }

    private static volatile Method chrModData, tRawget, tRawset;
    private static volatile boolean tagFailed;

    /**
     * The zombie's body for Lua: ModData RBS_BodyVariant = index of NAMES (0 thin .. 3 obese). The mod's Lua reads it,
     * e.g. so a clothing size mod gives a fat zombie big clothes instead of a random size (user, 2026-11-11: a big
     * zombie in XS, a small one in XXL). Written only when it changes.
     */
    private static void tagVariant(Object chr, int v) {
        if (tagFailed) return;
        try {
            if (tRawset == null) {
                Class<?> kt = Class.forName("se.krka.kahlua.vm.KahluaTable", false, RBSHook.class.getClassLoader());
                tRawget = kt.getMethod("rawget", Object.class);
                tRawset = kt.getMethod("rawset", Object.class, Object.class);
                chrModData = Class.forName("zombie.iso.IsoObject", false, RBSHook.class.getClassLoader()).getMethod("getModData");
            }
            Object t = chrModData.invoke(chr);
            if (t == null) return;
            Double val = (double) v;
            if (!val.equals(tRawget.invoke(t, "RBS_BodyVariant"))) tRawset.invoke(t, "RBS_BodyVariant", val);
        } catch (Throwable e) {
            tagFailed = true;
            warn("zombie body tag for Lua failed (sizes of other mods keep their own): " + e);
        }
    }

    /** the game's shared vanilla body (also what a body replacer's files load into), not a model another mod chose */
    private static boolean isSharedBody(Object m) throws Exception {
        return m != null && (m == fFemaleModel.get(modelManager) || m == fMaleModel.get(modelManager));
    }

    private static void kept(Object key, Object model) {
        if (keptBody.put(key, Boolean.TRUE) == null && keptLogged < 8) {
            keptLogged++;
            log("zombie body: another mod chose " + model + " for a zombie - it keeps that body (no variant, no clothes fit, no belly)");
        }
    }

    /** this zombie (character, or the visual of a corpse) draws a Zombie Bodies variant, not a body another mod chose */
    static boolean wearsVariant(Object key) {
        return key == null || !Boolean.TRUE.equals(keptBody.get(key));
    }

    private static Object corpseModel(Object visual, Object orig) throws Exception {
        if (failed) return null;
        if (!ready) init();
        if (failed) return null;
        loadConfig();
        if (!enabled || visual == null || !visualClass.isInstance(visual)) return null;
        if (!(Boolean) vIsZombie.invoke(visual)) {
            // a UI preview of another mod's NPC (its info window): that NPC's body and head, not the player's file
            Object[] p = NpcHeads.previewOf(visual);
            if (p == null || !isSharedBody(orig)) return null;
            boolean female = (Boolean) p[0];
            int v = (Integer) p[1];
            String head = (String) p[2];
            Object h = head.isEmpty() ? null : hdHeadModelOf(v, head);
            if (h == null) h = hdVariantModelOf(female, v);
            return h != null ? h : variantModelOf(female, v);
        }
        if ((Boolean) vIsSkeleton.invoke(visual)) return null;
        // only replace the shared body (forceModel / skeleton / animal results are returned by the original as-is)
        if (!isSharedBody(orig)) {
            kept(visual, orig);
            return null;
        }
        boolean female = (Boolean) vIsFemale.invoke(visual);
        if (female && !females) {
            kept(visual, orig);
            return null;
        }
        keptBody.remove(visual);
        int v = variantOfVisual(visual);
        // another mod's NPC's corpse (it keeps the rbsNpc mark): the HD body it wore alive, when there
        Object owner = ownerOf(visual);
        if (owner != null && !isLiveZombie(owner) && isNpc(owner)) {
            Object h = npcModel(owner, visual, female, v);
            if (h != null) return h;
        }
        return variantModelOf(female, v);
    }

    private static Object variantModel(boolean female, int hash) throws Exception {
        return variantModelOf(female, variant(hash));
    }

    /** the loaded body model of a variant (index of NAMES), null when the Zombie Bodies mod is off */
    static Object variantModelOf(boolean female, int v) throws Exception {
        String base = "RBS_Zombie" + (female ? "Female" : "Male") + "_" + NAMES[v];
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

    // ---------------- shared helpers (also used by Limbs) ----------------

    /** zombie character (not animal, not skeleton); false for anything else */
    static boolean isLiveZombie(Object chr) throws Exception {
        if (chr == null || !zombieClass.isInstance(chr)) return false;
        if ((Boolean) isAnimal.invoke(chr)) return false;
        if (!(Boolean) isZombie.invoke(chr)) return false;
        return !(Boolean) isSkeleton.invoke(chr);
    }

    static Object visualOf(Object chr) throws Exception {
        return chrVisual.invoke(chr);
    }

    static boolean isHumanVisual(Object visual) {
        return visual != null && visualClass != null && visualClass.isInstance(visual);
    }

    static boolean isZombieVisual(Object visual) throws Exception {
        if (visual == null || !visualClass.isInstance(visual)) return false;
        if (!(Boolean) vIsZombie.invoke(visual)) return false;
        return !(Boolean) vIsSkeleton.invoke(visual);
    }

    static boolean ensureInit() {
        if (!ready && !failed) init();
        return ready;
    }

    /** zombie bodies switched on in the config (the mod's Lua writes it) and the hook works */
    static boolean zombiesEnabled() {
        if (!ensureInit()) return false;
        loadConfig();
        return enabled;
    }

    static boolean isFemaleVisual(Object visual) throws Exception {
        return (Boolean) vIsFemale.invoke(visual);
    }

    /** the body variant (index of NAMES) this visual gets */
    static int variantOf(Object visual) throws Exception {
        return variantOfVisual(visual);
    }

    /**
     * A zombie's or a corpse's body (index of NAMES), the same everywhere it is asked for (its body model, its clothes,
     * its belly): a corpse keeps the body its zombie had (ModData RBS_BodyVariant, copied to the corpse when it dies,
     * saved with it); otherwise the chances of its line of work (its outfit, which a corpse also keeps) or of its sex,
     * split by the stable hash of its looks.
     */
    static int variantOfVisual(Object visual) throws Exception {
        // the looks are checked every time: a visual can be filled anew - the game reuses zombies, and DeadBodyAtlas
        // draws every corpse through one AnimatedModel whose own visual is a copy of each corpse in turn (keyed by the
        // visual alone, those corpses all got the first one's body: zombies changed body, height and clothes when they
        // died and became part of the atlas, 2026-11-17)
        int h = visualHash(visual);
        int[] c = variantCache.get(visual);
        int ver = cfgVersion;
        Object owner = ownerOf(visual);
        int chosen = chosenVariant(visual, owner);
        // another mod's NPC / companion (a zombie it marks "useless"): rolled from the looks that mod gives it, not from
        // the skin and body hair indexes - those are the random ones of every zombie it spawns for the same companion
        // (Stay With Me spawns it anew and sets the skin by name only: thin one day, obese the next, 2026-11-18)
        boolean npc = owner != null && chrClass != null && chrClass.isInstance(owner) && isNpc(owner);
        int roll = npc ? npcHash(visual) : h;
        if (c != null && c[1] == ver && c[2] == roll && c[3] == chosen) return c[0];
        boolean corpse = owner != null && deadBodyClass != null && deadBodyClass.isInstance(owner);
        int v = corpse ? storedVariant(owner, "RBS_BodyVariant") : chosen;
        if (chosen >= 0 && chosenLogs < 6 && (c == null || c[3] != chosen)) {
            chosenLogs++;
            log("zombie body: another mod chose the body of one of its characters (" + CHOSEN_KEY + ") - " + NAMES[chosen]);
        }
        if (v < 0) {
            // the line of work from the visual itself (a copy carries it; its owner - an AnimatedModel - has none),
            // else from the owner. Neither (a corpse of an earlier session, drawn from a copy): the body this session
            // already saw with these looks, if any
            String outfit = outfitOfVisual(visual);
            if (outfit == null) outfit = outfitOf(owner);
            // not right after another mod took its choice back: these looks were seen with the chosen body
            boolean unchosen = c != null && c[3] >= 0;
            Integer seen = outfit == null && !corpse && !unchosen && !npc ? bodyByLooks.get(h) : null;
            if (seen != null) v = seen;
            else {
                boolean female = (Boolean) vIsFemale.invoke(visual);
                v = variant(roll, weightsFor(female, outfit));
            }
            boolean copy = !corpse && !(chrClass != null && chrClass.isInstance(owner));
            if (copy && copyLogs < 6) {
                copyLogs++;
                Integer before = bodyByLooks.get(h);
                log("zombie body: a corpse drawn from a copy of its looks (" + (owner == null ? "no owner" : owner.getClass().getSimpleName())
                        + ") - " + NAMES[v] + (seen != null ? ", the body seen with these looks this session"
                        : outfit != null ? ", by its line of work '" + outfit + "'" : ", by its sex (no line of work, not seen this session)")
                        + (before != null && before != v ? " - NOT the " + NAMES[before] + " it had" : ""));
            }
        }
        // by the usual hash also for an NPC: a corpse drawn from a copy of its looks is looked up by that one
        if (corpse || chrClass != null && chrClass.isInstance(owner)) bodyByLooks.put(h, v);
        variantCache.put(visual, new int[]{ v, ver, roll, chosen });
        if (npc && npcLogs < 6 && chosen < 0 && (c == null || c[0] != v)) {
            npcLogs++;
            log("zombie body: another mod's NPC / companion - " + NAMES[v] + ", from the looks that mod gives it (skin "
                    + skinName(visual) + "), the same body whenever it is spawned again with them");
        }
        return v;
    }

    private static int npcLogs;

    /**
     * The roll of another mod's NPC: the looks that mod sets on it - the skin by name (HumanVisual.getSkinTexture:
     * the name it was given, else the one of its index), hair, beard, hair colour - so the same companion gets the same
     * body on every zombie the mod spawns for it, in every session (String hashes are the same on every machine).
     */
    static int npcHash(Object visual) throws Exception {
        int h = 23;
        h = h * 31 + Objects.hashCode(skinName(visual));
        h = h * 31 + Objects.hashCode(vHairModel.invoke(visual));
        h = h * 31 + Objects.hashCode(vBeardModel.invoke(visual));
        Object col = vHairColor.invoke(visual);
        if (col != null) {
            h = h * 31 + Math.round(fR.getFloat(col) * 255);
            h = h * 31 + Math.round(fG.getFloat(col) * 255);
            h = h * 31 + Math.round(fB.getFloat(col) * 255);
        }
        return h;
    }

    /** the skin texture's name of a visual, or its index when the name cannot be read */
    private static Object skinName(Object visual) {
        try {
            if (vSkinName != null) {
                Object n = vSkinName.invoke(visual);
                if (n != null && !n.toString().isEmpty()) return n.toString();
            }
            return vSkin.invoke(visual);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * The ModData key another mod sets on a zombie it owns (an NPC / companion built on IsoZombie, e.g. Stay With Me)
     * to choose its body: 0 Thin, 1 Normal, 2 Fat, 3 Obese (index of NAMES); absent or anything else = the usual pick.
     * Its own key, not RBS_BodyVariant: that one this patch writes on every zombie it shapes (tagVariant, for the Lua
     * and other mods to read), and the game reuses zombies, so reading it back would let a reused zombie keep the body
     * of the one before. Proposed by Come and See (Steam discussion, 2026-11-18) with RBS_BodyVariant; Lua helper:
     * RealBodyShape.setZombieBody(zombie, body).
     */
    static final String CHOSEN_KEY = "RBS_BodyVariantSet";
    private static int chosenLogs;

    /**
     * the body another mod chose for this live character (CHOSEN_KEY), or -1 - read anew every time (two reflected
     * calls, like the looks above): a mod sets it and resets the model at once, often right after the spawn - a
     * value kept for half a second gave that reset the old body until the next one (2026-11-18)
     */
    private static int chosenVariant(Object visual, Object owner) {
        if (owner == null || chrClass == null || !chrClass.isInstance(owner)) return -1;
        return storedVariant(owner, CHOSEN_KEY);
    }

    /**
     * ModData beside CHOSEN_KEY: how far (0..1) the body is drawn from the chosen variant toward the next one. The mod's
     * Lua writes it for an NPC whose weight it keeps (RealBodyShape.npcStepForKg: 14 bodies instead of 4, user
     * 2026-11-22 - 80 and 85 kg looked 27 kg apart). Fit draws it with the chosen variant, its body and clothes moved
     * that far by the per-bone maps: no files.
     */
    static final String BLEND_KEY = "RBS_BodyBlend";
    /** a blend is read in these steps (1/15: the Lua's thirds and fifths of the way land on them) */
    static final int BLEND_STEPS = 15;

    /** a live character drawn between two bodies: a chosen body below Obese and BLEND_KEY above 0 -> the step
     *  (1..BLEND_STEPS-1) toward the next body, else 0 (read anew, like the chosen body: Fit asks every two seconds) */
    static int blendStep(Object visual) {
        Object owner = ownerOf(visual);
        if (owner == null || chrClass == null || !chrClass.isInstance(owner)) return 0;
        int chosen = storedVariant(owner, CHOSEN_KEY);
        if (chosen < 0 || chosen >= NAMES.length - 1) return 0;
        double t = modDataNumber(owner, BLEND_KEY);
        if (!(t > 0)) return 0;                                    // none, NaN
        return (int) Math.max(0, Math.min(BLEND_STEPS - 1, Math.round(t * BLEND_STEPS)));
    }

    /** the outfit's name of a visual (in memory: a corpse loaded from a save has none), or null */
    private static String outfitOfVisual(Object visual) {
        try {
            Object o = vOutfit.invoke(visual);
            Object n = o == null ? null : outfitName.invoke(o);
            return n == null ? null : n.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    private static int[] weightsFor(boolean female, String outfit) {
        if (outfit != null) {
            String g = outfitGroup.get(outfit.toLowerCase(java.util.Locale.ROOT));
            int[] w = g == null ? null : groupWeights.get(g);
            if (w != null) return w;
        }
        int[] w = female ? wFemale : wMale;
        return w != null ? w : weights;
    }

    /** the zombie or corpse a visual belongs to (HumanVisual.owner), or null */
    private static Object ownerOf(Object visual) {
        if (ownerFailed || visual == null) return null;
        try {
            if (fVisualOwner == null) {
                ClassLoader cl = visual.getClass().getClassLoader();
                java.lang.reflect.Field f = Class.forName("zombie.core.skinnedmodel.visual.HumanVisual", false, cl).getDeclaredField("owner");
                f.setAccessible(true);
                deadBodyClass = Class.forName("zombie.iso.objects.IsoDeadBody", false, cl);
                chrClass = Class.forName("zombie.characters.IsoGameCharacter", false, cl);
                fVisualOwner = f;
            }
            return fVisualOwner.get(visual);
        } catch (Throwable e) {
            ownerFailed = true;
            warn("zombie bodies by line of work off (every zombie by its sex): " + e);
            return null;
        }
    }

    private static String outfitOf(Object owner) {
        if (owner == null) return null;
        try {
            Object o = owner.getClass().getMethod("getOutfitName").invoke(owner);
            return o == null ? null : o.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    private static Method zUseless;
    private static volatile boolean zUselessTried;

    /**
     * A zombie another mod drives as an NPC or a companion (Project A-Life, Stay With Me...): "useless" (it does not
     * attack by itself), or marked rbsNpc by the dismemberment Lua (RealBodyShape.limbsNpc - the mark stays on its
     * corpse). Never dismembered (The Virginslayer, 2026-11-18: A-Life NPCs spawned with missing limbs).
     */
    static boolean isNpc(Object chr) {
        if (chr == null) return false;
        try {
            if (zombieClass != null && zombieClass.isInstance(chr)) {
                if (!zUselessTried) {
                    try {
                        zUseless = zombieClass.getMethod("isUseless");
                    } catch (NoSuchMethodException e) {
                        zUseless = null;
                    }
                    zUselessTried = true;
                }
                if (zUseless != null && (Boolean) zUseless.invoke(chr)) return true;
            }
            return Boolean.TRUE.equals(modDataValue(chr, "rbsNpc"));
        } catch (Throwable e) {
            return false;
        }
    }

    /** the corpse or zombie a visual belongs to (HumanVisual.owner), or null - for Limbs */
    static Object visualOwner(Object visual) {
        return ownerOf(visual);
    }

    /** a value in an object's ModData (rawget, no Lua call), or null */
    private static Object modDataValue(Object owner, String key) {
        try {
            if (tRawget == null) {
                Class<?> kt = Class.forName("se.krka.kahlua.vm.KahluaTable", false, RBSHook.class.getClassLoader());
                tRawget = kt.getMethod("rawget", Object.class);
                tRawset = kt.getMethod("rawset", Object.class, Object.class);
                chrModData = Class.forName("zombie.iso.IsoObject", false, RBSHook.class.getClassLoader()).getMethod("getModData");
            }
            Object t = chrModData.invoke(owner);
            return t == null ? null : tRawget.invoke(t, key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** a value in an object's ModData, or null (for the other classes) */
    static Object modData(Object owner, String key) {
        return modDataValue(owner, key);
    }

    /** a number in a character's ModData, or NaN */
    static double modDataNumber(Object owner, String key) {
        Object v = modDataValue(owner, key);
        return v instanceof Number ? ((Number) v).doubleValue() : Double.NaN;
    }

    /** a number written in a character's ModData for the Lua side, only when it differs from what is there */
    static void setModDataNumber(Object owner, String key, double value) {
        try {
            if (tRawset == null) modDataValue(owner, key);
            Object t = chrModData.invoke(owner);
            if (t == null) return;
            Double val = value;
            if (!val.equals(tRawget.invoke(t, key))) tRawset.invoke(t, key, val);
        } catch (Throwable ignored) {
        }
    }

    /** a body index (0..NAMES.length-1) stored in the owner's ModData under key - RBS_BodyVariant: the body a corpse's
     *  zombie had; CHOSEN_KEY: the body another mod chose - or -1 */
    private static int storedVariant(Object owner, String key) {
        try {
            if (tRawget == null) {
                Class<?> kt = Class.forName("se.krka.kahlua.vm.KahluaTable", false, RBSHook.class.getClassLoader());
                tRawget = kt.getMethod("rawget", Object.class);
                tRawset = kt.getMethod("rawset", Object.class, Object.class);
                chrModData = Class.forName("zombie.iso.IsoObject", false, RBSHook.class.getClassLoader()).getMethod("getModData");
            }
            Object t = chrModData.invoke(owner);
            Object v = t == null ? null : tRawget.invoke(t, key);
            if (v instanceof Number) {
                int i = ((Number) v).intValue();
                if (i >= 0 && i < NAMES.length) return i;
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    /**
     * stable identity of a zombie that survives death AND saving: what it looks like. Only fields the game stores
     * with a corpse (skin, body hair, hair, beard, hair colour) - the Outfit object is not saved with corpses, so a
     * corpse loaded from a save has none and anything derived from it would change after a reload.
     */
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
        return h;
    }

    /**
     * Limb seed: identical to seed(seedString(v)) in RealBodyShapeLimbs.lua, so the Lua side (blood, crawling)
     * and this side (hidden bones) pick the same limbs for the same zombie. Integer-only inputs, 32-bit arithmetic
     * that Lua doubles reproduce exactly. Same saved-with-the-corpse fields as visualHash (no outfit).
     */
    static long seedOf(Object visual) throws Exception {
        Object col = vHairColor.invoke(visual);
        int r = 0, g = 0, b = 0;
        if (col != null) {
            r = (Integer) cRedInt.invoke(col);
            g = (Integer) cGreenInt.invoke(col);
            b = (Integer) cBlueInt.invoke(col);
        }
        Object hair = vHairModel.invoke(visual), beard = vBeardModel.invoke(visual);
        String s = vSkin.invoke(visual) + "|" + vBodyHair.invoke(visual) + "|" + (hair == null ? "" : hair) + "|"
                + (beard == null ? "" : beard) + "|" + r + "|" + g + "|" + b;
        long h = 0;
        for (int i = 0; i < s.length(); i++) h = (h * 31 + s.charAt(i)) & 0xFFFFFFFFL;
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
            try {
                vSkinName = visualClass.getMethod("getSkinTexture");
            } catch (NoSuchMethodException e) {
                vSkinName = null;
            }
            vBodyHair = visualClass.getMethod("getBodyHairIndex");
            vHairModel = visualClass.getMethod("getHairModel");
            vBeardModel = visualClass.getMethod("getBeardModel");
            vHairColor = visualClass.getMethod("getHairColor");
            vOutfit = visualClass.getMethod("getOutfit");
            outfitName = outfit.getMethod("getName");
            fR = color.getField("r");
            fG = color.getField("g");
            fB = color.getField("b");
            cRedInt = color.getMethod("getRedInt");
            cGreenInt = color.getMethod("getGreenInt");
            cBlueInt = color.getMethod("getBlueInt");
            modelManager = mm.getField("instance").get(null);
            fFemaleModel = mm.getField("femaleModel");
            fMaleModel = mm.getField("maleModel");
            getLoadedModel = mm.getMethod("getLoadedModel", String.class);
            scriptManager = sm.getField("instance").get(null);
            getModelScript = sm.getMethod("getModelScript", String.class);
            ready = true;
            String note = checkSource(cl);
            log("ready (" + mode + ") v" + RBSAgent.VERSION + " - zombies and corpses use RBS_Zombie*_{Thin,Normal,Fat,Obese} model scripts; " + note);
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
                warn("the game's classes changed (game update?) but the stale patched copies are in use - run install.bat (or zombie_patch.py --patch --install) again:" + stale);
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
        return variant(id, weights);
    }

    static int variant(int id, int[] w) {
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
            boolean en = enabled, fe = true, pl = true;
            int[] wf = null, wm = null;
            Map<String, int[]> gw = new java.util.HashMap<>();
            Map<String, String> og = new java.util.HashMap<>();
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                try {
                    if (k.startsWith("chances.")) {
                        String[] parts = v.split(",");
                        if (parts.length == 4) {
                            int[] c = new int[4];
                            for (int i = 0; i < 4; i++) c[i] = Math.max(0, Integer.parseInt(parts[i].trim()));
                            String g = k.substring(8);
                            if (g.equals("female")) wf = c;
                            else if (g.equals("male")) wm = c;
                            else gw.put(g, c);
                        }
                        continue;
                    }
                    if (k.startsWith("outfits.")) {
                        String g = k.substring(8);
                        for (String o : v.split(",")) if (!o.trim().isEmpty()) og.put(o.trim().toLowerCase(java.util.Locale.ROOT), g);
                        continue;
                    }
                    switch (k) {
                        case "enabled": en = !(v.equals("0") || v.equalsIgnoreCase("false")); break;
                        case "thin": w[0] = Integer.parseInt(v); break;
                        case "normal": w[1] = Integer.parseInt(v); break;
                        case "fat": w[2] = Integer.parseInt(v); break;
                        case "obese": w[3] = Integer.parseInt(v); break;
                        case "female": fe = !(v.equals("0") || v.equalsIgnoreCase("false")); break;
                        case "players": pl = !(v.equals("0") || v.equalsIgnoreCase("false")); break;
                        default: break;
                    }
                } catch (NumberFormatException ignored) {}
            }
            weights = w;
            wFemale = wf;
            wMale = wm;
            groupWeights = gw;
            outfitGroup = og;
            enabled = en;
            females = fe;
            others = pl;
            cfgVersion++;
            StringBuilder groups = new StringBuilder();
            for (Map.Entry<String, int[]> e : gw.entrySet()) {
                int[] c = e.getValue();
                groups.append(", ").append(e.getKey()).append(' ').append(c[0]).append('/').append(c[1]).append('/').append(c[2]).append('/').append(c[3]);
            }
            log("config: enabled=" + en + " thin/normal/fat/obese="
                    + (wf != null ? "women " + wf[0] + "/" + wf[1] + "/" + wf[2] + "/" + wf[3] + ", men " + (wm != null ? wm : w)[0] + "/"
                        + (wm != null ? wm : w)[1] + "/" + (wm != null ? wm : w)[2] + "/" + (wm != null ? wm : w)[3]
                        : w[0] + "/" + w[1] + "/" + w[2] + "/" + w[3])
                    + (gw.isEmpty() ? "" : "; by line of work (" + og.size() + " outfits)" + groups)
                    + (fe ? "" : ", female zombies keep the body the game loads")
                    + (pl ? "" : ", other players keep the shared body"));
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

    /** what went wrong, through the reflection wrappers (an InvocationTargetException says nothing by itself), with the
     *  first places it came from */
    static String why(Throwable e) {
        Throwable c = e;
        while ((c instanceof java.lang.reflect.InvocationTargetException
                || c instanceof java.lang.reflect.UndeclaredThrowableException) && c.getCause() != null) c = c.getCause();
        StringBuilder b = new StringBuilder(String.valueOf(c));
        StackTraceElement[] st = c.getStackTrace();
        for (int i = 0; i < st.length && i < 3; i++) b.append(i == 0 ? " at " : " < ").append(st[i]);
        return b.toString();
    }
}
