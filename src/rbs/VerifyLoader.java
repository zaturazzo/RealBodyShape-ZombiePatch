package rbs;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Class loader for checking patched game classes offline: every class of projectzomboid.jar is defined here (the
 * patched ones from their new bytes), so a patched class and the game classes next to it share one loader and one
 * runtime package, as in the game. (With only the patched classes in a child loader, a patched SmartTexture was a
 * different class from the one CharacterSmartTexture extends, and its protected access to Texture failed: false
 * VerifyErrors, 2026-11-07.) Our own rbs.* classes and the JDK come from the parent.
 */
final class VerifyLoader extends ClassLoader {
    private final JarFile jar;
    private final Map<String, byte[]> patched;        // binary name (dots) -> bytes

    VerifyLoader(JarFile jar, Map<String, byte[]> patched, ClassLoader parent) {
        super(parent);
        this.jar = jar;
        this.patched = patched;
    }

    @Override
    protected Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
        if (n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jdk.") || n.startsWith("sun.")
                || n.startsWith("rbs.")) {
            return super.loadClass(n, resolve);
        }
        synchronized (getClassLoadingLock(n)) {
            Class<?> c = findLoadedClass(n);
            if (c != null) return c;
            byte[] b = patched.get(n);
            if (b == null) {
                JarEntry e = jar.getJarEntry(n.replace('.', '/') + ".class");
                if (e == null) return super.loadClass(n, resolve);
                try (InputStream in = jar.getInputStream(e)) {
                    b = in.readAllBytes();
                } catch (IOException x) {
                    throw new ClassNotFoundException(n, x);
                }
            }
            c = defineClass(n, b, 0, b.length);
            if (resolve) resolveClass(c);
            return c;
        }
    }
}
