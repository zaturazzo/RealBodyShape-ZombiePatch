package rbs;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * Offline check (no game running):  java -cp "rbs_agent.jar;projectzomboid.jar" rbs.PatchTest <projectzomboid.jar> [outDir]
 * Patches every class in RBSAgent.SPECS from the real jar, writes <SimpleName>.patched.class to outDir, then
 * defines them in a child class loader and forces linking so the JVM verifier checks the new bytecode.
 * Exit code 0 = OK.
 */
public final class PatchTest {
    public static void main(String[] a) throws Exception {
        Path jar = Paths.get(a[0]);
        Path outDir = Paths.get(a.length > 1 ? a[1] : ".");
        Files.createDirectories(outDir);
        final Map<String, byte[]> patched = new HashMap<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            for (String cls : RBSAgent.targets()) {
                byte[] orig = jf.getInputStream(jf.getEntry(cls + ".class")).readAllBytes();
                byte[] out = RBSAgent.patchClass(cls, orig);
                String simple = cls.substring(cls.lastIndexOf('/') + 1);
                Files.write(outDir.resolve(simple + ".patched.class"), out);
                patched.put(cls.replace('/', '.'), out);
                System.out.println("patched " + cls + ": " + orig.length + " -> " + out.length + " bytes");
            }
        }
        ClassLoader child = new ClassLoader(PatchTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
                byte[] b = patched.get(n);
                if (b != null) {
                    synchronized (getClassLoadingLock(n)) {
                        Class<?> c = findLoadedClass(n);
                        if (c == null) c = defineClass(n, b, 0, b.length);
                        return c;
                    }
                }
                return super.loadClass(n, resolve);
            }
        };
        int bad = 0;
        for (String[] spec : RBSAgent.SPECS) {
            String cn = spec[0].replace('/', '.');
            try {
                Class<?> c = Class.forName(cn, false, child);
                boolean orig = false, renamed = false;      // by name: the game may have other overloads of the method
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(spec[1]) || m.getName().equals(spec[3])) System.out.println("  " + m);
                    if (m.getName().equals(spec[1])) orig = true;
                    if (m.getName().equals(spec[3])) renamed = true;
                }
                try {
                    Class.forName(cn, true, child);
                    System.out.println("  " + cn + ": class initialised");
                } catch (VerifyError | ClassFormatError e) {
                    throw e;
                } catch (Throwable t) {
                    System.out.println("  " + cn + ": verified OK (static init stopped at " + t.getClass().getSimpleName() + ", expected outside the game)");
                }
                if (!orig || !renamed) { System.out.println("  FAILED: expected " + spec[1] + " + " + spec[3]); bad++; }
            } catch (VerifyError | ClassFormatError e) {
                System.out.println("  VERIFY FAILED for " + cn + ": " + e);
                bad++;
            }
        }
        System.out.println(bad == 0 ? "OK" : "FAILED (" + bad + ")");
        System.exit(bad == 0 ? 0 : 1);
    }
}
