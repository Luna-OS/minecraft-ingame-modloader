# Ingame Modloader

A Fabric mod for **Minecraft 26.3** that lets you add new mods from the title screen and load them
**without closing and restarting the game**.

*Deutsch weiter unten.*

## Usage

1. Install [Fabric Loader](https://fabricmc.net/use/) (≥ 0.19.5) and [Fabric API](https://modrinth.com/mod/fabric-api) for 26.3, and put this mod into your `mods` folder.
2. Start the game. On the title screen there is a new **`+`** button to the left of *Options*.
3. It opens a screen that works like the resource pack screen: **Disabled** mods on the left, **Enabled** mods on the right.
   - Add mods by dropping `.jar` files onto the window, or click **Open Mods Folder** and put them there yourself (the lists update on their own).
   - Hover a mod's icon and click the arrow to switch it on or off (double-click or Enter works too). The search box filters both lists.
4. Click **Done**. Every enabled mod that is not running yet is loaded right away, without restarting.

Each mod shows what happens with it:

| Status | Meaning |
| --- | --- |
| New (green) | Will be loaded when you click *Done*. |
| Loaded without restart ✔ | Was loaded during this session. |
| Restart required (orange) | Valid mod, but it has to change classes the game has already loaded (see below). It is loaded on the next start. |
| Still running – off from the next start (orange) | You switched off a mod that is running. Mods cannot be unloaded, so it stops after a restart. |
| Red background | Missing dependencies or a broken jar. Dependencies that are enabled in the mods folder are loaded automatically. |

Switching a mod off renames it to `.jar.disabled`, like other launchers do, so Fabric skips it on the next start.
On Windows the jar of a running mod is locked; it is then renamed by a small helper process right after the game has closed.

## What works without a restart

- Classes, `main`/`client`/`preLaunch` entrypoints and custom language adapters
- Registering blocks, items, entities, block entities, sounds, new registries etc. (built-in registries are unfrozen while the mod initializes)
- Assets and data: textures, models, translations, recipes, loot tables, tags (resources are reloaded after loading; data is loaded when you open a world)
- Key mappings registered with Fabric API, creative tab contents
- Bundled jar-in-jar dependencies
- Mixins and access wideners, **as long as they only target classes that are not loaded yet**

## Limitations

These are limits of the JVM and Fabric, not something a mod can work around:

- **Mixins/access wideners targeting already-loaded classes** (e.g. `Minecraft`, `LevelRenderer`, most of the GUI) cannot be applied to a running game. Most mods that change existing game behavior use such mixins, so they show *Restart required*. The mod checks every mixin target before loading, so such mods are never half-loaded.
- **Mods cannot be unloaded or updated** without a restart. Switching off a running mod takes effect on the next start, and a different version of an already loaded mod shows *Restart required*.
- Lifecycle events that already happened (e.g. `ClientLifecycleEvents.CLIENT_STARTED`) are not fired again for late mods.
- Loading is only offered from the title screen, not while a world is open.
- Mods that read the mod list only once at startup (e.g. Mod Menu) will not list late-loaded mods until the next start.

## Building

Requires JDK 25.

```sh
./gradlew build
```

The mod jar is written to `build/libs/`.

---

## Deutsch

Ein Fabric-Mod für **Minecraft 26.3**, mit dem du im Hauptmenü neue Mods hinzufügen und laden kannst, **ohne das Spiel zu schließen und neu zu starten**.

### Benutzung

1. [Fabric Loader](https://fabricmc.net/use/) (≥ 0.19.5) und [Fabric API](https://modrinth.com/mod/fabric-api) für 26.3 installieren und diesen Mod in den `mods`-Ordner legen.
2. Spiel starten. Im Hauptmenü gibt es links neben *Optionen* einen neuen **`+`**-Knopf.
3. Er öffnet einen Bildschirm wie bei den Ressourcenpaketen: links **Ausgeschaltet**, rechts **Eingeschaltet**.
   - Mods hinzufügen: `.jar`-Dateien ins Fenster ziehen oder auf **Mods-Ordner öffnen** klicken und sie selbst hineinlegen (die Listen aktualisieren sich von selbst).
   - Mit der Maus aufs Icon eines Mods gehen und auf den Pfeil klicken, um ihn ein- oder auszuschalten (Doppelklick oder Enter geht auch). Das Suchfeld filtert beide Listen.
4. Auf **Fertig** klicken. Alle eingeschalteten Mods, die noch nicht laufen, werden sofort geladen – ohne Neustart.

Ausschalten benennt die Datei in `.jar.disabled` um (wie bei anderen Launchern), Fabric überspringt sie dann beim nächsten Start.
Ein Mod, der gerade läuft, kann nicht entladen werden – er ist ab dem nächsten Start aus. Unter Windows ist seine Datei gesperrt; sie wird dann von einem kleinen Hilfsprozess umbenannt, sobald das Spiel geschlossen ist.

### Was ohne Neustart geht

- Klassen, Entrypoints (`main`, `client`, `preLaunch`)
- Neue Blöcke, Items, Entities, Sounds usw. registrieren
- Texturen, Modelle, Übersetzungen, Rezepte, Tags (Ressourcen werden danach neu geladen)
- Tastenbelegungen über Fabric API, Kreativ-Tabs
- Mitgelieferte Abhängigkeiten (Jar-in-Jar)
- Mixins und Access Widener, **solange sie nur Klassen ändern, die noch nicht geladen sind**

### Grenzen

- **Mixins/Access Widener auf bereits geladene Klassen** (z. B. `Minecraft`, `LevelRenderer`, große Teile der GUI) lassen sich in einem laufenden Spiel nicht mehr anwenden – das ist eine Grenze der Java-VM. Die meisten Mods, die bestehendes Spielverhalten ändern, nutzen solche Mixins und zeigen deshalb *Neustart nötig*. Sie bleiben im mods-Ordner und werden beim nächsten Start ganz normal geladen. Der Mod prüft das vorher, solche Mods werden also nie nur halb geladen.
- Mods können ohne Neustart **nicht entladen oder aktualisiert** werden. Ausschalten eines laufenden Mods wirkt ab dem nächsten Start.
- Events, die schon vorbei sind (z. B. „Client gestartet“), werden für spät geladene Mods nicht nochmal ausgelöst.
- Laden geht nur im Hauptmenü, nicht in einer offenen Welt.
