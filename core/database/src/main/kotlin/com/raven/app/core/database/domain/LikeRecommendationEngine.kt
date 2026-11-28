package com.raven.app.core.database.domain

import com.raven.app.core.model.LikedMedia
import com.raven.app.core.model.LikedTagWeight
import com.raven.app.core.model.MediaItem
import com.raven.app.core.model.TagRef
import java.util.Locale
import kotlin.math.exp

/**
 * Pure domain recommendation & ranking engine powered by local [LikedMedia] history.
 *
 * Responsibilities:
 * 1. Analyze tags from liked works with recency decay and namespace weighting to produce sorted [LikedTagWeight]s.
 * 2. Rank similar works strictly by priority:
 *    - Tier 3: Same author / circle
 *    - Tier 2: Same title / same series (after stripping translation/group/chapter noise)
 *    - Tier 1: High-weight shared tags (weighted by user's liked tag profile + source item overlap)
 * 3. Score smart recommendation candidates for the Library Hub from liked tags & authors.
 */
object LikeRecommendationEngine {

    private const val RECENCY_HALF_LIFE_DAYS: Double = 45.0
    private const val MILLIS_PER_DAY: Double = 86_400_000.0
    private const val MIN_RECENCY_FACTOR: Double = 0.35

    private val LOW_SIGNAL_NAMESPACES: Set<String> = setOf(
        "language",
        "lang",
        "category",
        "reclass",
        "rows",
    )

    private val LOW_SIGNAL_SLUGS: Set<String> = setOf(
        "translated",
        "chinese",
        "japanese",
        "english",
        "korean",
        "rewrite",
        "speechless",
        "textless",
        "uncensored",
        "full color",
        "full_color",
        "mosaic censorship",
        "mosaic_censorship",
        "doujinshi",
        "manga",
    )

    private val BRACKET_NOISE_REGEX = Regex("""\[[^\]]*\]|\([^)]*\)|【[^】]*】|（[^）]*）""")
    private val CHAPTER_NOISE_REGEX = Regex(
        """(?i)(?:第\s*\d+\s*[话話章卷集回部期]|(?:ch|chapter|ep|episode|vol|volume|part)\.?\s*\d+|\b\d+\s*[p页頁]\b)""",
    )
    private val NON_WORD_REGEX = Regex("""[^\p{L}\p{N}]+""")
    private val TITLE_SEPARATORS_REGEX = Regex("""[|｜/／\-—–_:：]+""")
    private val WHITESPACE_RUN_REGEX = Regex("""\s+""")

    enum class SimilarMatchTier(val priority: Int, val label: String) {
        SAME_AUTHOR(3, "同作者"),
        SAME_TITLE(2, "同名系列"),
        HIGH_WEIGHT_TAG(1, "高权重同标签"),
        FALLBACK(0, "相关推荐"),
    }

    data class RankedRecommendation(
        val item: MediaItem,
        val tier: SimilarMatchTier,
        val score: Double,
        val matchedTags: List<String> = emptyList(),
        val reason: String = "",
    )

    /**
     * Aggregates tags across all locally liked works into a sorted, normalized weight profile.
     */
    fun computeTagWeights(
        likes: List<LikedMedia>,
        nowMs: Long = System.currentTimeMillis(),
        limit: Int = 40,
    ): List<LikedTagWeight> {
        if (likes.isEmpty()) return emptyList()

        data class Accumulator(
            var representative: TagRef,
            var weight: Double = 0.0,
            var count: Int = 0,
            var lastLikedAt: Long = 0L,
        )

        val byKey = LinkedHashMap<String, Accumulator>()
        for (like in likes) {
            val ageDays = ((nowMs - like.likedAt).coerceAtLeast(0L)).toDouble() / MILLIS_PER_DAY
            val recency = exp(-ageDays / RECENCY_HALF_LIFE_DAYS).coerceIn(MIN_RECENCY_FACTOR, 1.0)
            val seenInWork = HashSet<String>()
            for (tag in like.tags) {
                val key = canonicalTagKey(tag) ?: continue
                if (!seenInWork.add(key)) continue
                val baseWeight = tagSignalMultiplier(tag)
                val contribution = baseWeight * recency
                val acc = byKey.getOrPut(key) { Accumulator(representative = tag) }
                acc.weight += contribution
                acc.count += 1
                if (like.likedAt >= acc.lastLikedAt) {
                    acc.lastLikedAt = like.likedAt
                    if (tag.label.length >= acc.representative.label.length) {
                        acc.representative = tag
                    }
                }
            }
        }

        if (byKey.isEmpty()) return emptyList()
        val sorted = byKey.values
            .sortedWith(
                compareByDescending<Accumulator> { it.weight }
                    .thenByDescending { it.count }
                    .thenByDescending { it.lastLikedAt },
            )
            .take(limit)
        val maxWeight = sorted.firstOrNull()?.weight?.takeIf { it > 0.0 } ?: 1.0
        return sorted.map { acc ->
            LikedTagWeight(
                tag = acc.representative,
                weight = acc.weight,
                normalizedWeight = (acc.weight / maxWeight).toFloat().coerceIn(0f, 1f),
                likeCount = acc.count,
                lastLikedAt = acc.lastLikedAt,
            )
        }
    }

