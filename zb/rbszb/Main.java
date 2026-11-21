package rbszb;

/** Run by ZombieBuddy when it loads the jar (javaPkgName rbszb). The patches (Patches) are found and applied by it. */
public class Main {
    public static void main(String[] args) {
        RBSHook.log("ZombieBuddy build v" + RBSAgent.VERSION + " loaded - its patches take over from the classpath patch "
                + "as soon as one of them runs");
        preload();
    }

    /**
     * The game's classes our patches are in, loaded now (not initialised: nothing of theirs runs) - before ZombieBuddy
     * applies the patches, so it finds them loaded and patches them there and then.
     *
     * With Project Viewpoint (another ZombieBuddy mod, its jar right after ours) ModelManager first loaded while its
     * patches went in (one of them is on ModelManager$ModelSlot), and a class loaded then is not patched: our
     * getBodyModel / newAdditionalModelInstance never ran - the players' clothes and hair kept the plain shader, so bags
     * and hair stayed still in the isometric view, and the zombie clothes copies were not used (user, 2026-11-21: "ทั้ง
     * ทรงผมและกระเป๋า" on Tomb's body; the log had no "patching ...ModelManager" line). ModelInstanceTextureCreator too.
     */
    private static void preload() {
        ClassLoader cl = Main.class.getClassLoader();
        StringBuilder done = new StringBuilder(), failed = new StringBuilder();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (Class<?> p : Patches.class.getDeclaredClasses()) {
            me.zed_0xff.zombie_buddy.Patch a = p.getAnnotation(me.zed_0xff.zombie_buddy.Patch.class);
            if (a == null || !a.className().startsWith("zombie.") || !seen.add(a.className())) continue;
            try {
                Class.forName(a.className(), false, cl);
                done.append(done.length() == 0 ? "" : ", ").append(a.className().substring(a.className().lastIndexOf('.') + 1));
            } catch (Throwable t) {
                failed.append(failed.length() == 0 ? "" : ", ").append(a.className()).append(" (").append(t).append(')');
            }
        }
        RBSHook.log("ZombieBuddy build: the game's classes to patch loaded ahead (" + done + ")"
                + (failed.length() == 0 ? "" : "; not found: " + failed));
    }
}
