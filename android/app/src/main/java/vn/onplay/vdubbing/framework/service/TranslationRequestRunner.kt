package vn.onplay.vdubbing.framework.service

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import vn.onplay.vdubbing.domain.model.Language
import vn.onplay.vdubbing.domain.model.Translation
import vn.onplay.vdubbing.domain.model.TranslationException

/** The service supplies a Main scope so admission and overlay updates stay on one thread. */
internal class TranslationRequestRunner(
    private val scope: CoroutineScope,
    private val execute: suspend (Language, Language) -> Translation,
    private val prepare: suspend () -> Unit,
    private val setBusy: (Boolean) -> Unit,
    private val onSuccess: (Translation) -> Unit,
) {
    private val running = MutableStateFlow(false)
    val busy: Boolean get() = running.value

    suspend fun awaitIdle() { running.first { !it } }

    fun translate(source: Language, target: Language, callback: (Result<Translation>) -> Unit) {
        val delivered = AtomicBoolean()
        fun respond(result: Result<Translation>) {
            if (delivered.compareAndSet(false, true)) callback(result)
        }
        val job = scope.launch {
            if (busy) {
                respond(Result.failure(TranslationException.Busy()))
                return@launch
            }
            running.value = true
            val result = try {
                setBusy(true)
                prepare()
                Result.success(execute(source, target).also(onSuccess))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: TranslationException) {
                Result.failure(failure)
            } catch (failure: Exception) {
                Result.failure(TranslationException.TranslationFailed(failure.message.orEmpty(), failure))
            } finally {
                setBusy(false)
                running.value = false
            }
            respond(result)
        }
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                respond(Result.failure(TranslationException.TranslationFailed("Đã dừng dịch.", cause)))
            }
        }
    }
}
