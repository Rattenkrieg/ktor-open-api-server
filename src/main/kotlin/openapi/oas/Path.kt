// Originally derived from kompendium (https://github.com/bkbnio/kompendium), MIT License
package openapi.oas

import kotlinx.serialization.Serializable

@Serializable
data class Path(
    var get: PathOperation? = null,
    var put: PathOperation? = null,
    var post: PathOperation? = null,
    var delete: PathOperation? = null,
    var patch: PathOperation? = null,
)

@Serializable
data class PathOperation(
    var tags: List<String>? = null,
    var summary: String? = null,
    var description: String? = null,
    var operationId: String? = null,
    var parameters: List<Parameter>? = null,
    var requestBody: Request? = null,
    var responses: Map<Int, Response>? = null,
    var deprecated: Boolean = false,
    var security: List<Map<String, List<String>>>? = null,
)
