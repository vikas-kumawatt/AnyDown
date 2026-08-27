package com.anydown.downloader.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorsTest {

    @Test
    fun `detects login-walled content`() {
        assertEquals(
            Errors.Code.PRIVATE_CONTENT,
            Errors.classify(
                "ERROR: [instagram] Requested content is not available, " +
                    "login required to view this account"
            ).code,
        )
    }

    @Test
    fun `detects an unsupported url`() {
        assertEquals(
            Errors.Code.UNSUPPORTED_URL,
            Errors.classify("ERROR: Unsupported URL: https://example.com/x").code,
        )
    }

    @Test
    fun `detects removed content`() {
        assertEquals(
            Errors.Code.GONE,
            Errors.classify("ERROR: [youtube] abc: Video unavailable").code,
        )
    }

    @Test
    fun `detects network trouble`() {
        assertEquals(
            Errors.Code.NETWORK,
            Errors.classify("ERROR: Unable to download webpage: timed out").code,
        )
    }

    @Test
    fun `does not guess at unfamiliar errors`() {
        assertEquals(Errors.Code.FAILED, Errors.classify("ERROR: something new").code)
        assertEquals(Errors.Code.FAILED, Errors.classify(null).code)
    }

    @Test
    fun `ansi colour codes do not break matching`() {
        assertEquals(
            Errors.Code.PRIVATE_CONTENT,
            Errors.classify("[0;31mERROR:[0m This video is private").code,
        )
    }
}