    /**
     * Selects the highest-priority tags of a work (preferring user's liked tag weights first, then
     * informative namespaces over generic language/category metadata).
     */
    fun selectPrioritizedTags(
        sourceTags: List<TagRef>,
        likedTagWeights: List<LikedTagWeight>,
        limit: Int = 3,
    ): List<TagRef> {
        if (sourceTags.isEmpty()) return emptyList()
        val likedScoreByKey = likedTagWeights.associate { (canonicalTagKey(it.tag) ?: "") to it.weight }
        return sourceTags
            .mapNotNull { tag ->
                val key = canonicalTagKey(tag) ?: return@mapNotNull null
                val score = (likedScoreByKey[key] ?: 0.0) * 2.5 + tagSignalMultiplier(tag)
                key to (tag to score)
            }
            .distinctBy { it.first }
            .sortedByDescending { it.second.second }
            .take(limit)
            .map { it.second.first }
    }

    /**
     * Extracts a clean search query representing the core work/series title without group/language/chapter noise.
     */
    fun extractCoreTitleQuery(title: String): String {
        val stripped = BRACKET_NOISE_REGEX.replace(title, " ")
            .let { CHAPTER_NOISE_REGEX.replace(it, " ") }
            .let { TITLE_SEPARATORS_REGEX.replace(it, " ") }
            .trim()
            .let { WHITESPACE_RUN_REGEX.replace(it, " ") }
        val candidate = if (stripped.length >= 2) stripped else title.trim()
        return candidate.take(32).trim()
    }

