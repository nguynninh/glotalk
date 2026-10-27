package vn.onplay.vdubbing

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerDependenciesTest {
    @Test
    fun `layers keep implementation dependencies outside presentation and domain`() {
        val sources = File("src/main/java/vn/onplay/vdubbing")
        assertTrue("Run through the app Gradle test task", sources.isDirectory)
        val forbiddenImports = mapOf(
            "presentation" to Regex("^vn\\.onplay\\.vdubbing\\.(data|di|framework)(\\.|$)"),
            "data" to Regex("^vn\\.onplay\\.vdubbing\\.(di|presentation)(\\.|$)"),
            "presentation/designsystem" to Regex("^vn\\.onplay\\.vdubbing\\.presentation\\.(feature|preview|speech)(\\.|$)"),
        )
        val violations = forbiddenImports.flatMap { (layer, forbidden) ->
            assertTrue("Missing source directory: $layer", File(sources, layer).isDirectory)
            File(sources, layer).walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val imported = line.trim().takeIf { it.startsWith("import ") }
                        ?.removePrefix("import ") ?: return@mapIndexedNotNull null
                    if (forbidden.containsMatchIn(imported)) {
                        "${file.relativeTo(sources)}:${index + 1}: $imported"
                    } else null
                }
            }.toList()
        }
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun `domain lives in an independent module without Android or application imports`() {
        val sources = File("../domain/src/main/kotlin/vn/onplay/vdubbing/domain")
        assertTrue(sources.isDirectory)
        assertTrue(!File("src/main/java/vn/onplay/vdubbing/domain").exists())
        val forbidden = Regex("^import (android|androidx|vn\\.onplay\\.vdubbing\\.(data|di|framework|presentation))(\\.|$)")
        val violations = sources.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (forbidden.containsMatchIn(line.trim())) "$file:${index + 1}: $line" else null
            }
        }.toList()
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }
}
