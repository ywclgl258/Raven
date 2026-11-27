package com.raven.app.core.database.domain

import com.raven.app.core.model.AuthorRef
import com.raven.app.core.model.LikedMedia
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.TagRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LikeRecommendationEngineTest {

    private val now = 1_750_000_000_000L
    private val dayMs = 86_400_000L

    @Test
    fun `computeTagWeights prioritizes frequent and recent content tags over generic metadata`() {
        val likes = listOf(
            LikedMedia(
                siteId = SiteId.NHENTAI,
                mediaId = "1",
                kind = MediaKind.COMIC,
                title = "Work A",
                url = "https://example.test/1",
                likedAt = now - dayMs,
                tags = listOf(
                    TagRef(label = "chinese", slug = "chinese", namespace = "language"),
                    TagRef(label = "glasses", slug = "glasses", namespace = "tag"),
                    TagRef(label = "alice", slug = "alice", namespace = "character"),
                ),
            ),
            LikedMedia(
                siteId = SiteId.NHENTAI,
                mediaId = "2",
                kind = MediaKind.COMIC,
                title = "Work B",
                url = "https://example.test/2",
                likedAt = now - 2 * dayMs,
                tags = listOf(
                    TagRef(label = "chinese", slug = "chinese", namespace = "language"),
                    TagRef(label = "glasses", slug = "glasses", namespace = "tag"),
                ),
            ),
        )

        val weights = LikeRecommendationEngine.computeTagWeights(likes, nowMs = now)
        assertTrue(weights.isNotEmpty())
        assertEquals("glasses", weights.first().tag.slug)
        val glassesIdx = weights.indexOfFirst { it.tag.slug == "glasses" }
        val chineseIdx = weights.indexOfFirst { it.tag.slug == "chinese" }
        assertTrue("Content tag should outrank generic language tag", glassesIdx < chineseIdx)
    }

    @Test
    fun `rankSimilarWorks strictly orders same author then same title then high weight tags`() {
        val likedWeights = LikeRecommendationEngine.computeTagWeights(
            likes = listOf(
                LikedMedia(
                    siteId = SiteId.NHENTAI,
                    mediaId = "seed",
                    kind = MediaKind.COMIC,
                    title = "Seed",
                    url = "https://example.test/seed",
                    likedAt = now,
                    tags = listOf(TagRef(label = "mecha", slug = "mecha", namespace = "tag")),
                ),
            ),
            nowMs = now,
        )

        val highWeightTagWork = MediaItem(
            siteId = SiteId.NHENTAI,
            id = "tag_hit",
            url = "https://example.test/tag",
            kind = MediaKind.COMIC,
            title = "Unrelated Title",
            author = AuthorRef(id = "other", name = "Other Artist"),
            tags = listOf(TagRef(label = "mecha", slug = "mecha", namespace = "tag")),
        )
        val sameTitleWork = MediaItem(
            siteId = SiteId.NHENTAI,
            id = "title_hit",
            url = "https://example.test/title",
            kind = MediaKind.COMIC,
            title = "[汉化组] Starlight Chronicles 第2话 [Chinese]",
            author = AuthorRef(id = "another", name = "Another Artist"),
            tags = emptyList(),
        )
        val sameAuthorWork = MediaItem(
            siteId = SiteId.NHENTAI,
            id = "author_hit",
            url = "https://example.test/author",
            kind = MediaKind.COMIC,
            title = "Completely Different Story",
            author = AuthorRef(id = "tanaka", name = "Sensei Tanaka"),
            tags = emptyList(),
        )
        val lowTagWork = MediaItem(
            siteId = SiteId.NHENTAI,
            id = "low_hit",
            url = "https://example.test/low",
            kind = MediaKind.COMIC,
            title = "Random Gallery",
            author = AuthorRef(id = "nobody", name = "Nobody"),
            tags = listOf(TagRef(label = "translated", slug = "translated", namespace = "language")),
        )

        val ranked = LikeRecommendationEngine.rankSimilarWorks(
            sourceTitle = "(C108) [Circle] Starlight Chronicles 第1话 [Chinese]",
            sourceAuthors = listOf("Sensei Tanaka"),
            sourceTags = listOf(
                TagRef(label = "mecha", slug = "mecha", namespace = "tag"),
                TagRef(label = "translated", slug = "translated", namespace = "language"),
            ),
            sourceCompositeKey = "nhentai:source",
            sourceMediaId = "source",
            candidates = listOf(lowTagWork, highWeightTagWork, sameTitleWork, sameAuthorWork),
            likedTagWeights = likedWeights,
        )

        assertEquals(
            listOf("author_hit", "title_hit", "tag_hit", "low_hit"),
            ranked.map { it.id },
        )
    }

    @Test
    fun `rankSmartRecommendations excludes already liked works and scores by liked tags`() {
        val likes = listOf(
            LikedMedia(
                siteId = SiteId.NHENTAI,
                mediaId = "liked_1",
                kind = MediaKind.COMIC,
                title = "Liked One",
                url = "https://example.test/liked_1",
                authorName = "FavAuthor",
                likedAt = now,
                tags = listOf(
                    TagRef(label = "cyberpunk", slug = "cyberpunk"),
                    TagRef(label = "noir", slug = "noir"),
                ),
            ),
        )
        val weights = LikeRecommendationEngine.computeTagWeights(likes, nowMs = now)
        val candidates = listOf(
            MediaItem(
                siteId = SiteId.NHENTAI,
                id = "liked_1",
                url = "https://example.test/liked_1",
                kind = MediaKind.COMIC,
                title = "Liked One",
                tags = listOf(TagRef(label = "cyberpunk", slug = "cyberpunk")),
            ),
            MediaItem(
                siteId = SiteId.NHENTAI,
                id = "cand_one_tag",
                url = "https://example.test/c1",
                kind = MediaKind.COMIC,
                title = "One Tag",
                tags = listOf(TagRef(label = "noir", slug = "noir")),
            ),
            MediaItem(
                siteId = SiteId.NHENTAI,
                id = "cand_two_tags",
                url = "https://example.test/c2",
                kind = MediaKind.COMIC,
                title = "Two Tags",
                tags = listOf(
                    TagRef(label = "cyberpunk", slug = "cyberpunk"),
                    TagRef(label = "noir", slug = "noir"),
                ),
            ),
        )

        val recs = LikeRecommendationEngine.rankSmartRecommendations(
            likes = likes,
            tagWeights = weights,
            candidates = candidates,
        )
        assertEquals(listOf("cand_two_tags", "cand_one_tag"), recs.map { it.item.id })
    }
}
