package com.example

import com.example.util.FaceBiometricHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricEngineUnitTest {

    @Test
    fun testCosineSimilarityIdenticalVectors() {
        val v1 = FloatArray(128) { 0.5f }
        val v2 = FloatArray(128) { 0.5f }
        val sim = FaceBiometricHelper.calculateSimilarity(v1, v2)
        assertTrue(sim > 0.99f)
    }

    @Test
    fun testCosineSimilarityOrthogonalVectors() {
        val v1 = FloatArray(128) { if (it < 64) 1.0f else 0.0f }
        val v2 = FloatArray(128) { if (it >= 64) 1.0f else 0.0f }
        val sim = FaceBiometricHelper.calculateSimilarity(v1, v2)
        assertEquals(0.0f, sim, 0.01f)
    }

    @Test
    fun testEmbeddingSerializationDeserialization() {
        val original = FloatArray(128) { (it * 0.01f) - 0.5f }
        val json = FaceBiometricHelper.serializeEmbedding(original)
        val deserialized = FaceBiometricHelper.deserializeEmbedding(json)
        assertEquals(original.size, deserialized?.size)
        assertEquals(original[0], deserialized!![0], 0.001f)
        assertEquals(original[127], deserialized[127], 0.001f)
    }
}
