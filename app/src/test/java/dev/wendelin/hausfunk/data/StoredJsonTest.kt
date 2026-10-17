package dev.wendelin.hausfunk.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class StoredJsonTest {
    private fun <T> read(serializer: JsonSerializer<T>, text: String): T =
        runBlocking { serializer.readFrom(text.byteInputStream()) }

    @Test
    fun olderSettingsGetTheNewDefaults() {
        val settings = read(JsonSerializer(Settings.serializer(), Settings()), """{"deviceName":"Papa","unknown":1}""")
        assertEquals("Papa", settings.deviceName)
        assertEquals(Settings.DEFAULT_ALARM_GRACE_SECONDS, settings.alarmGraceSeconds)
        assertEquals(SoundPrefs(), settings.soundFor("any"))
    }

    @Test
    fun emptyDocumentsAreDefaults() {
        assertEquals(Settings(), read(JsonSerializer(Settings.serializer(), Settings()), "{}"))
        assertEquals(TrustStore(), read(JsonSerializer(TrustStore.serializer(), TrustStore()), "{}"))
    }
}
