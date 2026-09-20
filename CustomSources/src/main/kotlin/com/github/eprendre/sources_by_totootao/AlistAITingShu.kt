package com.github.eprendre.sources_by_totootao

import com.github.eprendre.tingshu.sources.*
import com.github.eprendre.tingshu.utils.*
import com.github.kittinunf.fuel.Fuel
import org.json.JSONObject
import kotlin.text.Charsets

/**
 * 基于 Alist 部署的「AI 有声书」听书源。
 *
 * 站点标准 API 路径形如「$SITE/api/...」，需要账号密码登录后访问。
 *
 * 目录结构（已实测）：
 *   /otterhub/audio/有声书/AI有声书/      <- 各有声书文件夹（21 本）
 *       书名/                            <- 每本书是一个文件夹
 *           xxxx书名·第xxxx章·xxx.mp3     <- 音频/视频文件（type=2/3）
 *
 * 支持的媒体格式：
 *   音频：mp3 m4a flac wav aac ogg wma ape opus mka mp2 amr mid midi
 *   视频：mp4 mkv avi mov webm flv m3u8 ts wmv rm rmvb mpg mpeg 3gp
 *   识别优先看 Alist 的 type 字段（2=视频、3=音频），扩展名为兜底。
 *
 * 注意：本文件中的站点地址、账号密码均为明文，请勿发布到不可信环境。
 */
object AlistAITingShu : TingShu() {

    private const val SITE = "https://www.totootao.top/alist"
    private const val API = "$SITE/api"

    private const val USERNAME = "totootao"
    private const val PASSWORD = "Hhangxing963."

    /**
     * Alist 内部路径（不含站点前缀）。
     * 若你访问的 URL 整体都是 Alist 路径（例如根路径就是 /alist），则应改为：
     *   "/alist/otterhub/audio/有声书/AI有声书"
     */
    private const val ROOT_PATH = "/otterhub/audio/有声书/AI有声书"