    /**
     * Ranks similar works with strict 3-tier priority:
     * 1. Same author / circle
     * 2. Same title / same series
     * 3. High-weight shared tags (guided by local likes + source tag overlap)
     */
    fun rankSimilarWorks(
        sourceTitle: String,
        sourceAuthors: Collection<String>,
        sourceTags: List<TagRef>,
        sourceCompositeKey: String,
        sourceMediaId: String,
        candidates: List<MediaItem>,
        likedTagWeights: List<LikedTagWeight> = emptyList(),
        cachedTagsByKey: Map<String, List<TagRef>> = emptyMap(),
        tagHintKeysByItem: Map<String, Set<String>> = emptyMap(),
        authorCandidateKeys: Set<String> = emptySet(),
        titleCandidateKeys: Set<String> = emptySet(),
        limit: Int = 18,
    ): List<MediaItem> {
        if (candidates.isEmpty()) return emptyList()

        val normalizedAuthors = sourceAuthors
            .map(::normalizeToken)
            .filter { it.length >= 2 }
            .toSet()
        val normalizedSourceTitle = normalizeTitleForSimilarity(sourceTitle)
        val sourceTitleTokens = titleTokens(normalizedSourceTitle)

        val likedWeightMap = likedTagWeights.associate { (canonicalTagKey(it.tag) ?: "") to it.weight }
        val sourceTagKeys = sourceTags.mapNotNull(::canonicalTagKey).toSet()

        val sourceSitePrefix = sourceCompositeKey.substringBefore(':', "")
        val ranked = candidates
            .asSequence()
            .filterNot {
                it.compositeKey == sourceCompositeKey ||
                    (it.id == sourceMediaId && (sourceSitePrefix.isEmpty() || it.siteId.value == sourceSitePrefix))
            }
            .distinctBy { it.compositeKey }
            .map { item ->
                val itemAuthorNorm = normalizeToken(item.author?.name.orEmpty())
                val itemTitleNorm = normalizeTitleForSimilarity(item.title)
                val itemTitleTokens = titleTokens(itemTitleNorm)

                val isSameAuthor = item.compositeKey in authorCandidateKeys ||
                    (itemAuthorNorm.length >= 2 && itemAuthorNorm in normalizedAuthors) ||
                    (normalizedAuthors.any { author -> author.length >= 3 && item.title.lowercase(Locale.ROOT).contains(author) })

                val titleSim = computeTitleSimilarity(
                    sourceNorm = normalizedSourceTitle,
                    sourceTokens = sourceTitleTokens,
                    targetNorm = itemTitleNorm,
                    targetTokens = itemTitleTokens,
                )
                val isSameTitle = item.compositeKey in titleCandidateKeys && titleSim >= 0.35 || titleSim >= 0.55

                val combinedTags = buildList {
                    addAll(item.tags)
                    cachedTagsByKey[item.compositeKey]?.let(::addAll)
                }
                val itemTagKeys = combinedTags.mapNotNull(::canonicalTagKey).toMutableSet()
                tagHintKeysByItem[item.compositeKey]?.let(itemTagKeys::addAll)

                var tagScore = 0.0
                for (key in itemTagKeys) {
                    val likedBoost = (likedWeightMap[key] ?: 0.0) * 1.8
                    val sharedBoost = if (key in sourceTagKeys) 2.2 else 0.0
                    val signal = if (key in LOW_SIGNAL_SLUGS) 0.25 else 1.0
                    tagScore += (sharedBoost + likedBoost) * signal
                }

                val tier = when {
                    isSameAuthor -> SimilarMatchTier.SAME_AUTHOR
                    isSameTitle -> SimilarMatchTier.SAME_TITLE
                    tagScore > 0.0 -> SimilarMatchTier.HIGH_WEIGHT_TAG
                    else -> SimilarMatchTier.FALLBACK
                }
                val compositeScore = tagScore + titleSim * 4.0 + if (isSameAuthor) 10.0 else 0.0
                RankedRecommendation(
                    item = item,
                    tier = tier,
                    score = compositeScore,
                )
            }
            .sortedWith(
                compareByDescending<RankedRecommendation> { it.tier.priority }
                    .thenByDescending { it.score },
            )
            .take(limit)
            .map { it.item }
            .toList()

        return ranked
    }

    /**
     * Scores candidate works for the Library Hub "智能推荐" tab purely from local like history.
     */
    fun rankSmartRecommendations(
        likes: List<LikedMedia>,
        tagWeights: List<LikedTagWeight>,
        candidates: List<MediaItem>,
        sourceTagHintByKey: Map<String, Set<String>> = emptyMap(),
        cachedTagsByKey: Map<String, List<TagRef>> = emptyMap(),
        limit: Int = 36,
    ): List<RankedRecommendation> {
        if (likes.isEmpty() || candidates.isEmpty()) return emptyList()

        val likedKeys = likes.mapTo(HashSet()) { it.compositeKey }
        val likedAuthors = likes
            .map { normalizeToken(it.authorName) }
            .filter { it.length >= 2 }
            .groupingBy { it }
            .eachCount()

        val weightByKey = tagWeights.associateBy { canonicalTagKey(it.tag) ?: "" }

        return candidates
            .asSequence()
            .filterNot { it.compositeKey in likedKeys }
            .distinctBy { it.compositeKey }
            .map { item ->
                val itemTags = buildList {
                    addAll(item.tags)
                    cachedTagsByKey[item.compositeKey]?.let(::addAll)
                }
                val keys = itemTags.mapNotNull(::canonicalTagKey).toMutableSet()
                sourceTagHintByKey[item.compositeKey]?.let(keys::addAll)

                val matchedLabels = ArrayList<Pair<String, Double>>()
                var score = 0.0
                for (key in keys) {
                    val weightEntry = weightByKey[key]
                    if (weightEntry != null) {
                        score += weightEntry.weight * 2.0
                        matchedLabels += (weightEntry.tag.label.ifBlank { weightEntry.tag.slug }) to weightEntry.weight
                    }
                }

                val authorNorm = normalizeToken(item.author?.name.orEmpty())
                val authorHits = likedAuthors[authorNorm] ?: 0
                if (authorHits > 0) {
                    score += authorHits * 3.2
                }

                val topMatched = matchedLabels
                    .sortedByDescending { it.second }
                    .map { it.first }
                    .distinct()
                    .take(3)

                val reason = buildString {
                    if (authorHits > 0 && !item.author?.name.isNullOrBlank()) {
                        append("偏好作者 ${item.author?.name}")
                    }
                    if (topMatched.isNotEmpty()) {
                        if (isNotEmpty()) append(" · ")
                        append("匹配 ${topMatched.joinToString(" / ")}")
                    }
                }

                RankedRecommendation(
                    item = item,
                    tier = if (authorHits > 0) SimilarMatchTier.SAME_AUTHOR else SimilarMatchTier.HIGH_WEIGHT_TAG,
                    score = score,
                    matchedTags = topMatched,
                    reason = reason,
                )
            }
            .filter { it.score > 0.0 }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }

