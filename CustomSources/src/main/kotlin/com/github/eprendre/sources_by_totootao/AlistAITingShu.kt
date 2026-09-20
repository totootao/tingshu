package com.github.eprendre.sources_by_totootao

import com.github.eprendre.tingshu.sources.*
import com.github.eprendre.tingshu.utils.*
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.json.responseJson

/**
 * 基于 Alist 部署的「AI 有声书」听书源。
 *
 * 站点标准 API 路径形如「$SITE/api/...」，需要账号密码登录后访问。
 *
 * 目录结构（已实测）：
 *   /otterhub/audio/有声书/AI有声书/      <- 各有声书文件夹（21 本）
 *       书名/                            <- 每本书是一个文件夹
 *           xxxx书名·第xxxx章·xxx.mp3     <- 音频文件（type=3，已验证全为 mp3）
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
    private val AUDIO_EXT = setOf("mp3", "m4a", "flac", "wav", "aac", "ogg", "wma", "ape", "opus", "mka")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")

    private var token: String = ""

    private data class AlistItem(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val thumb: String,
        val size: Long
    )

    /**
     * 登录获取 token（缓存复用）。
     */
    private fun ensureToken(): String {
        if (token.isNotEmpty()) return token
        val url = "$API/auth/login"
        val body = """{"username":"$USERNAME","password":"$PASSWORD"}"""
        val (_, _, result) = Fuel.post(url)
            .header("Content-Type", "application/json")
            .body(body)
            .responseJson()
        val json = result.get().obj()
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

    private fun doList(path: String): List<AlistItem> {
        val tk = ensureToken()
        val url = "$API/fs/list"
        val body = """{"path":"$path","password":"","page":1,"per_page":$PER_PAGE,"refresh":false}"""
        val (_, _, result) = Fuel.post(url)
            .header("Authorization", tk)
            .header("Content-Type", "application/json")
            .body(body)
            .responseJson()
        val json = result.get().obj()
        val arr = json.getJSONObject("data").getJSONArray("content")
        val list = ArrayList<AlistItem>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            list.add(
                AlistItem(
                    name = item.getString("name"),
                    path = item.getString("path"),
                    isDir = item.getBoolean("is_dir"),
                    thumb = item.optString("thumb", ""),
                    size = item.optLong("size", 0L)
                )
            )
        }
        return list
    }

    /**
     * 把 Alist 文件路径换成可直接播放的音频直链。
     */
    fun getRawUrl(path: String): String {
        return try {
            doGetRawUrl(path)
        } catch (e: Exception) {
            token = ""
            doGetRawUrl(path)
        }
    }

    private fun doGetRawUrl(path: String): String {
        val tk = ensureToken()
        val url = "$API/fs/get"
        val body = """{"path":"$path","password":""}"""
        val (_, _, result) = Fuel.post(url)
            .header("Authorization", tk)
            .header("Content-Type", "application/json")
            .body(body)
            .responseJson()
        val json = result.get().obj()
        val data = json.getJSONObject("data")
        return if (data.optString("raw_url", "").isNotEmpty()) {
            data.getString("raw_url")
        } else {
            data.getString("url")
        }
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

    // 标准 Alist 无内置搜索 API，用发现页浏览即可
    override fun isSearchable(): Boolean = false

    // 不可搜索，返回空结果（抽象方法必须实现，即使不启用搜索）
    override fun search(keywords: String, page: Int): Pair<List<Book>, Int> {
        return Pair(emptyList(), 1)
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
        val items = listFiles(url).filter { it.isDir }
        val list = ArrayList<Book>()
        items.forEach { item ->
            list.add(
                Book(item.thumb, item.path, item.name, "", "").apply {
                    this.sourceId = getSourceId()
                }
            )
        }
        return Category(list, 1, 1, url, "")
    }

    override fun getBookDetailInfo(bookUrl: String, loadEpisodes: Boolean, loadFullPages: Boolean): BookDetail {
        val episodes = ArrayList<Episode>()
        var coverUrl = ""
        if (loadEpisodes) {
            val items = listFiles(bookUrl)
            // 优先用文件夹内第一张图片做封面
            val cover = items.firstOrNull { !it.isDir && it.name.substringAfterLast('.').lowercase() in IMAGE_EXT }
            coverUrl = cover?.thumb ?: ""
            items.filter { !it.isDir }
                .filter { it.name.substringAfterLast('.').lowercase() in AUDIO_EXT }
                .sortedBy { it.name }   // Alist 返回乱序，必须按文件名排序（文件名以 0 填充序号开头）
                .forEach { item ->
                    val title = item.name.substringBeforeLast('.')  // 去掉扩展名，标题更干净
                    episodes.add(Episode(title, item.path))
                }
        }
        return BookDetail(episodes, coverUrl = coverUrl)
    }

    override fun getAudioUrlExtractor(): AudioUrlExtractor {
        return AlistAudioExtractor
    }

    /**
     * 排错：
     * 1. 若 App 里列表为空 / 401：多半是 ROOT_PATH 前缀问题。
     *    先确认：浏览器打开 $SITE ，看地址栏里 URL 是否形如 .../alist/d/otterhub/...
     *    如果路径里 /alist 之后就是 otterhub，说明 Alist 根就是 /alist，
     *    则把 ROOT_PATH 改成 "/alist/otterhub/audio/有声书/AI有声书"。
     * 2. 若音频播放失败：检查 getRawUrl 返回的 raw_url（对象存储直链）是否可直连；
     *    若被防盗链拦截，可改用 url 字段（Alist 代理链接，带签名）并视情况在源上实现 AudioUrlExtraHeaders。
     */
}

/**
 * 自定义音频提取器：先把 Alist 文件 path 换成直链，再交给直链提取器播放。
 */
object AlistAudioExtractor : AudioUrlExtractor {
    override fun extract(url: String, autoPlay: Boolean, isCache: Boolean, isDebug: Boolean) {
        val rawUrl = AlistAITingShu.getRawUrl(url)
        AudioUrlDirectExtractor.extract(rawUrl, autoPlay, isCache, isDebug)
    }
}
