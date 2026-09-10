package com.airemote.network.airemote

import com.airemote.network.airemote.dto.AgentsResponse
import com.airemote.network.airemote.dto.ClaudeSessionsResponse
import com.airemote.network.airemote.dto.EventsResponse
import com.airemote.network.airemote.dto.HealthResponse
import com.airemote.network.airemote.dto.OkResponse
import com.airemote.network.airemote.dto.PermissionDecisionRequest
import com.airemote.network.airemote.dto.RenameRequest
import com.airemote.network.airemote.dto.RenameResponse
import com.airemote.network.airemote.dto.RunsResponse
import com.airemote.network.airemote.dto.SessionDetailResponse
import com.airemote.network.airemote.dto.SessionsResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface AiremoteApi {

    @GET("api/health")
    suspend fun health(): HealthResponse

    @GET("api/sessions")
    suspend fun sessions(): SessionsResponse

    @GET("api/sessions/{id}")
    suspend fun session(@Path("id") id: String): SessionDetailResponse

    @PATCH("api/sessions/{id}")
    suspend fun renameSession(@Path("id") id: String, @Body body: RenameRequest): RenameResponse

    @DELETE("api/sessions/{id}")
    suspend fun deleteSession(@Path("id") id: String): OkResponse

    @GET("api/agents")
    suspend fun agents(): AgentsResponse

    @GET("api/claude-sessions")
    suspend fun claudeSessions(): ClaudeSessionsResponse

    @GET("api/runs")
    suspend fun runs(): RunsResponse

    @POST("api/runs/{id}/cancel")
    suspend fun cancelRun(@Path("id") id: String): OkResponse

    @GET("api/runs/{id}/events")
    suspend fun runEvents(@Path("id") id: String, @Query("after") after: Long? = null): EventsResponse

    @POST("api/permissions/{id}/decision")
    suspend fun decidePermission(
        @Path("id") id: String,
        @Body body: PermissionDecisionRequest,
    ): OkResponse
}