    private const val PER_PAGE = 1000
    private val AUDIO_EXT = setOf("mp3", "m4a", "flac", "wav", "aac", "ogg", "wma", "ape", "opus", "mka", "mp2", "amr", "mid", "midi")
    private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "m3u8", "ts", "wmv", "rm", "rmvb", "mpg", "mpeg", "3gp")
    private val MEDIA_EXT = AUDIO_EXT + VIDEO_EXT
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    /** Alist 的 type：2=视频，3=音频，1=文件夹 */
    private const val ALIST_TYPE_DIR = 1
    private const val ALIST_TYPE_VIDEO = 2
    private const val ALIST_TYPE_AUDIO = 3

    private var token: String = ""

    private data class AlistItem(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val thumb: String,
        val size: Long,
        val type: Int = 0
    )

    /**
     * 统一 POST 请求：先按字符串取响应，再手动转 JSON，失败时把状态码和响应原文抛出来，方便排错。
     */
    private fun postJson(
        url: String,
        body: String,
        authToken: String = ""
    ): JSONObject {
        val headers = mutableMapOf("Content-Type" to "application/json")
        if (authToken.isNotEmpty()) {
            headers["Authorization"] = authToken
        }

        val (request, response, result) = Fuel.post(url)
            .header(headers)
            .body(body.toByteArray(Charsets.UTF_8))
            .responseString()

        val text = result.get()
        val status = response.statusCode
        if (status < 200 || status >= 300) {
            throw RuntimeException("HTTP $status @ $url : ${text.take(300)}")
        }
        if (text.isBlank()) {
            throw RuntimeException("HTTP $status @ $url 返回空响应体")
        }
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw RuntimeException("JSON 解析失败 @ $url : ${text.take(300)}")
        }
        val code = json.optInt("code", 200)
        if (code != 200) {
            val msg = json.optString("message", "未知错误")
            throw RuntimeException("Alist 错误 code=$code message=$msg @ $url")
        }
        return json
    }

    /**
     * 登录获取 token（缓存复用）。
     */
    private fun ensureToken(): String {
        if (token.isNotEmpty()) return token
        val url = "$API/auth/login"
        val body = """{"username":"$USERNAME","password":"$PASSWORD"}"""
        val json = postJson(url, body)
        token = json.getJSONObject("data").getString("token")
        return token
    }

    /**
     * 列出目录内容；token 失效时自动重新登录重试一次。
     */
    private fun listFiles(path: String): List<AlistItem> {
        return try {
            doList(path)
        } catch (e: Exception) {
            token = ""
            doList(path)
        }
    }

    /**
     * 列出目录内容。
     *
     * 注意：Alist 的 /api/fs/list 返回项里「没有 path 字段」，
     * 必须由调用方用「父路径 + / + name」自行拼接完整路径，
     * 否则传给 /api/fs/get 的 path 为空，拿不到音频直链。
     */
    private fun doList(path: String): List<AlistItem> {
        val tk = ensureToken()
        val base = path.trimEnd('/')
        val list = ArrayList<AlistItem>()
        var page = 1
        while (true) {
            val json = postJson(
                "$API/fs/list",
                """{"path":"$path","password":"","page":$page,"per_page":$PER_PAGE,"refresh":false}""",
                tk
            )
            val dataObj = json.optJSONObject("data")
                ?: throw RuntimeException("Alist /api/fs/list 返回无 data 字段：$json")
            val arr = dataObj.optJSONArray("content")
                ?: throw RuntimeException("Alist /api/fs/list 返回无 content 字段：$json")

            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val name = item.optString("name", "")
                // 服务端若返回了 path 就用，否则用 父路径/文件名 拼接
                val rawPath = item.optString("path", "")
                val fullPath = if (rawPath.isNotEmpty()) rawPath else "$base/$name"
                list.add(
                    AlistItem(
                        name = name,
                        path = fullPath,
                        isDir = item.optBoolean("is_dir", false),
                        thumb = item.optString("thumb", ""),
                        size = item.optLong("size", 0L),
                        type = item.optInt("type", 0)
                    )
                )
            }

            // 一本书可能有 2000+ 集，单页装不下，必须按 total 继续翻页，否则会丢章节
            val total = dataObj.optLong("total", 0L)
            if (arr.length() < PER_PAGE || list.size >= total || arr.length() == 0) break
            page++
        }
        return list
    }

    /** 按 URL 规则编码路径（保留 / 分隔符），中文、空格、· 等都会被正确转义。 */
    private fun encodePath(path: String): String {
        return path.split("/").joinToString("/") { seg ->
            java.net.URLEncoder.encode(seg, "UTF-8")
                .replace("+", "%20")   // 空格应为 %20 而不是 +
        }
    }

    /**
     * 由 Alist 内部路径生成可直接播放的地址：$SITE/d/<path>。
     *
     * 用固定下载路径而不是 /api/fs/get 的 raw_url：
     * - 无需 token，不会过期，也不用为每个章节多打一次网络请求
     * - 实测返回 206 + audio/mpeg，支持拖动进度条
     */
    private fun buildDownloadUrl(path: String): String {
        return "$SITE/d/" + encodePath(path.trimStart('/'))
    }

    override fun getSourceId(): String {
        return "03768816cadd43f787d7326f71ed06d8"
    }

    override fun getUrl(): String {
        return SITE
    }

    override fun getName(): String {
        return "Alist·AI有声书"
    }

    override fun getDesc(): String {
        return "基于 totootao.top 的 Alist「AI有声书」源，账号登录后读取目录，纯 API 无需 WebView。\n" +
                "注意：jar 内含明文账号密码，请勿发布到不可信环境。"
    }

    // Alist 没有全局搜索 API，这里采用「列根目录 + 书名本地匹配」实现搜索
    override fun isSearchable(): Boolean = true

    /**
     * 搜索：列出根目录下全部有声书（均为文件夹），按书名包含关键词过滤。
     * 标准 Alist 无搜索接口，而本源只有 21 本、都在同一目录，本地过滤即可满足需求。
     */
    override fun search(keywords: String, page: Int): Pair<List<Book>, Int> {
        return try {
            val items = listFiles(ROOT_PATH).filter { it.isDir }
            val kw = keywords.lowercase()
            val list = ArrayList<Book>()
            items.filter { it.name.lowercase().contains(kw) }
                .forEach { item ->
                    list.add(
                        Book(item.thumb, item.path, item.name, "", "").apply {
                            this.sourceId = getSourceId()
                        }
                    )
                }
            Pair(list, 1)
        } catch (e: Exception) {
            val msg = "⚠️搜索失败：${e.javaClass.simpleName}：${e.message ?: "未知错误"}"
            Pair(arrayListOf(Book("", "", msg, "", "")), 1)
        }
    }

    // 全部走 Fuel HTTP 请求，不需要 WebView，手表等设备也能用
    override fun isWebViewNotRequired(): Boolean = true

    override fun isMultipleEpisodePages(): Boolean = false

    override fun getCategoryMenus(): List<CategoryMenu> {
        return listOf(
            CategoryMenu(
                "AI有声书", listOf(
                    CategoryTab("全部", ROOT_PATH)
                )
            )
        )
    }

    override fun getCategoryList(url: String): Category {
        return try {
            val items = listFiles(url).filter { it.isDir }
            val list = ArrayList<Book>()
            items.forEach { item ->
                list.add(
                    Book(item.thumb, item.path, item.name, "", "").apply {
                        this.sourceId = getSourceId()
                    }
                )
            }
            Category(list, 1, 1, url, "")
        } catch (e: Exception) {
            // 让加载失败的原因可见，便于排查（而不是一片空白）
            val msg = "⚠️加载失败：${e.javaClass.simpleName}：${e.message ?: "未知错误"}"
            Category(arrayListOf(Book("", "", msg, "", "")), 1, 1, url, "")
        }
    }

    override fun getBookDetailInfo(bookUrl: String, loadEpisodes: Boolean, loadFullPages: Boolean): BookDetail {
        return try {
            val episodes = ArrayList<Episode>()
            var coverUrl = ""
            if (loadEpisodes) {
                val items = listFiles(bookUrl)
                // 优先用文件夹内第一张图片做封面
                val cover = items.firstOrNull { !it.isDir && it.name.substringAfterLast('.').lowercase() in IMAGE_EXT }
                coverUrl = cover?.thumb ?: ""

                // Alist 的 type=2 表示视频、type=3 表示音频；同时兜底用扩展名判断，
                // 这样无论 Alist 是否返回准确的 type，都能覆盖所有音频/视频格式。
                val mediaItems = items.filter { !it.isDir }
                    .filter {
                        it.type == ALIST_TYPE_VIDEO || it.type == ALIST_TYPE_AUDIO ||
                            it.name.substringAfterLast('.').lowercase() in MEDIA_EXT
                    }
                    .sortedBy { it.name }

                mediaItems.forEach { item ->
                    val title = item.name.substringBeforeLast('.')  // 去掉扩展名，标题更干净
                    // 章节里直接存「最终可播放地址」，播放阶段用官方 AudioUrlDirectExtractor 即可，
                    // 避免自定义 extractor 触发 app 内部未实现的 stub 导致崩溃。
                    episodes.add(Episode(title, buildDownloadUrl(item.path)))
                }

                // 如果识别不到音视频，把目录内容快照返回给用户，便于排错
                if (episodes.isEmpty()) {
                    val summary = items.take(5).joinToString { "${it.name}(dir=${it.isDir},type=${it.type})" }
                    val total = items.size
                    return BookDetail(
                        emptyList(),
                        intro = "⚠️未识别到音视频文件（共 $total 项）。前 5 项：$summary"
                    )
                }
            }
            BookDetail(episodes, coverUrl = coverUrl)
        } catch (e: Exception) {
            BookDetail(emptyList(), intro = "⚠️详情加载失败：${e.javaClass.simpleName}：${e.message ?: "未知错误"}")
        }
    }

    /**
     * 章节播放地址已在 getBookDetailInfo 阶段生成完毕（buildDownloadUrl），
     * 这里直接返回官方的直链提取器，不再自定义 extractor。
     *
     * 注意：不要去包装 AudioUrlDirectExtractor 并调用它的 extract()——
     * 该单例的 extract 只在 app 进程内有真实实现，在源 jar 里是 stub，
     * 调用会直接抛异常导致播放崩溃。
     */
    override fun getAudioUrlExtractor(): AudioUrlExtractor {
        return AudioUrlDirectExtractor
    }

    /**
     * 排错：
     * 1. 若 App 里列表为空 / 401：多半是 ROOT_PATH 前缀问题。
     *    浏览器打开 $SITE，地址栏形如 .../alist/d/otterhub/... 时，
     *    /alist 之后就是 Alist 根路径，故 ROOT_PATH 用 "/otterhub/..." 是正确的。
     * 2. 播放地址在章节生成阶段就写成 $SITE/d/<path> 固定下载链接
     *    （已实测 206 + audio/mpeg），播放时走官方 AudioUrlDirectExtractor。
     */
}
