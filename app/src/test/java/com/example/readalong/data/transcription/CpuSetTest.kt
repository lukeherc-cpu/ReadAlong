package com.example.readalong.data.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CpuSetTest {
    @Test fun readsAllowedList() {
        val status = "Name:\tdroid.readalong\nCpus_allowed:\tff\nCpus_allowed_list:\t0-7\nMems_allowed:\t1\n"
        assertEquals("0-7", CpuSet.parse(status))
        assertEquals("0-3,5-6", CpuSet.parse("Cpus_allowed_list:  0-3,5-6 \n"))
    }
    @Test fun missingOrEmptyIsUnknown() {
        assertNull(CpuSet.parse("Name:\tx\n"))
        assertNull(CpuSet.parse("Cpus_allowed_list:\t\n"))
    }
}
