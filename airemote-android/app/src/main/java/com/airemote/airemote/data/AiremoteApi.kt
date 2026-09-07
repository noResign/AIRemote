package com.airemote.airemote.data

import com.airemote.airemote.model.agent.AgentsResponse
import com.airemote.airemote.model.claude.ClaudeSessionsResponse
import com.airemote.airemote.model.common.OkResponse
import com.airemote.airemote.model.common.RenameRequest
import com.airemote.airemote.model.common.RenameResponse
import com.airemote.airemote.model.connect.HealthResponse
import com.airemote.airemote.model.event.EventsResponse
import com.airemote.airemote.model.event.PermissionDecisionRequest
import com.airemote.airemote.model.session.RunsResponse
import com.airemote.airemote.model.session.SessionDetailResponse
import com.airemote.airemote.model.session.SessionsResponse
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
