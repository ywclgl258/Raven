package com.raven.app.core.network

import com.raven.app.core.model.SiteId
import com.raven.app.core.security.SessionVault

/**
 * Binds credential-change signals from [SessionVault] to the caches that hold account-derived data.
 *
 * Rationale: site plugins write credentials straight through `SiteSessionStore` (`loginWithCredentials`,
 * `importCookies`, `logout`). A signed CDN media URL or a URL-keyed PAGE/API response body fetched
 * for account A must never be replayed for account B or after a logout, so whenever the vault's auth
 * epoch moves for an account we drop:
 * - [MediaRedirectCache] entries minted for that account (signed URL replay), and
 * - the shared OkHttp response cache (URL-only key, cannot distinguish accounts).
 *
 * Registered as a Koin singleton purely for its constructor side effect. Keeping the listener here
 * rather than inside the jar means the invalidation also covers plugin-driven logins that never
 * touch a response `Set-Cookie`.
 */
class SessionCacheInvalidationBinding(
    private val sessionVault: SessionVault,
    private val mediaRedirectCache: MediaRedirectCache,
    private val okHttpPool: RavenOkHttpPool,
) {
    init {
        sessionVault.addAccountChangeListener(::onAccountChanged)
    }

    private fun onAccountChanged(accountId: SiteId) {
        mediaRedirectCache.invalidateForSite(accountId)
        okHttpPool.evictHttpCache()
    }
}
