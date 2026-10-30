package uz.alifbo.klaviatura

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordAndVaultTest {

    @Test
    fun yaratilganParolKuchli() {
        val rnd = SecureRandom()
        repeat(200) {
            val p = PasswordGen.generate(16, rnd)
            assertEquals(16, p.length)
            assertTrue(p.any { it.isLowerCase() })
            assertTrue(p.any { it.isUpperCase() })
            assertTrue(p.any { it.isDigit() })
            assertTrue(p.any { !it.isLetterOrDigit() })
            // adashtiradigan belgilar yo'q
            assertTrue(p.none { it in "0O1lI" })
            assertTrue(PasswordGen.strength(p) >= 3)
        }
        assertNotEquals(PasswordGen.generate(16, rnd), PasswordGen.generate(16, rnd))
    }

    @Test
    fun zaifParollarAniqlanadi() {
        for (w in listOf("123456", "password", "qwerty123", "parol123", "aaaaaaaa", "12345678", "salom1")) {
            assertTrue(w, PasswordGen.strength(w) <= 1)
        }
        assertTrue(PasswordGen.strength("Tog'daGi-qor7#Yuz") >= 3)
    }

    @Test
    fun omborBirIlovadaBirLogin() {
        val s = VaultStore()
        assertTrue(s.upsert(Cred("1", "org.telegram", "Telegram", "ali@mail.uz", "eski", 1000)))
        // xuddi shu login, yangi parol -> yangilanadi, yangi yozuv qo'shilmaydi
        assertTrue(s.upsert(Cred("2", "org.telegram", "Telegram", "ALI@mail.uz", "yangi", 2000)))
        assertEquals(1, s.size)
        assertEquals("yangi", s.forApp("org.telegram")[0].pass)
        // eskiroq yozuv yangisini bosib ketmaydi
        assertFalse(s.upsert(Cred("3", "org.telegram", "Telegram", "ali@mail.uz", "juda eski", 500)))
        // boshqa ilova — alohida
        assertTrue(s.upsert(Cred("4", "uz.click", "Click", "+998901234567", "p", 3000)))
        assertEquals(2, s.size)
        assertEquals("uz.click", s.all()[0].app)
    }

    @Test
    fun omborSatrlariQaytaOqiladi() {
        val s = VaultStore()
        s.upsert(Cred("a1", "uz.app", "Ilova\tnomi", "user\nname", "p\tass:ʼö", 42))
        s.upsert(Cred("b2", "com.x", "", "", "x", 43))
        val back = VaultStore.parse(s.serialize())
        assertEquals(s.all(), back.all())
        assertNull(VaultStore.parseLine("buzuq"))
        assertTrue(s.remove("a1"))
        assertEquals(1, s.size)
    }
}
