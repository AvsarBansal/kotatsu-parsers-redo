package org.koitharu.kotatsu.parsers.site.fr

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.test_util.mangaOf

internal class AniverseParserTest {

	private fun newParser(): MangaParser =
		MangaLoaderContextMock.newParserInstance(MangaParserSource.ANIVERSE)

	@Test
	fun list() = runTest {
		val list = newParser().getList(0, SortOrder.UPDATED, MangaListFilter.EMPTY)
		assertEquals(28, list.size)
		assertTrue(list.all { it.title.isNotBlank() })
		assertTrue(list.all { it.url.startsWith("/manga/") && it.publicUrl.startsWith("https://") })
		assertNotNull(list.first().coverUrl)
	}

	@Test
	fun listByGenre() = runTest {
		val parser = newParser()
		val tag = parser.getFilterOptions().availableTags.first { it.key == "Romance" }
		val list = parser.getList(0, SortOrder.UPDATED, MangaListFilter(tags = setOf(tag)))
		assertTrue(list.isNotEmpty())
	}

	@Test
	fun detailsChaptersAndPages() = runTest {
		val parser = newParser()
		val details = parser.getDetails(mangaOf(MangaParserSource.ANIVERSE, "/manga/chainsaw-man"))
		assertEquals("Chainsaw Man", details.title)
		assertNotNull(details.coverUrl)
		assertNotNull(details.description)
		assertTrue(details.authors.isNotEmpty())
		assertTrue(details.rating in 0f..1f)
		assertTrue(details.tags.isNotEmpty())

		val chapters = details.chapters
		assertNotNull(chapters)
		assertTrue(chapters!!.size > 100, "expected > 100 chapters, got ${chapters.size}")
		assertEquals(1f, chapters.first().number)
		assertTrue(chapters.last().number > chapters.first().number)

		val pages = parser.getPages(chapters.last())
		assertTrue(pages.isNotEmpty())
		assertTrue(pages.all { it.url.startsWith("https://cdn.onefy.me/") })
	}

	@Test
	fun search() = runTest {
		val results = newParser().getList(0, SortOrder.UPDATED, MangaListFilter(query = "chainsaw man"))
		assertTrue(results.isNotEmpty())
		assertTrue(results.any { it.url == "/manga/chainsaw-man" })
	}
}
