package com.raven.app.feature.reader

import com.raven.app.core.common.ContentFilter
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.TagRef

/**
 * Pure tag/author rules shared by [GalleryDetailViewModel] and [GalleryTagActionController].
 *
 * Kept as top-level internal functions so both the screen-level ViewModel and the tag action
 * collaborator apply exactly the same matching/label logic.
 */

/** Canonical subscription key; some plugins expose only a readable label without a slug. */
internal fun tagSubscriptionSlug(tag: TagRef): String =
    tag.slug.trim().ifBlank { tag.label.trim() }

/** Display label honouring the user's Chinese tag-translation preference. */
internal fun tagDisplayLabel(tag: TagRef, translateTags: Boolean, dictionary: TagDictionary): String {
    val raw = tag.label.ifBlank { tag.slug }
    if (!translateTags) return raw
    val translated = dictionary.translate(tag.slug, tag.namespace)
        ?: dictionary.translate(raw, tag.namespace)
    return translated ?: raw
}

internal fun tagMatchesBlockedTerms(
    tag: TagRef,
    blockedTerms: Set<String>,
    dictionary: TagDictionary,
): Boolean {
    if (blockedTerms.isEmpty()) return false
    return blockedTerms.any { tagMatchesBlockedTerm(tag, it, dictionary) }
}

internal fun tagMatchesBlockedTerm(
    tag: TagRef,
    blockedTerm: String,
    dictionary: TagDictionary,
): Boolean {
    val slugOrLabel = tag.slug.ifBlank { tag.label }
    val tagCandidates = buildSet {
        val norm = ContentFilter.normalizeTerm(slugOrLabel)
        if (norm.isNotEmpty()) add(norm)
        val bare = ContentFilter.canonicalizeForMatching(slugOrLabel)
        if (bare.isNotEmpty()) add(bare)
        addAll(dictionary.expandSynonyms(slugOrLabel))
        if (tag.label.isNotBlank() && tag.label != slugOrLabel) {
            addAll(dictionary.expandSynonyms(tag.label))
        }
    }
    val bNorm = ContentFilter.normalizeTerm(blockedTerm)
    val bBare = ContentFilter.canonicalizeForMatching(blockedTerm)
    if (bNorm in tagCandidates || bBare in tagCandidates) return true
    return dictionary.expandSynonyms(blockedTerm).any { it in tagCandidates }
}

/** Favorites store tags/works under a coarse [FavoriteKind], not the raw [MediaKind]. */
internal fun favoriteKindOf(kind: MediaKind): FavoriteKind = when (kind) {
    MediaKind.PHOTO -> FavoriteKind.PHOTO
    MediaKind.VIDEO -> FavoriteKind.VIDEO
    MediaKind.COMIC, MediaKind.SERIAL_COMIC -> FavoriteKind.COMIC
}
