package com.noresign.paboot.network.llm

/**
 * 按模型 id 解析出供应商名。
 *
 * **依赖倒置**：接口定义在网络层，实现由业务层提供——这样网络层不必反向依赖业务层的
 * 模型配置服务（对应 Java 版 `module-ai` 的 `ModelConfigResolver`）。
 */
interface ModelResolver {
    /** @return 供应商名；模型未知 / 禁用时返回 `null`（由路由兜底）。 */
    fun resolveProvider(modelId: String): String?
}
