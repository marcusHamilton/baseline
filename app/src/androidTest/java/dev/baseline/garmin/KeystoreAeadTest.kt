package dev.baseline.garmin

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KeystoreAeadTest {
    private val aead = KeystoreAead("baseline-test-key")

    @Test
    fun roundTrips() {
        val plain = "garmin-api-key".toByteArray()
        assertArrayEquals(plain, aead.decrypt(aead.encrypt(plain)))
    }

    @Test
    fun encryptionsDiffer() {
        val plain = "same input".toByteArray()
        assertFalse(aead.encrypt(plain).contentEquals(aead.encrypt(plain)))
    }
}
