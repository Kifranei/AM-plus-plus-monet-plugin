package dev.kifranei.ampp.monet

internal class MonetText(
    val name: String,
    val introduction: String,
    val off: String,
    val player: String,
    val app: String,
    val restartHint: String,
    val save: String,
    val saved: String,
    val saveFailed: String,
) {
    fun label(mode: MonetMode) = when (mode) { MonetMode.OFF -> off; MonetMode.PLAYER -> player; MonetMode.APP -> app }

    companion object {
        private val zh = MonetText(
            name = "莫奈取色",
            introduction = "从当前播放的专辑封面提取主题色（Material You / 莫奈风格）。取色在后台完成，按封面缓存，切歌时渐变过渡，并保证文字对比度。",
            off = "关闭（默认，保持原样）",
            player = "仅播放页（播放页控件、进度条、歌词高亮跟随封面）",
            app = "整个应用（强调色全局跟随正在播放的封面）",
            restartHint = "修改后需要重启 Apple Music 才会生效。",
            save = "保存",
            saved = "已保存，重启 Apple Music 后生效",
            saveFailed = "保存失败",
        )
        private val en = MonetText(
            name = "Monet cover color",
            introduction = "Derives a Material You style theme colour from the cover of the playing album. Extraction runs in the background, is cached per cover, fades on track changes and keeps text readable.",
            off = "Off (default, untouched)",
            player = "Player page only (controls, progress bar and lyric highlight follow the cover)",
            app = "Whole app (accent colour follows the playing cover)",
            restartHint = "Restart Apple Music for a change to take effect.",
            save = "Save",
            saved = "Saved. Restart Apple Music to apply.",
            saveFailed = "Save failed",
        )
        fun forLanguage(language: String?) = if (language.equals("zh", true)) zh else en
    }
}
