# Multiverse world reset and Japan time synchronization

## Goal

Integrate the existing scheduled resource-world reset with Multiverse-Core
5.8.1. A reset must generate a new random seed while retaining the world’s
Multiverse configuration, game rules, and world border. It must not retain the
previous spawn position.

Add opt-in synchronization for selected worlds so their visible sun position
matches Japan Standard Time (JST) continuously.

The two features are functionally independent. A world may use either feature,
both features, or neither: `world-reset` never reads time-sync configuration,
and `world-time-sync` never reads reset configuration or invokes Multiverse.
They share only the plugin lifecycle that starts and restarts their separate
scheduled tasks.

## Scope

### Multiverse reset

- Add Multiverse-Core 5.8.1 as a `compileOnly` dependency and as a plugin
  `softdepend`.
- Replace the `WorldCreator`/manual-folder-deletion branch in
  `WorldResetManager` with the Multiverse-Core `WorldManager#regenWorld` API.
- Regenerate each configured reset target with a random seed and explicitly
  preserve the Multiverse world configuration, game rules, and world border.
- After a successful regeneration, overwrite Multiverse’s stored spawn with
  the newly generated Bukkit world spawn. This keeps every other Multiverse
  setting while deliberately discarding the former spawn coordinate.
- Continue the existing evacuation, lockout, home/warp deletion, and scheduled
  announcement behavior. Only successfully regenerated worlds have their
  homes and warps deleted and their lockout cleared.
- If Multiverse-Core is absent, inaccessible, or a configured world is not a
  loaded Multiverse world, do not fall back to destructive Bukkit regeneration.
  Log the reason and leave that world locked out for an administrator to
  resolve, matching the current safety-first failure behavior.

### JST world-time synchronization

- Add a reloadable `JapanTimeSyncManager`, running once per second on Paper’s
  global region scheduler.
- `world-time-sync.enabled` controls the feature and `world-time-sync.worlds`
  lists Bukkit world names. Missing or unloaded names are skipped with a
  one-time warning per configuration cycle.
- Use the fixed `Asia/Tokyo` zone, never the host operating system’s time zone.
- Convert JST wall-clock time to Minecraft’s visible solar time:

  | JST | Minecraft ticks |
  | --- | ---: |
  | 00:00 | 18000 |
  | 06:00 | 0 |
  | 12:00 | 6000 |
  | 18:00 | 12000 |

  The manager derives the tick value from the current second-of-day, modulo
  24000, and calls `World#setTime` for each loaded target.
- The plugin does not set the game rule itself. The configuration comments
  instruct operators to run `/gamerule advance_time false` for each target
  (the Java 1.21.11 replacement for `doDaylightCycle`). Stopping the natural
  cycle prevents it drifting between one-second corrections.

## Configuration

Keep the existing `timevote.disabled-worlds` list as the mechanism that
prevents manual vote-driven changes. Add a comment there telling operators to
list every `world-time-sync.worlds` entry in it.

Add this disabled-by-default section immediately after `timevote`:

```yaml
world-time-sync:
  enabled: false
  worlds: []
  # 対象ワールド内で /gamerule advance_time false を実行してください。
  # timevote セクションの disabled-worlds にも、同じワールド名を追加してください。
```

## Wiring and reload behavior

- Construct the new manager in `StellariaCore#onEnable`, start it after the
  existing manager startup, and expose it only where lifecycle management
  needs it.
- Extend `reloadFeatureManagers()` to restart the time-sync task alongside the
  existing world-reset scheduler.
- Cancel the task during disable if the manager owns an active scheduled task.

## Errors and safety

- All Multiverse world operations run on the global/main server scheduler as
  required by Multiverse’s API.
- A Multiverse regeneration failure is logged with the API failure reason. It
  does not remove database locations or release the lockout.
- A failed post-regeneration spawn update is logged as severe; its world stays
  locked out, since the requested spawn-inheritance guarantee cannot be made.
- Synchronization failures for one world do not prevent synchronization of
  other configured worlds.

## Verification

- Add unit tests for the JST-to-Minecraft-tick conversion at midnight, dawn,
  noon, dusk, and fractional seconds.
- Run the focused unit tests, the complete Gradle test suite, and the Gradle
  build.
- Manual server validation: configure a Multiverse resource world with a
  non-default border, gamerule, and MV spawn; reset it; confirm the border and
  gamerule persist while `/mv spawn` uses the regenerated spawn. Then enable
  time sync and confirm the sky matches JST while `advance_time` is false.
