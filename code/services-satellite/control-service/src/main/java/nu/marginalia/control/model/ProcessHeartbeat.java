package nu.marginalia.control.model;

public record ProcessHeartbeat(
        String processId,
        String processBase,
        String uuid,
        double lastSeenMillis,
        int progress,
        String status
) {
    public boolean isMissing() {
        return lastSeenMillis > 10000;
    }
    public boolean isStopped() {
        return "STOPPED".equals(status);
    }
    public String progressStyle() {
        if ("RUNNING".equals(status) && progress > 0) {
            return """
                    background: linear-gradient(90deg, #ccc %d%%, #ccc %d%%, #fff %d%%)
                    """.formatted(progress, progress, progress);
        }
        return "";
    }
}
