package com.noresign.paboot.network.llm

import com.noresign.paboot.network.llm.dto.LlmRequest
import com.noresign.paboot.network.llm.dto.LlmResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [RoutingLlmProvider] 单测：验证「模型 id → 供应商」的路由与未知模型的兜底。
 *
 * `ModelResolver` 是接口（依赖倒置），所以这里直接用一个匿名实现即可，无需引入业务层的模型配置。
 */
class RoutingLlmProviderTest {

    private class StubProvider(private val tag: String) : LlmProvider {
        override fun stream(request: LlmRequest): Flow<LlmStreamEvent> =
            flow { emit(LlmStreamEvent.Completed(LlmResponse(content = tag))) }

        override suspend fun generate(request: LlmRequest) = LlmResponse(content = tag)
    }

    private val resolver = object : ModelResolver {
        override fun resolveProvider(modelId: String): String? = when (modelId) {
            "m-a" -> "a"
            "m-b" -> "b"
            else -> null
        }
    }

    private fun routing() = RoutingLlmProvider(
        providers = mapOf("a" to StubProvider("a"), "b" to StubProvider("b")),
        resolver = resolver,
    )

    @Test
    fun `model id routes to its provider`() = runTest {
        assertEquals("a", routing().collectStream(LlmRequest(model = "m-a", messages = emptyList())).content)
        assertEquals("b", routing().collectStream(LlmRequest(model = "m-b", messages = emptyList())).content)
    }

    @Test
    fun `unknown model falls back to the first provider`() = runTest {
        val routing = routing()
        assertEquals("a", routing.collectStream(LlmRequest(model = "nope", messages = emptyList())).content)
        assertEquals("a", routing.collectStream(LlmRequest(messages = emptyList())).content)
    }

    @Test
    fun `non stream call is routed too`() = runTest {
        assertEquals("b", routing().generate(LlmRequest(model = "m-b", messages = emptyList())).content)
    }
}
