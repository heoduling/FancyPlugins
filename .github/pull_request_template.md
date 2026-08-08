## What changed

Describe the root cause and the smallest behavior change.

## Scope

- [ ] FancyNpcs only
- [ ] FancyNpcsModel only
- [ ] Shared dependency required by one of those modules

## Folia safety

- [ ] No tick thread waits on network, file I/O, locks or futures
- [ ] Bukkit/NPC state is accessed from its owning scheduler
- [ ] Disable/reload cleanup was reviewed

## Validation

- [ ] Relevant Gradle tasks pass
- [ ] Luminol 1.21.11 startup and normal stop pass
- [ ] FancyNpcs + FancyNpcsModel + BetterModel + SkinsRestorer smoke test passes

List exact versions, commands, observed behavior and artifact hashes below.
