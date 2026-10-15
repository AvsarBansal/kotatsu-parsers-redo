package org.koitharu.kotatsu.parsers.site.all

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Demographic
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.network.OkHttpWebClient
import org.koitharu.kotatsu.parsers.network.WebClient
import org.koitharu.kotatsu.parsers.util.LinkResolver
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

private const val DOMAIN = "mangaball.com"
private const val PAGE_SIZE = 24

internal abstract class MangaBallParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	private val siteLanguages: Set<String>,
) : PagedMangaParser(context, source, pageSize = PAGE_SIZE), Interceptor {

	override val configKeyDomain = ConfigKey.Domain(DOMAIN)

	private val showSuspiciousContentKey = ConfigKey.ShowSuspiciousContent(false)

	private val client: WebClient by lazy {
		OkHttpWebClient(
			context.httpClient.newBuilder()
				.addInterceptor(this)
				.build(),
			source,
		)
	}

	@Volatile
	private var tagsCache: Set<MangaTag>? = null

	private val chapterDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.RELEVANCE,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
		keys.add(showSuspiciousContentKey)
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = tagsCache ?: loadTags().also { tagsCache = it },
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
			MangaState.ABANDONED,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.COMICS,
		),
		availableDemographics = EnumSet.of(
			Demographic.SHOUNEN,
			Demographic.SHOUJO,
			Demographic.SEINEN,
			Demographic.JOSEI,
		),
	)

	private suspend fun loadTags(): Set<MangaTag> = runCatching {
		val json = client.httpGet("$API_URL/tag/get-grouped", getApiHeaders()).parseJson()
		val data = json.optJSONObject("data") ?: return@runCatching emptySet()
		val result = LinkedHashSet<MangaTag>()
		val groups = data.keys()
		while (groups.hasNext()) {
			val tags = data.optJSONArray(groups.next()) ?: continue
			for (i in 0 until tags.length()) {
				val tag = tags.optJSONObject(i) ?: continue
				val id = tag.optString("id").nullIfEmpty() ?: continue
				val name = tag.optString("name").nullIfEmpty() ?: continue
				result += MangaTag(key = id, title = name, source = source)
			}
		}
		result
	}.getOrDefault(emptySet())

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (page < 1) {
			return emptyList()
		}
		val query = filter.query?.trim().orEmpty()
		if (page == 1 && query.startsWith("http")) {
			return resolveSearchUrl(query)
		}
		val url = "$API_URL/title/search-advanced".toHttpUrl().newBuilder().apply {
			addQueryParameter("page", page.toString())
			addQueryParameter("limit", PAGE_SIZE.toString())
			addQueryParameter("adult_mode", if (config[showSuspiciousContentKey]) "all" else "no_18")
			query.nullIfEmpty()?.let { addQueryParameter("keyword", it) }
			if (filter.tags.isNotEmpty()) {
				addQueryParameter("included_tags", filter.tags.joinToString(",") { it.key })
				addQueryParameter("tag_mode", "AND")
			}
			if (filter.tagsExclude.isNotEmpty()) {
				addQueryParameter("excluded_tags", filter.tagsExclude.joinToString(",") { it.key })
			}
			stateValue(filter.states.oneOrThrowIfMany())?.let { addQueryParameter("status", it) }
			typeValue(filter.types.oneOrThrowIfMany())?.let { addQueryParameter("type", it) }
			demographicValue(filter.demographics.oneOrThrowIfMany())?.let {
				addQueryParameter("publicationDemographic", it)
			}
			val (sortBy, sortOrder) = sortValue(order)
			addQueryParameter("sort_by", sortBy)
			addQueryParameter("sort_order", sortOrder)
		}.build()
		val json = client.httpGet(url, getApiHeaders()).parseJson()
		val data = json.optJSONArray("data") ?: return emptyList()
		return List(data.length()) { i ->
			data.optJSONObject(i)?.let(::parseManga)
		}.filterNotNull()
	}

	override suspend fun getDetails(manga: Manga): Manga {
		// The detail endpoint accepts both the ObjectId and the canonical slug, and its response always
		// carries the ObjectId. The chapter listing below, however, silently returns `data = null` for a
		// slug, so every request is addressed by the id resolved from the detail response.
		val requested = manga.url
		val data = runCatchingCancellable {
			client.httpGet("$API_URL/title/detail/$requested", getApiHeaders())
				.parseJson()
				.optJSONObject("data")
		}.getOrNull()
		val id = data?.let { it.optNullableString("id") ?: it.optNullableString("_id") } ?: requested
		val chapters = runCatchingCancellable { getChapterList(id) }.getOrDefault(emptyList())
		val detailed = data?.toManga(id)
		if (detailed == null) {
			// Removed title, unknown id or a transient server error: never let the details screen fail
			// with a generic error — return the input with a non-null chapter list instead.
			return manga.copy(chapters = manga.chapters ?: chapters)
		}
		return detailed.copy(
			title = detailed.title.nullIfEmpty() ?: manga.title,
			altTitles = detailed.altTitles.ifEmpty { manga.altTitles },
			coverUrl = detailed.coverUrl ?: manga.coverUrl,
			largeCoverUrl = detailed.largeCoverUrl ?: detailed.coverUrl ?: manga.largeCoverUrl,
			description = data.optJSONArray("description").toStringSet().joinToString("\n").nullIfEmpty()
				?: manga.description,
			chapters = chapters,
		)
	}

	private suspend fun getChapterList(titleId: String): List<MangaChapter> {
		val body = JSONObject().apply { put("title_id", titleId) }
		val json = client.httpPost(
			"$API_URL/chapter/chapter-listing-by-title-id".toHttpUrl(),
			body,
			getApiHeaders(),
		).parseJson()
		val data = json.optJSONArray("data") ?: return emptyList()
		val candidates = ArrayList<ChapterCandidate>(data.length())
		for (i in 0 until data.length()) {
			val item = data.optJSONObject(i) ?: continue
			val language = item.optString("lang").nullIfEmpty() ?: continue
			if (language !in siteLanguages) {
				continue
			}
			val chapterId = item.optString("id").nullIfEmpty() ?: continue
			val number = item.optDouble("number", 0.0).toFloat()
			val volume = item.optInt("volume", 0)
			val name = item.optString("name").trim().nullIfEmpty()
			val scanlator = item.optJSONObject("group")?.optString("name")?.trim()?.nullIfEmpty()
				?: item.optString("group_name").trim().nullIfEmpty()
			val uploadDate = chapterDateFormat.parseSafe(
				item.optString("updated_at").nullIfEmpty() ?: item.optString("created_at"),
			)
			candidates += ChapterCandidate(
				language = language,
				chapter = MangaChapter(
					id = generateUid(chapterId),
					title = name,
					number = number,
					volume = volume,
					url = chapterId,
					scanlator = scanlator,
					uploadDate = uploadDate,
					branch = language.takeIf { siteLanguages.size > 1 },
					source = source,
				),
			)
		}
		return candidates
			.groupBy { it.language to it.chapter.number }
			.mapNotNull { (_, group) -> group.maxByOrNull { it.chapter.uploadDate }?.chapter }
			.sortedByDescending { it.number }
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val html = client.httpGet(getChapterUrl(chapter), getRefererHeaders()).use { it.body.string() }
		val pagesBlock = CHAPTER_PAGES_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return emptyList()
		return IMAGE_URL_REGEX.findAll(pagesBlock).mapTo(ArrayList()) { match ->
			val url = match.value
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? = resolveMangaFromUrl(link)

	private suspend fun resolveMangaFromUrl(link: HttpUrl): Manga? {
		val host = link.host
		if (host != domain && host != "www.$domain") {
			return null
		}
		val slugOrId = when (link.pathSegments.firstOrNull()) {
			"title-detail" -> link.pathSegments.getOrNull(1)
			"chapter-detail" -> resolveTitleIdFromChapter(
				"$BASE_URL/chapter-detail/${link.pathSegments.getOrNull(1)}",
			)
			else -> null
		}?.takeIf { it.isNotEmpty() } ?: return null
		// getDetails accepts a slug as well and resolves the ObjectId from the response.
		return getDetails(seedManga(slugOrId))
	}

	private suspend fun resolveSearchUrl(url: String): List<Manga> {
		val link = runCatching { url.toHttpUrl() }.getOrNull() ?: return emptyList()
		return listOfNotNull(resolveMangaFromUrl(link))
	}

	private suspend fun resolveTitleIdFromChapter(chapterUrl: String): String? {
		val html = client.httpGet(chapterUrl, getRefererHeaders()).use { it.body.string() }
		return CHAPTER_TITLE_ID_REGEX.find(html)?.groupValues?.getOrNull(1)
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		if (request.header("Referer").isNullOrEmpty() &&
			(request.url.host == domain || request.url.host.endsWith(PAGE_HOST_SUFFIX))
		) {
			return chain.proceed(
				request.newBuilder()
					.header("Referer", "$BASE_URL/")
					.build(),
			)
		}
		return chain.proceed(request)
	}

	private fun parseManga(item: JSONObject): Manga? {
		val id = item.optString("id").nullIfEmpty()
			?: item.optString("_id").nullIfEmpty()
			?: return null
		return item.toManga(id)
	}

	private fun JSONObject.toManga(id: String): Manga {
		val tags = optJSONArray("tags").toMangaTags()
		val cover = extractCover()
		return Manga(
			id = generateUid(id),
			title = optString("name").nullIfEmpty() ?: id,
			altTitles = optJSONArray("alternateName").toStringSet(),
			url = id,
			publicUrl = getMangaUrl(id),
			rating = RATING_UNKNOWN,
			contentRating = if (optBoolean("is18plus", false) || tags.any { it.title in ADULT_TAG_TITLES }) {
				ContentRating.ADULT
			} else {
				null
			},
			coverUrl = cover,
			tags = tags,
			state = stateFromStatus(optString("status")),
			authors = optJSONArray("author").toNameSet(),
			largeCoverUrl = cover,
			source = source,
		)
	}

	private fun seedManga(id: String): Manga = Manga(
		id = generateUid(id),
		title = id,
		altTitles = emptySet(),
		url = id,
		publicUrl = getMangaUrl(id),
		rating = RATING_UNKNOWN,
		contentRating = null,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = source,
	)

	private fun getMangaUrl(id: String): String = "$BASE_URL/title-detail/$id"

	private fun getChapterUrl(chapter: MangaChapter): String = "$BASE_URL/chapter-detail/${chapter.url}"

	private fun getApiHeaders(): Headers = getRequestHeaders().newBuilder()
		.add("Accept", "application/json")
		.add("Referer", "$BASE_URL/")
		.build()

	private fun getRefererHeaders(): Headers = getRequestHeaders().newBuilder()
		.add("Referer", "$BASE_URL/")
		.build()

	private fun JSONObject.extractCover(): String? {
		val image = optJSONObject("image")
		if (image != null) {
			// Prefer hosts that serve covers to any client. The site's own CDN (`cover.path`) is the
			// canonical cover but requires a `Referer` and throttles bursts of requests, while
			// `cdn.mangaupdates.com` (`image.file` for part of the catalogue) no longer resolves —
			// so both are used only as a fallback.
			image.optNullableString("cdn_mangadex")?.let { return it }
			val mirror = image.optNullableString("file")
				?: image.optNullableString("cdn_mangaupdate")
				?: image.optNullableString("cdn_mangaupdates")
			if (mirror != null && !mirror.contains("mangaupdates.com")) {
				return mirror
			}
			val path = image.optJSONObject("cover")?.optNullableString("path")
			if (path != null) {
				return if (path.startsWith("http")) path else "$COVER_BASE_URL/$path"
			}
			if (mirror != null) {
				return mirror
			}
		}
		return optNullableString("cover")
	}

	/**
	 * [JSONObject.optString] turns a JSON `null` into the literal string `"null"`, so it cannot be
	 * used for nullable fields. This helper returns a real `null` for missing/JSON-null/empty values.
	 */
	private fun JSONObject.optNullableString(key: String): String? =
		if (isNull(key)) null else optString(key).nullIfEmpty()

	private fun stateFromStatus(status: String?): MangaState? = when (status) {
		"ongoing" -> MangaState.ONGOING
		"completed" -> MangaState.FINISHED
		"hiatus" -> MangaState.PAUSED
		"cancelled" -> MangaState.ABANDONED
		"on_hold" -> MangaState.PAUSED
		else -> null
	}

	private fun JSONArray?.toMangaTags(): Set<MangaTag> {
		if (this == null) {
			return emptySet()
		}
		val result = LinkedHashSet<MangaTag>(length())
		for (i in 0 until length()) {
			val tag = optJSONObject(i) ?: continue
			val id = tag.optString("id").nullIfEmpty() ?: continue
			val name = tag.optString("name").nullIfEmpty() ?: continue
			result += MangaTag(key = id, title = name, source = source)
		}
		return result
	}

	private fun JSONArray?.toStringSet(): Set<String> {
		if (this == null) {
			return emptySet()
		}
		val result = LinkedHashSet<String>(length())
		for (i in 0 until length()) {
			optString(i).nullIfEmpty()?.let(result::add)
		}
		return result
	}

	private fun JSONArray?.toNameSet(): Set<String> {
		if (this == null) {
			return emptySet()
		}
		val result = LinkedHashSet<String>(length())
		for (i in 0 until length()) {
			optJSONObject(i)?.optString("name")?.nullIfEmpty()?.let(result::add)
		}
		return result
	}

	private fun sortValue(order: SortOrder): Pair<String, String> = when (order) {
		SortOrder.UPDATED -> "lastupdate" to "desc"
		SortOrder.UPDATED_ASC -> "lastupdate" to "asc"
		SortOrder.POPULARITY -> "views" to "desc"
		SortOrder.POPULARITY_ASC -> "views" to "asc"
		SortOrder.RATING -> "rating" to "desc"
		SortOrder.RATING_ASC -> "rating" to "asc"
		SortOrder.NEWEST -> "created_at" to "desc"
		SortOrder.NEWEST_ASC -> "created_at" to "asc"
		SortOrder.ALPHABETICAL -> "name" to "asc"
		SortOrder.ALPHABETICAL_DESC -> "name" to "desc"
		SortOrder.RELEVANCE -> "lastupdate" to "desc"
		else -> "lastupdate" to "desc"
	}

	private fun stateValue(state: MangaState?): String? = when (state) {
		MangaState.ONGOING -> "ongoing"
		MangaState.FINISHED -> "completed"
		MangaState.PAUSED -> "hiatus"
		MangaState.ABANDONED -> "cancelled"
		else -> null
	}

	private fun typeValue(type: ContentType?): String? = when (type) {
		ContentType.MANGA -> "manga"
		ContentType.MANHWA -> "manhwa"
		ContentType.MANHUA -> "manhua"
		ContentType.COMICS -> "comics"
		else -> null
	}

	private fun demographicValue(demographic: Demographic?): String? = when (demographic) {
		Demographic.SHOUNEN -> "shounen"
		Demographic.SHOUJO -> "shoujo"
		Demographic.SEINEN -> "seinen"
		Demographic.JOSEI -> "josei"
		else -> null
	}

	private data class ChapterCandidate(
		val language: String,
		val chapter: MangaChapter,
	)

	companion object {

		private const val BASE_URL = "https://$DOMAIN"
		private const val API_URL = "$BASE_URL/api/v1"
		private const val PAGE_HOST_SUFFIX = ".poke-black-and-white.net"

		/**
		 * Host for the site's own covers (`image.cover.path`). Verified stable across titles.
		 * These URLs require `Referer: https://mangaball.com/`, which [intercept] supplies.
		 */
		private const val COVER_BASE_URL = "https://bulbasaur$PAGE_HOST_SUFFIX/covers"
		private val CHAPTER_PAGES_REGEX = Regex("""\\"pages\\":\[(.*?)\]""")
		private val IMAGE_URL_REGEX = Regex("""https?://[^"\\\s]+""")
		private val CHAPTER_TITLE_ID_REGEX = Regex("""\\"title\\":\{\\"id\\":\\"([a-fA-F0-9]{24})""")
		private val ADULT_TAG_TITLES = setOf(
			"Adult",
			"Ecchi",
			"Mature",
			"Smut",
			"Hentai",
			"Manhwa 18+",
			"Sexual Violence",
		)
	}

	@MangaSourceParser("MANGABALL_AR", "Manga Ball (Arabic)", "ar")
	class Arabic(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_AR, setOf("ar"))

	@MangaSourceParser("MANGABALL_BG", "Manga Ball (Bulgarian)", "bg")
	class Bulgarian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_BG, setOf("bg"))

	@MangaSourceParser("MANGABALL_BN", "Manga Ball (Bengali)", "bn")
	class Bengali(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_BN, setOf("bn"))

	@MangaSourceParser("MANGABALL_CA", "Manga Ball (Catalan)", "ca")
	class Catalan(context: MangaLoaderContext) : MangaBallParser(
		context,
		MangaParserSource.MANGABALL_CA,
		setOf("ca", "ca-ad", "ca-es", "ca-fr", "ca-it", "ca-pt"),
	)

	@MangaSourceParser("MANGABALL_CS", "Manga Ball (Czech)", "cs")
	class Czech(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_CS, setOf("cs"))

	@MangaSourceParser("MANGABALL_DA", "Manga Ball (Danish)", "da")
	class Danish(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_DA, setOf("da"))

	@MangaSourceParser("MANGABALL_DE", "Manga Ball (German)", "de")
	class German(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_DE, setOf("de"))

	@MangaSourceParser("MANGABALL_EL", "Manga Ball (Greek)", "el")
	class Greek(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_EL, setOf("el"))

	@MangaSourceParser("MANGABALL_EN", "Manga Ball (English)", "en")
	class English(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_EN, setOf("en"))

	@MangaSourceParser("MANGABALL_ES", "Manga Ball (Spanish)", "es")
	class Spanish(context: MangaLoaderContext) : MangaBallParser(
		context,
		MangaParserSource.MANGABALL_ES,
		setOf("es", "es-ar", "es-mx", "es-es", "es-la", "es-419"),
	)

	@MangaSourceParser("MANGABALL_FA", "Manga Ball (Persian)", "fa")
	class Persian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_FA, setOf("fa"))

	@MangaSourceParser("MANGABALL_FI", "Manga Ball (Finnish)", "fi")
	class Finnish(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_FI, setOf("fi"))

	@MangaSourceParser("MANGABALL_FR", "Manga Ball (French)", "fr")
	class French(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_FR, setOf("fr"))

	@MangaSourceParser("MANGABALL_HE", "Manga Ball (Hebrew)", "he")
	class Hebrew(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_HE, setOf("he"))

	@MangaSourceParser("MANGABALL_HI", "Manga Ball (Hindi)", "hi")
	class Hindi(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_HI, setOf("hi"))

	@MangaSourceParser("MANGABALL_HU", "Manga Ball (Hungarian)", "hu")
	class Hungarian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_HU, setOf("hu"))

	@MangaSourceParser("MANGABALL_ID", "Manga Ball (Indonesian)", "id")
	class Indonesian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_ID, setOf("id"))

	@MangaSourceParser("MANGABALL_IT", "Manga Ball (Italian)", "it")
	class Italian(context: MangaLoaderContext) : MangaBallParser(
		context,
		MangaParserSource.MANGABALL_IT,
		setOf("it", "it-it"),
	)

	@MangaSourceParser("MANGABALL_IS", "Manga Ball (Icelandic)", "is")
	class Icelandic(context: MangaLoaderContext) : MangaBallParser(
		context,
		MangaParserSource.MANGABALL_IS,
		setOf("ib", "ib-is", "is"),
	)

	@MangaSourceParser("MANGABALL_JA", "Manga Ball (Japanese)", "ja")
	class Japanese(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_JA, setOf("ja", "jp"))

	@MangaSourceParser("MANGABALL_KO", "Manga Ball (Korean)", "ko")
	class Korean(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_KO, setOf("ko", "kr"))

	@MangaSourceParser("MANGABALL_KN", "Manga Ball (Kannada)", "kn")
	class Kannada(context: MangaLoaderContext) : MangaBallParser(
		context,
		MangaParserSource.MANGABALL_KN,
		setOf("kn", "kn-in", "kn-my", "kn-sg", "kn-tw"),
	)

	@MangaSourceParser("MANGABALL_ML", "Manga Ball (Malayalam)", "ml")
	class Malayalam(context: MangaLoaderContext) : MangaBallParser(
		context,
		MangaParserSource.MANGABALL_ML,
		setOf("ml", "ml-in", "ml-my", "ml-sg", "ml-tw"),
	)

	@MangaSourceParser("MANGABALL_MS", "Manga Ball (Malay)", "ms")
	class Malay(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_MS, setOf("ms"))

	@MangaSourceParser("MANGABALL_NE", "Manga Ball (Nepali)", "ne")
	class Nepali(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_NE, setOf("ne"))

	@MangaSourceParser("MANGABALL_NL", "Manga Ball (Dutch)", "nl")
	class Dutch(context: MangaLoaderContext) : MangaBallParser(
		context,
		MangaParserSource.MANGABALL_NL,
		setOf("nl", "nl-be"),
	)

	@MangaSourceParser("MANGABALL_NO", "Manga Ball (Norwegian)", "no")
	class Norwegian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_NO, setOf("no"))

	@MangaSourceParser("MANGABALL_PL", "Manga Ball (Polish)", "pl")
	class Polish(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_PL, setOf("pl"))

	@MangaSourceParser("MANGABALL_PTBR", "Manga Ball (Portuguese)", "pt")
	class PortugueseBrazil(context: MangaLoaderContext) : MangaBallParser(
		context,
		MangaParserSource.MANGABALL_PTBR,
		setOf("pt-br", "pt-pt"),
	)

	@MangaSourceParser("MANGABALL_RO", "Manga Ball (Romanian)", "ro")
	class Romanian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_RO, setOf("ro"))

	@MangaSourceParser("MANGABALL_RU", "Manga Ball (Russian)", "ru")
	class Russian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_RU, setOf("ru"))

	@MangaSourceParser("MANGABALL_SK", "Manga Ball (Slovak)", "sk")
	class Slovak(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_SK, setOf("sk"))

	@MangaSourceParser("MANGABALL_SL", "Manga Ball (Slovenian)", "sl")
	class Slovenian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_SL, setOf("sl"))

	@MangaSourceParser("MANGABALL_SQ", "Manga Ball (Albanian)", "sq")
	class Albanian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_SQ, setOf("sq"))

	@MangaSourceParser("MANGABALL_SR", "Manga Ball (Serbian)", "sr")
	class Serbian(context: MangaLoaderContext) : MangaBallParser(
		context,
		MangaParserSource.MANGABALL_SR,
		setOf("sr", "sr-cyrl"),
	)

	@MangaSourceParser("MANGABALL_SV", "Manga Ball (Swedish)", "sv")
	class Swedish(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_SV, setOf("sv"))

	@MangaSourceParser("MANGABALL_TA", "Manga Ball (Tamil)", "ta")
	class Tamil(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_TA, setOf("ta"))

	@MangaSourceParser("MANGABALL_TH", "Manga Ball (Thai)", "th")
	class Thai(context: MangaLoaderContext) : MangaBallParser(
		context,
		MangaParserSource.MANGABALL_TH,
		setOf("th", "th-hk", "th-kh", "th-la", "th-my", "th-sg"),
	)

	@MangaSourceParser("MANGABALL_TR", "Manga Ball (Turkish)", "tr")
	class Turkish(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_TR, setOf("tr"))

	@MangaSourceParser("MANGABALL_UK", "Manga Ball (Ukrainian)", "uk")
	class Ukrainian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_UK, setOf("uk"))

	@MangaSourceParser("MANGABALL_VI", "Manga Ball (Vietnamese)", "vi")
	class Vietnamese(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_VI, setOf("vi"))

	@MangaSourceParser("MANGABALL_ZH", "Manga Ball (Chinese)", "zh")
	class Chinese(context: MangaLoaderContext) : MangaBallParser(
		context,
		MangaParserSource.MANGABALL_ZH,
		setOf("cn", "zh", "zh-cn", "zh-hk", "zh-mo", "zh-sg", "zh-tw"),
	)
}
