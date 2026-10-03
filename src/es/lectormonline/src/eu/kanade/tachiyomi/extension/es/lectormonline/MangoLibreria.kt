package eu.kanade.tachiyomi.extension.es.lectormonline

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

@Source
abstract class MangoLibreria : HttpSource() {

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override val client = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            // The image CDN rejects the main site's Referer and non-browser image requests.
            if (request.url.host != baseUrl.toHttpUrl().host) {
                val newRequest = request.newBuilder()
                    .removeHeader("Referer")
                    .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    .header("Sec-Fetch-Dest", "image")
                    .header("Sec-Fetch-Mode", "no-cors")
                    .header("Sec-Fetch-Site", "cross-site")
                    .build()
                chain.proceed(newRequest)
            } else {
                chain.proceed(request)
            }
        }
        .build()

    private val chapterNumberRegex = Regex("""\d+(?:\.\d+)?""")

    // ============================== Popular ==============================
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/comics?sort=views&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select("a[href*='/comics/']:not([href*='/chapters/'])")
            .filter { link ->
                val slug = link.attr("href").substringBefore("?").trimEnd('/').substringAfter("/comics/")
                slug.isNotEmpty() && '/' !in slug
            }
            .distinctBy { it.attr("href").substringBefore("?").trimEnd('/') }
            .mapNotNull { link ->
                val container = link.parents().firstOrNull { it.tagName() == "article" } ?: link.parent()
                val img = link.selectFirst("img") ?: container?.selectFirst("img")
                val name = link.attr("title")
                    .ifBlank { container?.selectFirst("h3")?.text().orEmpty() }
                    .ifBlank { img?.attr("alt").orEmpty() }
                    .ifBlank { link.text() }
                if (name.isBlank()) return@mapNotNull null

                SManga.create().apply {
                    setUrlWithoutDomain(link.absUrl("href"))
                    title = name.trim()
                    thumbnail_url = img?.let { it.absUrl("src").ifBlank { it.absUrl("data-src") } }
                }
            }

        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = document.selectFirst("a[href*='page=${currentPage + 1}']") != null

        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/comics?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ============================== Search ===============================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/comics".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (query.isNotBlank()) {
                addQueryParameter("q", query.trim())
            } else {
                addQueryParameter("sort", "views")
            }
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ============================== Details ==============================
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        return SManga.create().apply {
            title = document.selectFirst("h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                    ?.substringBefore(" |")
                    .orEmpty()
            description = document.selectFirst("meta[property=og:description]")?.attr("content")
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")

            val labels = document.select("span").map { it.text().trim() }
            status = when {
                labels.any { it.equals("En emisión", ignoreCase = true) } -> SManga.ONGOING
                labels.any { it.equals("Finalizado", ignoreCase = true) } -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ============================= Chapters ==============================
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val mangaPath = response.request.url.encodedPath

        return document.select("a[href*='/chapters/']")
            .filter { it.attr("href").startsWith(mangaPath) }
            // Skip the "Comenzar lectura" button, which repeats the first chapter.
            .filterNot { it.text().contains("lectura", ignoreCase = true) }
            .distinctBy { it.attr("href").substringAfter("/chapters/").substringBefore("?").trimEnd('/') }
            .map { link ->
                SChapter.create().apply {
                    setUrlWithoutDomain(link.absUrl("href"))
                    name = link.attr("title").substringBefore("·").trim()
                        .ifBlank { link.text().trim() }
                    chapter_number = chapterNumberRegex.find(name)?.value?.toFloatOrNull() ?: -1f
                }
            }
            .sortedByDescending { it.chapter_number }
    }

    // =============================== Pages ===============================
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        return document.select("#reader-top .reader-page-slot img")
            .filter { it.hasAttr("src") }
            .mapIndexed { index, img ->
                Page(index, imageUrl = img.absUrl("src"))
            }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
}
