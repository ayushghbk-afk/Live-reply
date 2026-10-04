package com.liveaireply.app.adapters

import com.liveaireply.app.conversation.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAdapterTest {

    private val registry = ChatAdapterRegistry()

    @Test
    fun picksTheRightAdapterForEachPackage() {
        assertEquals("whatsapp", registry.forPackage("com.whatsapp").id)
        assertEquals("telegram", registry.forPackage("org.telegram.messenger").id)
        assertEquals("instagram", registry.forPackage("com.instagram.android").id)
        assertEquals("discord", registry.forPackage("com.discord").id)
        assertEquals("browser", registry.forPackage("com.android.chrome").id)
        assertEquals("generic", registry.forPackage("com.some.unknown.chat").id)
    }

    @Test
    fun findsTheFocusedEditableField() {
        val composer = TestFixtures.composer().copy(isFocused = true)
        val root = TestFixtures.screen(listOf(TestFixtures.incoming("hi", 400), composer))
        val found = registry.forPackage("com.whatsapp").locateComposer(
            root, TestFixtures.SCREEN_W, TestFixtures.SCREEN_H
        )
        assertNotNull(found)
        assertTrue(found!!.confidence >= 0.85f)
        assertEquals(composer.bounds, found.bounds)
    }

    @Test
    fun findsTheComposerByItsMessagingHint() {
        val composer = TestFixtures.textNode(
            text = "Type a message",
            left = 24, top = 2240, right = 900, bottom = 2340,
            viewId = "com.example:id/input",
            editable = true,
            className = "android.widget.EditText"
        )
        val root = TestFixtures.screen(listOf(composer))
        val found = GenericChatAdapter().locateComposer(root, TestFixtures.SCREEN_W, TestFixtures.SCREEN_H)
        assertNotNull(found)
        assertTrue(found!!.reason.contains("hint"))
    }

    @Test
    fun returnsNullWhenThereIsNoEditableFieldAtAll() {
        val root = TestFixtures.screen(listOf(TestFixtures.incoming("hi", 400)))
        assertNull(GenericChatAdapter().locateComposer(root, TestFixtures.SCREEN_W, TestFixtures.SCREEN_H))
    }

    @Test
    fun neverExposesAPasswordFieldAsTheComposer() {
        val password = TestFixtures.textNode(
            text = "", left = 100, top = 2240, right = 900, bottom = 2340,
            editable = true, password = true, className = "android.widget.NumberPasswordEditText"
        )
        val root = TestFixtures.screen(listOf(password))
        assertNull(GenericChatAdapter().locateComposer(root, TestFixtures.SCREEN_W, TestFixtures.SCREEN_H))
    }

    @Test
    fun findsTheSendButtonByItsLabel() {
        val composer = TestFixtures.composer()
        val send = TestFixtures.sendButton()
        val root = TestFixtures.screen(listOf(composer, send))
        val target = GenericChatAdapter().locateSendTarget(
            root,
            ComposerCandidate(composer, composer.viewIdResourceName, composer.bounds, 0.9f, "test")
        )
        assertEquals(SendTargetKind.NODE_ACTION, target.kind)
        assertEquals(send.viewIdResourceName, target.node?.viewIdResourceName)
    }

    @Test
    fun neverInventsACoordinateWhenNoSendControlExists() {
        val composer = TestFixtures.composer()
        val root = TestFixtures.screen(listOf(composer))
        val target = GenericChatAdapter().locateSendTarget(root, null)
        assertEquals(SendTargetKind.UNAVAILABLE, target.kind)
        assertNull(target.point)
    }

    @Test
    fun usesAUserConfiguredCoordinateOnlyWhenTheySetOne() {
        val composer = TestFixtures.composer()
        val root = TestFixtures.screen(listOf(composer))
        val target = GenericChatAdapter().locateSendTarget(
            root,
            null,
            AdapterOverrides(sendPoint = PointView(1000, 2290))
        )
        assertEquals(SendTargetKind.GESTURE_POINT, target.kind)
        assertEquals(PointView(1000, 2290), target.point)
    }

    @Test
    fun userOverridesBeatBuiltInHeuristics() {
        val composer = TestFixtures.composer(viewId = "com.example:id/weird_name")
        val root = TestFixtures.screen(listOf(composer))
        val found = GenericChatAdapter().locateComposer(
            root, TestFixtures.SCREEN_W, TestFixtures.SCREEN_H,
            AdapterOverrides(composerViewId = "weird_name")
        )
        assertEquals(0.98f, found!!.confidence, 0.0001f)
    }

    @Test
    fun whatsAppAdapterRecognisesItsKnownIds() {
        val adapter = WhatsAppAdapter()
        val composer = TestFixtures.textNode(
            text = "", left = 24, top = 2240, right = 900, bottom = 2340,
            viewId = "com.whatsapp:id/entry", editable = true, className = "android.widget.EditText"
        )
        val send = TestFixtures.textNode(
            text = "", left = 930, top = 2250, right = 1050, bottom = 2330,
            viewId = "com.whatsapp:id/send", className = "android.widget.ImageButton"
        ).copy(isClickable = true)
        val root = TestFixtures.screen(listOf(composer, send))

        val located = adapter.locateComposer(root, TestFixtures.SCREEN_W, TestFixtures.SCREEN_H)
        assertNotNull(located)
        assertTrue(located!!.confidence >= 0.9f)

        val target = adapter.locateSendTarget(root, located)
        assertEquals(SendTargetKind.NODE_ACTION, target.kind)
        assertNotNull(target.node)
    }

    @Test
    fun directionHintsKnowRightSideIsOutgoing() {
        val hints = WhatsAppAdapter().directionHints()
        assertTrue(hints.outgoingRightOfFraction > 0.5f)
        assertTrue(hints.incomingLeftOfFraction < 0.5f)
        assertTrue(hints.outgoingViewIdContains.isNotEmpty())
    }

    @Test
    fun chatTitleIsReadFromTheHeader() {
        val header = TestFixtures.textNode("Hellen", 120, 80, 600, 160)
        val root = TestFixtures.screen(listOf(header, TestFixtures.incoming("hi", 400)))
        assertEquals("Hellen", GenericChatAdapter().chatTitle(root, TestFixtures.SCREEN_H))
    }

    @Test
    fun settingsActivitiesAreRecognisedAsNonChat() {
        assertTrue(GenericChatAdapter().isNonChatActivity("com.whatsapp.Settings"))
        assertFalse(GenericChatAdapter().isNonChatActivity("com.whatsapp.Conversation"))
    }

    @Test
    fun browserAdapterDistrustsNarrowFields() {
        val narrow = TestFixtures.textNode(
            text = "", left = 400, top = 2240, right = 500, bottom = 2340,
            editable = true, className = "android.widget.EditText"
        )
        val root = TestFixtures.screen(listOf(narrow))
        val found = BrowserChatAdapter().locateComposer(root, TestFixtures.SCREEN_W, TestFixtures.SCREEN_H)
        assertNotNull(found)
        assertTrue("browser confidence was ${found!!.confidence}", found.confidence < 0.6f)
    }

    @Test
    fun everyKnownAppRowHasAnAdapter() {
        for (app in ChatAdapterRegistry.KNOWN_APPS) {
            if (app.packageName.isEmpty()) continue
            val adapter = registry.forPackage(app.packageName)
            assertNotNull("no adapter for ${app.packageName}", adapter)
        }
    }
}
