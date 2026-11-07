package rbszb;

import me.zed_0xff.zombie_buddy.Patch;
import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Quaternion;
import zombie.characters.IsoGameCharacter;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.SkinningData;
import zombie.core.skinnedmodel.population.ClothingItem;
import zombie.core.skinnedmodel.visual.HumanVisual;
import zombie.core.skinnedmodel.visual.ItemVisual;
import zombie.core.skinnedmodel.visual.ItemVisuals;

/**
 * Real Body Shape's ten game hooks as ZombieBuddy patches (Byte Buddy advice: the code below is copied into the game
 * method, before or after its own). They call the same RBSHook functions the classpath patch calls, so both builds do
 * exactly the same; the first one of these that runs marks the JVM and the classpath copy steps aside (RBSHook.zbMark).
 * strictMatch: only the overload whose parameter types are the @Argument types below.
 */
public final class Patches {
    private Patches() {}

    @Patch(className = "zombie.core.skinnedmodel.ModelManager", methodName = "getBodyModel", strictMatch = true)
    public static class BodyModel {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) IsoGameCharacter chr, @Patch.Return(readOnly = false) Model ret) {
            RBSHook.zbMark();
            ret = (Model) RBSHook.afterBodyModel(chr, ret);
        }
    }

    @Patch(className = "zombie.core.skinnedmodel.visual.HumanVisual", methodName = "getModel", strictMatch = true)
    public static class VisualModel {
        @Patch.OnExit
        public static void exit(@Patch.This Object visual, @Patch.Return(readOnly = false) Model ret) {
            RBSHook.zbMark();
            ret = (Model) RBSHook.afterVisualModel(visual, ret);
        }
    }

    @Patch(className = "zombie.core.skinnedmodel.animation.AnimationPlayer", methodName = "getSkinTransforms", strictMatch = true)
    public static class SkinTransforms {
        @Patch.OnExit
        public static void exit(@Patch.This Object player, @Patch.Argument(0) SkinningData data,
                                @Patch.Return(readOnly = false) Matrix4f[] ret) {
            RBSHook.zbMark();
            ret = (Matrix4f[]) RBSHook.afterSkinTransforms(player, data, ret);
        }
    }

    @Patch(className = "zombie.core.skinnedmodel.animation.AnimationPlayer", methodName = "UpdateSkinTransforms", strictMatch = true)
    public static class UpdateSkin {
        @Patch.OnExit
        public static void exit(@Patch.This Object player) {
            RBSHook.zbMark();
            RBSHook.afterUpdateSkinTransforms(player);
        }
    }

    @Patch(className = "zombie.core.skinnedmodel.animation.AnimationPlayer", methodName = "transformRootChildBones", strictMatch = true)
    public static class RootChildBones {
        @Patch.OnExit
        public static void exit(@Patch.This Object player, @Patch.Argument(0) String bone, @Patch.Argument(1) Quaternion rotation) {
            RBSHook.zbMark();
            RBSHook.afterRootChildBones(player);
        }
    }

    @Patch(className = "zombie.core.skinnedmodel.advancedanimation.AnimatedModel", methodName = "getAnimationPlayer", strictMatch = true)
    public static class AnimatedPlayer {
        @Patch.OnExit
        public static void exit(@Patch.This Object model, @Patch.Return AnimationPlayer ret) {
            RBSHook.zbMark();
            RBSHook.afterAnimatedPlayer(model, ret);
        }
    }

    @Patch(className = "zombie.core.skinnedmodel.ModelManager", methodName = "newAdditionalModelInstance", strictMatch = true)
    public static class AdditionalModel {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(value = 0, readOnly = false) String mesh, @Patch.Argument(1) String tex,
                                 @Patch.Argument(2) IsoGameCharacter chr, @Patch.Argument(3) AnimationPlayer ap,
                                 @Patch.Argument(value = 4, readOnly = false) String shader) {
            RBSHook.zbMark();
            mesh = (String) RBSHook.additionalMesh(mesh, chr);
            shader = (String) RBSHook.additionalShader(shader, mesh, chr);
        }
    }

    @Patch(className = "zombie.core.skinnedmodel.advancedanimation.AnimatedModel", methodName = "addClothingItem", strictMatch = true)
    public static class AnimatedClothing {
        @Patch.OnEnter
        public static void enter(@Patch.This Object model, @Patch.Argument(0) ItemVisual itemVisual,
                                 @Patch.Argument(1) ClothingItem clothingItem) {
            RBSHook.zbMark();
            RBSHook.enterClothing(model);
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit() {
            RBSHook.exitClothing();
        }
    }

    @Patch(className = "zombie.core.textures.SmartTexture", methodName = "calculate", strictMatch = true)
    public static class SmartCalculate {
        @Patch.OnEnter
        public static void enter(@Patch.This Object smart) {
            RBSHook.zbMark();
            RBSHook.beforeSmartCalculate(smart);
        }
    }

    @Patch(className = "zombie.core.skinnedmodel.model.ModelInstanceTextureCreator", methodName = "init", strictMatch = true)
    public static class TextureCreatorInit {
        @Patch.OnExit
        public static void exit(@Patch.This Object creator, @Patch.Argument(0) HumanVisual visual,
                                @Patch.Argument(1) ItemVisuals items, @Patch.Argument(2) ModelInstance modelInstance) {
            RBSHook.zbMark();
            RBSHook.afterTextureCreatorInit(creator);
        }
    }
}
