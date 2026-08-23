package dev.magnor.kompakt.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThemeStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun missingFileDefaultsToLight() {
        val store = ThemeStore(File(tmp.root, "absent.txt"))
        assertEquals("absent file must resolve LIGHT", ThemePolarity.LIGHT, store.polarity.value)
    }

    @Test
    fun unknownContentFailsTowardLight() {
        val f = tmp.newFile()
        f.writeText("darkmode")
        val store = ThemeStore(f)
        assertEquals("garbage content must resolve LIGHT", ThemePolarity.LIGHT, store.polarity.value)
    }

    @Test
    fun caseAndWhitespaceTolerant() {
        val f = tmp.newFile()
        f.writeText("  inverted \n")
        val store = ThemeStore(f)
        assertEquals("case/whitespace tolerant", ThemePolarity.INVERTED, store.polarity.value)
    }

    @Test
    fun setPolarityUpdatesFlowImmediately() {
        val store = ThemeStore(File(tmp.root, "flow.txt"))
        store.setPolarity(ThemePolarity.INVERTED)
        assertEquals("flow updates synchronously", ThemePolarity.INVERTED, store.polarity.value)
    }

    @Test
    fun persistsAcrossStoreInstances() {
        val f = File(tmp.root, "persist.txt")
        ThemeStore(f).setPolarity(ThemePolarity.INVERTED)
        assertEquals(
            "value survives store recreation",
            ThemePolarity.INVERTED,
            ThemeStore(f).polarity.value,
        )
    }

    @Test
    fun noTmpFileLeftBehind() {
        val f = File(tmp.root, "clean.txt")
        ThemeStore(f).setPolarity(ThemePolarity.LIGHT)
        val siblings = f.parentFile!!.listFiles()!!.map { it.name }.sorted()
        assertEquals("atomic rename leaves no .tmp", listOf("clean.txt"), siblings)
    }
}
