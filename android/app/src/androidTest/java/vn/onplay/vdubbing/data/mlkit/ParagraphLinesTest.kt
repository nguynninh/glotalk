package vn.onplay.vdubbing.data.mlkit

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParagraphLinesTest {
    private val first = Rect(40, 100, 240, 130)

    @Test fun wrappedParagraphLinesStayTogether() {
        assertTrue(canJoinParagraph(first, Rect(40, 138, 180, 168)))
    }

    @Test fun sideBySideColumnsNeverMerge() {
        assertFalse(canJoinParagraph(first, Rect(280, 100, 480, 130)))
    }

    @Test fun separateTableRowsNeverMerge() {
        assertFalse(canJoinParagraph(first, Rect(40, 170, 240, 200)))
    }

    @Test fun headingAndBodySizesDoNotMerge() {
        assertFalse(canJoinParagraph(first, Rect(40, 136, 240, 151)))
    }
}
