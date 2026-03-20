package life.fxs.purr.server.model

enum class CallState(val wireValue: String) {
    ACTIVE("active"),
    ENDED("ended"),
}

enum class RecordingStatus(val wireValue: String) {
    IDLE("idle"),
    STARTING("starting"),
    RECORDING("recording"),
    STOPPING("stopping"),
    STOPPED("stopped"),
    FAILED("failed"),
}
