import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Offline check of the ZombieBuddy build (run on the game's JRE, classpath: our jar, ZombieBuddy.jar, projectzomboid.jar):
 * for every @Patch class, the target class loads, and exactly one method has its name and the @Argument types
 * (ZombieBuddy's strictMatch); a writable @Return must be the method's own return type, a writable @Argument its
 * parameter type; advice methods are public static. Prints OK / FAIL lines, exit code 1 on any failure.
 */
public class ZBCheck {
    public static void main(String[] args) throws Exception {
        String jar = args[0], pkg = args[1];
        int fails = 0, patches = 0;
        ClassLoader cl = ZBCheck.class.getClassLoader();
        try (JarFile jf = new JarFile(jar)) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (!n.startsWith(pkg.replace('.', '/') + "/") || !n.endsWith(".class")) continue;
                Class<?> c = Class.forName(n.substring(0, n.length() - 6).replace('/', '.'), false, cl);
                Patch p = c.getAnnotation(Patch.class);
                if (p == null) continue;
                patches++;
                List<String> errs = new ArrayList<>();
                Class<?> target;
                try {
                    target = Class.forName(p.className(), false, cl);
                } catch (Throwable t) {
                    System.out.println("FAIL " + c.getSimpleName() + ": target class " + p.className() + " - " + t);
                    fails++;
                    continue;
                }
                TreeMap<Integer, Class<?>> argTypes = new TreeMap<>();
                List<Method> advice = new ArrayList<>();
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getAnnotation(Patch.OnEnter.class) == null && m.getAnnotation(Patch.OnExit.class) == null) continue;
                    advice.add(m);
                    if (!java.lang.reflect.Modifier.isStatic(m.getModifiers()) || !java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                        errs.add(m.getName() + " is not public static");
                    for (Parameter prm : m.getParameters()) {
                        Patch.Argument a = prm.getAnnotation(Patch.Argument.class);
                        if (a != null) {
                            Class<?> was = argTypes.put(a.value(), prm.getType());
                            if (was != null && was != prm.getType()) errs.add("argument " + a.value() + " typed twice");
                        }
                    }
                }
                if (advice.isEmpty()) errs.add("no @OnEnter / @OnExit method");
                int count = argTypes.isEmpty() ? 0 : argTypes.lastKey() + 1;
                Class<?>[] want = new Class<?>[count];
                for (var e : argTypes.entrySet()) want[e.getKey()] = e.getValue();
                List<Method> hits = new ArrayList<>();
                for (Method tm : target.getDeclaredMethods()) {
                    if (!tm.getName().equals(p.methodName()) || tm.getParameterCount() != count) continue;
                    boolean ok = true;
                    for (int i = 0; i < count; i++) {
                        if (want[i] != null && want[i] != tm.getParameterTypes()[i]) ok = false;
                    }
                    if (ok) hits.add(tm);
                }
                if (hits.size() != 1) {
                    errs.add(hits.size() + " methods match " + p.methodName() + " with " + count + " argument(s)");
                } else {
                    Method tm = hits.get(0);
                    for (Method m : advice) {
                        for (Parameter prm : m.getParameters()) {
                            Patch.Return r = prm.getAnnotation(Patch.Return.class);
                            if (r != null) {
                                if (tm.getReturnType() == void.class) errs.add("@Return on a void method");
                                else if (!r.readOnly() && prm.getType() != tm.getReturnType())
                                    errs.add("writable @Return " + prm.getType().getSimpleName() + " != " + tm.getReturnType().getSimpleName());
                                else if (r.readOnly() && !prm.getType().isAssignableFrom(tm.getReturnType()))
                                    errs.add("@Return " + prm.getType().getSimpleName() + " cannot hold " + tm.getReturnType().getSimpleName());
                            }
                            Patch.Argument a = prm.getAnnotation(Patch.Argument.class);
                            if (a != null && !a.readOnly() && prm.getType() != tm.getParameterTypes()[a.value()])
                                errs.add("writable argument " + a.value() + " type");
                            if (prm.getAnnotation(Patch.This.class) != null && !prm.getType().isAssignableFrom(target))
                                errs.add("@This type");
                        }
                    }
                }
                if (errs.isEmpty()) {
                    System.out.println("OK   " + c.getSimpleName() + " -> " + target.getSimpleName() + "." + hits.get(0).getName()
                            + "(" + count + " arg)");
                } else {
                    System.out.println("FAIL " + c.getSimpleName() + " -> " + p.className() + "." + p.methodName() + ": " + String.join("; ", errs));
                    fails++;
                }
            }
        }
        System.out.println(patches + " patches, " + fails + " failed");
        System.exit(fails == 0 && patches > 0 ? 0 : 1);
    }
}
