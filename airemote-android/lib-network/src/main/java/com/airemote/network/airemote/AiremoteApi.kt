package com.airemote.network.airemote

import com.airemote.network.airemote.dto.AgentsResponse
import com.airemote.network.airemote.dto.ChangesResponse
import com.airemote.network.airemote.dto.ClaudeSessionsResponse
import com.airemote.network.airemote.dto.DiffResponse
import com.airemote.network.airemote.dto.ConfigResponse
import com.airemote.network.airemote.dto.ConfigUpdateResponse
import com.airemote.network.airemote.dto.CreateWorkspaceRequest
import com.airemote.network.airemote.dto.DirectoriesResponse
import com.airemote.network.airemote.dto.EventsResponse
import com.airemote.network.airemote.dto.FileContentDto
import com.airemote.network.airemote.dto.FilesResponse
import com.airemote.network.airemote.dto.HealthResponse
import com.airemote.network.airemote.dto.OkResponse
import com.airemote.network.airemote.dto.PermissionDecisionRequest
import com.airemote.network.airemote.dto.RenameRequest
import com.airemote.network.airemote.dto.RenameResponse
import com.airemote.network.airemote.dto.RunsResponse
import com.airemote.network.airemote.dto.SessionDetailResponse
import com.airemote.network.airemote.dto.SessionPermissionsResponse
import com.airemote.network.airemote.dto.SessionsResponse
import com.airemote.network.airemote.dto.UpdateConfigRequest
import com.airemote.network.airemote.dto.UpdateSessionPermissionsRequest
import com.airemote.network.airemote.dto.UpdateWorkspaceRequest
import com.airemote.network.airemote.dto.WorkspaceDirsRequest
import com.airemote.network.airemote.dto.WorkspaceDirsResponse
import com.airemote.network.airemote.dto.WorkspaceResponse
import com.airemote.network.airemote.dto.WorkspaceShortcutsResponse
import com.airemote.network.airemote.dto.WorkspacesResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface AiremoteApi {

    @GET("api/health")
    suspend fun health(): HealthResponse

    @GET("api/sessions")
    suspend fun sessions(@Query("workspaceId") workspaceId: String? = null): SessionsResponse

    @GET("api/sessions/{id}")
    suspend fun session(@Path("id") id: String): SessionDetailResponse

    @PATCH("api/sessions/{id}")
    suspend fun renameSession(@Path("id") id: String, @Body body: RenameRequest): RenameResponse

    @DELETE("api/sessions/{id}")
    suspend fun deleteSession(@Path("id") id: String): OkResponse

    @GET("api/agents")
    suspend fun agents(): AgentsResponse

    @GET("api/claude-sessions")
    suspend fun claudeSessions(@Query("workspaceId") workspaceId: String? = null): ClaudeSessionsResponse

    @GET("api/runs")
    suspend fun runs(@Query("workspaceId") workspaceId: String? = null): RunsResponse

    @POST("api/runs/{id}/cancel")
    suspend fun cancelRun(@Path("id") id: String): OkResponse

    @GET("api/runs/{id}/events")
    suspend fun runEvents(@Path("id") id: String, @Query("after") after: Long? = null): EventsResponse

    @POST("api/permissions/{id}/decision")
    suspend fun decidePermission(
        @Path("id") id: String,
        @Body body: PermissionDecisionRequest,
    ): OkResponse

    // ---- Workspace / Config ----

    @GET("api/workspaces")
    suspend fun workspaces(): WorkspacesResponse

    @POST("api/workspaces")
    suspend fun createWorkspace(@Body body: CreateWorkspaceRequest): WorkspaceResponse

    @PATCH("api/workspaces/{id}")
    suspend fun updateWorkspace(@Path("id") id: String, @Body body: UpdateWorkspaceRequest): WorkspaceResponse

    @DELETE("api/workspaces/{id}")
    suspend fun deleteWorkspace(@Path("id") id: String, @Query("cascade") cascade: Boolean? = null): OkResponse

    @GET("api/fs/directories")
    suspend fun directories(
        @Query("path") path: String? = null,
        @Query("showHidden") showHidden: Boolean? = null,
    ): DirectoriesResponse

    /** 给工作区添加一个额外可达目录（附加目录）。 */
    @POST("api/workspaces/{id}/dirs")
    suspend fun addWorkspaceDir(
        @Path("id") id: String,
        @Body body: WorkspaceDirsRequest,
    ): WorkspaceDirsResponse

    // Retrofit 的 @DELETE 不允许带 body，故用 @HTTP。
    @HTTP(method = "DELETE", path = "api/workspaces/{id}/dirs", hasBody = true)
    suspend fun removeWorkspaceDir(
        @Path("id") id: String,
        @Body body: WorkspaceDirsRequest,
    ): WorkspaceDirsResponse

    /** 文件 Tab 的浏览书签：只多一个 tab，不授予 agent 权限。 */
    @POST("api/workspaces/{id}/shortcuts")
    suspend fun addWorkspaceShortcut(
        @Path("id") id: String,
        @Body body: WorkspaceDirsRequest,
    ): WorkspaceShortcutsResponse

    @HTTP(method = "DELETE", path = "api/workspaces/{id}/shortcuts", hasBody = true)
    suspend fun removeWorkspaceShortcut(
        @Path("id") id: String,
        @Body body: WorkspaceDirsRequest,
    ): WorkspaceShortcutsResponse

    /** `root` 省略时用工作区主目录；给了就能浏览工作区之外的目录。 */
    @GET("api/changes")
    suspend fun changes(
        @Query("workspaceId") workspaceId: String? = null,
        @Query("root") root: String? = null,
    ): ChangesResponse

    @GET("api/changes/diff")
    suspend fun changeDiff(
        @Query("workspaceId") workspaceId: String? = null,
        @Query("root") root: String? = null,
        @Query("path") path: String,
    ): DiffResponse

    @GET("api/files")
    suspend fun files(
        @Query("workspaceId") workspaceId: String? = null,
        @Query("root") root: String? = null,
        @Query("path") path: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("showHidden") showHidden: Boolean? = null,
        @Query("showIgnored") showIgnored: Boolean? = null,
    ): FilesResponse

    @GET("api/files/content")
    suspend fun fileContent(
        @Query("workspaceId") workspaceId: String? = null,
        @Query("root") root: String? = null,
        @Query("path") path: String,
    ): FileContentDto

    @GET("api/config")
    suspend fun config(): ConfigResponse

    @PATCH("api/config")
    suspend fun updateConfig(@Body body: UpdateConfigRequest): ConfigUpdateResponse

    // ---- Session permissions ----

    @GET("api/sessions/{id}/permissions")
    suspend fun sessionPermissions(@Path("id") id: String): SessionPermissionsResponse

    @PATCH("api/sessions/{id}/permissions")
    suspend fun updateSessionPermissions(
        @Path("id") id: String,
        @Body body: UpdateSessionPermissionsRequest,
    ): OkResponse

    @DELETE("api/sessions/{id}/permissions/grants/{toolName}")
    suspend fun deletePermissionGrant(
        @Path("id") id: String,
        @Path("toolName") toolName: String,
    ): OkResponse

    @DELETE("api/sessions/{id}/permissions/grants")
    suspend fun deletePermissionGrants(@Path("id") id: String): OkResponse
}