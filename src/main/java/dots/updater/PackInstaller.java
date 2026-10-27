package dots.updater;

import com.google.gson.*;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;

import static java.nio.file.StandardOpenOption.*;


public final class PackInstaller {
    public interface Progress { void status(String message, double fraction); }

    // install results
    public static final class Result {
        public final boolean restart;
        public final List<String> notes;
        Result(boolean restart, List<String> notes) { this.restart = restart; this.notes = notes; }
    }

    static final class State {
        List<String> files = new ArrayList<>();                          // every path the pack manages
        Map<String, String> overrides = new LinkedHashMap<>();           // override path -> sha1 of what we shipped
    }

    static final String STATE_FILE = ".dots-state.json";
    static final String VERSION_FILE = ".dots-version";
    static final String BACKUP_DIR = ".dots-backup";

    static final Set<String> KEEP_IF_EXISTS = Set.of("options.txt", "optionsof.txt", "servers.dat");

    static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    // modrinth.index.json
    static final class Index {
        Map<String, String> dependencies;
        List<Entry> files;
    }
    static final class Entry {
        String path;
        Map<String, String> hashes;
        Map<String, String> env;
        List<String> downloads;
    }

    public static Result install(Path mrpack, Path instance, Path game, String version, Progress p) throws Exception {
        Set<String> now = new LinkedHashSet<>();                  // every path this version manages
        Map<String, String> shipped = new LinkedHashMap<>();      // override path -> sha1 of the file this version ships
        List<String> notes = new ArrayList<>();
        State prev = loadState(game);                             // null = installed before tracking existed (or fresh)
        Index index;

        try (ZipFile zf = new ZipFile(mrpack.toFile())) {
            ZipEntry idxEntry = zf.getEntry("modrinth.index.json");
            if (idxEntry == null) throw new IOException("Not a valid .mrpack (no modrinth.index.json)");
            try (Reader r = new InputStreamReader(zf.getInputStream(idxEntry), StandardCharsets.UTF_8)) {
                index = GSON.fromJson(r, Index.class);
            }
            if (index == null || index.dependencies == null || !index.dependencies.containsKey("forge")
                    || !index.dependencies.containsKey("minecraft"))
                throw new IOException("This updater only supports Forge packs; dependencies = "
                        + (index == null ? null : index.dependencies));

            // 1. Mods and other indexed files
            Set<String> packMods = new HashSet<>();
            List<Entry> files = index.files == null ? List.of() : index.files;
            for (int n = 0; n < files.size(); n++) {
                Entry f = files.get(n);
                if (f.env != null && "unsupported".equals(f.env.get("client"))) continue;
                if (f.path == null || f.hashes == null || f.downloads == null)
                    throw new IOException("Malformed file entry in pack index: " + f.path);

                Path dest = safeResolve(game, f.path);
                now.add(f.path);
                if (f.path.startsWith("mods/")) packMods.add(f.path.substring("mods/".length()));
                double frac = 0.9 * n / files.size();
                p.status("Checking " + dest.getFileName(), frac);
                if (Files.exists(dest) && hashOk(dest, f.hashes)) continue;      // already current
                p.status("Downloading " + dest.getFileName(), frac);
                download(f.downloads, dest, f.hashes);
            }

            // 1b. First update of an instance we have no record for: old pack jars were never tracked, so any
            //     jar the new pack doesn't list may be a stale version of a mod
            //     Move them aside instead of deleting, so nothing is lost
            if (prev == null) {
                Path modsDir = game.resolve("mods");
                if (Files.isDirectory(modsDir)) {
                    int moved = 0;
                    try (var s = Files.list(modsDir)) {
                        for (Path jar : (Iterable<Path>) s::iterator) {
                            String name = jar.getFileName().toString();
                            if (!Files.isRegularFile(jar) || !name.toLowerCase(Locale.ROOT).endsWith(".jar")
                                    || packMods.contains(name)) continue;
                            Path to = safeResolve(game, BACKUP_DIR + "/mods/" + name);
                            Files.createDirectories(to.getParent());
                            Files.move(jar, to, StandardCopyOption.REPLACE_EXISTING);
                            moved++;
                        }
                    }
                    if (moved > 0) notes.add("Moved " + moved + " mod(s) not in this pack to " + BACKUP_DIR + "/mods/.");
                }
            }

            // 2. overrides/ then client-overrides/ (later one wins), each path handled once
            p.status("Extracting configs", 0.92);
            Map<String, ZipEntry> overrides = new LinkedHashMap<>();
            for (String prefix : new String[] { "overrides/", "client-overrides/" })
                for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory() || !e.getName().startsWith(prefix)) continue;
                    String rel = e.getName().substring(prefix.length());
                    if (!rel.isEmpty()) overrides.put(rel, e);
                }

