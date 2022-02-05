package com.lagradost.cloudstream3.movieproviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlin.collections.ArrayList

class SeriesflixProvider:MainAPI() {
    override val mainUrl = "https://seriesflix.video"
    override val name = "Seriesflix"
    override val lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    override suspend fun getMainPage(): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("$mainUrl/ver-series-online/", "Series"),
            Pair("$mainUrl/genero/accion/", "Acción"),
            Pair("$mainUrl/genero/ciencia-ficcion/", "Ciencia ficción"),
        )
        for (i in urls) {
            try {
                val soup = app.get(i.first).document
                val home = soup.select("article.TPost.B").map {
                    val title = it.selectFirst("h2.title").text()
                    val link = it.selectFirst("a").attr("href")
                    TvSeriesSearchResponse(
                        title,
                        link,
                        this.name,
                        TvType.Movie,
                        it.selectFirst("figure img").attr("src"),
                        null,
                        null,
                    )
                }

                items.add(HomePageList(i.second, home))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }
    override suspend fun search(query: String): ArrayList<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        val search = doc.select("article.TPost.B").map {
            val href = it.selectFirst("a").attr("href")
            val poster = it.selectFirst("figure img").attr("src")
            val name = it.selectFirst("h2.title").text()
            val isMovie = href.contains("/movies/")
            if (isMovie) {
                MovieSearchResponse(
                    name,
                    href,
                    this.name,
                    TvType.Movie,
                    poster,
                    null
                )
            } else {
                TvSeriesSearchResponse(
                    name,
                    href,
                    this.name,
                    TvType.TvSeries,
                    poster,
                    null,
                    null
                )
            }
        }
        return ArrayList(search)
    }



    override suspend fun load(url: String): LoadResponse? {
        val type = if (url.contains("/movies/")) TvType.Movie else TvType.TvSeries

        val document = app.get(url).document

        val title = document.selectFirst("h1.Title").text()
        val descRegex = Regex("(Recuerda.*Seriesflix.)")
        val descipt = document.selectFirst("div.Description > p").text().replace(descRegex,"")
        val rating =
            document.selectFirst("div.Vote > div.post-ratings > span")?.text()?.toFloatOrNull()
                ?.times(1000)?.toInt()
        val year = document.selectFirst("span.Date")?.text()
        val duration = try {
            document.selectFirst("span.Time").text()
        } catch (e: Exception) {
            null
        }
        val poster = try {
            document.selectFirst("head meta[property=og:image]").attr("content")
        } catch (e: Exception) {
            document.select(".TPostBg").attr("src")
        }

        if (type == TvType.TvSeries) {
            val list = ArrayList<Pair<Int, String>>()

            document.select("main > section.SeasonBx > div > div.Title > a").apmap { element ->
                val season = element.selectFirst("> span")?.text()?.toIntOrNull()
                val href = element.attr("href")
                if (season != null && season > 0 && !href.isNullOrBlank()) {
                    list.add(Pair(season, fixUrl(href)))
                }
            }
            if (list.isEmpty()) throw ErrorLoadingException("No se ha encontrado temporadas")

            val episodeList = ArrayList<TvSeriesEpisode>()

            for (season in list) {
                val seasonDocument = app.get(season.second).document
                val episodes = seasonDocument.select("table > tbody > tr")
                if (episodes.isNotEmpty()) {
                    episodes.apmap { episode ->
                        val epNum = episode.selectFirst("> td > span.Num")?.text()?.toIntOrNull()
                        val epthumb = episode.selectFirst("img")?.attr("src")
                        val aName = episode.selectFirst("> td.MvTbTtl > a")
                        val name = aName.text()
                        val href = aName.attr("href")
                        val date = episode.selectFirst("> td.MvTbTtl > span")?.text()
                        episodeList.add(
                            TvSeriesEpisode(
                                name,
                                season.first,
                                epNum,
                                href,
                                fixUrlNull(epthumb),
                                date
                            )
                        )
                    }
                }
            }
            return TvSeriesLoadResponse(
                title,
                url,
                this.name,
                type,
                episodeList,
                fixUrlNull(poster),
                year?.toIntOrNull(),
                descipt,
                null,
                null,
                rating
            )
        } else {
            return newMovieLoadResponse(
                title,
                url,
                type,
                url
            ) {
                posterUrl = fixUrlNull(poster)
                this.year = year?.toIntOrNull()
                this.plot = descipt
                this.rating = rating
                setDuration(duration)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select("ul.ListOptions li").apmap {
            val movieID = it.attr("data-id")
            val serverID = it.attr("data-key")
            val type = if (data.contains("movies")) 1 else 2
            val url = "$mainUrl/?trembed=$serverID&trid=$movieID&trtype=$type" //This is to get the POST key value
            val doc1 = app.get(url).document
            doc1.select("div.Video iframe").apmap {
                val iframe = it.attr("src")
                val postkey = iframe.replace("https://sc.seriesflix.video/index.php?h=","") // this obtains
                // djNIdHNCR2lKTGpnc3YwK3pyRCs3L2xkQmljSUZ4ai9ibTcza0JRODNMcmFIZ0hPejdlYW0yanJIL2prQ1JCZA POST KEY
                app.post("https://sc.seriesflix.video/r.php",
                    headers = mapOf("Host" to "sc.seriesflix.video",
                        "User-Agent" to USER_AGENT,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                        "Accept-Language" to "en-US,en;q=0.5",
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "Origin" to "null",
                        "DNT" to "1",
                        "Alt-Used" to "sc.seriesflix.video",
                        "Connection" to "keep-alive",
                        "Upgrade-Insecure-Requests" to "1",
                        "Sec-Fetch-Dest" to "iframe",
                        "Sec-Fetch-Mode" to "navigate",
                        "Sec-Fetch-Site" to "same-origin",
                        "Sec-Fetch-User" to "?1",),
                    params = mapOf(Pair("h", postkey)),
                    data =  mapOf(Pair("h", postkey)),
                    allowRedirects = false
                ).response.headers.values("location").apmap {link ->
                    val url1 = link.replace("#bu","")
                    for (extractor in extractorApis) {
                        if (url1.startsWith(extractor.mainUrl)) {
                            extractor.getSafeUrl(url1, data)?.apmap {
                                callback(it)
                            }
                        }
                    }
                }
            }

        }
        return true
    }
}
