package rbs;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * RealBodyShape zombie patch (local use only, not a Workshop item).
 * Four game methods are redirected to RBSHook so zombies get their own bodies and (optionally) missing limbs:
 *   ModelManager.getBodyModel(IsoGameCharacter)   -> getBodyModel$rbs       + RBSHook.getBodyModel(mgr, chr)
 *   HumanVisual.getModel()                         -> getModel$rbs           + RBSHook.visualModel(visual)      (corpses)
 *   AnimationPlayer.getSkinTransforms(SkinningData)-> getSkinTransforms$rbs  + RBSHook.skinTransforms(player, data)
 *   AnimatedModel.getAnimationPlayer()             -> getAnimationPlayer$rbs + RBSHook.animatedPlayer(model)    (corpse atlas)
 * Two ways to apply the same patch:
 *   1. classpath mode (default, used by zombie_patch.py / install.bat): rbs_patch.jar holds the pre-patched classes
 *      and is listed first in ProjectZomboid64.json "classpath", so it shadows the game's copies.
 *   2. javaagent mode: -javaagent:rbs_agent.jar patches while loading. Works with java.exe, but the game's own
 *      ProjectZomboid64.exe launcher cannot load the JVM's instrument library, so 1. is what gets installed.
 * The game files on disk are never modified.
 */
public final class RBSAgent {
    public static final String VERSION = "5";
    static final String HOOK_CLASS = "rbs/RBSHook";
    static final String MODEL = "zombie/core/skinnedmodel/model/Model";
    static final String RENAMED_BODY = "getBodyModel$rbs";
    static final String RENAMED_VISUAL = "getModel$rbs";
    static final String RENAMED_SKIN = "getSkinTransforms$rbs";
    static final String RENAMED_ANIMATED = "getAnimationPlayer$rbs";

    /** {class (internal name), method, descriptor, renamed-to, hook method, hook descriptor, cast class of the result} */
    static final String[][] SPECS = {
        { "zombie/core/skinnedmodel/ModelManager", "getBodyModel",
          "(Lzombie/characters/IsoGameCharacter;)Lzombie/core/skinnedmodel/model/Model;", RENAMED_BODY,
          "getBodyModel", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", MODEL },
        { "zombie/core/skinnedmodel/visual/HumanVisual", "getModel",
          "()Lzombie/core/skinnedmodel/model/Model;", RENAMED_VISUAL,
          "visualModel", "(Ljava/lang/Object;)Ljava/lang/Object;", MODEL },
        { "zombie/core/skinnedmodel/animation/AnimationPlayer", "getSkinTransforms",
          "(Lzombie/core/skinnedmodel/model/SkinningData;)[Lorg/lwjgl/util/vector/Matrix4f;", RENAMED_SKIN,
          "skinTransforms", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", "[Lorg/lwjgl/util/vector/Matrix4f;" },
        { "zombie/core/skinnedmodel/advancedanimation/AnimatedModel", "getAnimationPlayer",
          "()Lzombie/core/skinnedmodel/animation/AnimationPlayer;", RENAMED_ANIMATED,
          "animatedPlayer", "(Ljava/lang/Object;)Ljava/lang/Object;", "zombie/core/skinnedmodel/animation/AnimationPlayer" },
    };
    static volatile boolean agentActive;

    public static void premain(String args, Instrumentation inst) {
        agentActive = true;
        log("v" + VERSION + " loaded");
        Marker.write(false, "javaagent", "waiting");
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
                String[] spec = specFor(name);
                if (spec == null) return null;
                try {
                    byte[] out = patch(spec, bytes);
                    log("patched " + name + "." + spec[1] + " (" + bytes.length + " -> " + out.length + " bytes)");
                    return out;
                } catch (Throwable t) {
                    log("patch of " + name + " failed, game keeps the vanilla behaviour: " + t);
                    Marker.write(false, "javaagent", String.valueOf(t));
                    return null;
                }
            }
        });
    }

    static String[] specFor(String internalName) {
        for (String[] s : SPECS) if (s[0].equals(internalName)) return s;
        return null;
    }

    static byte[] patch(String[] spec, byte[] bytes) throws java.io.IOException {
        return ClassPatcher.patch(bytes, spec[1], spec[2], spec[3], HOOK_CLASS, spec[4], spec[5], spec[6]);
    }

    static void log(String s) {
        System.out.println("[RBS agent] " + s);
    }
}
