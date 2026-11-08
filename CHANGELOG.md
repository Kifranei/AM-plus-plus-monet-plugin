# 更新日志 / Changelog

## v1.0.1 / 2

- 新增 Apple Music build **1614** 适配（`applemusic-1614.json`；1614 的 versionName 为 `7.0.0`，不再带 `-beta`；颜色资源与 `id/player_root` 与 1613 一致）。
  Adds Apple Music build **1614** support (`applemusic-1614.json`; 1614 reports versionName `7.0.0` without `-beta`; colour resources and `id/player_root` match 1613).
- 新增 Apple Music build **1613** 适配（`applemusic-1613.json`；颜色资源与 `id/player_root` 与 1611 一致）。
  Adds Apple Music build **1613** support (`applemusic-1613.json`; colour resources and `id/player_root` match 1611).
- 修复关闭液态玻璃后原生底栏仍为默认蓝色的问题（[#1](https://github.com/Kifranei/AM-plus-plus-monet-plugin/issues/1)）：带状态的颜色选择器与底部导航栏现在也会跟随封面色。
  Fixes the native bottom bar staying default blue with Liquid Glass off ([#1](https://github.com/Kifranei/AM-plus-plus-monet-plugin/issues/1)): stateful colour selectors and the bottom navigation bar now follow the cover colour too.
- 失败开放：同一阶段连续失败 3 次仅关闭该阶段。诊断：`monet-diag:` / logcat `AMPP-monet` / `monet-diag.log`。
  Fail-open: a stage that fails 3 times turns only itself off. Diagnostics: `monet-diag:` / logcat `AMPP-monet` / `monet-diag.log`.

## v1.0.0 / 1

- 首个版本：从当前播放的专辑封面提取莫奈主题色，支持 Apple Music 1606 / 1607 / 1609 / 1611。
  First release: Monet theme colour from the playing album cover, for Apple Music 1606 / 1607 / 1609 / 1611.
