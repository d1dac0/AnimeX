package com.anilili.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

// ---- Request envelope ----
@Serializable
data class GraphQLRequest(val query: String, val variables: JsonObject)

// ---- Media fields (superset of AniList MEDIA_LIST_FIELDS + info extras we render) ----
@Serializable
data class MediaTitle(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
    val userPreferred: String? = null,
) {
    /** The app is English-first; AniList's viewer preference is only a fallback. */
    val preferred: String get() = english ?: userPreferred ?: romaji ?: native ?: "Untitled"
}

@Serializable
data class CoverImage(
    val large: String? = null,
    val extraLarge: String? = null,
    val color: String? = null,
) {
    val best: String? get() = extraLarge ?: large
}

@Serializable
data class FuzzyDate(val year: Int? = null, val month: Int? = null, val day: Int? = null)

@Serializable
data class NextAiringEpisode(
    val episode: Int? = null,
    val airingAt: Long? = null,
    val timeUntilAiring: Long? = null,
)

@Serializable
data class StudioNode(
    val id: Int = 0,
    val name: String? = null,
    val isAnimationStudio: Boolean = false,
)

@Serializable
data class StudioConnection(val nodes: List<StudioNode> = emptyList())

@Serializable
data class Trailer(val id: String? = null, val site: String? = null, val thumbnail: String? = null) {
    /**
     * The page the trailer plays on, handed to whatever app claims it (usually YouTube).
     *
     * AniList only ever names `youtube` or `dailymotion`; anything else has no URL we could build,
     * and a card that opens nothing is worse than no card.
     */
    val watchUrl: String?
        get() {
            val videoId = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return when (site?.lowercase()) {
                "youtube" -> "https://www.youtube.com/watch?v=$videoId"
                "dailymotion" -> "https://www.dailymotion.com/video/$videoId"
                else -> null
            }
        }
}

/**
 * One row of AniList's per-episode streaming links, which carry a thumbnail.
 *
 * These are the licensor's own stills (mostly Crunchyroll) and they ride the detail query the app
 * already makes, so they cost nothing extra. What they do not carry is an episode *number* — the
 * number only ever appears inside [title], as "Episode 12 - Something". That is why this is the
 * last source consulted and can never outrank TMDB: a title AniList wrote differently is an
 * episode this cannot place at all.
 */
@Serializable
data class StreamingEpisode(
    val title: String? = null,
    val thumbnail: String? = null,
    val url: String? = null,
    val site: String? = null,
)

@Serializable
data class MediaRelationEdge(
    val relationType: String? = null,
    val node: Media? = null,
)

@Serializable
data class MediaRelationConnection(val edges: List<MediaRelationEdge> = emptyList())

/** One community "if you liked this" pick; the recommended title itself hangs off the node. */
@Serializable
data class MediaRecommendationNode(
    val rating: Int = 0,
    val mediaRecommendation: Media? = null,
)

@Serializable
data class MediaRecommendationConnection(val nodes: List<MediaRecommendationNode> = emptyList())

@Serializable
data class RecommendedMedia(
    val recommendations: MediaRecommendationConnection = MediaRecommendationConnection(),
)

@Serializable
data class RecommendedMediaData(@SerialName("Media") val media: RecommendedMedia? = null)

@Serializable
data class GqlRecommendationsResponse(val data: RecommendedMediaData? = null)

@Serializable
data class MediaScoreCount(val score: Int = 0, val amount: Int = 0)

@Serializable
data class MediaScoreStats(val scoreDistribution: List<MediaScoreCount> = emptyList())

