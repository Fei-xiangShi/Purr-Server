package life.fxs.purr.server.application

class ApplicationException(
    val error: ApplicationError,
    override val message: String,
) : RuntimeException(message)

enum class ApplicationError {
    UNAUTHENTICATED,
    FORBIDDEN,
    NOT_FOUND,
    INVALID_ARGUMENT,
    CONFLICT,
    EXTERNAL_DEPENDENCY,
    TEMPORARILY_UNAVAILABLE,
}
