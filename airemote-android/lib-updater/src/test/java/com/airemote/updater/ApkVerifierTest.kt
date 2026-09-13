package com.airemote.updater

import com.airemote.updater.internal.ApkVerifier
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class ApkVerifierTest {

    @Test
    fun `computes sha256`() {
        val bytes = "hello airemote".toByteArray()
        val file = File.createTempFile("airemote-verifier", ".bin")
        try {
            file.writeBytes(bytes)
            val expected = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
            assertEquals(expected, ApkVerifier.sha256(file))
        } finally {
            file.delete()
        }
    }
}
