package com.hayden.testgraphsdk.sdk;

public enum NodeStatus {
    PASSED, FAILED, ERRORED, SKIPPED;

    public String wire() { return name().toLowerCase(); }
}
