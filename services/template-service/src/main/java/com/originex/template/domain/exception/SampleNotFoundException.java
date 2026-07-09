package com.originex.template.domain.exception;

import java.util.UUID;

public class SampleNotFoundException extends RuntimeException {

    private final UUID sampleId;

    public SampleNotFoundException(UUID sampleId) {
        super("Sample not found: " + sampleId);
        this.sampleId = sampleId;
    }

    public UUID getSampleId() {
        return sampleId;
    }
}