@Serializable
data class Media(
    val id: Int,
    val idMal: Int? = null,
    val title: MediaTitle = MediaTitle(),
    val description: String? = null,
    val coverImage: CoverImage = CoverImage(),
    val bannerImage: String? = null,
    val format: String? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val episodes: Int? = null,
    val duration: Int? = null,
    val status: String? = null,
    val averageScore: Int? = null,
    val meanScore: Int? = null,
    val popularity: Int? = null,
    val favourites: Int? = null,
    val isAdult: Boolean = false,
    /**
     * ISO 3166-1 alpha-2 country of the studio that made it: `JP` for anime, `CN` for donghua,
     * `KR` for aeni. Nullable because it is absent from every cached [Media] written before the
     * field was requested, and those entries stay readable for the life of the 24h media cache.
     */
    val countryOfOrigin: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<MediaTag> = emptyList(),
    val studios: StudioConnection = StudioConnection(),
    val nextAiringEpisode: NextAiringEpisode? = null,
    val startDate: FuzzyDate? = null,
    val endDate: FuzzyDate? = null,
    val trailer: Trailer? = null,
    val relations: MediaRelationConnection = MediaRelationConnection(),
    val streamingEpisodes: List<StreamingEpisode> = emptyList(),
    val stats: MediaScoreStats? = null,
) {
    /**
     * Backdrop for the wide hero banners, best landscape source first.
     *
     * AniList has no dedicated key art field, so this is a preference order over what it does
     * carry. `bannerImage` is the only purpose-built one (1900x400). The trailer frame is 16:9 and
     * therefore still crops sensibly across a hero; it is lower resolution than the cover, but a
     * soft landscape still behind a heavy scrim reads far better than a portrait poster sliced
     * down to a letterbox strip. The cover is the last resort.
     */
    val heroImage: String? get() =
        bannerImage ?: trailer?.thumbnail ?: coverImage.extraLarge ?: coverImage.best

    /**
     * How many episodes have actually been released, or null when that cannot be told apart from
     * "unknown".
     *
     * Distinct from [episodes], which is the *planned* run and is populated for a series long
     * before it has aired — treating the two as interchangeable is what let Continue Watching
     * advance onto an episode that does not exist yet. Only a releasing series can answer: a
     * finished one has no `nextAiringEpisode`, and there [episodes] is already the right bound.
     */
    val airedEpisodes: Int? get() = nextAiringEpisode?.episode?.minus(1)?.takeIf { it > 0 }
}

/** Filters supported by AniList's Media catalog query. */
data class DiscoverFilters(
    val query: String = "",
    val genres: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    /**
     * Genres and tags a title must *not* carry.
     *
     * Kept apart from [genres] and [tags] rather than expressed as negative entries in them,
     * because AniList takes them as separate arguments (`genre_not_in`, `tag_not_in`) and because a
     * set holding both senses cannot answer "is this chip on?" without parsing its own contents.
     */
    val excludedGenres: Set<String> = emptySet(),
    val excludedTags: Set<String> = emptySet(),
    val studioId: Int? = null,
    val studioName: String? = null,
    val year: Int? = null,
    val season: String? = null,
    /**
     * An inclusive span of release years, for the browsing nobody can express with [year].
     *
     * Mutually exclusive with [year] — that one is the exact season-year the season chips pair
     * with, and sending both would narrow to the intersection, which is at best the single year and
     * at worst empty. The view models enforce the exclusion; this type only records it.
     */
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val status: String? = null,
    val format: String? = null,
    /** ISO 3166-1 alpha-2: `JP` anime, `CN` donghua, `KR` aeni. */
    val country: String? = null,
    /** What it was adapted from — AniList's `MediaSource` enum, e.g. `MANGA`, `ORIGINAL`. */
    val source: String? = null,
    /** Inclusive bounds on the planned episode count; the UI offers them as buckets. */
    val episodesMin: Int? = null,
    val episodesMax: Int? = null,
    val minimumScore: Int? = null,
    /**
     * Narrow the catalog to titles a dub has been found for.
     *
     * The only filter here AniList itself cannot answer — it carries no notion of audio language —
     * so it is applied by sending the device's dub index as the query's id list. There is
     * deliberately no "sub only" counterpart: the index records positives and says nothing about
     * the titles it has never been asked about, so "no dub" is not a claim it can make.
     */
    val dubbedOnly: Boolean = false,
    /**
     * Leave out titles the viewer has already watched — see [WatchedFilter] for what counts.
     *
     * Like [dubbedOnly] it is answered from what the device knows rather than from a field AniList
     * has, and like it, it travels to AniList as an id list (`id_not_in`) so paging and the result
     * count stay honest.
     */
    val hideWatched: WatchedFilter = WatchedFilter.OFF,
    val sort: String = "TRENDING_DESC",
) {
    /**
     * How many filters are switched on, for the badge on the filter button.
     *
     * A span counts once however many of its ends are set: "1990–1999" and "13–26 episodes" are one
     * decision each to whoever made them, and counting the bounds would have the badge read 2 for a
     * single chip tap.
     */
    val activeCount: Int
        get() = genres.size + tags.size + excludedGenres.size + excludedTags.size +
            listOf(studioId, year, season, status, format, country, source, minimumScore)
                .count { it != null } +
            listOf(yearFrom ?: yearTo, episodesMin ?: episodesMax).count { it != null } +
            (if (dubbedOnly) 1 else 0) +
            if (hideWatched != WatchedFilter.OFF) 1 else 0
}

