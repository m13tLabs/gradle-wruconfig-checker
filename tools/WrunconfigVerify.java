import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

/** Prototype of the wrunconfig classpath verifier (the logic later ported into the Gradle plugin).
 *  Usage: java WrunconfigVerify.java <wrunconfig-dir> <application-root> */
public class WrunconfigVerify {

    static final Set<String> CP_OPTS = Set.of("-cp", "-classpath", "--class-path");
    static final Set<String> CP_OK   = Set.of(".jar", ".zip");
    static Path APP_ROOT;

    // --- placeholder resolution (build-time subset of the wrapper's grammar) ---
    static String resolveOnce(String s) {
        // handle the longer [APPDIR]\.. before bare [APPDIR], mirroring the reference validator
        s = s.replace("[APPDIR]\\..", APP_ROOT.toString());
        StringBuilder out = new StringBuilder();
        int i = 0;
        boolean changed = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '[') {
                int end = matchBracket(s, i);
                if (end < 0) { out.append(c); i++; continue; }
                String inner = s.substring(i + 1, end);
                String repl = resolveToken(inner);
                if (repl != null) { out.append(repl); changed = true; }
                else out.append(s, i, end + 1);   // leave dynamic token untouched
                i = end + 1;
            } else { out.append(c); i++; }
        }
        return changed ? out.toString() : s;
    }
    static String resolve(String s) {
        if (s == null) return "";
        String prev;
        int guard = 0;
        do { prev = s; s = resolveOnce(s); } while (!s.equals(prev) && ++guard < 25);
        return s.replace('\\', File.separatorChar);
    }
    static int matchBracket(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            if (s.charAt(i) == '[') depth++;
            else if (s.charAt(i) == ']' && --depth == 0) return i;
        }
        return -1;
    }
    static List<String> splitPipes(String s) {          // split on '|' at bracket depth 0
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') depth--;
            else if (c == '|' && depth == 0) { parts.add(s.substring(start, i)); start = i + 1; }
        }
        parts.add(s.substring(start));
        return parts;
    }
    static String resolveToken(String inner) {
        List<String> p = splitPipes(inner);
        switch (p.get(0)) {
            case "APPDIR":              return APP_ROOT.toString();
            case "ARGS": case "RESOLVED_ARGS": case "ARGSSELECTOR": return " ";
            case "QUOTE":               return p.size() > 1 ? p.get(1) : "";
            case "CHANGEEXTENSION":     return p.size() > 2 ? changeExt(p.get(1), p.get(2)) : null;
            case "RETRIVEFROMREGISTRY": return p.size() >= 5 ? p.get(4) : "";   // registry default
            case "ENV":                 return p.size() > 1 ? System.getenv(p.get(1)) : null;
            // runtime-only tokens (EXENAME, SPECIALFOLDER, WRAPPER_APPDATA, bare env, ...) stay dynamic
            default:                    return System.getenv(p.get(0));
        }
    }
    static String changeExt(String path, String ext) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        if (dot <= slash) return path + (ext.startsWith(".") ? ext : "." + ext);
        return path.substring(0, dot) + (ext.startsWith(".") ? ext : "." + ext);
    }

    // --- argument parsing ---
    static List<String> tokenize(String s) {
        List<String> t = new ArrayList<>();
        Matcher m = Pattern.compile("(?:\"[^\"]*\"|\\S)+").matcher(s);
        while (m.find()) t.add(m.group());
        return t;
    }
    static String[] cpAndMain(List<String> toks) {
        String cp = null, main = null;
        for (int i = 0; i < toks.size(); i++) {
            String t = toks.get(i);
            if (CP_OPTS.contains(t)) { cp = i + 1 < toks.size() ? toks.get(i + 1) : null; i++; }
            else if (t.startsWith("-")) { /* skip flag */ }
            else { main = t; break; }
        }
        return new String[]{cp, main};
    }
    static String unquote(String s) {
        return (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) ? s.substring(1, s.length() - 1) : s;
    }

    static String suffix(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase();
    }
    static boolean isDynamic(String e) { return e.indexOf('[') >= 0 || e.indexOf(']') >= 0; }

    // resolve one classpath entry to a filesystem path relative to the working dir
    static Path entryPath(Path wd, String e) {
        Path p = Paths.get(e);
        return p.isAbsolute() ? p : wd.resolve(e);
    }

    // --- per-file check; returns 1 on failure, 0 otherwise ---
    static int check(Path cfg) {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            doc = f.newDocumentBuilder().parse(cfg.toFile());
        } catch (Exception e) {
            System.out.println(cfg + "  FAIL (ParseError: " + e.getMessage() + ")");
            return 1;
        }
        String rawArgs = text(doc, "Process", "Arguments");
        String[] cm = cpAndMain(tokenize(resolve(rawArgs)));
        String cp = cm[0], main = cm[1];
        if (cp == null || main == null) {
            System.out.println(cfg + "  SKIP (no classpath/main)");
            return 0;
        }
        String rawWd = text(doc, "Process", "WorkingDirectory");
        Path wd = APP_ROOT.resolve(winBaseName(rawWd));
        if (!Files.isDirectory(wd)) {
            System.out.println(cfg + "  FAIL (workdir missing: " + wd + ")");
            return 1;
        }

        List<String> keep = new ArrayList<>(), dropped = new ArrayList<>(), dynamic = new ArrayList<>();
        for (String raw : unquote(cp).split(";")) {
            if (raw.isEmpty()) continue;
            if (isDynamic(raw)) { dynamic.add(raw); continue; }
            String e = raw.replace('\\', File.separatorChar);
            if (e.equals(".") || Files.isDirectory(entryPath(wd, e)) || CP_OK.contains(suffix(e))) keep.add(e);
            else dropped.add(e);
        }
        List<String> missing = new ArrayList<>();
        for (String e : keep) if (!Files.exists(entryPath(wd, e))) missing.add(e);
        if (!missing.isEmpty()) {
            System.out.println(cfg + "  FAIL (" + missing.size() + " cp entries missing, first: " + missing.get(0) + ")");
            return 1;
        }
        String[] link = linkage(keep, main, wd);
        boolean ok = link[0].equals("OK");

        System.out.println();
        System.out.println(cfg);
        System.out.println("  main    : " + main);
        System.out.println("  link    : " + link[1]);
        if (!dropped.isEmpty()) System.out.println("  drop    : " + dropped.size() + " non-class cp entries (e.g. " + new File(dropped.get(0)).getName() + ")");
        if (!dynamic.isEmpty()) System.out.println("  dynamic : " + dynamic.size() + " unresolved at build time, skipped (e.g. " + dynamic.get(0) + ")");
        return ok ? 0 : 1;
    }

    // deterministic linkage: is main's .class present on the classpath?
    static String[] linkage(List<String> entries, String main, Path wd) {
        String rel = main.replace('.', '/') + ".class";
        boolean found = false;
        List<String> bad = new ArrayList<>();
        for (String e : entries) {
            Path p = entryPath(wd, e);
            if (e.equals(".") || Files.isDirectory(p)) {
                if (Files.exists(p.resolve(rel))) found = true;
            } else if (CP_OK.contains(suffix(e)) && Files.exists(p)) {
                try (ZipFile z = new ZipFile(p.toFile())) {
                    if (z.getEntry(rel) != null) found = true;
                } catch (Exception ex) { bad.add(e); }
            }
        }
        String tag = found ? "OK (main class present)" : "FAIL: main class not on classpath";
        if (!bad.isEmpty()) tag += "  [" + bad.size() + " corrupt jar(s): " + bad.get(0) + "]";
        return new String[]{(found && bad.isEmpty()) ? "OK" : "FAIL", tag};
    }

    static String winBaseName(String s) {
        if (s == null) return "";
        String n = s.replace('/', '\\');
        int i = n.lastIndexOf('\\');
        return i < 0 ? n : n.substring(i + 1);
    }
    static String text(Document doc, String parent, String child) {
        NodeList ps = doc.getElementsByTagName(parent);
        for (int i = 0; i < ps.getLength(); i++) {
            for (Node c = ps.item(i).getFirstChild(); c != null; c = c.getNextSibling())
                if (c.getNodeType() == Node.ELEMENT_NODE && c.getNodeName().equals(child))
                    return c.getTextContent().trim();
        }
        return "";
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : "wrunconfig");
        APP_ROOT   = Paths.get(args.length > 1 ? args[1] : "application").toAbsolutePath().normalize();
        if (!Files.isDirectory(APP_ROOT)) { System.err.println("application root not found: " + APP_ROOT); System.exit(2); }
        List<Path> cfgs = new ArrayList<>();
        Files.walk(root).filter(p -> p.toString().endsWith(".wrunconfig")).sorted().forEach(cfgs::add);
        int failures = 0;
        for (Path c : cfgs) failures += check(c);
        System.out.println("\n=== " + failures + " failure(s) ===");
        System.exit(failures == 0 ? 0 : 1);
    }
}
