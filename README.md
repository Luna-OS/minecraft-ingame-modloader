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
| Switched off without restart | You switched off a running mod and clicked *Done* (see below). |
| Still running – off from the next start (orange) | The mod is still needed by another running mod, so it is switched off after a restart. |
| Red background | Missing dependencies or a broken jar. Dependencies that are enabled in the mods folder are loaded automatically. |

Switching a mod off renames it to `.jar.disabled`, like other launchers do, so Fabric skips it on the next start.
On Windows the jar of a running mod is locked; it is then renamed by a small helper process right after the game has closed.

### Switching off running mods without a restart

Java cannot unload classes, but when you switch off a running mod and click *Done*, the mod is cut off from the game and the resources are reloaded:

- all its Fabric API event listeners are removed (ticks, HUD, commands, screens, …), so its code is not called anymore
- its textures, models, translations and data are left out of the reload
- its key mappings disappear from the controls

Switching it on again in the same session restores all of that. What cannot be undone until the next start:
mixins into game classes (such mods show *Switched off – its mixins stay active until the next start*), registered blocks and items, and anything a mod hooked up outside Fabric API events.
A mod that another running mod depends on is only switched off after a restart.

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
- **Mods cannot be fully unloaded or updated** without a restart: switching off a running mod cuts it off from the game (see above), and a different version of an already loaded mod shows *Restart required*.
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

**Laufende Mods ohne Neustart ausschalten:** Java kann Klassen nicht entladen, aber beim Klick auf *Fertig* wird der Mod vom Spiel abgeschnitten und die Ressourcen werden neu geladen: alle seine Fabric-Events werden entfernt (sein Code wird nicht mehr aufgerufen), seine Texturen, Übersetzungen und Daten fallen weg und seine Tastenbelegungen verschwinden. Wieder einschalten stellt alles wieder her.
Bis zum nächsten Start bleiben nur Mixins in Spielklassen (solche Mods zeigen „seine Mixins wirken bis zum nächsten Start weiter“), registrierte Blöcke/Items und Dinge, die ein Mod außerhalb der Fabric-Events eingehängt hat. Ein Mod, den ein anderer laufender Mod braucht, ist erst ab dem nächsten Start aus.
Unter Windows ist die Datei eines laufenden Mods gesperrt; sie wird dann von einem kleinen Hilfsprozess umbenannt, sobald das Spiel geschlossen ist.

### Was ohne Neustart geht

- Klassen, Entrypoints (`main`, `client`, `preLaunch`)
- Neue Blöcke, Items, Entities, Sounds usw. registrieren
- Texturen, Modelle, Übersetzungen, Rezepte, Tags (Ressourcen werden danach neu geladen)
- Tastenbelegungen über Fabric API, Kreativ-Tabs
- Mitgelieferte Abhängigkeiten (Jar-in-Jar)
- Mixins und Access Widener, **solange sie nur Klassen ändern, die noch nicht geladen sind**

### Grenzen

- **Mixins/Access Widener auf bereits geladene Klassen** (z. B. `Minecraft`, `LevelRenderer`, große Teile der GUI) lassen sich in einem laufenden Spiel nicht mehr anwenden – das ist eine Grenze der Java-VM. Die meisten Mods, die bestehendes Spielverhalten ändern, nutzen solche Mixins und zeigen deshalb *Neustart nötig*. Sie bleiben im mods-Ordner und werden beim nächsten Start ganz normal geladen. Der Mod prüft das vorher, solche Mods werden also nie nur halb geladen.
- Mods können ohne Neustart **nicht vollständig entladen oder aktualisiert** werden – Ausschalten schneidet einen laufenden Mod nur vom Spiel ab (siehe oben).
- Events, die schon vorbei sind (z. B. „Client gestartet“), werden für spät geladene Mods nicht nochmal ausgelöst.
- Laden geht nur im Hauptmenü, nicht in einer offenen Welt.
