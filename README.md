# AM++ 莫奈取色插件 / AM++ Monet Cover Color plugin

独立的 AM++ 插件（SDK v1）。从**当前播放的专辑封面**提取 Material You / 莫奈风格的主题色，并让 Apple Music 的颜色跟随封面。
A standalone AM++ plugin (SDK v1). It derives a Material You style theme colour from the **cover of the playing album** and makes Apple Music's colours follow it.

- 插件 ID / Plugin ID：`dev.kifranei.ampp.monet`
- 名称 / Name：AM 莫奈取色 (Monet cover color)
- 适配 / Supported：Apple Music `7.0.0-beta` build **1606 / 1607 / 1609 / 1611 / 1613**，`7.0.0` build **1614**（精确匹配，其他版本拒绝加载 / exact match only; other builds are refused before any hook）

### v1.0.1 / 2

新增 Apple Music build **1614** 适配（`applemusic-1614.json`；1614 的 versionName 为 `7.0.0`，不再带 `-beta`；颜色资源与 `id/player_root` 与 1613 一致）。
Adds Apple Music build **1614** support (`applemusic-1614.json`; 1614 reports versionName `7.0.0` without `-beta`; colour resources and `id/player_root` match 1613).

新增 Apple Music build **1613** 适配（`applemusic-1613.json`；颜色资源与 `id/player_root` 与 1611 一致）。失败开放：同一阶段连续失败 3 次仅关闭该阶段。诊断：`monet-diag:` / logcat `AMPP-monet` / `monet-diag.log`。
Adds Apple Music build **1613** support (`applemusic-1613.json`; colour resources and `id/player_root` match 1611). Fail-open: a stage that fails 3 times turns only itself off. Diagnostics: `monet-diag:` / logcat `AMPP-monet` / `monet-diag.log`.

## 选项 / Options

在 AM++ 的插件设置页选择，修改后**重启 Apple Music** 生效 / Pick in the plugin's settings page; restart Apple Music to apply.

| 选项 / Option | 行为 / Behaviour |
| --- | --- |
| 关闭 Off（默认 / default） | 不安装任何 Hook，Apple Music 完全保持原样 / installs nothing |
| 仅播放页 Player page only | 播放页控件、进度条、歌词高亮等 `player_*` 颜色（白色+透明度）换成带封面色调的近白色，保持原透明度 / the `player_*` white-alpha colours become cover-tinted near-white, alpha preserved |
| 整个应用 Whole app | 在上面的基础上，强调色（`key_color`、`color_primary*`）全局换成封面色；已创建的视图在安静时段渐变换色 / additionally the accent family follows the cover; already-inflated views fade over after things settle |

## 工作方式 / How it works

1. **取色 / Extraction**：观察系统 `MediaSession.setMetadata`（公开框架 API，不依赖 Apple Music 混淆符号）。主线程只读取少量字段并提交任务；封面缩放到 24×24、Oklab 色相分区量化，全部在后台低优先级线程完成。结果按专辑封面缓存（LRU 64），灰度/无色封面则保持原色。
2. **推导 / Derivation**：固定色相的 Oklab 明度阶梯，强调色对黑/白背景（深色/浅色模式）保证 WCAG 对比度 ≥ 4.5；按压/悬停/渐变角色沿原生方向移动。
3. **应用 / Applying**：Hook `Resources.getColor / getColorStateList / loadColorStateList`，按资源**名称**（见 `assets/applemusic-<build>.json`，不是随版本变化的数字 ID）做一次哈希查找；没有封面色时原样返回。已显示视图仅替换“已知原生色/本插件生成色”，其余一律不动。
4. **开销 / Cost**：无主线程取色；相同封面不重复计算、相同颜色不重复发布；换色在 900 ms 稳定延迟后、主线程空闲时一次遍历（≤4000 个视图、≤256 个目标），用一个 380 ms 动画渐变。

## 构建与校验 / Build and verify

```text
./gradlew :monet-plugin:testDebugUnitTest :monet-plugin:lintDebug :monet-plugin:pluginZip
python3 -B scripts/verify-sdk.py
python3 -B scripts/verify-monet-plugin.py monet-plugin/build/dist/ampp-monet-cover-color-<version>.zip
python3 -B scripts/verify-host-profile.py <apple-music.apk|apkm> --glass     # needs aapt2 and the APK
python3 -B scripts/verify-all-profiles.py --apk-dir <dir-with-apks>
```

新增宿主版本 = 新增一个 `applemusic-<code>.json` 并在 `profiles.json` 登记 / Adding a host build = one JSON profile plus one index entry.

许可 / License：GPL-3.0（见 `LICENSE`）。
