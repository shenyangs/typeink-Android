package com.typeink.vad

import org.junit.Assert.assertEquals
import org.junit.Test

class VadConfigTest {

    @Test
    fun `fromStorage defaults to ENERGY for null or unknown values`() {
        assertEquals(VadBackend.ENERGY, VadBackend.fromStorage(null))
        assertEquals(VadBackend.ENERGY, VadBackend.fromStorage(""))
        assertEquals(VadBackend.ENERGY, VadBackend.fromStorage("legacy"))
    }

    @Test
    fun `fromStorage recognizes known values case-insensitively`() {
        assertEquals(VadBackend.ENERGY, VadBackend.fromStorage("energy"))
        assertEquals(VadBackend.TEN, VadBackend.fromStorage("TEN"))
    }
}
