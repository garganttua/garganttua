package com.garganttua.core.workflow.renderer;

/** A data-flow that bypasses one or more intermediate stages, drawn as a side lane in the diagram. */
    record BypassFlow(String variable, int sourceStage, int targetStage) {
        boolean isActive(int currentStage) {
            return currentStage > sourceStage && currentStage <= targetStage;
        }
        boolean startsAt(int stageIdx) { return stageIdx == sourceStage; }
        boolean endsAt(int stageIdx) { return stageIdx == targetStage; }
    }
