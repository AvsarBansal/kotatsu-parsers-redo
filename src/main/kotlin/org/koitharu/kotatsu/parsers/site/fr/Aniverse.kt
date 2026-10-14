package org.koitharu.kotatsu.parsers.site.fr

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
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
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.json.unescapeJson
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseRaw
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

/**
 * Aniverse (ex RaijinScans) — aniverse.fr
 *
 * Le site (Next.js) expose des API JSON publiques :
 *  - `/api/manga?page=N[&genre=...]` — catalogue (tri par date du dernier chapitre)
 *  - `/api/manga/reader?manga={slug}&chapter={uuid}` — chapitres + pages d'un chapitre
 *  - `/api/manga/known?alIds=...` — correspondance AniList → titres du site
 * Les pages HTML embarquent aussi le payload RSC (Next.js) avec l'objet manga complet
 * (titre, auteurs, genres, description, note) et la liste intégrale des chapitres.
 *
 * La recherche du site web est hors service (résultats jamais chargés) : la recherche
 * passe donc par l'API publique AniList puis par `/api/manga/known` pour ne conserver
 * que les titres réellement disponibles sur Aniverse.
 */
@MangaSourceParser("ANIVERSE", "Aniverse", "fr")
internal class Aniverse(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.ANIVERSE, pageSize = PAGE_SIZE, searchPageSize = SEARCH_PAGE_SIZE) {

