package com.example

import com.example.util.BiometricEncryptionHelper
import com.example.util.FaceBiometricHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricEngineUnitTest {

    @Test
    fun testCosineSimilarityIdenticalMobileFaceNetVectors() {
        val dim = FaceBiometricHelper.EMBEDDING_DIM
        val v1 = FloatArray(dim) { 0.5f }
        val v2 = FloatArray(dim) { 0.5f }
        FaceBiometricHelper.l2Normalize(v1)
        FaceBiometricHelper.l2Normalize(v2)
        val sim = FaceBiometricHelper.calculateSimilarity(v1, v2)
        assertTrue(sim > 0.99f)
    }

    @Test
    fun testCosineSimilarityOrthogonalVectors() {
        val dim = FaceBiometricHelper.EMBEDDING_DIM
        val v1 = FloatArray(dim) { if (it < dim / 2) 1.0f else 0.0f }
        val v2 = FloatArray(dim) { if (it >= dim / 2) 1.0f else 0.0f }
        FaceBiometricHelper.l2Normalize(v1)
        FaceBiometricHelper.l2Normalize(v2)
        val sim = FaceBiometricHelper.calculateSimilarity(v1, v2)
        assertEquals(0.0f, sim, 0.01f)
    }

    @Test
    fun testEncryptedEmbeddingSerializationDeserialization() {
        val dim = FaceBiometricHelper.EMBEDDING_DIM
        val original = FloatArray(dim) { (it * 0.01f) - 0.5f }
        val encryptedJson = FaceBiometricHelper.serializeEmbedding(original)

        // Verify it is stored encrypted with ENC: prefix
        assertTrue("Stored embedding must be encrypted", encryptedJson.startsWith("ENC:"))

        // Verify decryption reconstitutes original vector perfectly
        val deserialized = FaceBiometricHelper.deserializeEmbedding(encryptedJson)
        assertNotNull(deserialized)
        assertEquals(dim, deserialized?.size)
        assertEquals(original[0], deserialized!![0], 0.001f)
        assertEquals(original[dim - 1], deserialized[dim - 1], 0.001f)
    }

    @Test
    fun testLegacyEmbeddingBackwardCompatibility() {
        // Plain JSON without encryption prefix should decrypt / deserialize gracefully
        val legacy128 = FloatArray(128) { 0.2f }
        val plainJson = "[${legacy128.joinToString(",")}]"
        val deserialized = FaceBiometricHelper.deserializeEmbedding(plainJson)
        assertNotNull(deserialized)
        assertEquals(128, deserialized?.size)
    }
}
