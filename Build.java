import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the Dependency Explorer plugin and produces a zip that can be installed in Mirth Connect /
 * Open Integration Engine. Runs with JDK 11+ on any operating system:
 *
 * <pre>
 *   java Build.java --mirth "C:\Program Files\Mirth Connect"
 *   java Build.java --mirth /opt/mirthconnect
 * </pre>
 *
 * Steps: compile shared/server/client (Java 8 bytecode) → jars → sign the client jars → dist/depexplorer-&lt;version&gt;.zip
 */
public class Build {

    static final String VERSION = "1.1.0";
    static final String NAME = "depexplorer";

    /**
     * Mirth accepts an extension only when the server version (without the build number) is listed
     * literally in plugin.xml – there are no wildcards. So every possible 4.x version from 4.5.2 on is listed:
     * 4.5.2 … 4.19.19.
     */
    static String mirthVersions() {
        List<String> v = new ArrayList<>();
        for (int minor = 5; minor <= 19; minor++) {
            for (int patch = 0; patch <= 19; patch++) {
                if (minor == 5 && patch < 2) {
                    continue;
                }
                v.add("4." + minor + "." + patch);
            }
        }
        return String.join(", ", v);
    }

    static Path root = Paths.get("").toAbsolutePath();
    static Path out = root.resolve("build");

