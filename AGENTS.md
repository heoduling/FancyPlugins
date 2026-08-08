# AI Maintenance Guide

This repository is a public fork of `FancyInnovations/FancyPlugins`. Keep the fork easy to rebase and keep changes narrowly scoped.

## Primary Scope

- `plugins/fancynpcs-v2`
- `plugins/fancynpcs-model`

Do not modify unrelated plugins or libraries unless one of the two maintained modules requires the change to compile or behave correctly.

## Runtime Contract

- Minecraft: 1.21.11
- Java: 25
- Primary platform: Folia/Luminol
- Integrations: BetterModel, SkinsRestorer and FancyNpcsModel
- Preserve existing commands, permissions, configuration keys and public API unless an issue explicitly requests a breaking change.

## Folia Rules

- Never wait on network, file I/O, another region or a `Future` from a tick thread.
- Use a bounded executor for blocking profile and HTTP work.
- Use `EntityScheduler` for player/entity state, `RegionScheduler` for location state and `GlobalRegionScheduler` only for global lifecycle work.
- Pass immutable IDs/data across async boundaries. Refetch live Bukkit/NPC objects after returning to the owning scheduler.
- Cancel pending work and close owned executors/listeners/trackers during disable.
- Do not claim PlugMan hot-load support without a real unload/load/reload regression.

## Model Rules

- The BetterModel tracker is per NPC, while spawned visibility is per viewer.
- A new viewer must not force an already-spawned viewer's model to respawn.
- Keep the vanilla carrier invisible and non-glowing while a custom model is active.
- Preserve FancyNpcs right/left-click actions through BetterModel hitboxes and deduplicate duplicate carrier/hitbox events.
- Movement, teleport, visibility-range exit/re-entry, BetterModel reload and plugin disable are required lifecycle cases.

## Skin Rules

- `/npc skin` must return immediately; provider/profile lookups stay off the command region thread.
- Coalesce identical in-flight username/variant lookups.
- For one NPC, only the newest skin request may apply.
- Return to the proper Folia scheduler before firing Bukkit events or mutating NPC state.
- Keep all provider calls bounded by queue capacity and timeouts.

## Build And Check

Use Java 25 for Gradle and plugin compilation. The old 1.21.5, 1.21.6 and 1.21.9 Paperweight tasks explicitly launch CodeBook with Java 21 because their embedded ASM cannot read Java 25 runtime classes.

```bash
./gradlew :plugins:fancynpcs-v2:shadowJar :plugins:fancynpcs-model:shadowJar
./gradlew test
```

Before release, run an isolated Luminol 1.21.11 smoke test with FancyNpcs, FancyNpcsModel, BetterModel and SkinsRestorer. Create an NPC, apply a previously uncached premium username five times, issue an immediate marker command, leave/re-enter visibility range, move the NPC, test both click actions, then stop normally. Record versions, elapsed command time, errors and SHA-256 hashes.

## Repository Hygiene

- Never commit `build/`, `.gradle/`, server worlds, logs, caches, skins, model packs, credentials, private IP addresses or production configuration.
- Keep upstream attribution and the MIT license.
- Document every fork-only behavior in `docs/maintenance/FORK_MAINTENANCE_ZH.md`.
- Prefer one focused commit per root cause. Include the exact validation performed.
