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
4. Das Menü landet automatisch unter `plugins/GameUI/screen/Petsshop.yml` (Befehl `/petsshop`);
   danach führt PetShop `hexo-ui screen reload` aus. `/petshop` öffnet es.
   PetShop überschreibt diese Datei. Das Design änderst du in `plugins/PetShop/menu-template.yml`.

PetShop lädt vor GameUI (`loadbefore`) und schreibt die Menüdatei schon in `onEnable`, weil GameUI
die Scrollbereiche beim Start in sein Resource Pack einbaut.

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
| `open-command` | `petsshop` | Befehl, den der Spieler nach `/petshop` ausführt; muss zur `command:`-Zeile der Vorlage passen |
| `menu-file` | `plugins/GameUI/screen/Petsshop.yml` | Ziel der erzeugten Menüdatei (GameUI benennt den Screen nach der Datei), leer = keine Datei |
| `menu-reload-command` | `hexo-ui screen reload` | Konsolenbefehl nach dem Schreiben, leer = keiner |
| `generate-on-start` | `true` | beim Start neu schreiben, wenn sich der Inhalt unterscheidet (`false`: nur wenn die Datei fehlt) |
| `menu-layout.*` | `[590, 945, 1300]`, `668`, `240`, `32`, `'#FFC24A'` | Spalten-x, y der ersten Reihe, Reihenabstand, kleinste Daumenhöhe, Randfarbe des ausgewählten Pets (leer = keine) |
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

PetShop schreibt die GameUI-Datei aus `plugins/PetShop/menu-template.yml` (deine `Petsshop.yml`).
Die Vorlage enthält zwei Musterkarten:

| Musterkarte | Aussehen | Sichtbar für Spieler, die das Pet ... |
|---|---|---|
| `card_1` (Jupiter) | Icon 128 bei +36, Name bei −46, Preis bei −78 | noch nicht besitzen |
| `card_2` (Ebony Cat) | Icon 155 bei +22, Name bei −82, kein Preis | besitzen |

Erkannt wird das automatisch: die erste Karte **mit** `card_N_price` ist das Kauf-Layout, die erste
**ohne** das Besitz-Layout. Weitere `card_N*` der Vorlage werden ignoriert. Jede Pet-Karte bekommt:

- Elemente, die in beiden Mustern gleich sind (Rand, Hintergrund), nur einmal.
- Elemente, die sich unterscheiden, zweimal: `card_N_item`, `card_N_name`, `card_N_price` mit
  `visible: '{= !%petshop_owned_<id>% }'` und `card_N_item_owned`, `card_N_name_owned` mit
  `visible: '%petshop_owned_<id>%'`. GameUI liest das pro Spieler zweimal pro Sekunde; nach einem
  Kauf wechselt die Karte also von selbst ins Besitz-Layout.
- Die Klick-Aktion sitzt auf `card_N_edge`, damit die ganze hellgraue Fläche reagiert
  (GameUI gibt Klicks nur an Elemente mit `actions`, Beschriftungen blockieren nicht).
  `clickCooldown: 1000` passt zur Abklingzeit im Plugin.
- Der Rand des ausgewählten Pets wird gold:
  `color: "{= pick(%petshop_state_<id>%, 'equipped', '#FFC24A', '#5A6273') }"`.
- Position: Karte N (i = N − 1) liegt in Spalte `i % 3`, Reihe `i / 3`, `x = [590, 945, 1300]`,
  `y = 668 − 240 · Reihe`; Abstände der Elemente zur Karte wie in der Vorlage. `scrollGroup` ist
  `row<Reihe>`.
- Ersetzt werden außerdem Name, `itemId`, Preis-Platzhalter, `petshop click <id>`, die Textbreiten
  (Zeichenbreiten × `glyphScale` × 8) und die Daumenhöhe von `rows_thumb`:
  `clamp(round(Spur · Bereich / (Reihen · 240)), 32, Spur)` mit Spur = Höhe von `rows_track` und
  Bereich = Höhe des Scrollbereichs (bei dir beide 486).
- Alles andere bleibt Zeichen für Zeichen wie in der Vorlage. Hat eine Vorlage kein Besitz-Layout,
  zeigt die Preiszeile wie früher `Equip` / `Equipped`.
- Die Datei wird atomar geschrieben und nur, wenn sich der Inhalt geändert hat.
  `/petshop generate` schreibt immer.

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
| `%petshop_owned_<pet>%` | `true` / `false`: besitzt der Spieler das Pet |
| `%petshop_state_<pet>%` | `buy` / `owned` / `equipped` |
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
- GameUI zählt `false`, `no`, `0` und leeren Text als falsch (aus dem GameUI-0.0.26-Jar gelesen);
  darauf bauen die `visible`-Bedingungen. Dass ein verstecktes Element keine Klicks nimmt, steht
  so in der Doku (`startHidden`); die Karten verlassen sich darauf nicht, weil nur der Rand klickbar ist.
- PetShop liest beim Start `config.yml`, `messages.yml`, `pets.yml` und die MCPets-Dateien und
  schreibt die Menüdatei einmal in `onEnable` auf dem Haupt-Thread (bevor der Server tickt), damit
  GameUI sie beim Start vorfindet. Alle späteren Datei- und Datenbankzugriffe laufen async.
- Ein Pet wird beim Kauf zuerst reserviert. Stürzt der Server genau zwischen Reservierung und
  Abbuchung ab, behält der Spieler das Pet ohne Bezahlung.
- Folia ist nicht freigegeben (`folia-supported` fehlt), weil MCPets und PlaceholderAPI dort
  nicht laufen; PetShop selbst nutzt nur die Folia-tauglichen Scheduler.
