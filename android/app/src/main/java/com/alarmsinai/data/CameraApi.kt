package com.alarmsinai.data

import com.alarmsinai.data.model.CameraSaveResponse
import com.alarmsinai.data.model.CameraSettingsResponse
import com.alarmsinai.data.model.CamerasOverview
import com.alarmsinai.data.model.EventsResponse
import com.alarmsinai.data.model.FacesResponse
import com.alarmsinai.data.model.GenericResponse
import com.alarmsinai.data.model.ScanResponse
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** server.js /cameras/* — snapshots and video are fetched as raw URLs, not through this. */
interface CameraApi {
    @GET("cameras/")
    suspend fun overview(): CamerasOverview

    @POST("cameras/scan")
    suspend fun scan(): ScanResponse

    @PUT("cameras/settings")
    suspend fun saveSettings(@Body body: Map<String, @JvmSuppressWildcards Any?>): CameraSettingsResponse

    @POST("cameras/siren/stop")
    suspend fun stopSiren(@Body body: Map<String, String> = emptyMap()): GenericResponse

    @POST("cameras/")
    suspend fun addCamera(@Body body: Map<String, @JvmSuppressWildcards Any?>): CameraSaveResponse

    @PUT("cameras/{id}")
    suspend fun updateCamera(@Path("id") id: String, @Body body: Map<String, @JvmSuppressWildcards Any?>): CameraSaveResponse

    @DELETE("cameras/{id}")
    suspend fun deleteCamera(@Path("id") id: String): CameraSaveResponse

    @GET("cameras/events")
    suspend fun events(
        @Query("camera") camera: String? = null,
        @Query("before") before: String? = null,
        @Query("limit") limit: Int = 50
    ): EventsResponse

    @GET("cameras/faces")
    suspend fun faces(): FacesResponse

    @POST("cameras/faces/{name}")
    suspend fun addFace(@Path("name") name: String, @Body image: RequestBody): FacesResponse

    @DELETE("cameras/faces/{name}")
    suspend fun deleteFace(@Path("name") name: String): FacesResponse
}