/**
 * Which already-watched titles Browse leaves out.
 *
 * [COMPLETED] is the narrow one: finished on the signed-in account, or every episode played on this
 * device. [STARTED] also drops anything in progress, paused or dropped — everything the viewer has
 * had a look at. "Plan to watch" is never hidden by either: it is a list of things *not* watched.
 */
enum class WatchedFilter(val storedValue: String, val label: String, val description: String) {
    OFF("off", "Show everything", "Watched titles stay in the results"),
    COMPLETED("completed", "Hide completed", "Titles you finished, on your list or on this device"),
    STARTED("started", "Hide anything I've watched", "Also in progress, paused and dropped titles");

    companion object {
        fun fromStored(value: String?): WatchedFilter =
            entries.firstOrNull { it.storedValue == value } ?: OFF
    }
}

@Serializable
data class MediaTag(
    val name: String,
    val description: String? = null,
    val category: String? = null,
    val isAdult: Boolean = false,
    val rank: Int? = null,
    val isMediaSpoiler: Boolean = false,
    val isGeneralSpoiler: Boolean = false,
)

@Serializable
data class DiscoverOptions(
    val genres: List<String> = emptyList(),
    val tags: List<MediaTag> = emptyList(),
)

@Serializable
data class DiscoverOptionsData(
    @SerialName("GenreCollection") val genres: List<String> = emptyList(),
    @SerialName("MediaTagCollection") val tags: List<MediaTag> = emptyList(),
)

@Serializable
data class GqlDiscoverOptionsResponse(val data: DiscoverOptionsData? = null)

@Serializable
data class StudioPage(val studios: List<StudioNode> = emptyList())

@Serializable
data class StudioPageData(@SerialName("Page") val page: StudioPage? = null)

@Serializable
data class GqlStudioPageResponse(val data: StudioPageData? = null)

// ---- Response envelopes ----
@Serializable
data class PageInfo(
    val total: Int = 0,
    val currentPage: Int = 1,
    val lastPage: Int = 1,
    val hasNextPage: Boolean = false,
    val perPage: Int = 20,
)

@Serializable
data class Page(
    val pageInfo: PageInfo = PageInfo(),
    val media: List<Media> = emptyList(),
)

@Serializable
data class PageData(@SerialName("Page") val page: Page? = null)

@Serializable
data class MediaData(@SerialName("Media") val media: Media? = null)

@Serializable
data class GqlPageResponse(val data: PageData? = null)

@Serializable
data class HomeCollections(
    val spotlight: List<Media> = emptyList(),
    val newest: List<Media> = emptyList(),
    val popular: List<Media> = emptyList(),
    val movies: List<Media> = emptyList(),
    val topRated: List<Media> = emptyList(),
    val upcoming: List<Media> = emptyList(),
)

@Serializable
data class HomeCollectionsData(
    val spotlight: Page? = null,
    val newest: SchedulePage? = null,
    val popular: Page? = null,
    val movies: Page? = null,
    val topRated: Page? = null,
    val upcoming: Page? = null,
)

@Serializable
data class GqlHomeCollectionsResponse(val data: HomeCollectionsData? = null)

@Serializable
data class GqlMediaResponse(val data: MediaData? = null)

// ---- Airing schedule ----
@Serializable
data class AiringSchedule(
    val episode: Int = 0,
    val airingAt: Long = 0,
    val media: Media? = null,
)

