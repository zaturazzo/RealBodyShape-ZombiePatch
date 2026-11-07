package rbs;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * RealBodyShape zombie patch (local use only, not a Workshop item).
 * Ten methods of six game classes are redirected to RBSHook so zombies get their own bodies and heights, clothes
 * that are not the player's reshaped ones, and (optionally) missing limbs:
 *   ModelManager.getBodyModel(IsoGameCharacter)   -> getBodyModel$rbs       + RBSHook.getBodyModel(mgr, chr)
 *   ModelManager.newAdditionalModelInstance(...)   -> newAdditionalModelInstance$rbs + RBSHook.additionalModel(...)
 *   HumanVisual.getModel()                         -> getModel$rbs           + RBSHook.visualModel(visual)      (corpses)
 *   AnimationPlayer.getSkinTransforms(SkinningData)-> getSkinTransforms$rbs  + RBSHook.skinTransforms(player, data)
 *   AnimationPlayer.UpdateSkinTransforms()         -> UpdateSkinTransforms$rbs + RBSHook.updateSkinTransforms(player) (heights)
 *   AnimationPlayer.transformRootChildBones(...)   -> transformRootChildBones$rbs + RBSHook.rootChildBones(...)  (heights)
 *   AnimatedModel.getAnimationPlayer()             -> getAnimationPlayer$rbs + RBSHook.animatedPlayer(model)    (corpse atlas)
 *   AnimatedModel.addClothingItem(...)             -> addClothingItem$rbs    + RBSHook.animatedClothing(...)    (corpse atlas)
 *   SmartTexture.calculate()                       -> calculate$rbs          + RBSHook.smartCalculate(st)       (face layer)
 *   ModelInstanceTextureCreator.init(visual, ...)  -> init$rbs               + RBSHook.textureCreatorInit(...)  (face layer)
 * Two ways to apply the same patch:
 *   1. classpath mode (default, used by zombie_patch.py / install.bat): rbs_patch.jar holds the pre-patched classes
 *      and is listed first in ProjectZomboid64.json "classpath", so it shadows the game's copies.
 *   2. javaagent mode: -javaagent:rbs_agent.jar patches while loading. Works with java.exe, but the game's own
 *      ProjectZomboid64.exe launcher cannot load the JVM's instrument library, so 1. is what gets installed.
 * The game files on disk are never modified.
 */
public final class RBSAgent {
    public static final String VERSION = "6";
    static final String HOOK_CLASS = "rbs/RBSHook";
    static final String MODEL = "zombie/core/skinnedmodel/model/Model";
    static final String RENAMED_BODY = "getBodyModel$rbs";
    static final String RENAMED_VISUAL = "getModel$rbs";
    static final String RENAMED_SKIN = "getSkinTransforms$rbs";
    static final String RENAMED_ANIMATED = "getAnimationPlayer$rbs";
    static final String RENAMED_ADDITIONAL = "newAdditionalModelInstance$rbs";
    static final String RENAMED_CLOTHING = "addClothingItem$rbs";
    static final String RENAMED_UPDATE_SKIN = "UpdateSkinTransforms$rbs";
    static final String RENAMED_ROOT_CHILD = "transformRootChildBones$rbs";
    static final String ADDITIONAL_DESC = "(Ljava/lang/String;Ljava/lang/String;Lzombie/characters/IsoGameCharacter;"
            + "Lzombie/core/skinnedmodel/animation/AnimationPlayer;Ljava/lang/String;)Lzombie/core/skinnedmodel/model/ModelInstance;";
    static final String CLOTHING_DESC = "(Lzombie/core/skinnedmodel/visual/ItemVisual;Lzombie/core/skinnedmodel/population/ClothingItem;)V";
    static final String RENAMED_CALCULATE = "calculate$rbs";
    static final String RENAMED_CREATOR_INIT = "init$rbs";
    static final String CREATOR_INIT_DESC = "(Lzombie/core/skinnedmodel/visual/HumanVisual;Lzombie/core/skinnedmodel/visual/ItemVisuals;"
            + "Lzombie/core/skinnedmodel/model/ModelInstance;)V";

