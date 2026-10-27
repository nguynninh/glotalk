package vn.onplay.vdubbing.framework.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.Translation
import vn.onplay.vdubbing.domain.model.TranslationEngine
import vn.onplay.vdubbing.domain.model.TranslationException

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationRequestRunnerTest {
    @Test
    fun `queued requests admit one translation and reject the other without changing its languages`() = runTest {
        val finish = CompletableDeferred<Unit>()
        val languages = mutableListOf<Pair<Language, Language>>()
        val busy = mutableListOf<Boolean>()
        val results = mutableListOf<Result<Translation>>()
        val runner = TranslationRequestRunner(this, { source, target ->
            languages += source to target
            finish.await()
            result(source, target)
        }, {}, busy::add, {})

        runner.translate(Language.JAPANESE, Language.VIETNAMESE, results::add)
        runner.translate(Language.ENGLISH, Language.KOREAN, results::add)
        runCurrent()
        assertTrue(runner.busy)
        assertEquals(listOf(Language.JAPANESE to Language.VIETNAMESE), languages)
        assertTrue(results.single().exceptionOrNull() is TranslationException.Busy)
        assertEquals(listOf(true), busy)
        finish.complete(Unit)
        runCurrent()
        assertFalse(runner.busy)
        assertEquals(listOf(true, false), busy)
        assertEquals(2, results.size)
        assertEquals(Language.JAPANESE, results.last().getOrThrow().source)
    }

    @Test
    fun `preparation failure releases busy state and permits the next request`() = runTest {
        var fail = true
        val results = mutableListOf<Result<Translation>>()
        val runner = TranslationRequestRunner(this, ::result, { check(!fail) }, {}, {})
        runner.translate(Language.AUTO, Language.ENGLISH, results::add)
        runCurrent()
        assertTrue(results.single().exceptionOrNull() is TranslationException.TranslationFailed)
        assertFalse(runner.busy)
        fail = false
        runner.translate(Language.CHINESE, Language.KOREAN, results::add)
        runCurrent()
        assertTrue(results.last().isSuccess)
    }

    @Test
    fun `service cancellation reports failure once and never publishes a success`() = runTest {
        val job = Job()
        val scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))
        val results = mutableListOf<Result<Translation>>()
        val busy = mutableListOf<Boolean>()
        var successes = 0
        val runner = TranslationRequestRunner(scope, { _, _ ->
            CompletableDeferred<Translation>().await()
        }, {}, busy::add, { successes++ })
        runner.translate(Language.AUTO, Language.ENGLISH, results::add)
        runCurrent()
        scope.cancel()
        runCurrent()
        assertEquals(1, results.size)
        assertTrue(results.single().exceptionOrNull() is TranslationException.TranslationFailed)
        assertEquals(0, successes)
        assertFalse(runner.busy)
        assertEquals(listOf(true, false), busy)
    }

    @Test
    fun `cancellation before dispatch still responds once without touching overlays`() = runTest {
        val job = Job()
        val scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))
        val results = mutableListOf<Result<Translation>>()
        val runner = TranslationRequestRunner(scope, { _, _ -> error("Must not translate") },
            { error("Must not prepare") }, { error("Must not touch views") }, {})
        runner.translate(Language.AUTO, Language.ENGLISH, results::add)
        scope.cancel()
        runCurrent()
        assertEquals(1, results.size)
        assertTrue(results.single().exceptionOrNull() is TranslationException.TranslationFailed)
        assertFalse(runner.busy)
    }

    private fun result(source: Language, target: Language) =
        Translation(emptyList(), TranslationEngine.ON_DEVICE, source, target)
}
