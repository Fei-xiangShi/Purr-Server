package life.fxs.purr.server.model

enum class CallState(val wireValue: String) {
    WAITING("waiting"),
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
    DELETED("deleted"),
    ;

    /** Provider egress may still be writing, so the room must outlive the call until it settles. */
    val isInFlight: Boolean
        get() = this == STARTING || this == RECORDING || this == STOPPING
}
