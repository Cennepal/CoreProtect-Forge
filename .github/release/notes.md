First release of CoreProtect for **Forge 1.20.1** (Forge 47.x, Java 17).

## Installation
Put `CoreProtect-1.20.1-24.1.0-forge.jar` in your server's `mods` folder. Clients don't need it. A SQLite database is created in `<world>/coreprotect/`; MySQL/MariaDB can be enabled in `config/coreprotect-common.toml`.

## Features
- Logs block placements and breaks (with chest contents and sign text), blocks that break as a consequence, explosions, fire, leaf decay, endermen, zombies breaking doors, withers and the ender dragon.
- Logs container transactions (vanilla and modded inventories), item drops and pickups, kills, signs, chat, commands, sessions and username changes.
- `/co inspect`, `lookup`, `near`, `rollback`, `restore`, `undo`, `#preview`, `purge`, `status`, with the familiar `u:` `t:` `r:` `a:` `i:` `e:` parameters.
- Lag-free rollbacks and restores, including container contents and respawning killed entities.
- Permissions through Forge's permission API (works with LuckPerms); operators by default.

## Not yet supported
Piston movements, falling sand landing, liquid flow attribution, natural growth, item frames and armor stands, player inventory and crafting transactions, WorldEdit logging, and rolling back item drops and pickups.

See the [README](https://github.com/Cennepal/CoreProtect-Forge#readme) for the full command and permission reference.