    public static void main(String[] args) throws Exception {
        String mirth = System.getenv("MIRTH_HOME");
        boolean sign = true;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--mirth") && i + 1 < args.length) {
                mirth = args[++i];
            } else if (args[i].equals("--no-sign")) {
                sign = false;
            }
        }
        if (mirth == null) {
            mirth = root.resolve("mirth-lib").toString();
        }
        Path mirthHome = Paths.get(mirth);
        if (!Files.isDirectory(mirthHome.resolve("server-lib")) || !Files.isDirectory(mirthHome.resolve("client-lib"))) {
            fail("Mirth installation folder not found: " + mirthHome + "\n  (it must contain server-lib and client-lib). Pass --mirth <folder>.");
        }

        deleteTree(out);
        Files.createDirectories(out);
        String serverCp = jars(mirthHome.resolve("server-lib"));
        String clientCp = jars(mirthHome.resolve("client-lib"));

        log("1/5 compile (target Java 8)");
        Path shared = compile("shared", serverCp + File.pathSeparator + clientCp);
        Path server = compile("server", serverCp + File.pathSeparator + shared);
        Path client = compile("client", clientCp + File.pathSeparator + shared);

        log("2/5 jar files");
        Path dist = out.resolve(NAME);
        Files.createDirectories(dist);
        writeJar(dist.resolve(NAME + "-shared.jar"), shared);
        writeJar(dist.resolve(NAME + "-client.jar"), client);
        writeJar(dist.resolve(NAME + "-server.jar"), server);

        log("3/5 plugin.xml");
        String xml = new String(Files.readAllBytes(root.resolve("plugin.xml")), StandardCharsets.UTF_8).replace("@VERSION@", VERSION).replace("@MIRTH_VERSIONS@",
                mirthVersions());
        Files.write(dist.resolve("plugin.xml"), xml.getBytes(StandardCharsets.UTF_8));

        if (sign) {
            log("4/5 signing client jars");
            signJars(dist.resolve(NAME + "-shared.jar"), dist.resolve(NAME + "-client.jar"));
        } else {
            log("4/5 signing skipped (--no-sign)");
        }

        log("5/5 zip");
        if (!Files.exists(root.resolve("webadmin/web/plugin.js"))) {
            fail("webadmin/web/plugin.js is missing - build the web half first: cd webadmin && npm install && npm run build");
        }
        Path dir = root.resolve("dist");
        Files.createDirectories(dir);
        Path zip = dir.resolve(NAME + "-" + VERSION + ".zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip.toFile()))) {
            // Mirth's installer (Extensions > Install) does not create folders itself;
            // the folder entry must come BEFORE the files in the zip.
            z.putNextEntry(new ZipEntry(NAME + "/"));
            z.closeEntry();
            for (String f : new String[] { "plugin.xml", NAME + "-shared.jar", NAME + "-server.jar", NAME + "-client.jar" }) {
                z.putNextEntry(new ZipEntry(NAME + "/" + f));
                Files.copy(dist.resolve(f), z);
                z.closeEntry();
            }
            // Web half for the OIE web administrator (served by the Web Support extension from <extension>/webadmin/)
            Path web = root.resolve("webadmin");
            z.putNextEntry(new ZipEntry(NAME + "/webadmin/"));
            z.closeEntry();
            z.putNextEntry(new ZipEntry(NAME + "/webadmin/plugin.json"));
            z.write(new String(Files.readAllBytes(web.resolve("plugin.json")), StandardCharsets.UTF_8)
                    .replaceFirst("\"version\"\\s*:\\s*\"[^\"]*\"", "\"version\": \"" + VERSION + "\"").getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry(NAME + "/webadmin/web/"));
            z.closeEntry();
            z.putNextEntry(new ZipEntry(NAME + "/webadmin/web/plugin.js"));
            Files.copy(web.resolve("web/plugin.js"), z);
            z.closeEntry();
        }
        log("OK -> " + zip);
    }

    static Path compile(String module, String cp) throws Exception {
        Path src = root.resolve("src").resolve(module);
        Path dest = out.resolve("classes").resolve(module);
        Files.createDirectories(dest);
        List<String> files;
        try (Stream<Path> s = Files.walk(src)) {
            files = s.filter(p -> p.toString().endsWith(".java")).map(Path::toString).collect(Collectors.toList());
        }
        List<String> a = new ArrayList<>(List.of("--release", "8", "-Xlint:-options", "-encoding", "UTF-8", "-nowarn", "-cp", cp, "-d", dest.toString()));
        a.addAll(files);
        ToolProvider javac = ToolProvider.findFirst("javac").orElseThrow(() -> new IllegalStateException("javac not found - use a JDK, not a JRE"));
        if (javac.run(System.out, System.err, a.toArray(new String[0])) != 0) {
            fail(module + " did not compile");
        }
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile).filter(p -> !p.toString().endsWith(".java"))::iterator) {
                Path t = dest.resolve(src.relativize(p).toString());
                Files.createDirectories(t.getParent());
                Files.copy(p, t);
            }
        }
        return dest;
    }

    static void writeJar(Path jar, Path classes) throws IOException {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().putValue("Implementation-Title", "Dependency Explorer");
        mf.getMainAttributes().putValue("Implementation-Version", VERSION);
        Set<String> seen = new HashSet<>();
        try (JarOutputStream jo = new JarOutputStream(new FileOutputStream(jar.toFile()), mf)) {
            seen.add("META-INF/MANIFEST.MF");
            try (Stream<Path> s = Files.walk(classes)) {
                for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile).sorted()::iterator) {
                    String name = classes.relativize(p).toString().replace(File.separatorChar, '/');
                    put(jo, seen, name, Files.readAllBytes(p));
                }
            }
        }
    }

    static void put(JarOutputStream jo, Set<String> seen, String name, byte[] data) throws IOException {
        if (!seen.add(name)) {
            return;
        }
        int i = 0;
        while ((i = name.indexOf('/', i) + 1) > 0) {
            String dir = name.substring(0, i);
            if (seen.add(dir)) {
                jo.putNextEntry(new JarEntry(dir));
                jo.closeEntry();
            }
        }
        jo.putNextEntry(new JarEntry(name));
        jo.write(data);
        jo.closeEntry();
    }

    static void signJars(Path... jars) throws Exception {
        Path ks = root.resolve("keystore/depexplorer-signing.p12");
        String pass = System.getenv().getOrDefault("DEPEXPLORER_KEYSTORE_PASS", "depexplorer-changeit");
        String alias = "depexplorer";
        String bin = Paths.get(System.getProperty("java.home"), "bin").toString();
        String exe = System.getProperty("os.name").toLowerCase().contains("win") ? ".exe" : "";
        if (!Files.exists(ks)) {
            Files.createDirectories(ks.getParent());
            log("   creating signing key: " + ks + " (self-signed; can be replaced by a corporate certificate)");
            exec(bin + File.separator + "keytool" + exe, "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "3072", "-validity", "3650", "-dname",
                    "CN=Dependency Explorer, O=Local Build", "-storetype", "PKCS12", "-keystore", ks.toString(), "-storepass", pass, "-keypass", pass);
        }
        for (Path j : jars) {
            exec(bin + File.separator + "jarsigner" + exe, "-keystore", ks.toString(), "-storetype", "PKCS12", "-storepass", pass, "-digestalg", "SHA-256",
                    j.toString(), alias);
        }
    }

    static void exec(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String o = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            fail("Command failed: " + cmd[0] + "\n" + o);
        }
    }

    static String jars(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".jar")).map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
        }
        in.close();
        return bo.toByteArray();
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : (Iterable<Path>) s.sorted((a, b) -> b.compareTo(a))::iterator) {
                Files.delete(x);
            }
        }
    }

    static void log(String s) {
        System.out.println("[build] " + s);
    }

    static void fail(String s) {
        System.err.println("[build] ERROR: " + s);
        System.exit(1);
    }
}
