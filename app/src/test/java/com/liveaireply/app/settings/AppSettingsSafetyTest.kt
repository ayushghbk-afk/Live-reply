package com.liveaireply.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsSafetyTest {

    @Test
    fun `safe defaults are suggest with all sensitive features off`() {
        val defaults = AppSettings.DEFAULT
        assertEquals(AssistantMode.SUGGEST, defaults.mode)
        assertFalse(defaults.monitoringEnabled)
        assertFalse(defaults.autoReplyEnabled)
        assertFalse(defaults.overlayEnabled)
        assertFalse(defaults.ocrEnabled)
        assertEquals(CaptureScope.OFF, defaults.captureScope)
    }

    @Test
    fun `capabilities cannot remain enabled before disclosure acceptance`() {
        val unsafe = AppSettings(
            mode = AssistantMode.AUTO,
            monitoringEnabled = true,
            autoReplyEnabled = true,
            acknowledgedAutomationRisk = true,
            overlayEnabled = true,
            ocrEnabled = true,
            captureScope = CaptureScope.FULL_SCREEN,
            acknowledgedCapabilities = false
        ).enforceSafetyInvariants()

        assertEquals(AssistantMode.SUGGEST, unsafe.mode)
        assertFalse(unsafe.monitoringEnabled)
        assertFalse(unsafe.autoReplyEnabled)
        assertFalse(unsafe.overlayEnabled)
        assertFalse(unsafe.ocrEnabled)
        assertEquals(CaptureScope.OFF, unsafe.captureScope)
    }

    @Test
    fun `legacy full screen OCR is reduced to conversation crop`() {
        val migrated = AppSettings(
            acknowledgedCapabilities = true,
            ocrEnabled = true,
            captureScope = CaptureScope.FULL_SCREEN
        ).enforceSafetyInvariants()

        assertTrue(migrated.ocrEnabled)
        assertEquals(CaptureScope.CONVERSATION_AREA, migrated.captureScope)
    }

    @Test
    fun `auto requires all explicit gates`() {
        val optedIn = AppSettings(
            mode = AssistantMode.AUTO,
            monitoringEnabled = true,
            autoReplyEnabled = true,
            acknowledgedAutomationRisk = true,
            acknowledgedCapabilities = true
        )
        assertTrue(optedIn.autoSendPermitted())
        assertFalse(optedIn.copy(acknowledgedCapabilities = false).autoSendPermitted())
        assertFalse(optedIn.copy(acknowledgedAutomationRisk = false).autoSendPermitted())
        assertFalse(optedIn.copy(monitoringEnabled = false).autoSendPermitted())
        assertFalse(optedIn.copy(emergencyStopped = true).autoSendPermitted())

        val stopped = optedIn.copy(emergencyStopped = true).enforceSafetyInvariants()
        assertEquals(AssistantMode.SUGGEST, stopped.mode)
        assertFalse(stopped.monitoringEnabled)
        assertFalse(stopped.autoReplyEnabled)
    }
}
