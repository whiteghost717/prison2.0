# PrisonRealm 2.0.0

Paper 26.2 plugin source. Build with Java 25 and Gradle using `gradle build`.

## Crafting

```
R N R
N E N
R N R
```
R = Redstone Block, N = Nether Star, E = Dragon Egg. The recipe produces a Crying Obsidian item marked with persistent data and custom model data 26001.

## Commands
- `/prisonrealm give <player>` — admin gives the item.
- `/prisonrealm setprison` — admin saves their current Nether position as the prison spawn.
- `/prisonrealm status` — show ritual/prisoner counts.
- `/prisonrealm sealed` — list prisoners.
- `/prisonrealm release <player>` — admin emergency release.
- `/seal <player>` — only the owner of that player's active ritual can seal them.
- `/unseal <player>` — only the original sealer can release them.

## Ritual
Place the special item while another player is inside a 30-by-30 X/Z area. Four End Crystals spawn automatically. The target has 30 seconds to escape; leaving cancels the ritual. After 30 seconds, the crystals beam at and freeze the target. The original placer can seal at any later time with `/seal <player>`, as long as they stay online/alive and the target remains in the area. Owner death/disconnect, target disconnect, or destroying the core cancels the ritual. A successful seal teleports the target to the configured Nether prison, puts them in Adventure mode, and blocks damage/movement until release.

Set the Nether prison by travelling to the desired location and running `/prisonrealm setprison` as an admin. Prisoners and their original sealer UUIDs are saved in config.yml.

## Texture note
The plugin assigns custom model data 26001 but does not include a resource pack/model texture. To show the custom small purple cube texture, a matching resource pack must be created and distributed to players.
