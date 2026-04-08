package com.typeink.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class DraftRecognizerConfigTest {

    @Test
    fun `fromStorage defaults to SYSTEM for null or unknown values`() {
        assertEquals(DraftRecognizerBackend.SYSTEM, DraftRecognizerBackend.fromStorage(null))
        assertEquals(DraftRecognizerBackend.SYSTEM, DraftRecognizerBackend.fromStorage(""))
        assertEquals(DraftRecognizerBackend.SYSTEM, DraftRecognizerBackend.fromStorage("legacy"))
    }

    @Test
    fun `fromStorage recognizes known values case-insensitively`() {
        assertEquals(DraftRecognizerBackend.SYSTEM, DraftRecognizerBackend.fromStorage("system"))
        assertEquals(DraftRecognizerBackend.SHERPA, DraftRecognizerBackend.fromStorage("SHERPA"))
    }
}
