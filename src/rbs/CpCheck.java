package rbs;

import java.lang.reflect.Method;

/**
 * Offline check of classpath mode:  java -cp "rbs_patch.jar;projectzomboid.jar" rbs.CpCheck
 * Loads every patched class the way the game launcher would and reports where it came from and whether it is
 * patched. Exit code 0 = the patched copies shadow the game's classes.
 */
public final class CpCheck {
    public static void main(String[] a) throws Exception {
        int bad = 0;
        for (String[] spec : RBSAgent.SPECS) {
            String cn = spec[0].replace('/', '.');
            Class<?> c = Class.forName(cn, false, CpCheck.class.getClassLoader());
            System.out.println(cn + " from: " + c.getProtectionDomain().getCodeSource().getLocation());
            int found = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(spec[1]) || m.getName().equals(spec[3])) { System.out.println("  " + m.getName()); found++; }
            }
            if (found != 2) { System.out.println("  FAILED: game's own class is used"); bad++; }
        }
        System.out.println(bad == 0 ? "OK: patched classes are first on the classpath" : "FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
