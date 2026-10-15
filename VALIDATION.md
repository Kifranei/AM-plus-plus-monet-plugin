# Validation record / 验证记录

Verified without a device (see README for commands):

- `testDebugUnitTest`: palette derivation (contrast ≥ 4.5 for every hue in both themes, greyscale/transparent fallback), settings store, per-cover cache, stale-result dropping, role mapping, profile parsing and exact version selection.
- `verify-host-profile.py --glass` against the real 1606 / 1607 / 1609 / 1611 APKs: every profiled colour and `id/player_root` exists, player colours resolve to white, accents resolve to literals.
- `verify-monet-plugin.py`: ZIP layout, manifest, DEX integrity, no SDK/host classes bundled.

Not verified (no device with Apple Music): on-screen result, hook install on each host build at runtime, animation smoothness, behaviour of every Apple Music screen in whole-app mode.