@Serializable
data class SchedulePage(
    val pageInfo: PageInfo = PageInfo(),
    val airingSchedules: List<AiringSchedule> = emptyList(),
)

@Serializable
data class SchedulePageData(@SerialName("Page") val page: SchedulePage? = null)

@Serializable
data class GqlScheduleResponse(val data: SchedulePageData? = null)

// ---- Airing counts (the calendar grid's "N eps" badges) ----
//
// A separate, near-empty shape rather than the models above: the grid needs a number per day and
// nothing else, and a month asked for with `mediaListFields` would be a megabyte of cover art and
// synopses fetched to be counted and thrown away.

@Serializable
data class AiringCountMedia(val id: Int = 0, val isAdult: Boolean = false)

@Serializable
data class AiringCountEntry(val airingAt: Long = 0, val media: AiringCountMedia? = null)

@Serializable
data class AiringCountPage(
    val pageInfo: PageInfo = PageInfo(),
    val airingSchedules: List<AiringCountEntry> = emptyList(),
)

/**
 * One day per alias, keyed by the alias name the query gave it — `d0`, `d1`, … — which is why this
 * is a map and not a named field like every other response here.
 */
@Serializable
data class GqlAiringCountsResponse(val data: Map<String, AiringCountPage?>? = null)

/**
 * One day's tally, as cached.
 *
 * Kept unfiltered — [total] is everything AniList listed, [nonAdult] the same day with adult titles
 * removed — so that turning adult content on or off re-reads as the other number immediately,
 * instead of waiting out a cache entry that was filtered when it was written. Same reason the day
 * lists themselves are cached whole and filtered on the way out.
 *
 * [truncated] means the day overflowed the single page each one is asked for, so the number is a
 * floor rather than a count.
 */
@Serializable
data class AiringDayCount(
    val total: Int = 0,
    val nonAdult: Int = 0,
    val truncated: Boolean = false,
)

/** What a calendar cell shows: a count already filtered for this viewer, and whether it is a floor. */
data class ScheduleDayCount(val episodes: Int, val atLeast: Boolean = false)

// ---- Authenticated user (AniList login) ----
@Serializable
data class UserAvatar(val large: String? = null)

@Serializable
data class AnimeStat(
    val count: Int = 0,
    val episodesWatched: Int = 0,
    val minutesWatched: Long = 0,
    val meanScore: Double = 0.0,
)

@Serializable
data class ViewerStatistics(val anime: AnimeStat? = null)

@Serializable
data class Viewer(
    val id: Int,
    val name: String,
    val avatar: UserAvatar? = null,
    val bannerImage: String? = null,
    val createdAt: Long? = null,
    val statistics: ViewerStatistics? = null,
)

@Serializable
data class ViewerData(@SerialName("Viewer") val viewer: Viewer? = null)

@Serializable
data class GqlViewerResponse(val data: ViewerData? = null)

/**
 * AniList's own 18+ switch, which it applies to every request carrying the account's token
 * whatever the query asks for. Read only to explain an empty answer this app did not cause.
 */
@Serializable
data class ViewerOptions(val displayAdultContent: Boolean = false)

@Serializable
data class ViewerWithOptions(val options: ViewerOptions? = null)

@Serializable
data class ViewerOptionsData(@SerialName("Viewer") val viewer: ViewerWithOptions? = null)

@Serializable
data class GqlViewerOptionsResponse(val data: ViewerOptionsData? = null)

@Serializable
data class FavouriteAnimeConnection(
    val pageInfo: PageInfo = PageInfo(),
    val nodes: List<Media> = emptyList(),
)

@Serializable
data class ViewerFavourites(
    val anime: FavouriteAnimeConnection = FavouriteAnimeConnection(),
)

@Serializable
data class ViewerWithFavourites(val favourites: ViewerFavourites = ViewerFavourites())

@Serializable
data class ViewerFavouritesData(@SerialName("Viewer") val viewer: ViewerWithFavourites? = null)

@Serializable
data class GqlViewerFavouritesResponse(val data: ViewerFavouritesData? = null)

