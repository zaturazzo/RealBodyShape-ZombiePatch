package rbszb;

/** Run by ZombieBuddy when it loads the jar (javaPkgName rbszb). The patches (Patches) are found and applied by it. */
public class Main {
    public static void main(String[] args) {
        RBSHook.log("ZombieBuddy build v" + RBSAgent.VERSION + " loaded - its patches take over from the classpath patch "
                + "as soon as one of them runs");
    }
}
