package com.liveaireply.app.conversation

import com.liveaireply.app.conversation.TestFixtures.SCREEN_H
import com.liveaireply.app.conversation.TestFixtures.SCREEN_W
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleExtractorTest {

    private val extractor = BubbleExtractor()

    @Test
    fun collapsesNestedDuplicatesIntoOneBubble() {
        // Chat apps routinely repeat the same string at three levels of the tree.
        val inner = TestFixtures.textNode("Hello there", 30, 410, 400, 480)
        val middle = TestFixtures.textNode("Hello there", 24, 400, 520, 500, children = listOf(inner))
        val outer = TestFixtures.textNode("Hello there", 24, 390, 540, 520, children = listOf(middle))
        val root = TestFixtures.screen(listOf(outer))

        val bubbles = extractor.extractBubbles(root, SCREEN_W, SCREEN_H, null)
        assertEquals(1, bubbles.size)
        assertEquals("Hello there", bubbles[0].text)
    }

    @Test
    fun neverReadsTheComposerOrPasswordField() {
        val composer = TestFixtures.composer(text = "draft I am writing")
        val password = TestFixtures.textNode(
            text = "hunter2",
            left = 100, top = 500, right = 600, bottom = 580,
            editable = true, password = true, className = "android.widget.NumberPasswordEditText"
        )
        val root = TestFixtures.screen(listOf(TestFixtures.incoming("Real message", 400), composer, password))
        val bubbles = extractor.extractBubbles(root, SCREEN_W, SCREEN_H, composer.bounds)
        assertEquals(listOf("Real message"), bubbles.map { it.text })
    }

    @Test
    fun dropsTimestampsAndReceipts() {
        val root = TestFixtures.screen(
            listOf(
                TestFixtures.incoming("See you soon", 400),
                TestFixtures.textNode("16:43", 900, 410, 1040, 460),
                TestFixtures.textNode("Delivered", 900, 500, 1040, 550)
            )
        )
        val bubbles = extractor.extractBubbles(root, SCREEN_W, SCREEN_H, null)
        assertEquals(listOf("See you soon"), bubbles.map { it.text })
    }

    @Test
    fun ignoresTheToolbarAndTheInputBar() {
        val root = TestFixtures.screen(
            listOf(
                TestFixtures.textNode("WhatsApp", 40, 60, 400, 140),          // header
                TestFixtures.incoming("Actual message", 900),                // body
                TestFixtures.textNode("Message", 40, 2300, 900, 2380)        // input bar
            )
        )
        val bubbles = extractor.extractBubbles(root, SCREEN_W, SCREEN_H, TestFixtures.composer().bounds)
        assertEquals(listOf("Actual message"), bubbles.map { it.text })
    }

    @Test
    fun flagsBubblesThatMentionSensitiveData() {
        val root = TestFixtures.screen(listOf(TestFixtures.incoming("My OTP is 123456", 400)))
        val bubbles = extractor.extractBubbles(root, SCREEN_W, SCREEN_H, null)
        assertEquals(1, bubbles.size)
        assertTrue(bubbles[0].sensitive)
    }

    @Test
    fun sortsBubblesTopToBottom() {
        val root = TestFixtures.screen(
            listOf(
                TestFixtures.incoming("second", 800),
                TestFixtures.incoming("first", 400)
            )
        )
        val bubbles = extractor.extractBubbles(root, SCREEN_W, SCREEN_H, null)
        assertEquals(listOf("first", "second"), bubbles.map { it.text })
    }

    @Test
    fun rejectsDialogSizedBubblesThatCoverTheScreen() {
        val huge = TestFixtures.textNode("Are you sure you want to leave?", 0, 0, SCREEN_W, SCREEN_H)
        val root = TestFixtures.screen(listOf(huge, TestFixtures.incoming("small message", 900)))
        val bubbles = extractor.extractBubbles(root, SCREEN_W, SCREEN_H, null)
        assertEquals(listOf("small message"), bubbles.map { it.text })
        assertFalse(bubbles.any { it.text.contains("sure") })
    }
}
