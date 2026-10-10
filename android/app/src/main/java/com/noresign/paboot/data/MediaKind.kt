package com.noresign.paboot.data

/**
 * 文件 Tab 里能就地预览的媒体类型。
 *
 * 由扩展名判定——daemon 的 `/api/files/raw` 只吐字节、不带语义类型，所以「这是图片还是
 * 视频」由客户端决定。[of] 返回 null 表示不做媒体预览（回落到文本查看器 / 二进制提示）。
 */
enum class MediaKind {
    Image,
    Video;

    companion object {
        // 只收客户端解码器真能处理的类型：Coil 不带 SVG 解码（要另加 coil-svg）、也没有 ICO
        // 解码器，ExoPlayer 没有 AVI extractor——列进来只会变成「加载失败」，所以一律不列。
        // `avif` / `heic` 分别要 API 31+ / 28+，动图（GIF / WebP）要 API 28+，低版本退化为
        // 静态首帧或失败态。
        private val IMAGES = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "avif", "heic", "heif",
        )
        private val VIDEOS = setOf(
            "mp4", "m4v", "mov", "webm", "mkv", "3gp",
        )

        fun of(path: String): MediaKind? {
            val ext = path.substringAfterLast('.', "").lowercase()
            return when (ext) {
                in IMAGES -> Image
                in VIDEOS -> Video
                else -> null
            }
        }
    }
}
