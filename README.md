# PetShop

Shop und Garderobe für die Pets von [MCPets](https://github.com/Nocsy-Workshop/mcpets).
Spieler kaufen Pets mit **PlayerPoints**, Besitz und aktives Pet liegen in einer **MariaDB**
(auf allen Servern des Netzwerks gleich). Das Menü baut **GameUI** aus einer Datei, die PetShop
selbst erzeugt. PetShop hat keine eigene GUI.

- Paper und LeafMC **1.21.8 bis 26.2**, Java 21 oder neuer, nur Paper-API (kein NMS)
- Pflicht: PlayerPoints. Empfohlen: MCPets, PlaceholderAPI, Nexo, GameUI
- Der MariaDB-Treiber wird von Paper beim Start aus Maven Central geladen (`libraries:` in `plugin.yml`)

## Installation

1. `PetShop.jar` nach `plugins/` legen, Server starten. Es entstehen
   `config.yml`, `messages.yml`, `pets.yml` und `menu-template.yml` in `plugins/PetShop/`.
2. Datenbank anlegen (siehe unten) und in `config.yml` unter `database` eintragen.
3. `/petshop reload`. Die Konsole meldet `Connected to MariaDB ...`.
4. In GameUI landet das Menü automatisch unter `plugins/GameUI/screen/shop.yml`;
   danach führt PetShop `hexo-ui screen reload` aus. `/petshop` öffnet es.

## Datenbank

```sql
CREATE DATABASE minecraft;
CREATE USER 'minecraft'@'%' IDENTIFIED BY 'geheim';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE ON minecraft.* TO 'minecraft'@'%';
```

Alle Server zeigen auf dieselbe Datenbank. Die Tabellen legt PetShop selbst an:

| Tabelle | Inhalt |
|---|---|
| `petshop_owned (uuid BINARY(16), pet_id VARCHAR(64), acquired_at BIGINT)` | gekaufte Pets, Schlüssel `(uuid, pet_id)` |
| `petshop_player (uuid BINARY(16), active_pet VARCHAR(64) NULL, updated_at BIGINT)` | aktives Pet |

PetShop benutzt genau eine Verbindung auf einem eigenen Thread. Ist die Datenbank weg, läuft der
Server weiter: Käufe werden mit Meldung abgelehnt, das Menü zeigt den letzten bekannten Stand.

## Konfiguration (`config.yml`)

| Schlüssel | Standard | Bedeutung |
|---|---|---|
| `database.*` | | Host, Port, Datenbank, Benutzer, Passwort, `table-prefix`, `use-ssl`, zusätzliche JDBC-`properties` |
| `default-price` | `2000` | Preis für Pets ohne eigenen Preis |
| `currency-name` | `coins` | erscheint hinter dem Preis (`2000 coins`) |
| `sort` | `name` | Reihenfolge im Menü: `name`, `price`, `file` |
| `open-command` | `shop` | Befehl, den der Spieler nach `/petshop` ausführt (öffnet das GameUI-Menü) |
| `menu-file` | `plugins/GameUI/screen/shop.yml` | Ziel der erzeugten Menüdatei, leer = keine Datei |
| `menu-reload-command` | `hexo-ui screen reload` | Konsolenbefehl nach dem Schreiben, leer = keiner |
| `generate-on-start` | `true` | beim Start neu schreiben, wenn sich der Inhalt unterscheidet (`false`: nur wenn die Datei fehlt) |
| `menu-layout.*` | `[590, 945, 1300]`, `668`, `240`, `500`, `32` | Spalten-x, y der ersten Reihe, Reihenabstand, Höhe des Scrollbereichs, kleinste Daumenhöhe |
| `grant-mode` | `attachment` | `attachment` (Rechte nur im Speicher, DB ist die einzige Quelle) oder `command` |
| `grant-command` / `revoke-command` | LuckPerms-Befehle | für `grant-mode: command`, Platzhalter `{player}`, `{permission}` |
| `mcpets-hook` | `api` | `api` (MCPets-Java-API) oder `command` |
| `equip-command` / `unequip-command` | `mcpets spawn {pet} {player} false -s` / `mcpets revoke {player}` | Konsolenbefehle, Platzhalter `{player}`, `{uuid}`, `{pet}` |
| `restore-active-on-join` | `false` | aktives Pet nach dem Join selbst beschwören (MCPets macht das mit Velocity-Sync schon) |
| `auto-rescan` | `true` | alle 5 Minuten die MCPets-Dateien per Änderungszeit prüfen |
| `item-format-nexo` | `nexo:{id}` | `itemId` einer Karte mit Nexo-Item |
| `item-format-material` | `{material}` | `itemId` einer Karte ohne Nexo-Item |
| `fallback-material` | `PLAYER_HEAD` | Icon ohne Nexo-Item |
| `sounds.*` | an | Kauf-, Equip- und Fehlerton (Sound-Keys wie `entity.player.levelup`) |

Fehlende Schlüssel in `config.yml` und `messages.yml` werden beim Start und bei `/petshop reload`
mit dem Standardwert ergänzt. Vorhandene Werte bleiben.

### `pets.yml`

Jedes Pet aus `plugins/MCPets/Pets/**/*.yml` wird beim Scan eingetragen, wenn es noch fehlt:

```yaml
pets:
  dog:
    name: Golden Dog     # leer = Icon.Name aus der MCPets-Datei
    price: 2000
    nexo-item: ''        # leer = automatisch
    enabled: true        # false = nicht im Shop, Besitzer behalten es
```

PetShop überschreibt vorhandene Einträge nie. Gelesen werden aus der MCPets-Datei `Id`
(fehlt sie, der Dateiname, wie bei MCPets), `Permission`, `Icon.Name` (sonst `Icon.DisplayName`)
und `Icon.NexoId`. Pets ohne `Permission` werden übersprungen (eine Warnung pro Scan).
Die Pet-ID wird kleingeschrieben und darf nur `a-z 0-9 _ -` enthalten (höchstens 64 Zeichen).

Gescannt wird beim Start, nach `/petshop reload`, 3 Sekunden nach `/mcpets reload` und, wenn
`auto-rescan` an ist, sobald sich eine Datei geändert hat (Prüfung alle 5 Minuten).

### `messages.yml`

Alle Texte in MiniMessage, Standard Englisch. Ein leerer Text schickt nichts.
`labels.price`, `labels.equip` und `labels.equipped` sind die Texte der Preiszeile im Menü;
GameUI zeichnet sie als reinen Text (Farbe kommt vom Menü-Element).

## Menü

PetShop schreibt die GameUI-Datei aus `plugins/PetShop/menu-template.yml`. Die Vorlage ist die
`shop.yml` mit Scrollbereich (`scrollables`, `scrollRegion`, `scrollGroup`, `rows_track`,
`rows_thumb`). Eine eigene Vorlage kann die Datei ersetzen. Regeln:

- **Kopf**: alles vor `card_1_edge`. **Karte**: alle Elemente `card_1*` (Edge, Karte, Item,
  Name, Preis). Die übrigen `card_N*` der Vorlage werden ignoriert. **Fuß**: alles danach.
- Karte N (i = N − 1) liegt in Spalte `i % 3`, Reihe `i / 3`: `x = [590, 945, 1300]`,
  `y = 668 − 240 · Reihe`. Die Abstände von Item, Name und Preis zur Karte kommen aus der Vorlage.
- Ersetzt werden: Name (`'…'`), `itemId`, Preis (`'%petshop_label_<id>%'`), der `RUN_COMMAND`
  der Karte (`petshop click <id>`), die Textbreiten (Zeichenbreiten × `glyphScale` × 8), die
  Daumenhöhe von `rows_thumb` (`clamp(round(500·500 / (Reihen·240)), 32, 500)`) und die Breite
  von `title_right`. Alles andere bleibt Zeichen für Zeichen wie in der Vorlage.
- Die Datei wird atomar geschrieben (temporäre Datei, dann verschieben) und nur, wenn sich der
  Inhalt geändert hat. `/petshop generate` schreibt immer.

## Befehle

| Befehl | Permission | Wirkung |
|---|---|---|
| `/petshop` (`/ps`) | `petshop.use` (alle) | Besitz aus der DB neu laden, dann `open-command` ausführen |
| `/petshop click <pet>` | `petshop.use` | nicht besessen: kaufen. Besessen: beschwören. Aktiv: nach Hause schicken |
| `/petshop unequip` | `petshop.use` | aktives Pet nach Hause schicken |
| `/petshop generate` | `petshop.admin` | Menüdatei neu schreiben |
| `/petshop reload` | `petshop.admin` | Konfiguration neu laden und neu scannen |
| `/petshop give <spieler> <pet>` | `petshop.admin` | Pet schenken (auch offline, Spieler muss schon einmal online gewesen sein) |
| `/petshop take <spieler> <pet>` | `petshop.admin` | Pet entziehen |

`click` und `unequip` haben eine Sekunde Abklingzeit. Unbekannte IDs werden still ignoriert.

## Placeholder (PlaceholderAPI)

| Placeholder | Wert |
|---|---|
| `%petshop_label_<pet>%` | `2000 coins` / `Equip` / `Equipped` |
| `%petshop_owned%` | Anzahl besessener Pets, die im Shop sind |
| `%petshop_total%` | Anzahl Pets im Shop |
| `%petshop_active%` | Name des aktiven Pets oder leer |

Jeder Wert ist ein fertiger String im Speicher des Spielers; er wird nur bei Kauf, Equip,
Reload oder neuem Katalog neu berechnet. Unbekannte Placeholder ergeben einen leeren Text.

## Kauf

1. Prüfen: Pet sichtbar, nicht besessen, Daten geladen, DB erreichbar, `PlayerPointsAPI.look` reicht.
2. `INSERT IGNORE` in `petshop_owned` reserviert das Pet (0 Zeilen = gehört schon, keine Abbuchung).
3. `PlayerPointsAPI.take` auf dem Thread des Spielers. Schlägt es fehl, wird die Reservierung gelöscht.
4. Cache, Permission, Placeholder, Nachricht, Ton.

Geht der Spieler zwischen 2 und 3 offline oder wird der Server gestoppt, wird die Reservierung
zurückgenommen. Doppelklicks lösen nur einen Kauf aus, gleichzeitige Käufe auf zwei Servern
werden von der Datenbank entschieden.

## MCPets-Anbindung

PetShop nutzt die öffentliche Klasse `fr.nocsy.mcpets.api.MCPetsAPI` (kompiliert gegen MCPets
`v4.1.11` über JitPack, geprüft gegen den aktuellen Quellcode 4.2.1):

- Beschwören: `MCPetsAPI.getObjectPet(id)` (liefert eine Kopie), `setCheckPermission(false)`,
  `spawn(player, location)`. Andere Shop-Pets des Spielers werden vorher mit `despawn(REPLACED)`
  entfernt. `MCPetsAPI.setActivePet` wird nicht benutzt, weil es den Besitzer nicht setzt.
- Nach Hause schicken: `MCPetsAPI.getActivePetsForPlayer(uuid)` und `despawn(REVOKE)`.
- Wenn ein Spieler ein Pet über das MCPets-Menü beschwört oder zurückruft (`PetSpawnedEvent`,
  `PetDespawnEvent` mit `REVOKE`), passt PetShop das aktive Pet an.
- Fehlt MCPets oder passt seine API nicht, nimmt PetShop `equip-command` / `unequip-command`
  (`/mcpets spawn <petId> <player> <checkPermission> [-s]`, `/mcpets revoke <player>`).

## Bauen

```
./gradlew build                 # build/libs/PetShop.jar, mit Tests
./gradlew compileJava -PpaperApi=26.2.build.134-stable -PjavaRelease=25   # Prüfung gegen 26.2
```

## Offene Annahmen

- GameUI liest Nexo-Items als `nexo:<id>` (aus dem GameUI-0.0.26-Jar gelesen) und lädt Screens
  mit `hexo-ui screen reload` neu. Beides ist in `config.yml` änderbar.
- Die Bedeutung von `scrollables.position` (linke obere Ecke, y von oben gezählt) ist aus
  `armory.yml` abgeleitet; GameUI dokumentiert sie nicht.
- PetShop liest `config.yml`, `messages.yml` und `pets.yml` einmal in `onEnable` auf dem
  Haupt-Thread (bevor der Server tickt). Alle späteren Datei- und Datenbankzugriffe laufen async.
- Ein Pet wird beim Kauf zuerst reserviert. Stürzt der Server genau zwischen Reservierung und
  Abbuchung ab, behält der Spieler das Pet ohne Bezahlung.
- Folia ist nicht freigegeben (`folia-supported` fehlt), weil MCPets und PlaceholderAPI dort
  nicht laufen; PetShop selbst nutzt nur die Folia-tauglichen Scheduler.
