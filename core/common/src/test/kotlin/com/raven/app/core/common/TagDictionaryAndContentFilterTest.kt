package com.raven.app.core.common

import com.raven.app.core.model.BlockedTerm
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import com.raven.app.core.model.TagTranslationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagDictionaryAndContentFilterTest {

    private fun testDictionary(): TagDictionary = TagDictionary(
        mapOf(
            "big-breasts" to "巨乳",
            "stockings" to "丝袜",
            "tentacles" to "触手",
            "glasses" to "眼镜",
            "blonde" to "金发",
            "elf" to "精灵",
            "maid" to "女仆",
            "futanari" to "扶她",
            "futa" to "扶她",
        ),
    )

    @Test
    fun `four-tier tag dictionary enforces custom over community over builtin and normalizes inflections`() {
        val dict = testDictionary()
        dict.updateDynamicLayers(
            communityBare = mapOf(
                "black" to "黑色",
                "latex" to "乳胶",
                "girl" to "少女",
                "stockings" to "长筒袜",
                "fox" to "狐狸",
            ),
            communityNamespaced = mapOf(
                "female:stockings" to "女式长袜",
                "character:rem" to "蕾姆",
            ),
            customBare = mapOf(
                "elf" to "高阶精灵",
            ),
            customNamespaced = mapOf(
                "female:stockings" to "自定义丝袜",
            ),
        )

        // Tier 1: User Custom namespaced > bare
        assertEquals("自定义丝袜", dict.translate("female:stockings"))
        assertEquals("自定义丝袜", dict.translate("stockings", namespace = "female"))
        assertEquals("高阶精灵", dict.translate("ELF"))
        // Tier 2: Community bare & namespaced beat built-in
        assertEquals("长筒袜", dict.translate("stockings"))
        assertEquals("长筒袜", dict.translate("stocking")) // plural fallback -> stockings
        assertEquals("蕾姆", dict.translate("character:rem"))
        assertEquals("狐狸", dict.translate("foxes")) // -es singularization
        // Tier 3: Built-in with separator & plural/singular normalization
        assertEquals("巨乳", dict.translate("female:big_breasts"))
        assertEquals("巨乳", dict.translate("big-breast")) // plural fallback -> big breasts
        assertEquals("眼镜", dict.translate("glasses"))
        // Tier 4: Compound-word smart splitter
        assertEquals("黑色 乳胶 长筒袜", dict.translate("black_latex_stockings"))
        assertEquals("金发 高阶精灵 少女", dict.translate("blonde-elf-girl"))
        assertEquals("巨乳 女仆", dict.translate("big breasts maid"))
        // Avoids ugly partial gibberish when <2 segments match or <50% tokens translate
        assertNull(dict.translate("blonde unknownword"))
        assertNull(dict.translate("blonde elf foo bar baz"))
        assertNull(dict.translate("totally unknown tag"))

        // Reset restores built-in only
        dict.resetDynamicLayers()
        assertEquals(9, dict.size)
        assertEquals("丝袜", dict.translate("female:stockings"))
        assertEquals("精灵", dict.translate("elf"))
    }

    @Test
    fun `expandSynonyms, canonicalEnglishSlugs, and cross-layer suggestions work accurately`() {
        val dict = testDictionary()
        dict.updateDynamicLayers(
            communityBare = mapOf("sole female" to "单女主", "netorare" to "牛头人"),
            customBare = mapOf("custom_tag" to "自定义标签"),
        )

        val zhExpanded = dict.expandSynonyms("触手")
        assertTrue("触手" in zhExpanded)
        assertTrue("tentacles" in zhExpanded)

        val enExpanded = dict.expandSynonyms("tentacles")
        assertEquals(setOf("tentacles", "触手"), enExpanded)

        val nsExpanded = dict.expandSynonyms("female:stockings")
        assertEquals(setOf("female:stockings", "stockings", "丝袜"), nsExpanded)

        assertEquals(listOf("tentacles"), dict.canonicalEnglishSlugs("触手"))
        assertEquals(listOf("futanari", "futa"), dict.canonicalEnglishSlugs("扶她"))
        assertEquals(listOf("netorare"), dict.canonicalEnglishSlugs("牛头人"))
        assertEquals(listOf("big breasts"), dict.canonicalEnglishSlugs("big_breasts"))
        assertTrue(dict.canonicalEnglishSlugs("不存在的中文词").isEmpty())

        val enSuggest = dict.suggestions("custom", limit = 12)
        assertTrue(enSuggest.any { it.slug == "custom tag" && it.localizedName == "自定义标签" && it.source == TagTranslationSource.USER_CUSTOM })

        val zhSuggest = dict.suggestions("牛头", limit = 12)
        assertTrue(zhSuggest.any { it.slug == "netorare" && it.localizedName == "牛头人" && it.source == TagTranslationSource.COMMUNITY_EHTAG })
    }

    @Test
    fun `content filter handles underscores namespaces bilingual synonyms cached extra tags and global scope`() {
        val dict = testDictionary()
        val itemWithNsTag = MediaItem(
            siteId = SiteId.EHENTAI,
            id = "g1",
            url = "https://e-hentai.org/g/1/abc/",
            kind = MediaKind.COMIC,
            title = "Fantasy Story",
            tags = listOf(TagRef(slug = "female:big_breasts", label = "big_breasts", namespace = "female")),
        )
        val itemWithNoCardTags = MediaItem(
            siteId = SiteId.R34V,
            id = "v2",
            url = "https://rule34video.com/video/2/clip/",
            kind = MediaKind.VIDEO,
            title = "Untitled Clip",
            tags = emptyList(),
        )
        val itemWithChineseTitle = MediaItem(
            siteId = SiteId.HANIME1,
            id = "h3",
            url = "https://hanime1.me/watch?v=3",
            kind = MediaKind.VIDEO,
            title = "巨乳女仆物语",
            tags = emptyList(),
        )

        // Underscore / hyphen / namespace normalization
        assertFalse(ContentFilter.isAllowed(itemWithNsTag, listOf("big-breasts"), dict))
        assertFalse(ContentFilter.isAllowed(itemWithNsTag, listOf("big breasts"), dict))
        assertFalse(ContentFilter.isAllowed(itemWithNsTag, listOf("female:big breasts"), dict))

        // Bilingual Chinese -> English blocking
        assertFalse(ContentFilter.isAllowed(itemWithNsTag, listOf("巨乳"), dict))
        // Bilingual English -> Chinese blocking
        assertFalse(ContentFilter.isAllowed(itemWithChineseTitle, listOf("big_breasts"), dict))

        // Cached detail-page extraTags block items whose feed cards omit tags
        assertTrue(ContentFilter.isAllowed(itemWithNoCardTags, listOf("触手"), dict))
        assertFalse(
            ContentFilter.isAllowed(
                item = itemWithNoCardTags,
                blockedTerms = listOf("触手"),
                tagDictionary = dict,
                extraTags = listOf(TagRef(slug = "female:tentacles", label = "tentacles", namespace = "female")),
            ),
        )

        // SiteId.GLOBAL merges across all sites alongside per-site terms and extraTagsByMediaKey
        val filtered = ContentFilter.filterAllowed(
            items = listOf(itemWithNsTag, itemWithNoCardTags, itemWithChineseTitle),
            blockedTerms = listOf(
                BlockedTerm(SiteId.GLOBAL, "触手"),
                BlockedTerm(SiteId.EHENTAI, "巨乳"),
            ),
            tagDictionary = dict,
            extraTagsByMediaKey = mapOf(
                itemWithNoCardTags.compositeKey to listOf(TagRef(slug = "tentacles")),
            ),
        )
        // itemWithNsTag blocked by EHENTAI "巨乳"; itemWithNoCardTags blocked by GLOBAL "触手" via cache;
        // itemWithChineseTitle survives because "巨乳" was EHENTAI-only and "触手" doesn't match
        assertEquals(listOf(itemWithChineseTitle), filtered)
    }
}
