package tv.own.owntv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Settings → EPG Sources showed the guide address with the account's user name and password. The edit field
 * now shows them as •••, and saving must never store a placeholder as a credential.
 */
class EpgUrlMaskTest {

    // Placeholder account and host, not a real provider.
    private val stored = "http://guide.example.test:8080/xmltv.php?username=acct123&password=s3cr3t"
    private val field = EpgUrlMask.editable(stored)

    private fun assertNoMask(url: String) {
        assertFalse("placeholder saved: $url", url.contains(EpgUrlMask.MASK_CHAR))
        assertFalse("placeholder saved: $url", url.contains("***"))
    }

    @Test
    fun `the account never appears in the list or the edit field`() {
        for (shown in listOf(EpgUrlMask.display(stored), field)) {
            assertFalse(shown.contains("acct123"))
            assertFalse(shown.contains("s3cr3t"))
        }
        assertEquals("http://guide.example.test:8080/xmltv.php?username=•••&password=•••", field)
    }

    @Test
    fun `unchanged credentials are saved exactly as stored`() {
        assertEquals(stored, EpgUrlMask.restore(field, stored))
    }

    @Test
    fun `changing the host keeps the stored account`() {
        val edited = field.replace("guide.example.test:8080", "guide2.example.test")
        assertEquals("http://guide2.example.test/xmltv.php?username=acct123&password=s3cr3t", EpgUrlMask.restore(edited, stored))
    }

    @Test
    fun `partly deleting a mask keeps the stored credential`() {
        for (partial in listOf("password=••", "password=•", "password=• •")) {
            val restored = EpgUrlMask.restore(field.replace("password=•••", partial), stored)
            assertEquals(stored, restored)
            assertNoMask(restored)
        }
    }

    @Test
    fun `typing into a mask without clearing it keeps the stored credential`() {
        val restored = EpgUrlMask.restore(field.replace("password=•••", "password=•x•"), stored)
        assertEquals(stored, restored)
        val appended = EpgUrlMask.restore(field.replace("username=•••", "username=•••new"), stored)
        assertEquals(stored, appended)
    }

    @Test
    fun `fully replacing a credential saves the new value - including characters like star`() {
        val edited = field.replace("password=•••", "password=new*pass")
        assertEquals("http://guide.example.test:8080/xmltv.php?username=acct123&password=new*pass", EpgUrlMask.restore(edited, stored))
        val both = field.replace("username=•••", "username=other").replace("password=•••", "password=pw2")
        assertEquals("http://guide.example.test:8080/xmltv.php?username=other&password=pw2", EpgUrlMask.restore(both, stored))
    }

    @Test
    fun `a pasted new address is saved as typed`() {
        val pasted = "https://other.example.test/epg.xml.gz"
        assertEquals(pasted, EpgUrlMask.restore(pasted, stored))
    }

    @Test
    fun `a mask the rules cannot place is dropped, never saved`() {
        val renamed = field.replace("password=•••", "pwd=•••")
        val restored = EpgUrlMask.restore(renamed, stored)
        assertNoMask(restored)
        assertFalse(restored.contains("s3cr3t"))
    }

    @Test
    fun `addresses without credentials are unchanged`() {
        val plain = "https://epg.example.test/guide.xml"
        assertEquals(plain, EpgUrlMask.display(plain))
        assertEquals(plain, EpgUrlMask.editable(plain))
        assertEquals(plain, EpgUrlMask.restore(plain, plain))
    }

    @Test
    fun `user-info and path credentials, partly edited, are restored too`() {
        val withUserInfo = "http://acct123:s3cr3t@guide.example.test/epg.xml"
        val ui = EpgUrlMask.editable(withUserInfo)
        assertFalse(ui.contains("s3cr3t"))
        assertEquals(withUserInfo, EpgUrlMask.restore(ui, withUserInfo))
        assertEquals("http://acct123:s3cr3t@guide.example.test/epg2.xml", EpgUrlMask.restore(ui.replace("•••", "••").replace("epg.xml", "epg2.xml"), withUserInfo))

        val withPath = "http://guide.example.test/live/acct123/s3cr3t/guide.xml"
        val p = EpgUrlMask.editable(withPath)
        assertFalse(p.contains("s3cr3t"))
        assertEquals(withPath, EpgUrlMask.restore(p.replaceFirst("•••", "•"), withPath))
        assertEquals("http://guide.example.test/live/acct123/newpw/guide.xml", EpgUrlMask.restore(p.replace("•••/guide", "newpw/guide"), withPath))
    }

    @Test
    fun `a new source has nothing to restore and saves what was typed`() {
        val typed = "http://guide.example.test/xmltv.php?username=me&password=p*w"
        assertEquals(typed, EpgUrlMask.restore(typed, ""))
    }
}
