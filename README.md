# CoreProtect for Forge

A Forge port of [CoreProtect](https://github.com/PlayPro/CoreProtect), the block logging and anti-griefing tool. Inspect, look up, roll back and restore damage on your server without impacting performance.

| | |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47.x (built against 47.4.0) |
| Java | 17+ |
| Side | Server only. Clients do not need the mod (it also works in singleplayer/LAN). |

## Installation

1. Drop `CoreProtect-1.20.1-<version>-forge.jar` into the server's `mods` folder.
2. Start the server. By default a SQLite database is created at `<world>/coreprotect/database.db`. No other setup is needed.
3. Optionally edit `config/coreprotect-common.toml` (for example to use MySQL/MariaDB).

The SQLite and MySQL JDBC drivers are bundled inside the jar.

## Commands

`/co`, `/core` and `/coreprotect` are interchangeable.

| Command | Description |
| --- | --- |
| `/co help` | Show the command list. |
| `/co inspect` (`/co i`) | Toggle the inspector. |
| `/co lookup <params>` (`/co l`) | Look up logged data. `/co l <page>` or `/co l <page>:<rows>` pages through the last lookup or inspection. |
| `/co near [r:<radius>]` | Look up changes within 5 blocks (or the given radius). |
| `/co rollback <params>` (`/co rb`) | Roll back block changes, container transactions and entity kills. |
| `/co restore <params>` (`/co rs`) | Restore rolled back data. |
| `/co undo` | Revert your last rollback or restore. |
| `/co apply`, `/co cancel` | Apply or cancel a `#preview`. |
| `/co purge t:<time> [r:#world] [i:<blocks>] [#optimize]` | Delete data older than the given time (players: at least 30 days, console: at least 24 hours). |
| `/co status` | Show version, database type and queue size. |
| `/co teleport <world> <x> <y> <z>` | Teleport (used by the clickable coordinates in lookup results). |
| `/co reload` | The config file is reloaded automatically when saved. Database settings need a restart. |

### Parameters

| Parameter | Description |
| --- | --- |
| `u:<users>` | One or more users, e.g. `u:Notch,Intelli`. Non-player sources start with `#`: `#tnt`, `#creeper`, `#explosion`, `#fire`, `#decay`, `#enderman`, ... |
| `t:<time>` | Time to look back, e.g. `t:2w,5d,7h,2m,10s`, `t:5d2h`, `t:2.50h`, or a range `t:1h-2h`. |
| `r:<radius>` | Radius around you (x/z), or `r:#global`, `r:#overworld`, `r:#the_nether`, `r:#the_end`, `r:#modid:dimension`. |
| `a:<action>` | `block`, `+block`, `-block`, `click`, `kill`, `container`, `+container`, `-container`, `item`, `+item`, `-item`, `chat`, `command`, `session`, `+session`, `-session`, `sign`, `username`. Several can be combined: `a:chat,command`. |
| `i:<include>` | Only these blocks, items or entities, e.g. `i:diamond_ore,chest`. Modded ids work: `i:create:andesite_casing`. |
| `e:<exclude>` | Exclude blocks, items or entities, or users (`e:Notch`, `e:#tnt`). |
| `#preview` | Preview a rollback/restore (only you see the changes). |
| `#count` | Only count the matching rows. |
| `#optimize` | Purge only: compact the database afterwards. |

Examples:

```
/co rollback u:Griefer t:1d r:#global
/co rollback t:30m r:20 e:#tnt
/co lookup u:Notch t:3h a:container
/co restore u:Griefer t:1d r:#global
```

A rollback without a user uses the configured default radius (10). A radius larger than `max-radius` (100) needs `r:#global`.

### Inspector

With the inspector enabled (`/co i`):

* Left-click a block to see who placed or broke it.
* Right-click a container to see its transactions (both halves of a double chest).
* Right-click a sign to see its text history.
* Right-click any other block to see changes and interactions at that block and the space in front of the clicked face.

Inspecting cancels the click, so you won't break, place or open anything.

## Permissions

Permissions use Forge's permission API, so permission mods such as LuckPerms can manage them. Without a permission mod, `coreprotect.co` is granted to everyone and every other node is granted to operators (permission level 2 or higher). The console always has full access.

`coreprotect.co`, `coreprotect.help`, `coreprotect.inspect`, `coreprotect.lookup`, `coreprotect.lookup.near`, `coreprotect.lookup.block`, `coreprotect.lookup.click`, `coreprotect.lookup.kill`, `coreprotect.lookup.container`, `coreprotect.lookup.item`, `coreprotect.lookup.chat`, `coreprotect.lookup.command`, `coreprotect.lookup.session`, `coreprotect.lookup.sign`, `coreprotect.lookup.username`, `coreprotect.rollback`, `coreprotect.restore`, `coreprotect.purge`, `coreprotect.teleport`, `coreprotect.reload`, `coreprotect.status`

## What is logged

* Blocks placed and broken by players, including modded blocks and block entity data (chest contents, sign text, ...), so rollbacks restore them exactly.
* Blocks that break as a consequence of a player's action: torches, doors, beds, plants, and columns of sugar cane, cactus, bamboo, kelp, vines, etc.
* Explosions (TNT, creepers, fireballs, beds/anchors as `#explosion`), fire burning blocks, and leaf decay.
* Entities changing blocks: endermen (pick up and place), zombies breaking doors, withers, the ender dragon, crop trampling.
* Buckets, flint and steel, axe stripping, hoe tilling and other right-click block changes.
* Interactions that change a block: doors, trapdoors, gates, levers, buttons, repeaters, comparators, note blocks, ...
* Items taken from or put into block inventories (vanilla and modded, including `IItemHandler` inventories), attributed to the player who moved them.
* Items dropped and picked up.
* Entity kills (killed mobs are respawned by rollbacks and removed again by restores). Deaths of hostile mobs without a killer (burning in daylight, ...) are skipped.
* Sign text, chat, commands (except password commands such as `/login`, configurable), logins/logouts and username changes.

Every category can be turned off in the `[logging]` section of the config.

### Differences from the Bukkit version

This is a new implementation on Forge's events, not a line-by-line port. Not implemented:

* Piston movements, falling sand/gravel landing, liquid flow attribution, natural growth, and ravagers trampling leaves.
* Hanging entities (item frames, paintings), armor stands, entity spawns, and player inventory and crafting transactions.
* WorldEdit logging, networking API, DuckDB/ClickHouse backends, localization files.
* Rolling back item drops/pickups. They can be looked up, but are not rolled back.

## Configuration

`config/coreprotect-common.toml`:

* `[database]`: `use-mysql`, `table-prefix`, `mysql-host`, `mysql-port`, `mysql-database`, `mysql-username`, `mysql-password`, `mysql-use-ssl`.
* `[commands]`: `default-radius`, `max-radius`, `lookup-rows-per-page`, `inspector-rows-per-page`, `rollback-changes-per-tick`, `rollback-items`, `rollback-entities`.
* `[logging]`: one switch per category, plus `excluded-commands`.

## API

Other mods can use `net.coreprotect.CoreProtectAPI`:

```java
if (CoreProtectAPI.isEnabled()) {
    CoreProtectAPI.logPlacement("#mymachine", level, pos, state);
    CoreProtectAPI.logRemoval(player.getGameProfile().getName(), level, pos, state); // call before removing
}

// Off the server thread (these block while the database is queried):
List<LookupRow> history = CoreProtectAPI.blockLookup(level, pos, 3600);
List<LookupRow> rows = CoreProtectAPI.performLookup(level, pos, "u:Notch", "t:1d", "a:container");
```

## Building

```
./gradlew build              # jar in build/libs/CoreProtect-1.20.1-<version>-forge.jar
./gradlew runGameTestServer  # end-to-end tests on a real server (needs run/eula.txt containing eula=true)
./gradlew runServer          # development server
```

The game tests in `src/gametest` cover block logging and rollback/restore/undo, consequential breaks (doors, sugar cane), chest and double chest transactions, chests broken with their contents, explosions, fire, leaf decay, endermen, kills and entity respawning, buckets and clicks, signs, chat, commands, sessions, username changes, purge, previews and the inspector.

## License

CoreProtect is licensed under the [Artistic License 2.0](LICENSE).
