package vn.onplay.vdubbing

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSchedulingTest {
    @Test fun `runtime uses callbacks and countdowns instead of delay or thread sleeps`() {
        val roots = listOf(File("src/main/java"), File("../domain/src/main/kotlin"))
        val forbidden = Regex("\\bdelay\\s*\\(|Thread\\.sleep\\s*\\(")
        val violations = roots.flatMap { root ->
            assertTrue(root.isDirectory)
            root.walkTopDown().filter { it.extension == "kt" }.flatMap { source ->
                source.readLines().mapIndexedNotNull { line, text ->
                    if (forbidden.containsMatchIn(text)) "$source:${line + 1}" else null
                }
            }.toList()
        }
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }
}
