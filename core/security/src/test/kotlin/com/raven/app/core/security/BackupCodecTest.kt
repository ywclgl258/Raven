package com.raven.app.core.security

import com.raven.app.core.model.ComicLanguage
import com.raven.app.core.model.FavoriteItem
import com.raven.app.core.model.FavoriteKind
import com.raven.app.core.model.MediaKind
import com.raven.app.core.model.RavenBackupPayload
import com.raven.app.core.model.ReaderFit
import com.raven.app.core.model.ReaderMode
import com.raven.app.core.model.SearchScope
import com.raven.app.core.model.Shelf
import com.raven.app.core.model.ShelfItem
import com.raven.app.core.model.SiteDescriptor
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.SiteNetworkProfile
import com.raven.app.core.model.SmartShelfRule
import com.raven.app.core.model.WatchProgress
import com.raven.app.core.sitespi.SitePlugin
import com.raven.app.core.sitespi.SiteRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

    private val password = "raven-backup-pass!".toCharArray()

    private fun stubPlugin(
        id: SiteId,
        host: String,
        kind: MediaKind = MediaKind.VIDEO,
        mirrors: List<String> = emptyList(),
        apiHosts: Set<String> = emptySet(),
    ): SitePlugin = object : SitePlugin {
        override val descriptor: SiteDescriptor = SiteDescriptor(
            id = id,
            displayName = id.value,
            primaryKind = kind,
            defaultHost = host,
            mirrorHosts = mirrors,
            networkProfile = SiteNetworkProfile(apiHosts = apiHosts),
        )
    }

    private val registry = SiteRegistry(
        listOf(
            stubPlugin(SiteId.R34V, "rule34video.com"),
            stubPlugin(SiteId.HANIME1, "hanime1.me"),
            stubPlugin(SiteId.IWARA, "www.iwara.tv", apiHosts = setOf("apiq.iwara.tv", "api.iwara.tv")),
            stubPlugin(SiteId.EHENTAI, "e-hentai.org", kind = MediaKind.COMIC),
            stubPlugin(SiteId.NHENTAI, "nhentai.net", kind = MediaKind.COMIC),
            stubPlugin(SiteId.FOURKHD, "www.4khd.com", kind = MediaKind.PHOTO),
        ),
    )

    private val validator = BackupValidator(registry)
    private val codec = BackupCodec(validator = validator, siteRegistry = registry)

    private fun sampleNativePayload(): RavenBackupPayload = RavenBackupPayload(
        exportedAt = 1_730_000_000_000L,
        appVersion = "1.0.0",
        watchProgress = listOf(
            WatchProgress(
                siteId = SiteId.R34V,
                mediaId = "42",
                url = "https://rule34video.com/video/42/sample/",
                title = "测试收藏视频",
                positionMs = 45_000L,
                durationMs = 120_000L,
                updatedAt = 1_730_000_000_000L,
                quality = "1080p",
            ),
        ),
        favorites = listOf(
            FavoriteItem(
                siteId = SiteId.R34V,
                targetId = "42",
                kind = FavoriteKind.VIDEO,
                title = "测试收藏视频",
                url = "https://rule34video.com/video/42/sample/",
                addedAt = 1_730_000_000_000L,
            ),
        ),
        shelves = listOf(
            Shelf(id = Shelf.WATCH_LATER_ID, name = "稍后看", isSystem = true, modifiedAt = 1_730_000_000_000L),
        ),
        shelfItems = listOf(
            ShelfItem(
                shelfId = Shelf.WATCH_LATER_ID,
                siteId = SiteId.R34V,
                mediaId = "42",
                url = "https://rule34video.com/video/42/sample/",
                kind = MediaKind.VIDEO,
                title = "测试收藏视频",
                addedAt = 1_730_000_000_000L,
            ),
        ),
        siteLanguages = mapOf(SiteId.NHENTAI to ComicLanguage.CHINESE_AND_JAPANESE),
        settings = mapOf("translate_tags" to "true", "default_playback_speed" to "1.25"),
    )

    @Test
    fun `native RVNBK01 encodes exact 8-byte magic encrypts with PBKDF2-600k AES-256-GCM and round-trips`() {
        val original = sampleNativePayload()
        val wire = codec.encode(original, password)

        // Exact 8-byte magic "RVNBK01\u0000" (52 56 4E 42 4B 30 31 00)
        assertArrayEquals(BackupCodec.RAVEN_MAGIC_BYTES, wire.copyOfRange(0, 8))
        assertEquals(BackupFormat.RAVEN_V1, codec.detectFormat(wire))
        assertFalse(String(wire, Charsets.UTF_8).contains("测试收藏视频"))

        // Fresh random salt & IV on every export
        val wire2 = codec.encode(original, password)
        assertFalse(wire.contentEquals(wire2))
        assertFalse(wire.copyOfRange(8, 24).contentEquals(wire2.copyOfRange(8, 24)))
        assertFalse(wire.copyOfRange(24, 36).contentEquals(wire2.copyOfRange(24, 36)))

        val decoded = codec.decode(wire, password)
        assertEquals(BackupFormat.RAVEN_V1, decoded.format)
        assertEquals(original, decoded.payload)
        assertEquals(1, decoded.summary.favoritesCount)
        assertEquals(1, decoded.summary.watchProgressCount)
    }

    @Test
    fun `rejects weak password wrong password tampered header AAD tampered tag and unknown formats`() {
        val payload = sampleNativePayload()

        // Password < 8 chars
        assertThrows(BackupCodecException::class.java) {
            codec.encode(payload, "short7!".toCharArray())
        }

        val wire = codec.encode(payload, password)

        // Wrong password
        assertThrows(BackupCodecException::class.java) {
            codec.decode(wire, "wrong-password-123".toCharArray())
        }

        // Tampered ciphertext / GCM auth tag
        val tagFlip = wire.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte() }
        assertThrows(BackupCodecException::class.java) {
            codec.decode(tagFlip, password)
        }

        // Tampered 36-byte header (AAD violation: flip salt byte)
        val headerFlip = wire.copyOf().also { it[10] = (it[10].toInt() xor 0x40).toByte() }
        assertThrows(BackupCodecException::class.java) {
            codec.decode(headerFlip, password)
        }

        // Short & unrecognized magic
        assertNull(codec.detectFormat(ByteArray(4)))
        assertThrows(BackupCodecException::class.java) { codec.decode(ByteArray(12), password) }
        assertThrows(BackupCodecException::class.java) { codec.decode(ByteArray(80), password) }
    }

    @Test
    fun `decodes legacy R34VBK01 encrypted backup losslessly preserving watch-later retired sites and r34local`() {
        val localHash = "c".repeat(64)
        val legacyJson = """
            {
              "schema": 1,
              "at": 1720000000000,
              "sites": [
                {
                  "site": "r34v",
                  "favorites": [
                    {"k":"video","t":"101","title":"Legacy Video","path":"/video/101/demo/","thumb":"https://rule34video.com/t.jpg","sub":"02:00","at":1720000000100,"site":"r34v"}
                  ],
                  "watches": [
                    {"id":"101","title":"Legacy Video","thumb":"","path":"/video/101/demo/","pos":95,"dur":110,"at":1720000000200,"q":"720p","site":"r34v"}
                  ],
                  "subscriptions": [
                    {"slug":"cyberpunk","label":"赛博朋克","url":"/tags/cyberpunk/","site":"r34v","at":1720000000300}
                  ],
                  "blocked": ["spam-tag"],
                  "searches": ["mecha"],
                  "language": "ALL",
                  "order": "INTERLEAVED"
                },
                {
                  "site": "nhentai",
                  "readings": [
                    {
                      "entry": {"id":"500","title":"Legacy Comic","url":"https://nhentai.net/g/500/","thumb":"","site":"nhentai","mediaKind":"GALLERY"},
                      "page": 12,
                      "pages": 24,
                      "pageUrl": "https://nhentai.net/g/500/12/",
                      "at": 1720000000400,
                      "offset": 320
                    }
                  ],
                  "language": "CHINESE_JAPANESE",
                  "order": "SUBSCRIBED"
                },
                {
                  "site": "kbj",
                  "favorites": [
                    {"k":"video","t":"kbj-9","title":"Retired KBJ","path":"https://www.kbj.im/video/kbj-9","thumb":"","at":1720000000500,"site":"kbj"}
                  ]
                },
                {
                  "site": "netflav",
                  "favorites": [
                    {"k":"video","t":"nf-7","title":"Retired Netflav","path":"https://netflav.com/video?id=nf-7","thumb":"","at":1720000000600,"site":"netflav"}
                  ]
                }
              ],
              "personal": {
                "shelves": [
                  {
                    "id": "watch-later",
                    "name": "稍后看",
                    "entries": [
                      {
                        "video": {"id":"$localHash","title":"Local CBZ","url":"r34local://$localHash","thumb":"","site":"local","mediaKind":"GALLERY"},
                        "completed": true,
                        "at": 1720000000700,
                        "addedAt": 1720000000050
                      },
                      {
                        "video": {"id":"kbj-9","title":"Retired KBJ","url":"https://www.kbj.im/video/kbj-9","thumb":"","site":"kbj","mediaKind":"VIDEO"},
                        "completed": false,
                        "at": 1720000000800,
                        "addedAt": 1720000000800
                      }
                    ],
                    "note": "default queue",
                    "pinned": true,
                    "coverKey": "kbj:kbj-9",
                    "modifiedAt": 1720000000900
                  }
                ],
                "bookmarks": [
                  {"video":{"id":"500","title":"Legacy Comic","url":"https://nhentai.net/g/500/","site":"nhentai","mediaKind":"GALLERY"},"page":8,"offset":120,"at":1720000000910}
                ],
                "readerModes": {"nhentai":"RIGHT_TO_LEFT"},
                "readingBehaviors": {"nhentai":{"volumeKeys":true,"reverseVolume":true,"brightnessPercent":75,"keepAwake":true,"originalImages":true}},
                "readerPreferences": {"nhentai:500":{"mode":"SPREAD","fit":"HEIGHT","start":"FIRST","cropPercent":10,"doubleCover":true}},
                "playbackPreferences": {"r34v":{"defaultRate":1.5,"holdRate":2.5,"seekStepMs":15000,"gestureRangeMs":90000}},
                "savedSearches": [
                  {"name":"稀疏旧搜索","query":"sample","sources":["iwara","kbj"],"singleSiteScope":"MODEL"}
                ],
                "smartShelves": [
                  {"id":"smart-1","name":"未看完漫画","rule":"UNFINISHED","category":"COMIC","source":"nhentai"}
                ],
                "timeBookmarks": [
                  {"id":"tb-1","video":{"id":"101","title":"Legacy Video","url":"https://rule34video.com/video/101/demo/","site":"r34v"},"positionMs":62000,"note":"高能","group":"片段","at":1720000000920}
                ],
                "imageFavorites": [
                  {"video":{"id":"$localHash","title":"Local CBZ","url":"r34local://$localHash","site":"local","mediaKind":"GALLERY"},"page":3,"pageUrl":"r34local://$localHash/3","imageUrl":"","at":1720000000930}
                ],
                "channels": [
                  {"name":"ignored-legacy-channel","site":"iwara","kind":"MODEL","target":"foo"}
                ]
              },
              "settings": {
                "translate_tags": "true",
                "download_unmetered": "true",
                "quality": "1080p",
                "native_speed": "1.25",
                "cross_search_sites": "kbj,r34v,netflav"
              }
            }
        """.trimIndent()

        // Encrypt using exact R34VBK01 wire format (8B "R34VBK01" + 16B salt + 12B IV + 36B AAD + PBKDF2-600k + AES-256-GCM)
        val legacyWire = codec.encryptWireBytes(
            magic = BackupCodec.LEGACY_R34V_MAGIC_BYTES,
            plaintext = legacyJson.toByteArray(Charsets.UTF_8),
            password = password,
        )

        assertEquals(BackupFormat.LEGACY_R34V_V1, codec.detectFormat(legacyWire))

        val decoded = codec.decode(legacyWire, password)
        assertEquals(BackupFormat.LEGACY_R34V_V1, decoded.format)
        val payload = decoded.payload

        // Watch progress converted from seconds to milliseconds + completion preserved (95 >= 110 - 20)
        assertEquals(1, payload.watchProgress.size)
        val watch = payload.watchProgress.single()
        assertEquals(SiteId.R34V, watch.siteId)
        assertEquals("https://rule34video.com/video/101/demo/", watch.url)
        assertEquals(95_000L, watch.positionMs)
        assertEquals(110_000L, watch.durationMs)
        assertTrue(watch.completed)
        assertEquals("720p", watch.quality)

        // Site-scoped reading progress + language mapping
        val reading = payload.readingProgress.single()
        assertEquals(SiteId.NHENTAI, reading.siteId)
        assertEquals(12, reading.currentPage)
        assertEquals(24, reading.totalPages)
        assertEquals(320, reading.scrollOffsetPx)
        assertEquals(ComicLanguage.CHINESE_AND_JAPANESE, payload.siteLanguages[SiteId.NHENTAI])

        // Retired sites kbj & netflav preserved losslessly
        assertEquals(setOf(SiteId.RETIRED_KBJ, SiteId.RETIRED_NETFLAV), decoded.summary.retiredSiteIdsPresent)
        assertTrue(payload.favorites.any { it.siteId == SiteId.RETIRED_KBJ && it.targetId == "kbj-9" })
        assertTrue(payload.favorites.any { it.siteId == SiteId.RETIRED_NETFLAV && it.targetId == "nf-7" })

        // watch-later shelf + r34local:// URI + earliest addedAt preserved
        val watchLater = payload.shelves.single()
        assertEquals(Shelf.WATCH_LATER_ID, watchLater.id)
        assertTrue(watchLater.isSystem)
        assertTrue(watchLater.isPinned)
        assertEquals("kbj:kbj-9", watchLater.coverCompositeKey)
        assertEquals(2, payload.shelfItems.size)
        val localItem = payload.shelfItems.first { it.siteId == SiteId.LOCAL }
        assertEquals("r34local://$localHash", localItem.url)
        assertEquals(1720000000050L, localItem.addedAt)
        assertTrue(localItem.completed)

        // Local image favorite preserved
        val imgFav = payload.imageFavorites.single()
        assertEquals(SiteId.LOCAL, imgFav.siteId)
        assertEquals("r34local://$localHash/3", imgFav.pageUrl)
        assertEquals(3, imgFav.pageIndex)

        // Preferences & saved search & smart shelf mapped accurately
        val playback = payload.playbackPreferences.single()
        assertEquals(1.5f, playback.defaultSpeed)
        assertEquals(15, playback.seekStepSeconds)
        assertEquals(90, playback.gestureSensitivitySeconds)

        val siteReader = payload.readerPreferences.first { it.mediaId == null }
        assertEquals(ReaderMode.RTL, siteReader.mode)
        assertTrue(siteReader.preferOriginalImages)
        assertEquals(75, siteReader.brightnessPercent)

        val bookReader = payload.readerPreferences.first { it.mediaId == "500" }
        assertEquals(ReaderMode.SPREAD, bookReader.mode)
        assertEquals(ReaderFit.HEIGHT, bookReader.fit)
        assertEquals(10, bookReader.cropPercent)

        val search = payload.savedSearches.single()
        assertTrue(search.id.isNotBlank())
        assertEquals(SearchScope.AUTHOR, search.scope)
        assertEquals(listOf(SiteId.IWARA, SiteId.RETIRED_KBJ), search.siteIds)

        val smart = payload.smartShelves.single()
        assertEquals(SmartShelfRule.UNFINISHED, smart.rule)
        assertEquals(MediaKind.COMIC, smart.mediaKindFilter)

        // Settings mapped cleanly
        assertEquals("true", payload.settings["download_unmetered_only"])
        assertEquals("1080p", payload.settings["preferred_quality"])
        assertEquals("1.25", payload.settings["default_playback_speed"])
        assertEquals("kbj,r34v,netflav", payload.settings["cross_search_site_ids"])
        assertEquals("INTERLEAVED", payload.settings["legacy_subscription_order_r34v"])
    }

    @Test
    fun `rejects legacy R34VBK01 backup with cross-site mismatch or credential settings`() {
        val crossSiteJson = """
            {
              "schema": 1,
              "at": 100,
              "sites": [
                {"site":"r34v","watches":[{"id":"1","site":"hanime1"}]}
              ]
            }
        """.trimIndent()
        val wire1 = codec.encryptWireBytes(BackupCodec.LEGACY_R34V_MAGIC_BYTES, crossSiteJson.toByteArray(), password)
        assertThrows(BackupValidationException::class.java) { codec.decode(wire1, password) }

        val credSettingsJson = """
            {
              "schema": 1,
              "at": 100,
              "settings": {"native_cookies": "secret"}
            }
        """.trimIndent()
        val wire2 = codec.encryptWireBytes(BackupCodec.LEGACY_R34V_MAGIC_BYTES, credSettingsJson.toByteArray(), password)
        assertThrows(BackupValidationException::class.java) { codec.decode(wire2, password) }
    }
}