    fun canonicalTagKey(tag: TagRef): String? {
        val raw = tag.slug.trim().ifEmpty { tag.label.trim() }.lowercase(Locale.ROOT)
        if (raw.isEmpty()) return null
        return WHITESPACE_RUN_REGEX.replace(raw.replace('_', ' '), " ")
    }

    private fun tagSignalMultiplier(tag: TagRef): Double {
        val ns = tag.namespace.trim().lowercase(Locale.ROOT)
        val slug = canonicalTagKey(tag).orEmpty()
        if (ns in LOW_SIGNAL_NAMESPACES || slug in LOW_SIGNAL_SLUGS) return 0.22
        return when (ns) {
            "artist", "author", "group", "circle" -> 1.35
            "character", "parody", "series" -> 1.25
            "female", "male", "tag", "" -> 1.0
            else -> 0.85
        }
    }

    private fun normalizeToken(raw: String): String =
        NON_WORD_REGEX.replace(raw.trim().lowercase(Locale.ROOT), "")

    private fun normalizeTitleForSimilarity(raw: String): String {
        val core = extractCoreTitleQuery(raw).lowercase(Locale.ROOT)
        return NON_WORD_REGEX.replace(core, " ").trim()
    }

    private fun titleTokens(normalized: String): Set<String> {
        if (normalized.isBlank()) return emptySet()
        val words = normalized.split(' ').filter { it.length >= 2 }.toMutableSet()
        // Add CJK bigrams so Chinese/Japanese titles without spaces match reliably.
        val compact = normalized.replace(" ", "")
        if (compact.length >= 2) {
            for (i in 0 until compact.length - 1) {
                val c1 = compact[i]
                val c2 = compact[i + 1]
                if (!c1.isLetterOrDigit() || c1.code > 127 || c2.code > 127) {
                    words.add("$c1$c2")
                }
            }
        }
        return words
    }

    private fun computeTitleSimilarity(
        sourceNorm: String,
        sourceTokens: Set<String>,
        targetNorm: String,
        targetTokens: Set<String>,
    ): Double {
        if (sourceNorm.length < 2 || targetNorm.length < 2) return 0.0
        val sourceCompact = sourceNorm.replace(" ", "")
        val targetCompact = targetNorm.replace(" ", "")
        if (sourceCompact == targetCompact) return 1.0
        if (sourceCompact.length >= 3 && targetCompact.contains(sourceCompact)) return 0.85
        if (targetCompact.length >= 3 && sourceCompact.contains(targetCompact)) return 0.80
        if (sourceTokens.isEmpty() || targetTokens.isEmpty()) return 0.0
        val intersection = sourceTokens.count { it in targetTokens }
        if (intersection == 0) return 0.0
        val union = sourceTokens.size + targetTokens.size - intersection
        return if (union <= 0) 0.0 else intersection.toDouble() / union.toDouble()
    }
}
