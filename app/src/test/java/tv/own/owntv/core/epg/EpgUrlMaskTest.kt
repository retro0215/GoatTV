package tv.own.owntv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Settings → EPG Sources showed the guide address with the account's user name and password. */
class EpgUrlMaskTest {

    // Placeholder account and host, not a real provider.
    private val stored = "http://guide.example.test:8080/xmltv.php?username=acct123&password=s3cr3t"

    @Test
    fun `the account never appears in the displayed address`() {
        val shown = EpgUrlMask.display(stored)
        assertFalse(shown.contains("acct123"))
        assertFalse(shown.contains("s3cr3t"))
        assertEquals("http://guide.example.test:8080/xmltv.php?username=***&password=***", shown)
    }

    @Test
    fun `saving without touching the address keeps it exactly`() {
        assertEquals(stored, EpgUrlMask.restore(EpgUrlMask.display(stored), stored))
    }

    @Test
    fun `changing the host keeps the stored account`() {
        val edited = EpgUrlMask.display(stored).replace("guide.example.test:8080", "guide2.example.test")
        assertEquals("http://guide2.example.test/xmltv.php?username=acct123&password=s3cr3t", EpgUrlMask.restore(edited, stored))
    }

    @Test
    fun `typing a new password replaces only the password`() {
        val edited = EpgUrlMask.display(stored).replace("password=***", "password=newpass")
        assertEquals("http://guide.example.test:8080/xmltv.php?username=acct123&password=newpass", EpgUrlMask.restore(edited, stored))
    }

    @Test
    fun `a pasted new address is saved as typed`() {
        val pasted = "https://other.example.test/epg.xml.gz"
        assertEquals(pasted, EpgUrlMask.restore(pasted, stored))
    }

    @Test
    fun `addresses without credentials are unchanged`() {
        val plain = "https://epg.example.test/guide.xml"
        assertEquals(plain, EpgUrlMask.display(plain))
        assertEquals(plain, EpgUrlMask.restore(plain, plain))
    }

    @Test
    fun `user-info and path credentials are restored too`() {
        val withUserInfo = "http://acct123:s3cr3t@guide.example.test/epg.xml"
        assertFalse(EpgUrlMask.display(withUserInfo).contains("s3cr3t"))
        assertEquals(withUserInfo, EpgUrlMask.restore(EpgUrlMask.display(withUserInfo), withUserInfo))
        val editedUserInfo = EpgUrlMask.display(withUserInfo).replace("epg.xml", "epg2.xml")
        assertEquals("http://acct123:s3cr3t@guide.example.test/epg2.xml", EpgUrlMask.restore(editedUserInfo, withUserInfo))

        val withPath = "http://guide.example.test/live/acct123/s3cr3t/guide.xml"
        val shownPath = EpgUrlMask.display(withPath)
        assertFalse(shownPath.contains("s3cr3t"))
        assertEquals(
            "http://guide.example.test/live/acct123/s3cr3t/guide2.xml",
            EpgUrlMask.restore(shownPath.replace("guide.xml", "guide2.xml"), withPath),
        )
    }

    @Test
    fun `a new source has nothing to restore`() {
        val typed = "http://guide.example.test/xmltv.php?username=***&password=x"
        assertEquals(typed, EpgUrlMask.restore(typed, ""))
    }
}
