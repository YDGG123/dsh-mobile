package com.clarklevis.dsh.android

/** 无 query 回显的旧网关每次只允许一个请求，避免 A 的慢响应污染 B。 */
internal class AndroidRemoteSearch {
    data class Response(val acceptedQuery: String?, val nextQuery: String?, val matched: Boolean = false)
    private var desired = ""
    private var inFlight: String? = null
    private var blocked = false

    @Synchronized fun request(query: String): String? {
        desired = query
        if (query.isBlank() || inFlight != null || blocked) return null
        inFlight = query
        return query
    }

    @Synchronized fun response(echo: String?): Response {
        val active = inFlight ?: return Response(null, null)
        if (!echo.isNullOrBlank() && echo != active) return Response(null, null)
        inFlight = null
        val accepted = active.takeIf { it == desired && !blocked }
        val next = if (!blocked && desired.isNotBlank() && active != desired) desired.also { inFlight = it } else null
        return Response(accepted, next, matched = true)
    }

    @Synchronized fun timeout(query: String) {
        if (inFlight == query) {
            blocked = true
            inFlight = null
        }
    }

    @Synchronized fun disconnected() {
        inFlight = null
        blocked = false
    }
}