@Serializable
data class MediaListEntry(
    val id: Int = 0,
    val progress: Int = 0,
    val score: Double = 0.0,
    val status: String? = null,
    val updatedAt: Long = 0L,
    val media: Media? = null,
    /**
     * AniList's "only in my custom lists" flag. Such an entry still carries a status — AniList
     * cannot store one without — but the viewer never put it on that list, so nothing that reads
     * statuses (the watchlist, Continue Watching, the status sections) may count it.
     */
    val hiddenFromStatusLists: Boolean = false,
    /**
     * The custom lists this title is in: AniList list names, or MyAnimeList tags. Only the ones it
     * is actually *in* — AniList answers with every list the account has and a flag on each.
     */
    @Serializable(with = CustomListNamesSerializer::class)
    val customLists: List<String> = emptyList(),
)

/**
 * `MediaList.customLists(asArray: true)` is a JSON scalar, `[{"name": "Faves", "enabled": true}]`,
 * listing every custom list on the account. Read into the names that are enabled; the object form
 * AniList sends without `asArray` (`{"Faves": true}`) is accepted too, and so is a plain array of
 * names, which is how the field is written back out when a collection is cached.
 */
object CustomListNamesSerializer : JsonTransformingSerializer<List<String>>(
    ListSerializer(String.serializer()),
) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val names = when (element) {
            is JsonArray -> element.mapNotNull { item ->
                when (item) {
                    is JsonPrimitive -> item.takeIf { it.isString }?.content
                    is JsonObject -> {
                        val enabled = (item["enabled"] as? JsonPrimitive)?.content == "true"
                        (item["name"] as? JsonPrimitive)?.content?.takeIf { enabled }
                    }
                    else -> null
                }
            }
            is JsonObject -> element.mapNotNull { (name, flag) ->
                name.takeIf { (flag as? JsonPrimitive)?.content == "true" }
            }
            else -> emptyList()
        }
        return JsonArray(names.filter { it.isNotBlank() }.map(::JsonPrimitive))
    }
}

@Serializable
data class MediaListGroup(
    val name: String? = null,
    val status: String? = null,
    val isCustomList: Boolean = false,
    val entries: List<MediaListEntry> = emptyList(),
)

@Serializable
data class MediaListCollection(
    val lists: List<MediaListGroup> = emptyList(),
    /** The account's own settings, read alongside the entries for its custom list names. */
    val user: MediaListCollectionUser? = null,
) {
    /** Every custom list the account has, including the empty ones no entry can reveal. */
    val customListNames: List<String>
        get() = user?.mediaListOptions?.animeList?.customLists.orEmpty().filter { it.isNotBlank() }
}

@Serializable
data class MediaListCollectionUser(val mediaListOptions: MediaListOptionsSnapshot? = null)

@Serializable
data class MediaListOptionsSnapshot(val animeList: MediaListTypeOptionsSnapshot? = null)

@Serializable
data class MediaListTypeOptionsSnapshot(val customLists: List<String>? = null)

@Serializable
data class MediaListCollectionData(@SerialName("MediaListCollection") val collection: MediaListCollection? = null)

@Serializable
data class GqlMediaListResponse(val data: MediaListCollectionData? = null)

/** A page of results plus whether more exist — used to drive infinite scroll. */
@Serializable
data class MediaPage(
    val items: List<Media>,
    val hasNextPage: Boolean,
    val page: Int,
    /**
     * How many titles match in total, or 0 when the source cannot say.
     *
     * Defaulted rather than required because most callers here page through a list and never ask;
     * only Browse shows it, where "412 results" is the fastest way to tell a filter that narrowed
     * things from one that emptied them.
     */
    val total: Int = 0,
)

/** One AniList notification, flattened for the in-app notifications screen. */
data class AppNotification(
    val id: Int,
    val kind: Kind,
    val createdAt: Long, // epoch seconds
    val title: String,
    val badge: String?,
    val detail: String?,
    /** Set for [Kind.AIRING] only; the pushed notification is worded around it. */
    val episode: Int?,
    val mediaId: Int?,
    val image: String?,
    val banner: String?,
    val unread: Boolean,
) {
    enum class Kind { AIRING, MEDIA, SOCIAL }
}
