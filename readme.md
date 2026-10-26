# DOTS Updater

A Prism Launcher instance that installs and updates the *Minecraft: Disorder of the Stone* modpack.
This is a jarmod for 1.20.1 that launches a custom java app by spoofing the main minecraft class

## Requirements

- [JDK 17](https://adoptium.net/temurin/releases?version=17) or newer
- [Prism Launcher](https://prismlauncher.org/) to actually use the utility

## Setup

1. Clone the repository.
2. Open it in IntelliJ IDEA as a Gradle project and set the Gradle JVM and project SDK to JDK 17 or newer.
3. Let Gradle sync. The only dependency is Gson 2.10. Minecraft 1.20.1 already ships it, so it is not bundled into the jar; Gradle adds it to the classpath only for IDE runs.

## Building

```
./gradlew instanceZip
```

This produces `build/distributions/dots-updater-instance.zip`. Import it into Prism Launcher (Add Instance -> Import) to get the updater instance.

`./gradlew jar` builds just the jar. (if you need that for whatever reason)

The jar mod UUID is set in `build.gradle` (`jarmodUuid`). Keep it constant so importing a newer updater instance replaces the old jar instead of adding a second one.

## Configuration

In `Updater.java`:

- `REPO`: the GitHub repo where releases are pulled from (default `noelledotjpg/DOTS-Releases`). The release must have a `.mrpack` asset.
- `NEW_INSTANCE_NAME`: the display name given to newly created instances.
- `DOTS_INSTANCE`: the regex that decides which Prism instances are listed as existing DOTS instances (name or folder contains `dots` or `disorder`).

`instance.cfg` and `mmc-pack.json` in `src/main/resources/assets/template/` are the templates used when the updater creates a new DOTS instance.

## Running from the IDE

Run `dots.updater.Updater`. It looks for the Prism instances folder in two ways:

- the `INST_DIR` environment variable (Prism sets this when launching), or
- the parent of the working directory, assuming the working directory is `<instance>/minecraft`.

So for an IDE run, set the working directory to `<some-instances-folder>/<updater-instance>/minecraft`, or set `INST_DIR` to the updater instance folder. The sibling folders of that instance are then scanned for DOTS instances. The shared run configuration in `.run/` ("Updater (test)") does the latter, pointing `INST_DIR` at `fake-instances/updater`, so you can just run it.

`fake-instances/` is a fake Prism instances folder for local testing. `updater/` stands in for the updater instance. To test against a DOTS instance, add your own folder next to it (for example `fake-instances/TestPack/`, with an `instance.cfg` whose name contains `dots` or `disorder`). Don't commit it; everything in `fake-instances/` except `updater/` is git-ignored.