package com.kachat.app.services

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Plain Drive API v3 REST calls — no Google Java client library, just the app's existing
 * Retrofit/OkHttp stack (matches `NetworkService.kt`'s pattern) with the OAuth access token
 * attached as a Bearer header per call. Scoped entirely to `appDataFolder`: a hidden,
 * per-app storage area not visible in the user's regular Drive UI.
 */
interface GoogleDriveApi {

    /** Finds the backup file by name within appDataFolder — used to decide create-vs-update. */
    @GET("drive/v3/files")
    suspend fun listFiles(
        @Header("Authorization") authorization: String,
        @Query("spaces") spaces: String = "appDataFolder",
        @Query("q") query: String,
        @Query("fields") fields: String = "files(id,name,modifiedTime)"
    ): DriveFileListResponse

    /** Creates the backup file for the first time. */
    @Multipart
    @POST("upload/drive/v3/files?uploadType=multipart")
    suspend fun createFile(
        @Header("Authorization") authorization: String,
        @Part metadata: okhttp3.MultipartBody.Part,
        @Part content: okhttp3.MultipartBody.Part
    ): DriveFile

    /** Overwrites the backup file's content in place — the file is always a single, replaced-in-place copy, never accumulating duplicates. */
    @PATCH("upload/drive/v3/files/{fileId}?uploadType=media")
    suspend fun updateFileContent(
        @Header("Authorization") authorization: String,
        @Path("fileId") fileId: String,
        @Body content: RequestBody
    ): DriveFile

    /** Downloads the raw backup file content for restore. */
    @GET("drive/v3/files/{fileId}")
    suspend fun downloadFile(
        @Header("Authorization") authorization: String,
        @Path("fileId") fileId: String,
        @Query("alt") alt: String = "media"
    ): ResponseBody

    /** Permanently deletes the backup file — used by the "wipe account & Cloud" danger-zone action. */
    @DELETE("drive/v3/files/{fileId}")
    suspend fun deleteFile(
        @Header("Authorization") authorization: String,
        @Path("fileId") fileId: String
    )
}

data class DriveFileListResponse(val files: List<DriveFile>?)

data class DriveFile(val id: String?, val name: String? = null, val modifiedTime: String? = null)
