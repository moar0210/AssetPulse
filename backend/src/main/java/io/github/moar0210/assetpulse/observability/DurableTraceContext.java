package io.github.moar0210.assetpulse.observability;

public record DurableTraceContext(String traceParent, String traceState) {

    public DurableTraceContext {
        traceParent = normalize(traceParent);
        traceState = traceParent == null ? null : normalize(traceState);
    }

    public static DurableTraceContext empty() {
        return new DurableTraceContext(null, null);
    }

    public boolean isPresent() {
        return traceParent != null;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
