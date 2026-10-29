package vn.onplay.vdubbing.framework.audio

import org.junit.Assert.*
import org.junit.Test
import vn.onplay.vdubbing.domain.model.Language

class AudioSessionStoreTest {
    @Test fun `late permission and teardown from an old session cannot change a new one`() {
        val store = AudioSessionStore()
        val first = store.begin("first.app", 1234, Language.AUTO, Language.VIETNAMESE)
        store.end(first.id)
        val second = store.begin("second.app", 2345, Language.ENGLISH, Language.JAPANESE)
        store.update(first.id, AudioStage.SPEAKING)
        store.end(first.id)
        assertEquals(second, store.state.value)
        store.update(second.id, AudioStage.LISTENING)
        assertEquals(AudioStage.LISTENING, store.state.value?.stage)
        store.end(second.id)
        assertNull(store.state.value)
    }
}
