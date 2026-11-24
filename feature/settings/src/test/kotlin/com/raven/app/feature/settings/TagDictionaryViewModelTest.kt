package com.raven.app.feature.settings

import app.cash.turbine.test
import com.raven.app.core.common.TagDictionary
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TagDictionaryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private fun gzip(text: String): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return baos.toByteArray()
    }

    @Test
    fun `syncs community pack clears community cache and performs custom tag CRUD with live search filter`() =
        runTest {
            val server = MockWebServer()
            server.start()
            try {
                val payload = """
                    {
                      "version": 6,
                      "data": [
                        {
                          "namespace": "female",
                          "data": {
                            "stockings": { "name": "丝袜", "intro": "" },
                            "twintails": { "name": "双马尾", "intro": "" }
                          }
                        }
                      ]
                    }
                """.trimIndent()
                server.enqueue(
                    MockResponse().setResponseCode(200).setBody(
                        """
                        {
                          "id": 500,
                          "tag_name": "2026.06.10",
                          "assets": [
                            {
                              "id": 501,
                              "name": "db.text.json.gz",
                              "size": 128,
                              "browser_download_url": "${server.url("/pack.json.gz")}"
                            }
                          ]
                        }
                        """.trimIndent(),
                    ),
                )
                server.enqueue(
                    MockResponse().setResponseCode(200).setBody(Buffer().write(gzip(payload))),
                )

                val dict = TagDictionary(mapOf("anime" to "动漫"))
                val db = FakeDatabaseBundle(runtimeDictionary = dict)
                val syncer = EhTagTranslationSyncer(
                    apiClient = OkHttpClient(),
                    tagDictionaryRepository = db.tagDictionaryRepository,
                    preferencesRepository = db.preferencesRepository,
                    dispatchers = mainDispatcherRule.appDispatchers,
                    clockMs = { 1_760_000_000_000L },
                    releasesApiBaseUrlOverride = server.url("/releases").toString(),
                )
                val vm = TagDictionaryViewModel(
                    tagDictionaryRepository = db.tagDictionaryRepository,
                    preferencesRepository = db.preferencesRepository,
                    syncer = syncer,
                    dispatchers = mainDispatcherRule.appDispatchers,
                )

                vm.uiState.test {
                    advanceUntilIdle()
                    val initial = expectMostRecentItem()
                    assertEquals(1, initial.stats.builtInCount)
                    assertEquals(0, initial.stats.communityCount)
                    assertEquals(0, initial.stats.customCount)
                    assertTrue(initial.autoSyncEnabled)

                    // Toggle weekly auto-sync
                    vm.setAutoSync(false)
                    advanceUntilIdle()
                    assertFalse(expectMostRecentItem().autoSyncEnabled)

                    // Sync community pack (real OkHttp async callback against MockWebServer)
                    vm.syncCommunityNow()
                    var synced = awaitItem()
                    while (synced.isSyncing || synced.stats.communityCount != 2 || synced.releaseTag != "2026.06.10") {
                        synced = awaitItem()
                    }
                    assertEquals(2, synced.stats.communityCount)
                    assertEquals("2026.06.10", synced.releaseTag)
                    assertFalse(synced.isSyncing)
                    assertFalse(synced.syncStatusIsError)
                    assertEquals("双马尾", dict.translate("twintails", "female"))

                    // Add custom translation overriding community entry
                    vm.saveCustomTranslation(
                        rawSlug = "stockings",
                        chineseName = "长筒丝袜",
                        namespace = "female",
                    )
                    vm.saveCustomTranslation(
                        rawSlug = "cyberpunk",
                        chineseName = "赛博朋克",
                        namespace = "",
                    )
                    advanceUntilIdle()
                    val afterCustom = expectMostRecentItem()
                    assertEquals(2, afterCustom.stats.customCount)
                    assertEquals(2, afterCustom.customEntries.size)
                    assertEquals("长筒丝袜", dict.translate("stockings", "female"))

                    // Search filter narrows custom entries
                    vm.updateSearchQuery("赛博")
                    advanceUntilIdle()
                    val filtered = expectMostRecentItem()
                    assertEquals(1, filtered.customEntries.size)
                    assertEquals("cyberpunk", filtered.customEntries.single().rawSlug)

                    // Clear search & delete one custom entry
                    vm.updateSearchQuery("")
                    advanceUntilIdle()
                    val entryToDelete = expectMostRecentItem().customEntries.first { it.rawSlug == "cyberpunk" }
                    vm.deleteCustomTranslation(entryToDelete)
                    advanceUntilIdle()
                    assertEquals(1, expectMostRecentItem().stats.customCount)

                    // Clear community cache preserves custom entries and resets sync metadata
                    vm.clearCommunityCache()
                    advanceUntilIdle()
                    val cleared = expectMostRecentItem()
                    assertEquals(0, cleared.stats.communityCount)
                    assertEquals(1, cleared.stats.customCount)
                    assertEquals("", cleared.releaseTag)
                    assertEquals(0L, cleared.lastUpdatedAt)
                    cancelAndIgnoreRemainingEvents()
                }
            } finally {
                server.shutdown()
            }
        }
}
