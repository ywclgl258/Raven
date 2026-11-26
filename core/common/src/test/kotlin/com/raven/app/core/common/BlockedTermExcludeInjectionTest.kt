package com.raven.app.core.common

import com.raven.app.core.model.AdvancedSearchFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedTermExcludeInjectionTest {

    private fun testDictionary(): TagDictionary = TagDictionary(
        mapOf(
            "big-breasts" to "巨乳",
            "stockings" to "丝袜",
            "tentacles" to "触手",
        ),
    )

    @Test
    fun `feed filters gain localized blocked terms as server exclude slugs`() {
        val dict = testDictionary()
        val filters = AdvancedSearchFilters(excludeTags = listOf("guro"))

        val injected = BlockedTermExcludeInjection.apply(
            filters = filters,
            effectiveBlockedTerms = listOf("巨乳", "触手"),
            tagDictionary = dict,
        )

        assertEquals(listOf("guro", "big-breasts", "tentacles"), injected.excludeTags)
        // Existing user filters are preserved for every other field.
        assertEquals(filters.includeTags, injected.includeTags)
        assertEquals(filters.publishedFrom, injected.publishedFrom)
    }

    @Test
    fun `no blocked terms returns the very same filter instance`() {
        val filters = AdvancedSearchFilters(excludeTags = listOf("guro"))
        assertSame(
            filters,
            BlockedTermExcludeInjection.apply(filters, emptyList(), testDictionary()),
        )
    }

    @Test
    fun `already-covered terms do not duplicate and the term budget is respected`() {
        val dict = testDictionary()
        val filters = AdvancedSearchFilters(excludeTags = listOf("Big-Breasts", " guro "))

        val injected = BlockedTermExcludeInjection.apply(
            filters = filters,
            effectiveBlockedTerms = listOf("巨乳", "巨乳", "丝袜"),
            tagDictionary = dict,
            maxTerms = 3,
        )

        assertEquals(listOf("Big-Breasts", "guro", "stockings"), injected.excludeTags)
        assertEquals(3, injected.excludeTags.size)
    }

    @Test
    fun `unknown terms contribute nothing but never break existing excludes`() {
        val dict = testDictionary()
        val filters = AdvancedSearchFilters(excludeTags = listOf("guro"))
        val injected = BlockedTermExcludeInjection.apply(
            filters = filters,
            effectiveBlockedTerms = listOf("不存在的标签"),
            tagDictionary = dict,
        )
        assertTrue(injected.excludeTags.contains("guro"))
        assertEquals(filters.excludeTags, injected.excludeTags)
    }
}
