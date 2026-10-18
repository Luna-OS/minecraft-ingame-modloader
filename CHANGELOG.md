# Changelog

## 1.1.0

**Deutsch**

- Laufende Mods lassen sich jetzt **ohne Neustart** ausschalten: Beim Klick auf „Fertig“ werden ihre Fabric-Events, Tastenbelegungen und Ressourcen entfernt und die Ressourcen neu geladen. Wieder einschalten stellt alles wieder her.
- Mods mit Mixins in Spielklassen werden als „teilweise aus“ angezeigt, weil Mixins bis zum nächsten Start weiterwirken.
- Mods, die von einem anderen laufenden Mod gebraucht werden, werden erst nach einem Neustart ausgeschaltet; der Grund steht in der Liste.

**English**

- Running mods can now be switched off **without a restart**: clicking "Done" removes their Fabric event listeners, key mappings and resources and reloads the resources. Switching them on again restores everything.
- Mods with mixins into game classes are shown as partially off, since mixins stay active until the next start.
- Mods that another running mod needs are only switched off after a restart; the list shows why.

## 1.0.0

First release, for Minecraft 26.3 with Fabric Loader 0.19.5+ and Fabric API.

**Deutsch**

- Neuer **`+`**-Knopf im Hauptmenü öffnet die Mod-Auswahl, aufgebaut wie die Ressourcenpaket-Auswahl: links ausgeschaltete, rechts eingeschaltete Mods
- Mods per Drag & Drop hinzufügen oder über „Mods-Ordner öffnen“ selbst hineinlegen
- Eingeschaltete Mods werden beim Klick auf „Fertig“ **ohne Neustart** geladen (Blöcke, Items, Texturen, Übersetzungen, Tastenbelegungen, Abhängigkeiten)
- Mods, die schon geladene Spielklassen per Mixin ändern, werden erkannt und als „Neustart nötig“ angezeigt statt halb geladen
- Ausschalten benennt die Datei in `.jar.disabled` um; ein laufender Mod ist ab dem nächsten Start aus

**English**

- New **`+`** button on the title screen opens the mod selection, laid out like the resource pack screen: disabled mods on the left, enabled mods on the right
- Add mods by drag and drop or by putting them into the mods folder ("Open Mods Folder")
- Enabled mods are loaded **without a restart** when you click "Done" (blocks, items, textures, translations, key mappings, dependencies)
- Mods whose mixins target classes that are already loaded are detected and shown as "Restart required" instead of being half-loaded
- Disabling renames the jar to `.jar.disabled`; a running mod is off from the next start
