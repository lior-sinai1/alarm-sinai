package com.alarmsinai.data

import com.alarmsinai.data.model.ArmRequest
import com.alarmsinai.data.model.GenericResponse
import com.alarmsinai.data.model.StatusResponse
import com.alarmsinai.data.model.TokenRequest
import com.alarmsinai.data.model.WriteCoilRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface ApiService {
    @GET("status")
    suspend fun getStatus(): StatusResponse

    @POST("arm")
    suspend fun arm(@Body request: ArmRequest): GenericResponse

    @POST("disarm")
    suspend fun disarm(): GenericResponse

    @POST("register-token")
    suspend fun registerToken(@Body request: TokenRequest): GenericResponse

    @POST("write-coil")
    suspend fun writeCoil(@Body request: WriteCoilRequest): GenericResponse
}