    /**
     * {class (internal name), method, descriptor, renamed-to, hook method, hook descriptor, cast class of the result
     * (null for void)}. A class can appear more than once: its specs are applied one after another.
     */
    static final String[][] SPECS = {
        { "zombie/core/skinnedmodel/ModelManager", "getBodyModel",
          "(Lzombie/characters/IsoGameCharacter;)Lzombie/core/skinnedmodel/model/Model;", RENAMED_BODY,
          "getBodyModel", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", MODEL },
        { "zombie/core/skinnedmodel/ModelManager", "newAdditionalModelInstance", ADDITIONAL_DESC, RENAMED_ADDITIONAL,
          "additionalModel", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
          "zombie/core/skinnedmodel/model/ModelInstance" },
        { "zombie/core/skinnedmodel/visual/HumanVisual", "getModel",
          "()Lzombie/core/skinnedmodel/model/Model;", RENAMED_VISUAL,
          "visualModel", "(Ljava/lang/Object;)Ljava/lang/Object;", MODEL },
        { "zombie/core/skinnedmodel/animation/AnimationPlayer", "getSkinTransforms",
          "(Lzombie/core/skinnedmodel/model/SkinningData;)[Lorg/lwjgl/util/vector/Matrix4f;", RENAMED_SKIN,
          "skinTransforms", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", "[Lorg/lwjgl/util/vector/Matrix4f;" },
        { "zombie/core/skinnedmodel/animation/AnimationPlayer", "UpdateSkinTransforms", "()V", RENAMED_UPDATE_SKIN,
          "updateSkinTransforms", "(Ljava/lang/Object;)V", null },
        { "zombie/core/skinnedmodel/animation/AnimationPlayer", "transformRootChildBones",
          "(Ljava/lang/String;Lorg/lwjgl/util/vector/Quaternion;)V", RENAMED_ROOT_CHILD,
          "rootChildBones", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", null },
        { "zombie/core/skinnedmodel/advancedanimation/AnimatedModel", "getAnimationPlayer",
          "()Lzombie/core/skinnedmodel/animation/AnimationPlayer;", RENAMED_ANIMATED,
          "animatedPlayer", "(Ljava/lang/Object;)Ljava/lang/Object;", "zombie/core/skinnedmodel/animation/AnimationPlayer" },
        { "zombie/core/skinnedmodel/advancedanimation/AnimatedModel", "addClothingItem", CLOTHING_DESC, RENAMED_CLOTHING,
          "animatedClothing", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", null },
        { "zombie/core/textures/SmartTexture", "calculate", "()V", RENAMED_CALCULATE,
          "smartCalculate", "(Ljava/lang/Object;)V", null },
        { "zombie/core/skinnedmodel/model/ModelInstanceTextureCreator", "init", CREATOR_INIT_DESC, RENAMED_CREATOR_INIT,
          "textureCreatorInit", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", null },
    };
    static volatile boolean agentActive;

    public static void premain(String args, Instrumentation inst) {
        agentActive = true;
        log("v" + VERSION + " loaded");
        Marker.write(false, "javaagent", "waiting");
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
                if (!targets().contains(name)) return null;
                try {
                    byte[] out = patchClass(name, bytes);
                    log("patched " + name + " (" + bytes.length + " -> " + out.length + " bytes)");
                    return out;
                } catch (Throwable t) {
                    log("patch of " + name + " failed, game keeps the vanilla behaviour: " + t);
                    Marker.write(false, "javaagent", String.valueOf(t));
                    return null;
                }
            }
        });
    }

    /** the game classes the specs touch, in order of first appearance */
    static java.util.List<String> targets() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String[] s : SPECS) if (!out.contains(s[0])) out.add(s[0]);
        return out;
    }

    /** every spec of this class, applied one after another to the class bytes */
    static byte[] patchClass(String internalName, byte[] bytes) throws java.io.IOException {
        byte[] out = bytes;
        for (String[] s : SPECS) {
            if (s[0].equals(internalName)) out = ClassPatcher.patch(out, s[1], s[2], s[3], HOOK_CLASS, s[4], s[5], s[6]);
        }
        return out;
    }

    static void log(String s) {
        System.out.println("[RBS agent] " + s);
    }
}
