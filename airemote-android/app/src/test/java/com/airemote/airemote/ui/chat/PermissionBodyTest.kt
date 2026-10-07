package com.airemote.airemote.ui.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionBodyTest {
    @Test
    fun codexShowsEveryFileAndItsDiff() {
        val input = Json.parseToJsonElement("""{
            "changes": [
                {"path":"/project/a.kt","kind":{"type":"update","move_path":"/project/c.kt"},"diff":"-old\n+new"},
                {"path":"/project/b.kt","kind":{"type":"add"},"diff":"+created"}
            ]
        }""")
        val text = permissionBody("Edit", input)
        assertTrue(text.contains("/project/a.kt → /project/c.kt [update]"))
        assertTrue(text.contains("-old\n+new"))
        assertTrue(text.contains("/project/b.kt [add]"))
        assertTrue(text.contains("+created"))
    }

    @Test
    fun missingCodexChangesStillShowRequestedRootAndReason() {
        val input = Json.parseToJsonElement("""{"changes":null,"grantRoot":"/srv/data","reason":"update config"}""")
        val text = permissionBody("Edit", input)
        assertTrue(text.contains("/srv/data"))
        assertTrue(text.contains("update config"))
    }

    @Test
    fun claudeEditKeepsItsExistingPreview() {
        val input = Json.parseToJsonElement("""{"file_path":"a.kt","old_string":"before","new_string":"after"}""")
        assertEquals("a.kt\n\n--- old\nbefore\n+++ new\nafter", permissionBody("Edit", input))
    }
}
