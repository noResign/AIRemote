package com.noresign.paboot.network.llm

import com.noresign.paboot.network.llm.dto.LlmRequest
import com.noresign.paboot.network.llm.dto.LlmResponse
import kotlinx.coroutines.flow.Flow

/**
 * 按 `model` 路由到对应供应商：
 * `request.model → ModelResolver.resolveProvider() → providers[name]`；
 * 模型未知或未注册时用 `fallback`。
 *
 * 对应 Java 版 `module-ai` 的 `RoutingLlmProvider`。
 */
class RoutingLlmProvider(
    private val providers: Map<String, LlmProvider>,
    private val resolver: ModelResolver? = null,
    fallback: LlmProvider? = null,
) : LlmProvider {

    private val fallbackProvider: LlmProvider

    init {
        require(providers.isNotEmpty()) { "RoutingLlmProvider requires at least one provider" }
        // 默认兜底取字典序最小的 provider，避免依赖 Map 迭代顺序
        fallbackProvider = fallback ?: providers.entries.minByOrNull { it.key }!!.value
    }

    private fun resolve(request: LlmRequest): LlmProvider {
        val name = request.model?.let { resolver?.resolveProvider(it) }
        return name?.let { providers[it] } ?: fallbackProvider
    }

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> = resolve(request).stream(request)

    override suspend fun generate(request: LlmRequest): LlmResponse = resolve(request).generate(request)

    /** 暴露底层 provider（例如需要直接访问某个供应商时）。 */
    fun providers(): Map<String, LlmProvider> = providers
}
