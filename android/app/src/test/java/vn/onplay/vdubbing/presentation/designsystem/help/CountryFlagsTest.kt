package vn.onplay.vdubbing.presentation.designsystem.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import vn.onplay.vdubbing.domain.model.Language

class CountryFlagsTest {
    @Test
    fun offeredLanguagesResolveToTheirCountryFlags() {
        val expected = mapOf(
            Language.CHINESE to ("🇨🇳" to "16022842"),
            Language.VIETNAMESE to ("🇻🇳" to "16022964"),
            Language.ENGLISH to ("🇬🇧" to "16021826"),
            Language.JAPANESE to ("🇯🇵" to "16022262"),
            Language.KOREAN to ("🇰🇷" to "16022668"),
        )
        assertEquals(Language.TARGETS.toSet(), expected.keys)
        expected.forEach { (language, flag) ->
            assertEquals(flag.first, language.flag())
            assertEquals(
                "https://cdn-icons-png.flaticon.com/128/${flag.second.dropLast(3)}/${flag.second}.png",
                language.flagUrl(),
            )
        }
    }

    @Test
    fun unknownCountriesAndDetectionDoNotRequestImages() {
        assertNull(CountryFlags.url(""))
        assertNull(CountryFlags.url("xx"))
        assertNull(Language.AUTO.flagUrl())
        assertNull(Language.UNKNOWN.flagUrl())
        assertEquals("auto", Language.AUTO.flag())
        assertEquals(CountryFlags.url("vn"), CountryFlags.url("VN"))
    }
}
