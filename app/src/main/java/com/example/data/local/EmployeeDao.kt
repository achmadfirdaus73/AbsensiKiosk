package com.example.data.local

import androidx.room.*
import com.example.data.model.Employee
import kotlinx.coroutines.flow.Flow

@Dao
interface EmployeeDao {
    @Query("SELECT * FROM employees WHERE isActive = 1 ORDER BY name ASC")
    fun getAllActiveEmployees(): Flow<List<Employee>>

    @Query("SELECT * FROM employees WHERE id = :id LIMIT 1")
    suspend fun getEmployeeById(id: Long): Employee?

    @Query("SELECT * FROM employees WHERE nik = :nik LIMIT 1")
    suspend fun getEmployeeByNik(nik: String): Employee?

    @Query("SELECT * FROM employees WHERE nik = :nik AND pin = :pin LIMIT 1")
    suspend fun getEmployeeByNikAndPin(nik: String, pin: String): Employee?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmployee(employee: Employee): Long

    @Update
    suspend fun updateEmployee(employee: Employee)

    @Delete
    suspend fun deleteEmployee(employee: Employee)

    @Query("SELECT COUNT(*) FROM employees WHERE isActive = 1")
    suspend fun getActiveEmployeeCount(): Int
}
