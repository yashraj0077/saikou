package ani.saikou.parsers.anime

import ani.saikou.*
import ani.saikou.parsers.*
import com.lagradost.nicehttp.NiceResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URLDecoder.decode

open class Marin : AnimeParser() {

    override val name: String = "Marin"
    override val saveName: String = "marin_boe"
    override val hostUrl: String = "https://marin.moe"
    override val malSyncBackupName: String = "Tenshi"
    override val isDubAvailableSeparately: Boolean = false

    private var cookie:String?=null
    private var token:String?=null
    private val ddosCookie = ";__ddg1_=;__ddg2_=;"
    private suspend fun getCookieHeaders(): List<Pair<String,String>> {
        if(cookie==null)
            cookie = client.head(hostUrl, mapOf("cookie" to ddosCookie)).headers.toMultimap()["set-cookie"]!!.joinToString(";")
            cookie?.plus(ddosCookie)
        token = decode(cookie?.findBetween("XSRF-TOKEN=", ";")!!)
        return listOf("cookie" to cookie!!,"x-xsrf-token" to token!!)
    }

    inline fun <reified T> parse(res:NiceResponse):T{
        val htmlRes = res.document.selectFirst("div#app")!!.attr("data-page")
        return Mapper.parse(decode(htmlRes))
    }

    override suspend fun search(query: String): List<ShowResponse> {
        val res = client.post("$hostUrl/anime",
            getCookieHeaders().toMap(),
            data = mapOf("search" to query, "sort" to "vtt-d")
        )
        val json = parse<Json>(res)
        return json.props?.animeList?.data!!.map {
            ShowResponse(it.title, it.slug, it.cover)
        }
    }


    override suspend fun loadEpisodes(animeLink: String, extra: Map<String, String>?): List<Episode> {
        val map = mutableMapOf<String, Episode>()
        val res = parse<Json>(client.get("$hostUrl/anime/$animeLink"))
        (1..res.props?.episodeList?.meta?.total!!).forEach {
            val num = it.toString()
            map[num] = (Episode(num, "$hostUrl/anime/$animeLink/$num"))
        }
        fun add(list:List<Json.Props.EpisodeList.Datum>){
            list.forEach {
                val num = it.slug!!
                val link = "$hostUrl/anime/$animeLink/$num"
                val title = it.title
                val thumb = it.cover!!
                map[num] = Episode(num, link, title, thumb)
            }
        }
        add(res.props.episodeList.data!!)
        res.props.episodeList.meta.also {
            if(it.currentPage!! != it.lastPage!!) {
                val lastRes = parse<Json>(client.get("$hostUrl/anime/$animeLink?eps_page=${it.lastPage}"))
                add(lastRes.props?.episodeList?.data!!)
            }
        }
        return map.values.toList()
    }

    override suspend fun loadVideoServers(episodeLink: String, extra: Any?): List<VideoServer> {
        val res = parse<Json>(client.get(episodeLink))
        return res.props?.videoList?.data!!.map {
            VideoServer(
                "${it.title} : ${it.source?.name} - [${if(it.audio?.code=="jp") "Sub" else "Dub"}]",
                FileUrl(episodeLink,getCookieHeaders().toMap()),
                it.slug!!
            )
        }
    }

    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor = Extractor(server)
    class Extractor(override val server: VideoServer) : VideoExtractor(){
        inline fun <reified T> parse(res:NiceResponse):T{
            val htmlRes = res.document.selectFirst("div#app")!!.attr("data-page")
            return Mapper.parse(decode(htmlRes,"utf8"))
        }
        override suspend fun extract(): VideoContainer {
            val videos = parse<Json>(client.post(
                server.embed.url,
                server.embed.headers,
                server.embed.url,
                data = mapOf("video" to server.extraData as String)
            )).props?.video?.data?.mirror?.map {
                Video(
                    it.code!!.height?.toInt(),
                    VideoType.CONTAINER,
                    it.code.file!!,
                    getSize(it.code.file)
                )
            }
            return VideoContainer(videos!!)
        }
    }

    @Serializable
    data class Json (
        val props: Props? = null
    ) {
        @Serializable
        data class Props(
            @SerialName("anime_list")
            val animeList: AnimeList? = null,
            @SerialName("episode_list")
            val episodeList: EpisodeList? = null,
            @SerialName("video")
            val video: Video? = null,
            @SerialName("video_list")
            val videoList: VideoList? = null,
        ) {
            @Serializable
            data class AnimeList(
                val data: List<Datum>? = null
            ) {
                @Serializable
                data class Datum(
                    val title: String,
                    val slug: String,
                    val cover: String
                )
            }
            @Serializable
            data class EpisodeList(
                val data: List<Datum>? = null,
                val meta: Meta? = null
            ) {
                @Serializable
                data class Datum(
                    val title: String? = null,
                    val slug: String? = null,
                    val cover: String? = null,
                )
                @Serializable
                data class Meta(
                    @SerialName("current_page")
                    val currentPage: Long? = null,
                    @SerialName("last_page")
                    val lastPage: Long? = null,
                    val total: Long? = null
                )
            }
            @Serializable
            data class Video(
                val data: Data? = null
            ) {
                @Serializable
                data class Data(
                    val mirror: List<Mirror>? = null
                ) {
                    @Serializable
                    data class Mirror(
                        val resolution: String? = null,
                        val code: Code? = null
                    ) {
                        @Serializable
                        data class Code(
                            val file: String? = null,
                            val width: Long? = null,
                            val height: Long? = null,
                            val duration: Long? = null,
                            val thumbnail: String? = null,
                            val vtt: String? = null,
                            val sprite: String? = null
                        )
                    }
                }
            }

            @Serializable
            data class VideoList(
                val data: List<Datum>? = null
            ) {
                @Serializable
                data class Datum(
                    val title: String? = null,
                    val slug: String? = null,
                    val source: Source? = null,
                    val audio: Audio? = null,
                    val subtitle: Audio? = null
                ) {
                    @Serializable
                    data class Audio(
                        val code: String? = null
                    )
                    @Serializable
                    data class Source(
                        val name: String? = null
                    )
                }
            }
        }
    }
}