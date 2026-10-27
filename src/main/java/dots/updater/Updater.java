package dots.updater;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.*;


public class Updater {
    static final String REPO = "noelledotjpg/DOTS-Releases";
    static final String NEW_INSTANCE_NAME = "Minecraft: Disorder of the Stone";
    static final String ICON_FILE = "DOTS-Updater.png";   // iconKey = DOTS-Updater (the utility)
    static final String MODPACK_ICON_FILE = "DOTS.png";   // iconKey = DOTS (the modpack instance)
    static final Pattern ASSET =Pattern.compile(".*\\.mrpack", Pattern.CASE_INSENSITIVE);
    static final Pattern DOTS_INSTANCE = Pattern.compile(".*\\b(dots|disorder)\\b.*", Pattern.CASE_INSENSITIVE);
    // regex for dots instances

    // github release json
    static final class Release {
        @SerializedName("tag_name") String tag;
        String message;
        List<Asset> assets;
    }
    static final class Asset {
        String name;
        @SerializedName("browser_download_url") String url;
    }

    public static void main(String[] args) {
        try { run(); }
        catch (Exception e) {
            e.printStackTrace();
            MainWindow.message("Update failed:\n" + e);
        }
        System.exit(0);
    }

    static void run() throws Exception {
        Path self = findUpdaterInstanceDir();
        Path instancesDir = self.getParent();
        List<Path> candidates;
        try (Stream<Path> s = Files.list(instancesDir)) {
            candidates = s.filter(p -> Files.exists(p.resolve("instance.cfg")))
                    .filter(p -> !p.equals(self))
                    .filter(p -> DOTS_INSTANCE.matcher(displayName(p)).matches()
                            || DOTS_INSTANCE.matcher(p.getFileName().toString()).matches())
                    .sorted().collect(Collectors.toList());
        }
        List<String> names = candidates.stream().map(Updater::displayName).collect(Collectors.toList());

        boolean created;
        Path instance = null, game = null;
        String tag, url;

        for (;;) {
            int idx = MainWindow.selectInstance(names);
            if (idx < 0) return;
            created = idx >= names.size();
            String choice = created ? NEW_INSTANCE_NAME : names.get(idx);

            PackInstaller.Progress checking = MainWindow.progress("Checking for updates...");
            checking.status("Contacting GitHub...", 0);
            Release rel = latestRelease();
            tag = rel.tag;
            url = rel.assets == null ? null : rel.assets.stream()
                    .filter(a -> a.name != null && ASSET.matcher(a.name).matches())
                    .map(a -> a.url).findFirst().orElse(null);
            if (url == null) throw new IllegalStateException("Release " + tag + " has no .mrpack asset");

            String installed = "(none)";
            if (!created) {
                instance = candidates.get(idx);
                game = Files.isDirectory(instance.resolve("minecraft")) ? instance.resolve("minecraft") : instance.resolve(".minecraft");
                Path vf = game.resolve(PackInstaller.VERSION_FILE);
                if (Files.exists(vf)) installed = Files.readString(vf).trim();
                if (installed.equals(tag)) { MainWindow.message("Already up to date (" + tag + ")."); return; }
            }
            String question = created ? "Install " + tag + " into a new instance called\n\"" + choice + "\"?"
                    : "Update " + choice + " from " + installed + " to " + tag + "?";
            if (MainWindow.confirm(question, created ? "Install" : "Update")) break;
        }

        if (created) {
            instance = createInstance(instancesDir);
            game = instance.resolve(".minecraft");
        }
        Files.createDirectories(game);

        PackInstaller.Progress progress = MainWindow.progress(created ? "Installing DOTS..." : "Updating DOTS...");
        PackInstaller.Result result;
        Path tmp = Files.createTempFile("dots-", ".mrpack");
        try {
            progress.status("Downloading pack...", 0);
            PackInstaller.fetch(url, tmp);
            result = PackInstaller.install(tmp, instance, game, tag, progress);
            // The modpack ships its own icon.png at the mrpack root: install it as DOTS.png (iconKey=DOTS)
            if (created) {
                for (Path icons : findIconsDirs(instancesDir)) {
                    try { Files.createDirectories(icons); } catch (Exception ignored) {}
                    if (Files.isDirectory(icons)) PackInstaller.extractIcon(tmp, icons.resolve(MODPACK_ICON_FILE));
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }

        StringBuilder msg = new StringBuilder((created ? "Installed " : "Updated to ") + tag + ".");
        if (created) msg.append("\nClose and reopen Prism Launcher to see the new instance.");
        else if (result.restart)
            msg.append("\nThe Forge/Minecraft version changed: close and reopen Prism Launcher before playing.");
        for (String note : result.notes) msg.append("\n").append(note);
        MainWindow.message(msg.toString());
    }

    static String displayName(Path instanceDir) {
        try {
            for (String line : Files.readAllLines(instanceDir.resolve("instance.cfg")))
                if (line.startsWith("name=") && line.length() > 5) return line.substring(5).trim();
        } catch (Exception ignored) {}
        return instanceDir.getFileName().toString();
    }

    static Path createInstance(Path instancesDir) throws Exception {
        String base = "disorder";                       // folder name: no ':' (illegal on Windows)
        Path dir = instancesDir.resolve(base);
        int n = 1;
        while (Files.exists(dir)) dir = instancesDir.resolve(base + "-" + (++n));
        Files.createDirectories(dir);
        String name = n == 1 ? NEW_INSTANCE_NAME : NEW_INSTANCE_NAME + " (" + n + ")";
        String cfg = new String(Updater.class.getResourceAsStream("/assets/template/instance.cfg").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8).replaceFirst("(?m)^name=.*$", java.util.regex.Matcher.quoteReplacement("name=" + name));
        Files.writeString(dir.resolve("instance.cfg"), cfg);
        try (var in = Updater.class.getResourceAsStream("/assets/template/mmc-pack.json")) {
            Files.copy(in, dir.resolve("mmc-pack.json"));
        }
        // Install icons into Prism's icons folder (best effort).
        //   DOTS.png         -> the modpack instance's icon (template iconKey=DOTS); overwritten so a stale
        //                       copy of the utility icon from older updater versions gets replaced
        //   DOTS-Updater.png -> the utility's own icon, named differently so it can't collide with the above
        for (Path icons : findIconsDirs(instancesDir))
            if (Files.isDirectory(icons)) installIcon("/assets/icon.png", icons.resolve(ICON_FILE), false);
        return dir;
    }

    // Prism's instances folder can live anywhere (e.g. D:\PrismLauncher\instances) while its data folder, which holds
    // prismlauncher.cfg and icons/, stays elsewhere (e.g. %APPDATA%\PrismLauncher). There can even be more than one
    // data folder pointing at the same instances folder (a portable copy plus an installed one), and the updater can't
    // tell which Prism launched it. So return the icons folder of EVERY data folder whose prismlauncher.cfg has an
    // InstanceDir that resolves to the folder this updater lives in; installing into all of them is harmless.
    static List<Path> findIconsDirs(Path instancesDir) {
        java.util.Set<Path> cands = new java.util.LinkedHashSet<>();
        Path parent = instancesDir.getParent();
        if (parent != null) cands.add(parent.toAbsolutePath().normalize());       // portable install / default layout
        String appdata = System.getenv("APPDATA");
        if (appdata != null && !appdata.isEmpty()) cands.add(Paths.get(appdata, "PrismLauncher").toAbsolutePath().normalize());
        String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg != null && !xdg.isEmpty()) cands.add(Paths.get(xdg, "PrismLauncher").toAbsolutePath().normalize());
        String home = System.getProperty("user.home");
        if (home != null) {
            cands.add(Paths.get(home, "AppData", "Roaming", "PrismLauncher").toAbsolutePath().normalize());
            cands.add(Paths.get(home, ".local", "share", "PrismLauncher").toAbsolutePath().normalize());
            cands.add(Paths.get(home, "Library", "Application Support", "PrismLauncher").toAbsolutePath().normalize());
        }
        Path want = instancesDir.toAbsolutePath().normalize();
        java.util.Set<Path> matches = new java.util.LinkedHashSet<>();
        Path fallback = null;
        for (Path c : cands) {
            Path cfg = c.resolve("prismlauncher.cfg");
            if (!Files.isRegularFile(cfg)) continue;
            String instDir = null, iconsDir = null;
            try {
                for (String line : Files.readAllLines(cfg)) {
                    if (line.startsWith("InstanceDir=")) instDir = line.substring(12).trim();
                    else if (line.startsWith("IconsDir=")) iconsDir = line.substring(9).trim();
                }
            } catch (Exception ignored) {}
            Path icons = c.resolve(iconsDir != null && !iconsDir.isEmpty() ? iconsDir : "icons").normalize();
            boolean same = false;
            try {
                same = c.resolve(instDir != null && !instDir.isEmpty() ? instDir : "instances").toAbsolutePath().normalize().equals(want);
            } catch (Exception ignored) {}
            if (same) matches.add(icons);
            else if (fallback == null) fallback = icons;
        }
        if (!matches.isEmpty()) return new java.util.ArrayList<>(matches);
        if (fallback != null) return List.of(fallback);
        return parent == null ? List.of() : List.of(parent.resolve("icons"));      // last resort: old behaviour
    }

    private static void installIcon(String resource, Path dest, boolean overwrite) {
        try (var in = Updater.class.getResourceAsStream(resource)) {
            if (in == null) return;                                   // icon not bundled: skip silently
            if (overwrite) Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            else if (!Files.exists(dest)) Files.copy(in, dest);
        } catch (Exception ignored) {}
    }

    static Release latestRelease() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + REPO + "/releases/latest"))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "DOTS-Updater")
                .timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<String> resp = PackInstaller.HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        Release rel = new Gson().fromJson(resp.body(), Release.class);
        if (resp.statusCode() != 200 || rel == null || rel.tag == null)
            throw new IllegalStateException("GitHub returned HTTP " + resp.statusCode()
                    + (rel != null && rel.message != null ? ": " + rel.message : ""));
        return rel;
    }

    static Path findUpdaterInstanceDir() {
        String env = System.getenv("INST_DIR");
        if (env != null && !env.isEmpty()) return Paths.get(env).toAbsolutePath();
        return Paths.get("").toAbsolutePath().getParent(); // cwd is <instance>/minecraft
    }
}