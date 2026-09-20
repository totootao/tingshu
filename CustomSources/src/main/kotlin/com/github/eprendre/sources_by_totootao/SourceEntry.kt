package com.github.eprendre.sources_by_totootao

import com.github.eprendre.tingshu.sources.TingShu

object SourceEntry {

    /**
     * 源集合描述，显示在 App 的订阅详情里。
     */
    @JvmStatic
    fun getDesc(): String {
        return "totootao 的听书源"
    }

    /**
     * 内容分类，相同分类的源会在 App 里聚合展示/搜索。
     * 常见值："听书"、"视频"、"音乐"
     */
    @JvmStatic
    fun getCategory(): String {
        return "听书"
    }

    /**
     * 返回此包下面的所有源。
     * 每新增一个源，都在下面 listOf 里添加对应的对象。
     */
    @JvmStatic
    fun getSources(): List<TingShu> {
        return listOf(
            AlistAITingShu
//            SampleSource // 模板参考源，启用请取消注释
        )
    }
}
