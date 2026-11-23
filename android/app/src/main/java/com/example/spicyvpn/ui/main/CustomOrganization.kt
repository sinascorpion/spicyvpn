package com.example.spicyvpn.ui.main

data class CustomFolder(
    val id: String,
    val name: String,
    val urls: List<String> = emptyList(),
    val pinned: Boolean = false,
    val editLock: String? = null
)

data class CustomOrganization(
    val folders: List<CustomFolder> = emptyList(),
    val pinnedUrls: List<String> = emptyList(),
    val orderedUrls: List<String> = emptyList()
) {
    fun pin(url: String) = copy(pinnedUrls = if (url in pinnedUrls) pinnedUrls - url else pinnedUrls + url)
    fun movePinned(url: String, delta: Int): CustomOrganization {
        val from = pinnedUrls.indexOf(url)
        val to = from + delta
        if (from < 0 || to !in pinnedUrls.indices) return this
        val next = pinnedUrls.toMutableList()
        java.util.Collections.swap(next, from, to)
        return copy(pinnedUrls = next)
    }
    fun moveToFolder(url: String, id: String?) = copy(folders = folders.map {
        it.copy(urls = (it.urls - url) + if (it.id == id) listOf(url) else emptyList())
    })
    fun move(url: String, delta: Int, visible: List<String>): CustomOrganization {
        if (url in pinnedUrls) return movePinned(url, delta)
        val next = visible.toMutableList()
        val from = next.indexOf(url)
        val to = from + delta
        if (from < 0 || to !in next.indices) return this
        java.util.Collections.swap(next, from, to)
        return copy(orderedUrls = next + orderedUrls.filterNot { it in next })
    }
    fun <T> ordered(items: List<T>, url: (T) -> String): List<T> = items.sortedWith(
        compareBy { orderedUrls.indexOf(url(it)).let { index -> if (index < 0) Int.MAX_VALUE else index } }
    )
}
