package com.raven.app.core.sitespi

import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId

/**
 * Composable site plugin SPI.
 * Each site exposes only the capabilities it truly supports; `null` indicates unsupported capability at the type level.
 */
interface SitePlugin {
    val descriptor: SiteDescriptor
    val id: SiteId get() = descriptor.id

    val feedSource: FeedSource? get() = null
    val videoSource: VideoSource? get() = null
    val gallerySource: GallerySource? get() = null
    val searchSource: SearchSource? get() = null
    val directorySource: DirectorySource? get() = null
    val authProvider: AuthProvider get() = AuthProvider.None
    val linkResolver: LinkResolver? get() = null
    val hostRouter: HostRouter get() = HostRouter.Standard(descriptor.allHosts)
    val mediaTransportHook: MediaTransportHook get() = MediaTransportHook.None
    val imageUnscrambler: ImageUnscrambler get() = ImageUnscrambler.None
}
