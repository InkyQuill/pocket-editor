package net.inkyquill.pocketeditor.yandex

import okhttp3.Interceptor
import okhttp3.Response

class RedactingHttpLogger(
    private val sink: (String) -> Unit,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val origin = "${request.url.scheme}://${request.url.host}"
        return try {
            chain.proceed(request).also { response ->
                sink("${request.method} $origin/<redacted> -> ${response.code}")
            }
        } catch (error: Exception) {
            sink("${request.method} $origin/<redacted> -> ${error::class.simpleName}")
            throw error
        }
    }
}
