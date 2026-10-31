package rbs;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * End-user installer (no Python, no JDK: runs on the game's own jre64):
 *
 *   java -cp "rbs_agent.jar;<game>/projectzomboid.jar" rbs.MakePatch install|uninstall|status <game dir>
 *
 * install  : patches ModelManager + HumanVisual from the user's own projectzomboid.jar (never redistributed),
 *            verifies them with the JVM, writes ~/Zomboid/RealBodyShape/rbs_patch.jar and lists that jar first in
 *            the "classpath" of <game>/ProjectZomboid64.json (backup: ProjectZomboid64.json.rbs_backup).
 * uninstall: removes the jar from the launcher config again (the game is back to normal).
 * status   : shows what is installed and whether the patch still matches the game version.
 * Nothing inside the game folder is modified except that one launcher config line.
 */
public final class MakePatch {
    static final String JAR_NAME = "rbs_patch.jar";
    static final String LAUNCHER = "ProjectZomboid64.json";

    public static void main(String[] a) throws Exception {
        String cmd = a.length > 0 ? a[0].toLowerCase() : "status";
        Path game = a.length > 1 ? Paths.get(a[1]) : findGame();
        if (game == null || !Files.exists(game.resolve("projectzomboid.jar"))) {
            System.out.println("ERROR: Project Zomboid folder not found (projectzomboid.jar). Pass it as the 2nd argument.");
            System.exit(2);
        }
        Path installDir = Paths.get(System.getProperty("user.home"), "Zomboid", "RealBodyShape");
        Path jar = installDir.resolve(JAR_NAME);
        Path launcher = game.resolve(LAUNCHER);
        System.out.println("game     : " + game);
        switch (cmd) {
            case "install":
                install(game, installDir, jar, launcher);
                break;
            case "uninstall":
                uninstall(launcher);
                break;
            case "status":
                status(game, jar, launcher);
                break;
            default:
                System.out.println("usage: MakePatch install|uninstall|status [game dir]");
                System.exit(2);
        }
    }

    // ------------------------------------------------------------------ install
    static void install(Path game, Path installDir, Path jar, Path launcher) throws Exception {
        Path gameJar = game.resolve("projectzomboid.jar");
        Map<String, byte[]> patched = new HashMap<>();
        List<String> hashes = new ArrayList<>();
        try (JarFile jf = new JarFile(gameJar.toFile())) {
            for (String[] spec : RBSAgent.SPECS) {
                JarEntry e = jf.getJarEntry(spec[0] + ".class");
                if (e == null) throw new IOException("class " + spec[0] + " not found in projectzomboid.jar (unsupported game version)");
                byte[] orig = jf.getInputStream(e).readAllBytes();
                patched.put(spec[0] + ".class", RBSAgent.patch(spec, orig));
                hashes.add(spec[0] + ".class=" + RBSHook.sha256(orig));
                System.out.println("patched  : " + spec[0] + "." + spec[1] + " (" + orig.length + " -> " + patched.get(spec[0] + ".class").length + " bytes)");
            }
        }
        verify(patched);
        Files.createDirectories(installDir);
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> e : patched.entrySet()) put(z, e.getKey(), e.getValue());
            for (Map.Entry<String, byte[]> e : ownClasses().entrySet()) put(z, e.getKey(), e.getValue());
            put(z, "rbs/source.sha256", (String.join("\n", hashes) + "\n").getBytes(StandardCharsets.UTF_8));
            put(z, "rbs/source.txt", (gameJar + "\nsize=" + Files.size(gameJar) + "\nmtime=" + Files.getLastModifiedTime(gameJar).toMillis() + "\n").getBytes(StandardCharsets.UTF_8));
        }
        System.out.println("written  : " + jar + " (" + Files.size(jar) / 1024 + " KB)");
        editLauncher(launcher, jar, true);
        System.out.println("DONE. Start the game, enable BOTH mods 'Real Body Shape' and 'Real Body Shape - Zombie Bodies', then check console.txt for '[RBS hook] ready'.");
    }

    /** define the patched classes in a child loader and link them, so the JVM verifier checks the new bytecode */
    static void verify(Map<String, byte[]> patched) {
        final Map<String, byte[]> byName = new HashMap<>();
        for (Map.Entry<String, byte[]> e : patched.entrySet())
            byName.put(e.getKey().replace(".class", "").replace('/', '.'), e.getValue());
        ClassLoader child = new ClassLoader(MakePatch.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
                byte[] b = byName.get(n);
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
        for (String cn : byName.keySet()) {
            try {
                Class<?> c = Class.forName(cn, false, child);
                int found = 0;
                for (Method m : c.getDeclaredMethods()) if (m.getName().endsWith("$rbs")) found++;
                if (found == 0) throw new IllegalStateException("renamed method missing in " + cn);
                System.out.println("verified : " + cn);
            } catch (VerifyError | ClassFormatError e) {
                System.out.println("ERROR: the JVM rejected the patched class " + cn + ": " + e);
                System.exit(3);
            } catch (NoClassDefFoundError e) {
                System.out.println("verify   : skipped for " + cn + " (projectzomboid.jar not on the classpath: " + e.getMessage() + ")");
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** our own rbs/*.class files, taken from wherever this program runs from (jar or build directory) */
    static Map<String, byte[]> ownClasses() throws IOException {
        Map<String, byte[]> out = new HashMap<>();
        URL loc = MakePatch.class.getProtectionDomain().getCodeSource().getLocation();
        File f;
        try { f = new File(loc.toURI()); } catch (Exception e) { f = new File(loc.getPath()); }
        if (f.isFile()) {
            try (JarFile jf = new JarFile(f)) {
                Enumeration<JarEntry> en = jf.entries();
                while (en.hasMoreElements()) {
                    JarEntry e = en.nextElement();
                    if (e.getName().startsWith("rbs/") && e.getName().endsWith(".class"))
                        out.put(e.getName(), jf.getInputStream(e).readAllBytes());
                }
            }
        } else {
            Path dir = f.toPath().resolve("rbs");
            try (var s = Files.list(dir)) {
                for (Path p : (Iterable<Path>) s::iterator)
                    if (p.toString().endsWith(".class")) out.put("rbs/" + p.getFileName(), Files.readAllBytes(p));
            }
        }
        if (out.isEmpty()) throw new IOException("cannot find rbs/*.class next to " + loc);
        return out;
    }

    static void put(ZipOutputStream z, String name, byte[] data) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    // ------------------------------------------------------------------ launcher config
    static String jarRef(Path jar) {
        return jar.toAbsolutePath().toString().replace('\\', '/');
    }

    static void editLauncher(Path launcher, Path jar, boolean add) throws IOException {
        if (!Files.exists(launcher)) throw new IOException(launcher + " not found");
        String text = new String(Files.readAllBytes(launcher), StandardCharsets.UTF_8);
        Path backup = launcher.resolveSibling(LAUNCHER + ".rbs_backup");
        if (!Files.exists(backup)) Files.copy(launcher, backup, StandardCopyOption.COPY_ATTRIBUTES);
        String cleaned = removeOurs(text);
        if (add) {
            Matcher m = Pattern.compile("\"classpath\"\\s*:\\s*\\[").matcher(cleaned);
            if (!m.find()) throw new IOException("\"classpath\" array not found in " + launcher);
            int at = m.end();
            Matcher ind = Pattern.compile("\\r?\\n([ \\t]*)").matcher(cleaned);
            String indent = ind.find(at) ? ind.group(1) : "        ";
            String nl = cleaned.contains("\r\n") ? "\r\n" : "\n";
            cleaned = cleaned.substring(0, at) + nl + indent + "\"" + jarRef(jar) + "\"," + cleaned.substring(at);
        }
        if (!cleaned.equals(text)) {
            Files.write(launcher, cleaned.getBytes(StandardCharsets.UTF_8));
            System.out.println((add ? "launcher : " + jarRef(jar) + " added first in \"classpath\" of " : "launcher : patch removed from ") + launcher);
            System.out.println("backup   : " + backup);
        } else {
            System.out.println("launcher : nothing to change in " + launcher);
        }
    }

    /** drop our classpath line and any old -javaagent line, then repair a trailing comma before ']' */
    static String removeOurs(String text) {
        String out = text.replaceAll("(?m)^[ \\t]*\"[^\"\\r\\n]*rbs_patch\\.jar\"\\s*,?[ \\t]*\\r?\\n", "");
        out = out.replaceAll("(?m)^[ \\t]*\"-javaagent:[^\"\\r\\n]*rbs_agent\\.jar\"\\s*,?[ \\t]*\\r?\\n", "");
        out = out.replaceAll(",(\\s*)\\]", "$1]");
        return out;
    }

    static void uninstall(Path launcher) throws IOException {
        editLauncher(launcher, null, false);
        System.out.println("DONE. The game starts without the patch again (the jar in ~/Zomboid/RealBodyShape can stay).");
    }

    // ------------------------------------------------------------------ status
    static void status(Path game, Path jar, Path launcher) throws Exception {
        String text = Files.exists(launcher) ? new String(Files.readAllBytes(launcher), StandardCharsets.UTF_8) : "";
        boolean listed = text.contains("rbs_patch.jar");
        System.out.println("launcher : " + launcher + " -> " + (listed ? "patch listed in classpath" : "patch NOT installed"));
        if (text.contains("-javaagent:") && text.contains("rbs_agent.jar"))
            System.out.println("WARNING  : an old -javaagent line is still in vmArgs (the game would not start) - run uninstall then install");
        System.out.println("jar      : " + jar + " -> " + (Files.exists(jar) ? "present" : "missing"));
        if (Files.exists(jar)) {
            try (JarFile jf = new JarFile(jar.toFile()); JarFile gj = new JarFile(game.resolve("projectzomboid.jar").toFile())) {
                JarEntry e = jf.getJarEntry("rbs/source.sha256");
                List<String> stale = new ArrayList<>();
                int n = 0;
                if (e != null) {
                    for (String line : new String(jf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                        int eq = line.indexOf('=');
                        if (eq < 0) continue;
                        n++;
                        JarEntry ge = gj.getJarEntry(line.substring(0, eq));
                        if (ge == null || !RBSHook.sha256(gj.getInputStream(ge).readAllBytes()).equals(line.substring(eq + 1).trim()))
                            stale.add(line.substring(0, eq));
                    }
                }
                System.out.println("version  : " + n + " classes " + (stale.isEmpty() ? "match the current game" : "STALE " + stale + " (game updated - run install again)"));
            }
        }
        Path marker = Marker.path();
        System.out.println("marker   : " + marker + " -> " + (Files.exists(marker) ? new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).replace("\n", " ") : "absent (game not running, or patch never ran)"));
        Path mod = Paths.get(System.getProperty("user.home"), "Zomboid", "mods", "RealBodyShapeZombies", "mod.info");
        System.out.println("mod      : enable 'Real Body Shape - Zombie Bodies' in the game's mod list (it comes with the Real Body Shape Workshop item)"
                + (Files.exists(mod) ? " [also found in ~/Zomboid/mods]" : ""));
    }

    // ------------------------------------------------------------------ game folder
    static Path findGame() {
        List<Path> cands = new ArrayList<>();
        String prop = System.getProperty("pz.dir");
        if (prop != null) cands.add(Paths.get(prop));
        cands.add(Paths.get("").toAbsolutePath());
        cands.add(Paths.get("").toAbsolutePath().getParent());
        String steam = System.getProperty("steam.path");
        List<Path> libs = new ArrayList<>();
        if (steam != null) libs.add(Paths.get(steam));
        String pf86 = System.getenv("ProgramFiles(x86)");
        if (pf86 != null) libs.add(Paths.get(pf86, "Steam"));
        for (Path lib : new ArrayList<>(libs)) {
            Path vdf = lib.resolve("steamapps/libraryfolders.vdf");
            if (Files.exists(vdf)) {
                try {
                    Matcher m = Pattern.compile("\"path\"\\s*\"([^\"]+)\"").matcher(new String(Files.readAllBytes(vdf), StandardCharsets.UTF_8));
                    while (m.find()) libs.add(Paths.get(m.group(1).replace("\\\\", "\\")));
                } catch (IOException ignored) {}
            }
        }
        for (Path lib : libs) cands.add(lib.resolve("steamapps/common/ProjectZomboid"));
        for (Path c : cands) {
            if (c != null && Files.exists(c.resolve("projectzomboid.jar"))) return c;
        }
        return null;
    }

    static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        in.transferTo(b);
        return b.toByteArray();
    }
}
