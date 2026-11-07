package com.crunzex.linuxondex.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestActivityMonitorTest {

    @Test
    fun `cpu ticks sum user, system and reaped children`() {
        val stat = "1234 (node (worker)) S 1 1234 1234 0 -1 4194560 100 0 0 0 " +
            "70 30 5 2 20 0 11 0 100 1000 200"
        assertEquals(107L, GuestActivityMonitor.parseCpuTicks(stat))
    }

    @Test
    fun `malformed stat lines are ignored`() {
        assertNull(GuestActivityMonitor.parseCpuTicks("garbage"))
        assertNull(GuestActivityMonitor.parseCpuTicks("1 (x) S 1 2"))
    }

    @Test
    fun `activity needs cpu or network above threshold`() {
        assertFalse(GuestActivityMonitor.Sample(10, 1_000).isActive(60, 16_384))
        assertTrue(GuestActivityMonitor.Sample(61, 0).isActive(60, 16_384))
        assertTrue(GuestActivityMonitor.Sample(0, 20_000).isActive(60, 16_384))
    }
}
