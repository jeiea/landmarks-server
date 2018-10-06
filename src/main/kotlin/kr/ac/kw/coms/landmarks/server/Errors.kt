package kr.ac.kw.coms.landmarks.server

import io.ktor.application.ApplicationCall
import io.ktor.http.HttpStatusCode
import java.io.PrintWriter
import java.io.StringWriter

data class ValidException(
  val msg: String,
  val code: HttpStatusCode = HttpStatusCode.BadRequest
) : Throwable()

fun stacktraceToString(cause: Throwable): String {
  return StringWriter().also { sw ->
    cause.printStackTrace(PrintWriter(sw))
  }.toString()
}

fun notFoundPage(): Nothing {
  throw ValidException("Not found", HttpStatusCode.NotFound)
}

fun errorPage(msg: String): Nothing {
  throw ValidException(msg)
}

fun getIntParam(call: ApplicationCall, name: String): Int {
  return call.parameters[name]?.toIntOrNull() ?: errorPage("$name not valid")
}

fun getParamId(call: ApplicationCall): Int {
  return getIntParam(call, "id")
}