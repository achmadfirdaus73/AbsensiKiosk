package com.example.data.local

import androidx.room.*
import com.example.data.model.BiometricTemplate
import kotlinx.coroutines.flow.Flow

@Dao
interface BiometricTemplateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: BiometricTemplate): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplates(templates: List<BiometricTemplate>)

    @Query("SELECT * FROM biometric_templates WHERE employeeId = :employeeId ORDER BY updatedAt DESC")
    fun getTemplatesForEmployee(employeeId: Long): Flow<List<BiometricTemplate>>

    @Query("SELECT * FROM biometric_templates WHERE employeeId = :employeeId ORDER BY updatedAt DESC")
    suspend fun getTemplatesForEmployeeDirect(employeeId: Long): List<BiometricTemplate>

    @Query("SELECT * FROM biometric_templates ORDER BY employeeId ASC, updatedAt DESC")
    fun getAllTemplates(): Flow<List<BiometricTemplate>>

    @Query("SELECT * FROM biometric_templates ORDER BY employeeId ASC, updatedAt DESC")
    suspend fun getAllTemplatesDirect(): List<BiometricTemplate>

    @Query("DELETE FROM biometric_templates WHERE employeeId = :employeeId")
    suspend fun deleteTemplatesByEmployeeId(employeeId: Long)

    @Delete
    suspend fun deleteTemplate(template: BiometricTemplate)

    @Query("DELETE FROM biometric_templates")
    suspend fun deleteAllTemplates()
}
