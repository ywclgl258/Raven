package com.raven.app.feature.reader

import com.raven.app.core.common.ContentFilter
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.model.GalleryDetail
import com.raven.app.core.model.LikedTagWeight
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.TagRef
import com.raven.app.core.database.domain.LikeRecommendationEngine

/** Inputs the ViewModel tracks for similar-work ranking. */
internal data class SimilarRankingInputs(
    val likedTagWeights: List<LikedTagWeight>,
    val tagHintKeysByItem: Map<String, Set<String>>,
    val authorCandidateKeys: Set<String>,
    val titleCandidateKeys: Set<String>,
)

/**
 * Similar-work ranking: blocklist filtering plus the shared like/author/title recommendation engine.
 *
 * Extracted from [GalleryDetailViewModel] as pure functions so ranking can be unit-tested without a
 * ViewModel.
 */

internal fun filterRelatedCandidates(
        raw: List<MediaItem>,
        blockedTerms: Set<String>,
        cachedTagsByKey: Map<String, List<TagRef>>,
        tagDictionary: TagDictionary,
    ): List<MediaItem> {
        if (raw.isEmpty() || blockedTerms.isEmpty()) return raw
        return raw.filter { item ->
            ContentFilter.isAllowed(
                item = item,
                blockedTerms = blockedTerms,
                tagDictionary = tagDictionary,
                extraTags = cachedTagsByKey[item.compositeKey].orEmpty(),
            )
        }
    }

internal fun rankSimilarRelated(
        detail: GalleryDetail,
        candidates: List<MediaItem>,
        blockedTerms: Set<String>,
        cachedTagsByKey: Map<String, List<TagRef>>,
        tagDictionary: TagDictionary,
        inputs: SimilarRankingInputs,
    ): List<MediaItem> {
        val allowed = filterRelatedCandidates(
            raw = candidates,
            blockedTerms = blockedTerms,
            cachedTagsByKey = cachedTagsByKey,
            tagDictionary = tagDictionary,
        )
        val authors = buildList {
            detail.authors.mapTo(this) { it.name }
            detail.item.author?.name?.let(::add)
        }
        val sourceTags = detail.tags + detail.characters + detail.categories + detail.item.tags
        return LikeRecommendationEngine.rankSimilarWorks(
            sourceTitle = detail.item.title,
            sourceAuthors = authors,
            sourceTags = sourceTags,
            sourceCompositeKey = detail.item.compositeKey,
            sourceMediaId = detail.item.id,
            candidates = allowed,
            likedTagWeights = inputs.likedTagWeights,
            cachedTagsByKey = cachedTagsByKey,
            tagHintKeysByItem = inputs.tagHintKeysByItem,
            authorCandidateKeys = inputs.authorCandidateKeys,
            titleCandidateKeys = inputs.titleCandidateKeys,
            limit = 18,
        )
    }
