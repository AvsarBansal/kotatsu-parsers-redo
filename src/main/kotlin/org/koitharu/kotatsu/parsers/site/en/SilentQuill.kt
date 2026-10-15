package org.koitharu.kotatsu.parsers.site.en

import okhttp3.Headers
import org.json.JSONArray
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.util.Calendar
import java.util.EnumSet

@MangaSourceParser("SILENTQUILL", "SilentQuill", "en")
internal class SilentQuill(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.SILENTQUILL, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("silentquill.net")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = false,
		)

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
		.build()

	private val filterOptions = suspendLazy(initializer = ::fetchFilterOptions)

	override suspend fun getFilterOptions(): MangaListFilterOptions = filterOptions.get()

	private suspend fun fetchFilterOptions(): MangaListFilterOptions {
		val url = urlBuilder().addPathSegment("search").build()
		val html = webClient.httpGet(url, getRequestHeaders()).parseRaw()
		val fullDecoded = decodeNextChunks(html)
		val tags = GENRE_OPTION_REGEX.findAll(fullDecoded).mapNotNull { match ->
			val key = match.groupValues[1].trim()
			val label = match.groupValues[2].replace(TAG_COUNT_REGEX, "").trim()
			if (key.isNotEmpty() && label.isNotEmpty()) {
				MangaTag(title = label.toTitleCase(), key = key, source = source)
			} else {
				null
			}
		}.toSet()
		return MangaListFilterOptions(
			availableTags = tags,
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query
		if (!query.isNullOrBlank()) {
			if (page > 1) {
				return emptyList()
			}
			val url = urlBuilder().addPathSegments("api/suggest/")
				.addQueryParameter("q", query)
				.build()
			val response = webClient.httpGet(url, getRequestHeaders()).parseJson()
			val items = response.optJSONArray("items") ?: return emptyList()
			return items.mapJSON { item ->
				val slug = item.getString("slug")
				val mangaUrl = "/series/$slug/"
				val title = item.getString("title").trim()
				val coverUrl = item.getStringOrNull("cover_url")?.toAbsoluteUrl(domain)
				Manga(
					id = generateUid(mangaUrl),
					title = title,
					altTitles = emptySet(),
					url = mangaUrl,
					publicUrl = mangaUrl.toAbsoluteUrl(domain),
					rating = RATING_UNKNOWN,
					contentRating = null,
					coverUrl = coverUrl,
					largeCoverUrl = null,
					description = null,
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					source = source,
				)
			}
		}

		val url = urlBuilder().addPathSegments("search/")
			.addQueryParameter("page", page.toString())
			.apply {
				val state = filter.states.firstOrNull()
				if (state == MangaState.FINISHED) {
					addQueryParameter("status", "completed")
				} else if (state == MangaState.ONGOING) {
					addQueryParameter("status", "ongoing")
				}
				filter.tags.firstOrNull()?.let { tag ->
					addQueryParameter("genre", tag.key)
				}
			}
			.build()

		val html = webClient.httpGet(url, getRequestHeaders()).parseRaw()
		return parseListHtml(html)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()

		val title = doc.selectFirst("h1")?.textOrNull() ?: manga.title
		val coverUrl = doc.selectFirst("meta[property=og:image]")?.attrOrNull("content")
			?: doc.selectFirst("button img.object-cover, img.object-cover")?.src()?.toAbsoluteUrl(domain)
			?: manga.coverUrl
		val description = doc.selectFirst("div.reader-content")?.textOrNull()
			?: doc.selectFirst("meta[property=og:description]")?.attrOrNull("content")
		val authorsText = doc.selectFirst("div.min-w-0 > p.text-sm")?.textOrNull()
		val authors = authorsText?.split("·", ",")?.mapNotNullToSet { segment ->
			segment.trim().replace(AUTHOR_PREFIX_REGEX, "").trim().nullIfEmpty()
		} ?: manga.authors
		val stateText = doc.selectFirst("span.border-ok\\/50, dt:contains(Status) + dd")?.textOrNull()
		val state = when {
			stateText.equals("Completed", ignoreCase = true) -> MangaState.FINISHED
			stateText.equals("Ongoing", ignoreCase = true) -> MangaState.ONGOING
			else -> manga.state
		}
		val tags = doc.select("a[href*=/search/?genre=], a[href*=/search?genre=]").mapNotNullToSet { a ->
			val tagTitle = a.text().trim()
			val tagKey = a.attr("href").substringAfter("genre=").substringBefore("&").trim()
			if (tagTitle.isNotEmpty() && tagKey.isNotEmpty()) MangaTag(tagTitle, tagKey, source) else null
		}

		val chapterItems = ArrayList<Pair<Element, Int>>()
		val sections = doc.select("section:has(div.columns-1)")
		if (sections.isNotEmpty()) {
			for (section in sections) {
				val volText = section.selectFirst("div.mb-2 > span")?.textOrNull()
				val volume = volText?.let { VOLUME_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
				for (a in section.select("div.columns-1 a[href]")) {
					chapterItems.add(a to volume)
				}
			}
		} else {
			for (a in doc.select("div.columns-1 a[href]")) {
				chapterItems.add(a to 0)
			}
		}

		val chapters = chapterItems.mapChapters(reversed = true) { index, (a, volume) ->
			val relativeUrl = a.attrAsRelativeUrl("href")
			val chTitle = a.selectFirst("span.text-base")?.textOrNull() ?: a.text()
			val number = CHAPTER_NUMBER_REGEX.find(chTitle)?.groupValues?.get(1)?.toFloatOrNull() ?: (index + 1).toFloat()
			val dateText = a.selectFirst("span.text-right")?.textOrNull()
			MangaChapter(
				id = generateUid(relativeUrl),
				title = chTitle.trim(),
				number = number,
				volume = volume,
				url = relativeUrl,
				scanlator = null,
				uploadDate = parseRelativeDate(dateText),
				branch = null,
				source = source,
			)
		}

		return manga.copy(
			title = title,
			coverUrl = coverUrl,
			description = description,
			authors = authors,
			state = state,
			tags = tags,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val html = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), getRequestHeaders()).parseRaw()
		val fullDecoded = decodeNextChunks(html)

		val pagesJson = PAGES_REGEX.find(fullDecoded)?.groupValues?.get(1)
		if (pagesJson != null) {
			val jsonArray = runCatching { JSONArray(pagesJson) }.getOrNull()
			if (jsonArray != null) {
				return jsonArray.mapJSON { obj ->
					val pageUrl = obj.getString("url")
					MangaPage(
						id = generateUid(pageUrl),
						url = pageUrl.toAbsoluteUrl(domain),
						preview = null,
						source = source,
					)
				}
			}
		}

		return PAGE_IMG_REGEX.findAll(fullDecoded).map { match ->
			val pageUrl = match.groupValues[1]
			MangaPage(
				id = generateUid(pageUrl),
				url = pageUrl.toAbsoluteUrl(domain),
				preview = null,
				source = source,
			)
		}.toList()
	}

	private fun parseListHtml(html: String): List<Manga> {
		val fullDecoded = decodeNextChunks(html)
		val results = ArrayList<Manga>()
		for (match in SERIES_CARD_REGEX.findAll(fullDecoded)) {
			val mangaUrl = match.groupValues[1]
			val block = match.groupValues[3]
			val titleRaw = TITLE_REGEX.find(block)?.groupValues?.get(1)
				?: ALT_REGEX.find(block)?.groupValues?.get(1)
				?: continue
			val title = runCatching { titleRaw.unescapeJson() }.getOrDefault(titleRaw).trim()
			val srcRaw = SRC_REGEX.find(block)?.groupValues?.get(1)
			val coverUrl = srcRaw?.let { runCatching { it.unescapeJson() }.getOrDefault(it) }?.toAbsoluteUrl(domain)
			val statusStr = STATUS_REGEX.find(block)?.groupValues?.get(1)
			val state = when {
				statusStr.equals("Completed", ignoreCase = true) -> MangaState.FINISHED
				statusStr.equals("Ongoing", ignoreCase = true) -> MangaState.ONGOING
				else -> null
			}
			results.add(
				Manga(
					id = generateUid(mangaUrl),
					title = title,
					altTitles = emptySet(),
					url = mangaUrl,
					publicUrl = mangaUrl.toAbsoluteUrl(domain),
					rating = RATING_UNKNOWN,
					contentRating = null,
					coverUrl = coverUrl,
					largeCoverUrl = null,
					description = null,
					tags = emptySet(),
					state = state,
					authors = emptySet(),
					source = source,
				),
			)
		}
		return results
	}

	private fun decodeNextChunks(html: String): String {
		val builder = StringBuilder()
		for (match in NEXT_F_PUSH_REGEX.findAll(html)) {
			val segment = match.groupValues[1]
			builder.append(runCatching { segment.unescapeJson() }.getOrDefault(segment))
		}
		return builder.toString()
	}

	private fun parseRelativeDate(dateStr: String?): Long {
		if (dateStr.isNullOrBlank()) return 0L
		val match = RELATIVE_DATE_REGEX.find(dateStr.trim().lowercase()) ?: return 0L
		val amount = match.groupValues[1].toIntOrNull() ?: return 0L
		val unit = match.groupValues[2]
		val cal = Calendar.getInstance()
		when {
			unit.startsWith("y") -> cal.add(Calendar.YEAR, -amount)
			unit.startsWith("mo") -> cal.add(Calendar.MONTH, -amount)
			unit.startsWith("w") -> cal.add(Calendar.WEEK_OF_YEAR, -amount)
			unit.startsWith("d") -> cal.add(Calendar.DAY_OF_YEAR, -amount)
			unit.startsWith("h") -> cal.add(Calendar.HOUR_OF_DAY, -amount)
			unit.startsWith("m") -> cal.add(Calendar.MINUTE, -amount)
			unit.startsWith("s") -> cal.add(Calendar.SECOND, -amount)
		}
		return cal.timeInMillis
	}

	private companion object {
		private val NEXT_F_PUSH_REGEX = Regex("""self\.__next_f\.push\(\[\s*1\s*,\s*"(.*?)"\s*]\)""", RegexOption.DOT_MATCHES_ALL)
		private val SERIES_CARD_REGEX = Regex(""""href":"(/series/([^"/]+)/?)"(.*?)(?="href":"/series/|\Z)""", RegexOption.DOT_MATCHES_ALL)
		private val TITLE_REGEX = Regex(""""title":"((?:\\.|[^"])*)${'"'}""")
		private val ALT_REGEX = Regex(""""alt":"((?:\\.|[^"])*)${'"'}""")
		private val SRC_REGEX = Regex(""""src":"((?:\\.|[^"])*)${'"'}""")
		private val STATUS_REGEX = Regex(""""children":"(Completed|Ongoing)${'"'}""", RegexOption.IGNORE_CASE)
		private val PAGES_REGEX = Regex(""""pages":\s*(\[\{.*?\}])""")
		private val PAGE_IMG_REGEX = Regex(""""url":"(/img/p/[^"]+)${'"'}""")
		private val CHAPTER_NUMBER_REGEX = Regex("""(?:Ch\.|Chapter)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
		private val RELATIVE_DATE_REGEX = Regex("""^(\d+)\s*(s|m|h|d|w|mo|mos|y|yr|yrs|min|mins|sec|secs|hr|hrs|day|days|week|weeks|month|months|year|years)""")
		private val AUTHOR_PREFIX_REGEX = Regex("""^(?:art\s+by|story\s+by)\s+""", RegexOption.IGNORE_CASE)
		private val GENRE_OPTION_REGEX = Regex(""""value":"([a-zA-Z0-9_-]+)","label":"([^"]+)${'"'}""")
		private val TAG_COUNT_REGEX = Regex("""\s*\(\d+\)$""")
		private val VOLUME_REGEX = Regex("""(?:Volume|Vol\.)\s*(\d+)""", RegexOption.IGNORE_CASE)
	}
}
