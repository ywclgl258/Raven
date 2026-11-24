package com.raven.app.feature.settings

import app.cash.turbine.test
import com.raven.app.core.common.TagDictionary
import com.raven.app.core.model.RavenRoute
import com.raven.app.core.model.SiteId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BlockedTermsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val testDictionary = TagDictionary(
        mapOf(
            "animated" to "动画",
            "anime" to "动漫",
            "3d" to "3D",
            "cyberpunk" to "赛博朋克",
            "sound" to "有声",
        ),
    )

    @Test
    fun `provides TagDictionary autocomplete suggestions and isolates CRUD strictly by SiteId`() = runTest {
        val db = FakeDatabaseBundle()
        val registry = createTestSiteRegistry()

        // Pre-populate a different site so we verify strict per-site isolation
        db.blockedTermsRepository.addTerm(SiteId.NHENTAI, "nhentai-only")

        val vm = BlockedTermsViewModel(
            route = RavenRoute.BlockedTerms(SiteId.R34V.value),
            siteRegistry = registry,
            blockedTermsRepository = db.blockedTermsRepository,
            tagDictionary = testDictionary,
            dispatchers = mainDispatcherRule.appDispatchers,
        )

        vm.uiState.test {
            advanceUntilIdle()
            val initial = expectMostRecentItem()
            assertEquals(SiteId.R34V, initial.siteId)
            assertTrue(initial.terms.isEmpty())

            // Autocomplete matches English slug prefix and Chinese translation
            vm.updateQuery("anim")
            advanceUntilIdle()
            val withSuggestions = expectMostRecentItem()
            assertEquals(listOf("anime", "animated"), withSuggestions.suggestions.map { it.slug })

            // Selecting a suggestion adds it and removes it from future suggestions
            vm.addSuggestion(withSuggestions.suggestions.first())
            advanceUntilIdle()
            val afterAdd = expectMostRecentItem()
            assertEquals(listOf("anime"), afterAdd.terms.map { it.term })
            assertEquals("动漫", afterAdd.terms.single().localizedHint)
            assertEquals("", afterAdd.inputQuery)

            vm.updateQuery("anim")
            advanceUntilIdle()
            val filteredSuggestions = expectMostRecentItem()
            assertFalse(filteredSuggestions.suggestions.any { it.slug == "anime" })
            assertTrue(filteredSuggestions.suggestions.any { it.slug == "animated" })

            // Chinese lookup works too
            vm.updateQuery("赛博")
            advanceUntilIdle()
            assertEquals(listOf("cyberpunk"), expectMostRecentItem().suggestions.map { it.slug })

            // Add custom term, remove one, then clear all for R34V only
            vm.addTerm("  Custom_Spam  ")
            advanceUntilIdle()
            assertEquals(2, expectMostRecentItem().terms.size)

            vm.removeTerm("anime")
            advanceUntilIdle()
            assertEquals(1, expectMostRecentItem().terms.size)

            vm.clearAll()
            advanceUntilIdle()
            assertTrue(expectMostRecentItem().terms.isEmpty())

            // Verify nhentai's blocked term was completely untouched!
            val allRemaining = db.blockedTermDao.getAll()
            assertEquals(1, allRemaining.size)
            assertEquals(SiteId.NHENTAI.value, allRemaining.single().siteId)
            assertEquals("nhentai-only", allRemaining.single().term)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `supports Global vs Current-Site segmented toggle and blockTagsGlobally preference`() = runTest {
        val db = FakeDatabaseBundle(runtimeDictionary = testDictionary)
        val registry = createTestSiteRegistry()

        val vm = BlockedTermsViewModel(
            route = RavenRoute.BlockedTerms(SiteId.R34V.value),
            siteRegistry = registry,
            blockedTermsRepository = db.blockedTermsRepository,
            tagDictionary = testDictionary,
            dispatchers = mainDispatcherRule.appDispatchers,
            preferencesRepository = db.preferencesRepository,
            tagDictionaryRepository = db.tagDictionaryRepository,
        )

        vm.uiState.test {
            advanceUntilIdle()
            val initial = expectMostRecentItem()
            assertEquals(BlockedTermsScope.CURRENT_SITE, initial.selectedScope)
            assertTrue(initial.blockTagsGlobally)

            // Toggle global sync switch off and verify persistence
            vm.setBlockTagsGlobally(false)
            advanceUntilIdle()
            assertFalse(expectMostRecentItem().blockTagsGlobally)
            assertFalse(db.preferencesRepository.currentSettings().blockTagsGlobally)

            // Add site-scoped term on R34V
            vm.addTerm("site-only-tag")
            advanceUntilIdle()
            val afterSiteAdd = expectMostRecentItem()
            assertEquals(1, afterSiteAdd.siteTermsCount)
            assertEquals(0, afterSiteAdd.globalTermsCount)

            // Switch to Global scope and add a global term
            vm.selectScope(BlockedTermsScope.GLOBAL)
            vm.addTerm("global-tag")
            advanceUntilIdle()
            val globalState = expectMostRecentItem()
            assertEquals(BlockedTermsScope.GLOBAL, globalState.selectedScope)
            assertEquals(SiteId.GLOBAL, globalState.activeTargetSiteId)
            assertEquals(1, globalState.globalTermsCount)
            assertEquals(1, globalState.siteTermsCount)
            assertEquals(listOf("global-tag"), globalState.terms.map { it.term })

            // Verify global + site terms both appear in observeEffectiveTermsBySite(R34V)
            val allStored = db.blockedTermDao.getAll()
            assertTrue(allStored.any { it.siteId == SiteId.GLOBAL.value && it.term == "global-tag" })
            assertTrue(allStored.any { it.siteId == SiteId.R34V.value && it.term == "site-only-tag" })

            // Clearing Global only clears SiteId.GLOBAL, leaving R34V's term intact
            vm.clearAll()
            advanceUntilIdle()
            val afterClearGlobal = expectMostRecentItem()
            assertEquals(0, afterClearGlobal.globalTermsCount)
            assertEquals(1, afterClearGlobal.siteTermsCount)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
