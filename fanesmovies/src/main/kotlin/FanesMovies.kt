import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Base64
import kotlin.text.Regex

class FanesMovies : MainAPI() {
    override var name = "FanesMovies"
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    private val fallbackDomains = mutableListOf(
        "https://www.hdfilmcehennemi.nl",
        "https://www.hdfilmcehennemi.ws",
        "https://hdfilmcehennemi.mobi"
    )
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )
    private val domainListUrl = "https://raw.githubusercontent.com/Kraptor123/domainListesi/refs/heads/main/eklenti_domainleri.txt"
    private var lastDomainRefresh = 0L
    private val cloudflareInterceptor by lazy {
        WebViewResolver(
            interceptUrl = Regex("""https?://(www\.)?hdfilmcehennemi\..*"""),
            additionalUrls = emptyList(),
            userAgent = defaultHeaders["User-Agent"],
            useOkhttp = true,
            script = "",
            scriptCallback = { },
            timeout = 20_000L
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl/category/tavsiye-filmler-izle2/page/" to "Tavsiye Filmler",
        "$mainUrl/yabancidiziizle-5/page/" to "Yabanci Diziler",
        "$mainUrl/imdb-7-puan-uzeri-filmler/page/" to "IMDB 7+ Filmler",
        "$mainUrl/en-cok-yorumlananlar/page/" to "En Cok Yorumlananlar"
    )

    private fun isChallengePage(text: String): Boolean {
        val body = text.lowercase()
        return body.contains("cf-mitigated") ||
            body.contains("just a moment") ||
            body.contains("checking your browser") ||
            body.contains("attention required") ||
            body.contains("/cdn-cgi/challenge-platform")
    }

    private suspend fun refreshDomains() {
        val now = System.currentTimeMillis()
        if (now - lastDomainRefresh < 15 * 60 * 1000) return
        lastDomainRefresh = now

        try {
            val txt = app.get(domainListUrl, headers = defaultHeaders).text
            val line = txt.lineSequence()
                .firstOrNull { it.startsWith("|HDFilmCehennemi:", ignoreCase = true) }
            val dynamic = line?.substringAfter(":")?.trim()?.removeSuffix("/")
            if (!dynamic.isNullOrBlank() && dynamic.startsWith("http")) {
                fallbackDomains.remove(dynamic)
                fallbackDomains.add(0, dynamic)
                mainUrl = dynamic
            }
        } catch (_: Throwable) {
        }
    }

    private fun buildRelativePath(url: String): String? {
        return runCatching {
            val uri = URI(url)
            val path = if (uri.rawPath.isNullOrBlank()) "/" else uri.rawPath
            if (uri.rawQuery.isNullOrBlank()) path else "$path?${uri.rawQuery}"
        }.getOrNull()
    }

    private fun buildCandidateUrls(urlOrPath: String): List<String> {
        if (!urlOrPath.startsWith("http")) {
            val path = if (urlOrPath.startsWith("/")) urlOrPath else "/$urlOrPath"
            return fallbackDomains.map { "$it$path" }
        }

        val direct = mutableListOf(urlOrPath)
        val path = buildRelativePath(urlOrPath) ?: return direct
        fallbackDomains.forEach { base ->
            val candidate = "$base$path"
            if (candidate != urlOrPath) direct.add(candidate)
        }
        return direct.distinct()
    }

    private fun updateMainUrlFromUrl(url: String) {
        val base = Regex("""https?://[^/]+""").find(url)?.value ?: return
        mainUrl = base
    }

    private suspend fun requestDocument(urlOrPath: String, referer: String? = null): Document {
        refreshDomains()
        var lastError: Throwable? = null
        for (url in buildCandidateUrls(urlOrPath)) {
            try {
                val response = app.get(
                    url,
                    referer = referer ?: "$mainUrl/",
                    headers = defaultHeaders,
                    interceptor = cloudflareInterceptor
                )
                if (isChallengePage(response.text)) continue
                updateMainUrlFromUrl(url)
                return response.document
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw ErrorLoadingException(lastError?.message ?: "Site challenge blocked request")
    }

    private suspend fun requestSearchJson(query: String): String? {
        refreshDomains()
        for (base in fallbackDomains) {
            try {
                val response = app.post(
                    "$base/search/",
                    data = mapOf("query" to query),
                    referer = "$base/",
                    headers = defaultHeaders + mapOf(
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    interceptor = cloudflareInterceptor
                )
                if (isChallengePage(response.text)) continue
                if (response.text.contains("\"result\"")) {
                    mainUrl = base
                    return response.text
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = buildRelativePath("${request.data}$page") ?: "/"
        val document = requestDocument(path)
        val home = document.select("div.poster-container, div.poster.poster-pop, div.card-list-item")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun toAbsUrlOrNull(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching { fixUrl(url) }.getOrNull()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h2.title, h3.title, h3")?.text()?.trim() ?: return null
        val href = toAbsUrlOrNull(selectFirst("a[href]")?.attr("href")) ?: return null
        val posterRaw = selectFirst("img[data-src], img[src]")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        val posterUrl = toAbsUrlOrNull(posterRaw)
        val isSeries = href.contains("/dizi/") || title.contains("Sezon", true)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Media.toSearchResponse(): SearchResponse? {
        val mediaTitle = title ?: return null
        val mediaSlug = slug ?: return null
        val fullUrl = "$mainUrl/${slugPrefix ?: ""}$mediaSlug"
        val mediaPoster = poster?.let { "$mainUrl/uploads/poster/$it" }
        return if (fullUrl.contains("/dizi/")) {
            newTvSeriesSearchResponse(mediaTitle, fullUrl, TvType.TvSeries) {
                this.posterUrl = mediaPoster
            }
        } else {
            newMovieSearchResponse(mediaTitle, fullUrl, TvType.Movie) {
                this.posterUrl = mediaPoster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val htmlResults = requestDocument("/search/?q=${query.encodeUri()}")
            .select("div.poster-container, div.poster.poster-pop, div.card-list-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        if (htmlResults.isNotEmpty()) return htmlResults

        val json = requestSearchJson(query) ?: return emptyList()

        return tryParseJson<Result>(json)
            ?.result
            ?.mapNotNull { it.toSearchResponse() }
            ?.distinctBy { it.url }
            ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = requestDocument(url)

        val title = document.selectFirst("div.card-header > h1, div.card-header > h2, h1")
            ?.text()
            ?.removeSuffix("Filminin Bilgileri")
            ?.trim()
            ?: return null

        val posterRaw = document.select("img.img-fluid, img[data-src], img[src]")
            .lastOrNull()
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        val poster = toAbsUrlOrNull(posterRaw)
        val tags = document.select("div.mb-0.lh-lg div:nth-child(5) a, a[rel=tag]")
            .map { it.text() }
            .distinct()
        val year = document.selectFirst("div.mb-0.lh-lg div:nth-child(4) a")?.text()?.trim()?.toIntOrNull()
        val description = document.selectFirst("article.text-white > p, .post-content p, .description p")
            ?.text()
            ?.trim()
        val actors = document.select("div.mb-0.lh-lg div:last-child a.chip, a[rel=tag][href*=oyuncu]")
            .map { Actor(it.text(), toAbsUrlOrNull(it.selectFirst("img")?.attr("src"))) }

        val recommendations = document.select("div.swiper-wrapper div.poster.poster-pop, div.poster-container")
            .mapNotNull { it.toSearchResult() }

        val isSeries = url.contains("/dizi/") || document.select("nav#seasonsTabs, #seasonsTabs-tabContent").isNotEmpty()
        return if (isSeries) {
            val episodes = document.select("div#seasonsTabs-tabContent div.card-list-item, .episode-item, .episodes li")
                .mapNotNull { item ->
                    val href = toAbsUrlOrNull(item.selectFirst("a[href]")?.attr("href")) ?: return@mapNotNull null
                    val epName = item.selectFirst("h3, .title, a")?.text()?.trim().orEmpty().ifBlank { "Bolum" }
                    val season = item.parents().firstOrNull { it.id().contains("season-", true) }
                        ?.id()
                        ?.substringAfter("season-")
                        ?.toIntOrNull()
                    val episode = Regex("(\\d+)").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    newEpisode(href) {
                        name = epName
                        this.season = season
                        this.episode = episode
                    }
                }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addActors(actors)
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addActors(actors)
                this.recommendations = recommendations
            }
        }
    }

    private fun decodeBase64OrRaw(value: String): String {
        if (value.isBlank()) return value
        return runCatching {
            String(Base64.getDecoder().decode(value.trim()))
        }.getOrDefault(value)
    }

    private suspend fun invokeLocalSource(
        source: String,
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ) {
        val script = requestDocument(url, "$mainUrl/")
            .select("script")
            .firstOrNull { it.data().contains("sources:") || it.data().contains("file_link=") }
            ?.data()
            ?: return

        val unpacked = runCatching { getAndUnpack(script) }.getOrDefault(script)
        val encoded = unpacked.substringAfter("file_link=\"", "").substringBefore("\";", "")
        val decoded = decodeBase64OrRaw(encoded)
        val fallback = Regex("""file\s*:\s*["']([^"']+)["']""")
            .find(unpacked)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()

        val streamUrl = if (decoded.isNotBlank()) decoded else fallback
        if (streamUrl.isNotBlank()) {
            callback(
                newExtractorLink(source, source, fixUrl(streamUrl)) {
                    this.type = ExtractorLinkType.M3U8
                    this.referer = "$mainUrl/"
                }
            )
        }

        val tracksRaw = script.substringAfter("tracks: [", "").substringBefore("]", "")
        if (tracksRaw.isNotBlank()) {
            tryParseJson<List<SubSource>>("[$tracksRaw]")
                ?.filter { it.kind == "captions" && !it.file.isNullOrBlank() }
                ?.forEach {
                    val file = it.file ?: return@forEach
                    val subUrl = if (file.startsWith("http")) file else fixUrl(file)
                    subtitleCallback(SubtitleFile(it.label ?: "Sub", subUrl))
                }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ): Boolean {
        val document = requestDocument(data)
        val sourceTabs = document.select("nav.nav.card-nav.nav-slider a.nav-link, a.nav-link[data-bs-toggle]")
            .mapNotNull {
                val href = toAbsUrlOrNull(it.attr("href")) ?: return@mapNotNull null
                val sourceName = it.text().trim().ifBlank { name }
                href to sourceName
            }

        if (sourceTabs.isEmpty()) {
            val directFrame = document.selectFirst("div.card-video iframe, iframe[data-src], iframe[src]")
                ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            if (!directFrame.isNullOrBlank()) {
                val fixed = fixUrl(directFrame)
                if (fixed.startsWith(mainUrl)) {
                    invokeLocalSource(name, fixed, subtitleCallback, callback)
                } else {
                    loadExtractor(fixed, "$mainUrl/", subtitleCallback, callback)
                }
                return true
            }
            return false
        }

        for ((tabUrl, source) in sourceTabs) {
            try {
                val iframe = requestDocument(tabUrl)
                    .selectFirst("div.card-video iframe, iframe[data-src], iframe[src]")
                    ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                    ?: continue

                val frameUrl = fixUrl(iframe)
                if (frameUrl.startsWith(mainUrl)) {
                    invokeLocalSource(source, frameUrl, subtitleCallback, callback)
                } else {
                    loadExtractor(frameUrl, "$mainUrl/", subtitleCallback) { link ->
                        callback(link)
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return true
    }

    private data class SubSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )

    private data class Result(
        @JsonProperty("result") val result: List<Media>? = emptyList()
    )

    private data class Media(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("slug_prefix") val slugPrefix: String? = null
    )
}
