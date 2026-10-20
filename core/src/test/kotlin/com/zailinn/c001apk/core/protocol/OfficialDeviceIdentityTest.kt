package com.zailinn.c001apk.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OfficialDeviceIdentityTest {
    private val copied = """
        设备ID: 0123456789abcdef
        ShuzlmID: DUsyntheticSZLM0123456789abcdef
        AndroidID: fedcba9876543210
        UUID: 00000000-0000-0000-0000-000000000000
    """.trimIndent()

    @Test
    fun readsOnlyTheShuzlmField() {
        assertEquals("DUsyntheticSZLM0123456789abcdef", OfficialDeviceIdentity.parse(copied))
        assertEquals("DUsyntheticSZLM0123456789abcdef", OfficialDeviceIdentity.parse("\uFEFFshuzlmid：DUsyntheticSZLM0123456789abcdef\r\n"))
    }

    @Test
    fun rejectsPlainOaidMissingOrEmptyIds() {
        for (text in listOf(
            "OAID: 0b5c6a8e-1f2d-4c3b-9a7e-6d5f4e3c2b1a",
            "ShuzlmID: null",
            "ShuzlmID: ",
            "ShuzlmID: 有中文",
            "ShuzlmID: DUaaaaaaaaaa\nShuzlmID: DUbbbbbbbbbb",
        )) {
            assertThrows(text, IllegalArgumentException::class.java) { OfficialDeviceIdentity.parse(text) }
        }
    }
}
