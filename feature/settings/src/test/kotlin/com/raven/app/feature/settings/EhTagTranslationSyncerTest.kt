package com.raven.app.feature.settings

import com.raven.app.core.common.TagDictionary
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EhTagTranslationSyncerTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private fun gzipBytes(text: String): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return baos.toByteArray()
    }

    private val sampleEhTagPayload: String = """
        {
          "version": 6,
          "data": [
            {
              "namespace": "rows",
              "data": {
                "female": { "name": "女性", "intro": "namespace metadata" }
              }
            },
            {
              "namespace": "female",
              "data": {
                "stockings": {
                  "name": "![icon](https://ehgt.org/s.png) [丝袜](https://ehwiki.org/wiki/stockings)",
                  "intro": "穿戴在腿部的长袜"
                },
                "sole female": {
                  "name": "仅有一名女性",
                  "intro": "作品中只有一位女性角色"
                }
              }
            },
            {
              "namespace": "male",
              "data": {
                "tentacles": {
                  "name": "<b>触手</b>",
                  "intro": ""
                }
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `downloads gzipped db_text_json_gz cleans markdown saves to Room hydrates runtime dictionary and sends zero credentials`() =
        runTest {
            val server = MockWebServer()
            server.start()
            try {
                val assetUrl = server.url("/download/db.text.json.gz").toString()
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .addHeader("ETag", "\"rel-etag-1\"")
                        .setBody(
                            """
                            {
                              "id": 1001,
                              "tag_name": "2026.05.01",
                              "name": "2026.05.01",
                              "assets": [
                                {
                                  "id": 201,
                                  "name": "db.text.json",
                                  "size": 512,
                                  "browser_download_url": "${server.url("/download/db.text.json")}"
                                },
                                {
                                  "id": 202,
                                  "name": "db.text.json.gz",
                                  "size": 256,
                                  "browser_download_url": "$assetUrl"
                                }
                              ]
                            }
                            """.trimIndent(),
                        ),
                )
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .addHeader("ETag", "\"asset-etag-2026\"")
                        .setBody(Buffer().write(gzipBytes(sampleEhTagPayload))),
                )

                val dict = TagDictionary(mapOf("anime" to "动漫"))
                val db = FakeDatabaseBundle(runtimeDictionary = dict)
                val now = 1_750_000_000_000L
                val syncer = EhTagTranslationSyncer(
                    apiClient = OkHttpClient(),
                    tagDictionaryRepository = db.tagDictionaryRepository,
                    preferencesRepository = db.preferencesRepository,
                    dispatchers = mainDispatcherRule.appDispatchers,
                    clockMs = { now },
                    releasesApiBaseUrlOverride = server.url("/repos/EhTagTranslation/DatabaseReleases/releases").toString(),
                )

                val result = syncer.syncNow()
                assertTrue(result is TagSyncResult.Updated)
                val updated = result as TagSyncResult.Updated
                assertEquals("2026.05.01", updated.releaseTag)
                assertEquals(3, updated.entryCount)
                assertEquals("\"asset-etag-2026\"", updated.etag)

                // Runtime dictionary immediately reflects cleaned community entries & skips "rows"
                assertEquals("丝袜", dict.translate("stockings", namespace = "female"))
                assertEquals("仅有一名女性", dict.translate("sole_female", namespace = "female"))
                assertEquals("触手", dict.translate("tentacles", namespace = "male"))
                assertNull(dict.translate("female"))

                // Preferences persisted etag, releaseTag, and updatedAt
                val settings = db.preferencesRepository.currentSettings()
                assertEquals("\"asset-etag-2026\"", settings.tagDictEtag)
                assertEquals("2026.05.01", settings.tagDictReleaseTag)
                assertEquals(now, settings.tagDictUpdatedAt)

                // Verify zero credentials attached to both GitHub requests
                val req1 = server.takeRequest()
                val req2 = server.takeRequest()
                assertEquals("/repos/EhTagTranslation/DatabaseReleases/releases/latest", req1.path)
                assertEquals("/download/db.text.json.gz", req2.path)
                assertNull(req1.getHeader("Authorization"))
                assertNull(req1.getHeader("Cookie"))
                assertNull(req2.getHeader("Authorization"))
                assertNull(req2.getHeader("Cookie"))
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `handles If-None-Match 304 Not Modified by refreshing tagDictUpdatedAt without wiping cache`() =
        runTest {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setResponseCode(304))

                val dict = TagDictionary(emptyMap())
                val db = FakeDatabaseBundle(runtimeDictionary = dict)
                db.preferencesRepository.setTagDictSyncState(
                    etag = "\"cached-etag-9\"",
                    releaseTag = "2026.04.01",
                    updatedAt = 1_700_000_000_000L,
                )
                val nextNow = 1_700_900_000_000L
                val syncer = EhTagTranslationSyncer(
                    apiClient = OkHttpClient(),
                    tagDictionaryRepository = db.tagDictionaryRepository,
                    preferencesRepository = db.preferencesRepository,
                    dispatchers = mainDispatcherRule.appDispatchers,
                    clockMs = { nextNow },
                    releasesApiBaseUrlOverride = server.url("/repos/EhTagTranslation/DatabaseReleases/releases").toString(),
                )

                val result = syncer.syncNow()
                assertTrue(result is TagSyncResult.NotModified)
                val notMod = result as TagSyncResult.NotModified
                assertEquals("2026.04.01", notMod.releaseTag)

                val req = server.takeRequest()
                assertEquals("\"cached-etag-9\"", req.getHeader("If-None-Match"))

                val settings = db.preferencesRepository.currentSettings()
                assertEquals(nextNow, settings.tagDictUpdatedAt)
                assertEquals("\"cached-etag-9\"", settings.tagDictEtag)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `falls back to plain db_text_json and respects 7-day auto-sync throttle`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val plainUrl = server.url("/download/db.text.json").toString()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "id": 1002,
                          "tag_name": "2026.05.02",
                          "assets": [
                            {
                              "id": 301,
                              "name": "db.text.json",
                              "size": 512,
                              "browser_download_url": "$plainUrl"
                            }
                          ]
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(sampleEhTagPayload))

            val dict = TagDictionary(emptyMap())
            val db = FakeDatabaseBundle(runtimeDictionary = dict)
            var now = 1_750_000_000_000L
            val syncer = EhTagTranslationSyncer(
                apiClient = OkHttpClient(),
                tagDictionaryRepository = db.tagDictionaryRepository,
                preferencesRepository = db.preferencesRepository,
                dispatchers = mainDispatcherRule.appDispatchers,
                clockMs = { now },
                releasesApiBaseUrlOverride = server.url("/repos/EhTagTranslation/DatabaseReleases/releases").toString(),
            )

            // Disabled auto-sync -> skips without network call
            db.preferencesRepository.setTagDictAutoSync(false)
            assertTrue(syncer.syncIfDue() is TagSyncResult.Skipped)
            assertEquals(0, server.requestCount)

            // Re-enable -> first sync executes and downloads plain db.text.json fallback
            db.preferencesRepository.setTagDictAutoSync(true)
            val first = syncer.syncIfDue()
            assertTrue(first is TagSyncResult.Updated)
            assertEquals("丝袜", dict.translate("stockings", "female"))
            assertEquals(2, server.requestCount)

            // 3 days later -> throttled (< 7 days)
            now += 3L * 24L * 60L * 60L * 1_000L
            assertTrue(syncer.syncIfDue() is TagSyncResult.Skipped)
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
