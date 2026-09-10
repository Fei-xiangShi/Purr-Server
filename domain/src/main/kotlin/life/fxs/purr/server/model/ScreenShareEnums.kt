package life.fxs.purr.server.model

enum class ScreenShareSource(val wireValue: String) {
    OBS("obs"),
    MOBILE("mobile"),
}

enum class ScreenShareStatus(val wireValue: String) {
    AUTHORIZED("authorized"),
    LIVE("live"),
    STOPPING("stopping"),
    STOPPED("stopped"),
    EXPIRED("expired"),
    FAILED("failed"),
}

enum class ScreenSharePurpose(val wireValue: String) {
    PUBLISH("publish"),
    READ("read"),
}