            int keptEdited = 0, backedUp = 0;
            for (Map.Entry<String, ZipEntry> o : overrides.entrySet()) {
                String rel = o.getKey();
                Path dest = safeResolve(game, rel);
                boolean playerOwned = KEEP_IF_EXISTS.contains(rel);
                if (playerOwned && Files.exists(dest)) continue;           // never touched, never tracked

                byte[] data;
                try (InputStream in = zf.getInputStream(o.getValue())) { data = in.readAllBytes(); }
                String newHash = sha1(data);
                if (!playerOwned) { now.add(rel); shipped.put(rel, newHash); }

                if (Files.exists(dest)) {
                    String cur = sha1(Files.readAllBytes(dest));
                    if (cur.equals(newHash)) continue;                     // already identical
                    String old = prev == null ? null : prev.overrides.get(rel);
                    if (old != null && !cur.equals(old)) { keptEdited++; continue; }   // player edited it: keep theirs
                    if (old == null) {                                      // can't tell if it was edited: back it up
                        Path bak = safeResolve(game, BACKUP_DIR + "/" + rel);
                        Files.createDirectories(bak.getParent());
                        Files.copy(dest, bak, StandardCopyOption.REPLACE_EXISTING);
                        backedUp++;
                    }
                }
                Files.createDirectories(dest.getParent());
                Files.write(dest, data);
            }
            if (keptEdited > 0) notes.add("Kept " + keptEdited + " config file(s) you had edited.");
            if (backedUp > 0) notes.add("Replaced " + backedUp + " existing config file(s); old copies are in " + BACKUP_DIR + "/.");
        }

        // 3. Delete files the previous version installed but this one no longer has
        p.status("Removing old files", 0.95);
        if (prev != null)
            for (String rel : prev.files) {
                if (rel == null || rel.isBlank() || now.contains(rel) || KEEP_IF_EXISTS.contains(rel)) continue;
                Path old = safeResolve(game, rel);
                String shippedHash = prev.overrides.get(rel);
                if (shippedHash != null && Files.isRegularFile(old) && !sha1(Files.readAllBytes(old)).equals(shippedHash))
                    continue;                                               // player edited it: leave it alone
                Files.deleteIfExists(old);
            }

        // 4. Make sure the instance uses the right Minecraft/Forge version
        p.status("Updating Forge version", 0.98);
        boolean changed = updateMmcPack(instance, index.dependencies.get("minecraft"), index.dependencies.get("forge"));

        // 5. Only now record the new state and version, so a failed update can simply be retried
        State state = new State();
        state.files = new ArrayList<>(now);
        state.overrides = shipped;
        Path tmp = game.resolve(STATE_FILE + ".tmp");
        Files.writeString(tmp, GSON.toJson(state));
        Files.move(tmp, game.resolve(STATE_FILE), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(game.resolve(VERSION_FILE), version);

        p.status("Done", 1.0);
        return new Result(changed, notes);
    }

    // Copies icon.png from the root of the .mrpack to dest (best effort). Returns false if the pack has none.
    static boolean extractIcon(Path mrpack, Path dest) {
        try (ZipFile zf = new ZipFile(mrpack.toFile())) {
            ZipEntry e = zf.getEntry("icon.png");                       // same lookup order as Prism:
            if (e == null) e = zf.getEntry("overrides/icon.png");       // pack root, then overrides/
            if (e == null) return false;
            try (InputStream in = zf.getInputStream(e)) {
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException ex) { return false; }
    }

    static State loadState(Path game) throws IOException {
        Path file = game.resolve(STATE_FILE);
        if (Files.exists(file)) {
            State s = GSON.fromJson(Files.readString(file), State.class);
            if (s != null) {
                if (s.files == null) s.files = new ArrayList<>();
                if (s.overrides == null) s.overrides = new LinkedHashMap<>();
                return s;
            }
        }
        return null;
    }

    static boolean updateMmcPack(Path instance, String mc, String forge) throws IOException {
        Path file = instance.resolve("mmc-pack.json");
        if (!Files.exists(file)) return false;
        JsonObject pack = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        JsonArray comps = pack.getAsJsonArray("components");
        boolean changed = setComponent(comps, "net.minecraft", mc, "Minecraft");
        changed |= setComponent(comps, "net.minecraftforge", forge, "Forge");
        if (changed) {
            Files.copy(file, instance.resolve("mmc-pack.json.bak"), StandardCopyOption.REPLACE_EXISTING);
            Path tmp = instance.resolve("mmc-pack.json.tmp");
            Files.writeString(tmp, GSON.toJson(pack) + "\n");
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return changed;
    }

    private static boolean setComponent(JsonArray comps, String uid, String version, String name) {
        for (JsonElement el : comps) {
            JsonObject c = el.getAsJsonObject();
            if (!c.has("uid") || !uid.equals(c.get("uid").getAsString())) continue;
            if (c.has("version") && version.equals(c.get("version").getAsString())) return false;
            c.addProperty("version", version);
            c.addProperty("cachedVersion", version);
            // Stale metadata from the old version; Prism regenerates these.
            c.remove("cachedRequires");
            c.remove("cachedVolatile");
            return true;
        }
        JsonObject c = new JsonObject();
        c.addProperty("cachedName", name);
        c.addProperty("cachedVersion", version);
        c.addProperty("uid", uid);
        c.addProperty("version", version);
        comps.add(c);
        return true;
    }

    static void fetch(String url, Path dest) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "DOTS-Updater")
                .timeout(Duration.ofMinutes(5))
                .build();
        HttpResponse<Path> r = HTTP.send(req, HttpResponse.BodyHandlers.ofFile(dest, CREATE, WRITE, TRUNCATE_EXISTING));
        if (r.statusCode() != 200) {
            Files.deleteIfExists(dest);
            throw new IOException("HTTP " + r.statusCode() + " from " + url);
        }
    }

    private static void download(List<String> urls, Path dest, Map<String, String> hashes) throws Exception {
        Files.createDirectories(dest.getParent());
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        Exception last = null;
        for (String u : urls) {
            try {
                fetch(u, part);
                if (!hashOk(part, hashes)) throw new IOException("Hash mismatch for " + dest.getFileName());
                Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (Exception e) { last = e; Files.deleteIfExists(part); }
        }
        throw new IOException("All download URLs failed for " + dest.getFileName(), last);
    }

    private static boolean hashOk(Path file, Map<String, String> hashes) throws Exception {
        String algo = hashes.containsKey("sha512") ? "sha512" : hashes.containsKey("sha1") ? "sha1" : null;
        if (algo == null) return false;
        MessageDigest md = MessageDigest.getInstance(algo.equals("sha512") ? "SHA-512" : "SHA-1");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest()).equalsIgnoreCase(hashes.get(algo));
    }

    private static String sha1(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
    }

    static Path safeResolve(Path base, String rel) {
        Path root = base.toAbsolutePath().normalize();
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root)) throw new SecurityException("Bad path in pack: " + rel);
        return p;
    }
}