	override val configKeyDomain = ConfigKey.Domain("aniverse.fr")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = false,
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = TAGS,
	)

	private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
		timeZone = TimeZone.getTimeZone("UTC")
	}

	private val jsonHeaders: Headers = Headers.Builder()
		.add("Content-Type", "application/json")
		.add("Accept", "application/json")
		.build()

	private var flightCache: Pair<String, String>? = null

	// ---------------------------------------------------------------- list

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim()
		if (!query.isNullOrEmpty()) {
			return search(query, page)
		}
		val url = "https://$domain/api/manga".toHttpUrl().newBuilder()
			.addQueryParameter("page", page.toString())
			.apply {
				filter.tags.oneOrThrowIfMany()?.let { addQueryParameter("genre", it.key) }
			}
			.build()
		return webClient.httpGet(url).parseJson()
			.getJSONArray("items")
			.mapJSON { it.toListManga() }
	}

	/**
	 * Recherche via AniList (public) croisée avec /api/manga/known.
	 */
	private suspend fun search(query: String, page: Int): List<Manga> {
		val gqlQuery = buildString {
			append("query { Page(page: ")
			append(page)
			append(", perPage: ")
			append(SEARCH_PAGE_SIZE)
			append(") { media(search: ")
			append(JSONObject.quote(query))
			append(", type: MANGA, sort: SEARCH_MATCH) { id title { romaji english native userPreferred } coverImage { large } } } }")
		}
		val payload = JSONObject().apply {
			put("query", gqlQuery)
		}
		val media = webClient.httpPost(ANILIST_URL.toHttpUrl(), payload, jsonHeaders)
			.parseJson()
			.getJSONObject("data")
			.getJSONObject("Page")
			.getJSONArray("media")
		if (media.length() == 0) {
			return emptyList()
		}
		val alIds = ArrayList<String>(media.length())
		for (i in 0 until media.length()) {
			media.optJSONObject(i)?.optInt("id")?.takeIf { it > 0 }?.let { alIds.add(it.toString()) }
		}
		if (alIds.isEmpty()) {
			return emptyList()
		}
		val known = webClient.httpGet("https://$domain/api/manga/known?alIds=${alIds.joinToString(",")}")
			.parseJson()
			.getJSONArray("items")
		if (known.length() == 0) {
			return emptyList()
		}
		val byAlId = HashMap<Int, JSONObject>(known.length())
		for (i in 0 until known.length()) {
			val jo = known.getJSONObject(i)
			byAlId[jo.getInt("alId")] = jo
		}
		return media.mapJSONNotNull { jo ->
			val entry = byAlId[jo.optInt("id")] ?: return@mapJSONNotNull null
			val slug = entry.getString("id")
			Manga(
				id = generateUid("/manga/$slug"),
				title = entry.getString("title"),
				altTitles = emptySet(),
				url = "/manga/$slug",
				publicUrl = "https://$domain/manga/$slug",
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = jo.optJSONObject("coverImage")?.getStringOrNull("large"),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	private fun JSONObject.toListManga(): Manga {
		val slug = getString("slug")
		val title = getString("title")
		return Manga(
			id = generateUid("/manga/$slug"),
			title = title,
			altTitles = setOfNotNull(
				getStringOrNull("titleEnglish"),
				getStringOrNull("titleRomaji"),
				getStringOrNull("titleNative"),
			).minus(title),
			url = "/manga/$slug",
			publicUrl = "https://$domain/manga/$slug",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = getStringOrNull("image"),
			tags = emptySet(),
			state = parseState(getStringOrNull("status")),
			authors = emptySet(),
			source = source,
		)
	}

	// -------------------------------------------------------------- details

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = slugOf(manga)
		val flight = flightOf(slug)
		val obj = findMangaObject(flight, slug)
		val coverUrl = obj?.optJSONObject("coverImage")?.getStringOrNull("large")
		val score = obj?.optDouble("averageScore", 0.0) ?: 0.0
		return manga.copy(
			title = obj?.titleAsString() ?: manga.title,
			altTitles = obj?.altTitles() ?: manga.altTitles,
			description = obj?.getStringOrNull("description")?.cleanDescription(),
			coverUrl = coverUrl ?: manga.coverUrl,
			largeCoverUrl = obj?.getStringOrNull("bannerImage") ?: coverUrl,
			rating = if (score > 0.0) (score / 100.0).toFloat() else RATING_UNKNOWN,
			contentRating = if (obj?.optBoolean("isAdult", false) == true) {
				ContentRating.ADULT
			} else {
				null
			},
			tags = obj?.tagsOrEmpty() ?: emptySet(),
			state = obj?.let { parseState(it.getStringOrNull("status")) },
			authors = obj?.authorsOrEmpty() ?: emptySet(),
			chapters = findChaptersArray(flight)?.mapJSON { it.toChapter(slug) },
		)
	}

	// ---------------------------------------------------------------- pages

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val parts = chapter.url.trim('/').split('/')
		val slug = parts.getOrNull(1) ?: return emptyList()
		val uuid = parts.getOrNull(2) ?: return emptyList()
		val url = "https://$domain/api/manga/reader".toHttpUrl().newBuilder()
			.addQueryParameter("manga", slug)
			.addQueryParameter("chapter", uuid)
			.build()
		val pages = webClient.httpGet(url).parseJson().optJSONArray("pages")
		if (pages != null && pages.length() > 0) {
			return pages.mapJSON { jo ->
				MangaPage(
					id = generateUid(jo.getString("id")),
					url = jo.getString("src"),
					preview = null,
					source = source,
				)
			}
		}
		// Fallback : extraction depuis la page de lecture
		val html = webClient.httpGet("https://$domain/read/$slug/$uuid").parseRaw()
		val seen = HashSet<String>()
		return IMAGE_URL_REGEX.findAll(html)
			.map { it.value }
			.filter { seen.add(it) }
			.map { src ->
				MangaPage(
					id = generateUid(src),
					url = src,
					preview = null,
					source = source,
				)
			}
			.toList()
	}

	// ------------------------------------------------------------- internals

	private fun slugOf(manga: Manga): String =
		manga.url.substringAfter("/manga/").substringBefore('?').trim('/')

	private suspend fun flightOf(slug: String): String {
		flightCache?.let { (cachedSlug, cachedFlight) ->
			if (cachedSlug == slug) {
				return cachedFlight
			}
		}
		val html = webClient.httpGet("https://$domain/manga/$slug").parseRaw()
		val flight = buildFlightText(html)
		flightCache = slug to flight
		return flight
	}

	/**
	 * Extrait et concatène les payloads RSC (`self.__next_f.push([1, "..."])`)
	 * en dé-échappant les chaînes JavaScript — scan manuel (les gros payloads
	 * font exploser la pile de java.util.regex).
	 */
	private fun buildFlightText(html: String): String {
		val sb = StringBuilder(html.length)
		var searchFrom = 0
		while (true) {
			val marker = html.indexOf(NEXT_F_PREFIX, searchFrom)
			if (marker == -1) {
				break
			}
			var i = marker + NEXT_F_PREFIX.length
			if (i >= html.length || html[i] != '"') {
				searchFrom = i
				continue
			}
			i++ // guillemet ouvrant
			val start = i
			var escaped = false
			var end = -1
			while (i < html.length) {
				val c = html[i]
				if (escaped) {
					escaped = false
				} else if (c == '\\') {
					escaped = true
				} else if (c == '"') {
					end = i
					break
				}
				i++
			}
			if (end == -1) {
				break
			}
			val payload = html.substring(start, end)
			sb.append(
				try {
					payload.unescapeJson()
				} catch (_: IllegalArgumentException) {
					payload
				},
			)
			searchFrom = end + 1
		}
		return sb.toString()
	}

	private fun findMangaObject(flight: String, slug: String): JSONObject? {
		val needle = "\"slug\":\"$slug\""
		var index = flight.indexOf(needle)
		while (index != -1) {
			val start = flight.lastIndexOf('{', index)
			if (start != -1) {
				val end = findJsonEnd(flight, start, '{', '}')
				if (end != -1) {
					val json = runCatching { JSONObject(flight.substring(start, end + 1)) }.getOrNull()
					if (json != null && json.has("databaseId") && json.has("genres")) {
						return json
					}
				}
			}
			index = flight.indexOf(needle, index + 1)
		}
		return null
	}

	private fun findChaptersArray(flight: String): JSONArray? {
		var best: JSONArray? = null
		var index = flight.indexOf(CHAPTERS_ARRAY_MARKER)
		while (index != -1) {
			val end = findJsonEnd(flight, index, '[', ']')
			if (end != -1) {
				val parsed = runCatching { JSONArray(flight.substring(index, end + 1)) }.getOrNull()
				val first = parsed?.optJSONObject(0)
				if (parsed != null && first != null && first.has("previewImage") && first.has("number")) {
					if (best == null || parsed.length() > best.length()) {
						best = parsed
					}
				}
			}
			index = flight.indexOf(CHAPTERS_ARRAY_MARKER, index + 1)
		}
		return best
	}

	/**
	 * Recherche la fin d'un objet/tableau JSON à partir de son ouverture,
	 * en respectant les chaînes de caractères.
	 */
	private fun findJsonEnd(text: String, start: Int, open: Char, close: Char): Int {
		var depth = 0
		var inString = false
		var escaped = false
		for (i in start until text.length) {
			val c = text[i]
			if (inString) {
				when {
					escaped -> escaped = false
					c == '\\' -> escaped = true
					c == '"' -> inString = false
				}
			} else {
				when (c) {
					'"' -> inString = true
					open -> depth++
					close -> {
						depth--
						if (depth == 0) {
							return i
						}
					}
				}
			}
		}
		return -1
	}

	private fun JSONObject.toChapter(slug: String): MangaChapter {
		val uuid = getString("id")
		val number = when (val raw = opt("number")) {
			is Number -> raw.toFloat()
			is String -> raw.toFloatOrNull() ?: 0f
			else -> 0f
		}
		return MangaChapter(
			id = generateUid("/read/$slug/$uuid"),
			title = chapterTitle(number),
			number = number,
			volume = intOrZero("volume"),
			url = "/read/$slug/$uuid",
			scanlator = null,
			uploadDate = parseDate(getStringOrNull("updatedAt")),
			branch = null,
			source = source,
		)
	}

	/**
	 * "Chapitre 26 - Le flingue bat la pierre" -> "Le flingue bat la pierre"
	 * (le modèle Kotatsu ne doit pas contenir le numéro dans le titre)
	 */
	private fun JSONObject.chapterTitle(number: Float): String? {
		val raw = getStringOrNull("title") ?: return null
		if (raw.isBlank()) {
			return null
		}
		val prefix = Regex(
			"^\\s*(?:chapitre|chapter)\\s*" + Regex.escape(formatNumber(number)) + "\\s*(?:[-–—:.]\\s*)?",
			RegexOption.IGNORE_CASE,
		)
		return raw.replaceFirst(prefix, "").trim().ifEmpty { null }
	}

	private fun JSONObject.titleAsString(): String? {
		val titleObj = optJSONObject("title") ?: return opt("title") as? String
		return titleObj.getStringOrNull("userPreferred")
			?: titleObj.getStringOrNull("english")
			?: titleObj.getStringOrNull("romaji")
			?: titleObj.getStringOrNull("native")
	}

	private fun JSONObject.altTitles(): Set<String> {
		val titleObj = optJSONObject("title") ?: return emptySet()
		return setOfNotNull(
			titleObj.getStringOrNull("userPreferred"),
			titleObj.getStringOrNull("english"),
			titleObj.getStringOrNull("romaji"),
			titleObj.getStringOrNull("native"),
		).minus(titleAsString().orEmpty())
	}

	private fun JSONObject.tagsOrEmpty(): Set<MangaTag> {
		val genres = optJSONArray("genres") ?: return emptySet()
		val result = LinkedHashSet<MangaTag>(genres.length())
		for (i in 0 until genres.length()) {
			val name = genres.optString(i)
			if (name.isBlank()) {
				continue
			}
			result.add(TAGS_BY_KEY[name.lowercase(Locale.ROOT)] ?: MangaTag(name, name, source))
		}
		return result
	}

	private fun JSONObject.authorsOrEmpty(): Set<String> {
		val authors = optJSONArray("authors") ?: return emptySet()
		val result = LinkedHashSet<String>(authors.length())
		for (i in 0 until authors.length()) {
			val name = authors.optString(i)
			if (name.isNotBlank()) {
				result.add(name)
			}
		}
		return result
	}

	private fun parseState(raw: String?): MangaState? = when (raw?.lowercase(Locale.ROOT)?.replace(" ", "")?.replace("_", "")) {
		"ongoing", "releasing" -> MangaState.ONGOING
		"finished" -> MangaState.FINISHED
		"onhiatus", "hiatus" -> MangaState.PAUSED
		"cancelled", "canceled" -> MangaState.ABANDONED
		"upcoming", "notyetreleased" -> MangaState.UPCOMING
		else -> null
	}

	private fun JSONObject.intOrZero(name: String): Int = when (val raw = opt(name)) {
		is Number -> raw.toInt()
		is String -> raw.toIntOrNull() ?: 0
		else -> 0
	}

	private fun parseDate(raw: String?): Long {
		if (raw.isNullOrEmpty()) {
			return 0L
		}
		return try {
			synchronized(dateFormat) {
				dateFormat.parse(raw)?.time ?: 0L
			}
		} catch (_: Exception) {
			0L
		}
	}

	private fun formatNumber(value: Float): String =
		if (value % 1f == 0f) value.toInt().toString() else value.toString()

	private fun String.cleanDescription(): String? {
		val text = Jsoup.parse(replace(HTML_BREAK_REGEX, "\n")).text().trim()
		return text.ifEmpty { null }
	}
}

