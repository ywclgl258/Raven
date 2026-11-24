package com.raven.app.core.model

import kotlinx.serialization.Serializable

@Serializable
data class TagRef(
    val slug: String,
    val label: String = slug,
    val namespace: String = "",
    val url: String = "",
    val count: Int? = null,
)

@Serializable
data class AuthorRef(
    val id: String,
    val name: String,
    val role: String = "",
    val url: String = "",
    val avatarUrl: String = "",
)

@Serializable
data class DirectoryEntry(
    val siteId: SiteId,
    val id: String,
    val title: String,
    val url: String,
    val kind: SearchScope = SearchScope.ALL,
    val subtitle: String = "",
    val coverUrl: String = "",
    val itemCount: Int? = null,
)

@Serializable
data class CommentItem(
    val id: String,
    val author: String,
    val body: String,
    val timestampLabel: String = "",
    val avatarUrl: String = "",
    val likes: Int? = null,
)

/**
 * Core summary card model shared across feeds, search, library, shelves, and history.
 * Always requires an explicit [siteId].
 */
@Serializable
data class MediaItem(
    val siteId: SiteId,
    val id: String,
    val url: String,
    val kind: MediaKind,
    val title: String,
    val coverUrl: String = "",
    val coverAspectRatio: Float? = null,
    val durationSeconds: Int? = null,
    val pageCount: Int? = null,
    val views: Long? = null,
    val likes: Long? = null,
    val rating: Float? = null,
    val dateLabel: String? = null,
    val author: AuthorRef? = null,
    val tags: List<TagRef> = emptyList(),
    val language: String? = null,
    val previewUrl: String? = null,
    val extra: Map<String, String> = emptyMap(),
) {
    val compositeKey: String get() = "${siteId.value}:$id"
}

@Serializable
data class VideoStream(
    val quality: String,
    val label: String,
    val url: String,
    val isHls: Boolean = false,
    val mimeTypeHint: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val bandwidthBps: Long? = null,
    val isPreviewOnly: Boolean = false,
)

@Serializable
data class ExternalPlayerLink(
    val label: String,
    val url: String,
    val provider: String = "",
)

@Serializable
data class VideoDetail(
    val item: MediaItem,
    val description: String = "",
    val streams: List<VideoStream> = emptyList(),
    val downloadStreams: List<VideoStream> = emptyList(),
    val externalPlayers: List<ExternalPlayerLink> = emptyList(),
    val tags: List<TagRef> = emptyList(),
    val authors: List<AuthorRef> = emptyList(),
    val characters: List<TagRef> = emptyList(),
    val categories: List<TagRef> = emptyList(),
    val comments: List<CommentItem> = emptyList(),
    val related: List<MediaItem> = emptyList(),
    val thumbnailVttUrl: String? = null,
    val isPreviewOnly: Boolean = false,
)

@Serializable
data class GalleryPage(
    val index: Int,
    val pageUrl: String,
    val imageUrl: String = "",
    val originalImageUrl: String? = null,
    val thumbUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val reloadToken: String? = null,
    val unscrambleSeed: String? = null,
    val stripCount: Int = 0,
    val originalSizeLabel: String? = null,
)

@Serializable
data class GalleryDetail(
    val item: MediaItem,
    val description: String = "",
    val pageCount: Int,
    val pages: List<GalleryPage> = emptyList(),
    val tags: List<TagRef> = emptyList(),
    val authors: List<AuthorRef> = emptyList(),
    val characters: List<TagRef> = emptyList(),
    val categories: List<TagRef> = emptyList(),
    val comments: List<CommentItem> = emptyList(),
    val related: List<MediaItem> = emptyList(),
    /** Embedded video entries (e.g., Cosplaytele Cossora HLS embeds) kept distinct from gallery pages. */
    val embeddedVideos: List<MediaItem> = emptyList(),
    val chaptersUrl: String? = null,
    val previewPageSize: Int? = null,
    val fileSizeLabel: String? = null,
)