private const val PAGE_SIZE = 28
private const val SEARCH_PAGE_SIZE = 50
private const val ANILIST_URL = "https://graphql.anilist.co"

private const val NEXT_F_PREFIX = "self.__next_f.push([1,"

private val CHAPTERS_ARRAY_MARKER = "[{\"id\":\""
private val IMAGE_URL_REGEX = Regex("""https://cdn\.onefy\.me/raw/manga/[0-9a-fA-F-]{36}/[A-Za-z0-9_-]+/\d{3}\.(?:jpg|jpeg|png|webp)""")
private val HTML_BREAK_REGEX = Regex("(?i)<br\\s*/?>")

private val TAGS: Set<MangaTag> = linkedSetOf(
	MangaTag("Action", "Action", MangaParserSource.ANIVERSE),
	MangaTag("Aventure", "Adventure", MangaParserSource.ANIVERSE),
	MangaTag("Comédie", "Comedy", MangaParserSource.ANIVERSE),
	MangaTag("Drame", "Drama", MangaParserSource.ANIVERSE),
	MangaTag("Ecchi", "Ecchi", MangaParserSource.ANIVERSE),
	MangaTag("Fantasy", "Fantasy", MangaParserSource.ANIVERSE),
	MangaTag("Horreur", "Horror", MangaParserSource.ANIVERSE),
	MangaTag("Mahou shoujo", "Mahou Shoujo", MangaParserSource.ANIVERSE),
	MangaTag("Mecha", "Mecha", MangaParserSource.ANIVERSE),
	MangaTag("Musique", "Music", MangaParserSource.ANIVERSE),
	MangaTag("Mystère", "Mystery", MangaParserSource.ANIVERSE),
	MangaTag("Psychologique", "Psychological", MangaParserSource.ANIVERSE),
	MangaTag("Romance", "Romance", MangaParserSource.ANIVERSE),
	MangaTag("Science-fiction", "Sci-Fi", MangaParserSource.ANIVERSE),
	MangaTag("Tranche de vie", "Slice of Life", MangaParserSource.ANIVERSE),
	MangaTag("Sport", "Sports", MangaParserSource.ANIVERSE),
	MangaTag("Surnaturel", "Supernatural", MangaParserSource.ANIVERSE),
	MangaTag("Thriller", "Thriller", MangaParserSource.ANIVERSE),
)

private val TAGS_BY_KEY: Map<String, MangaTag> = TAGS.associateBy { it.key.lowercase(Locale.ROOT) }
