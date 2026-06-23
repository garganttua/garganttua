package com.garganttua.core.workflow.renderer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.garganttua.core.workflow.WorkflowStage;
import com.garganttua.core.workflow.chaining.CodeAction;

/**
 * Renders a human-readable textual representation of a workflow structure.
 *
 * <p>
 * This renderer produces an ANSI-colored box-drawing diagram showing stages,
 * scripts, data flows, bypass arrows, conditions, error handling, and code actions.
 * </p>
 *
 * @since 2.0.0-ALPHA01
 */
// AvoidDuplicateLiterals: box-drawing report builder — repeated ANSI/box fragments are layout, not logic.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class WorkflowRenderer extends AbstractRenderer {

    private final StageRenderer stageRenderer = new StageRenderer();

    /**
     * Renders the workflow as a formatted ANSI string.
     *
     * @param name            the workflow name
     * @param stages          the workflow stages
     * @param presetVariables the preset variables
     * @param inlineAll       whether all scripts are forced inline
     * @return the rendered workflow string
     */
    public String render(String name, List<WorkflowStage> stages,
                         Map<String, Object> presetVariables, boolean inlineAll) {
        StringBuilder sb = new StringBuilder();
        int boxWidth = 70;

        sb.append("\n");

        appendHeader(sb, name, boxWidth);

        Map<String, Integer> outputToStageIndex = new HashMap<>();
        Map<String, List<String>> stageOutputVars = new HashMap<>();
        collectDataFlow(stages, outputToStageIndex, stageOutputVars);

        Map<String, List<String>> configVarToStages = collectConfigUsage(stages, presetVariables);

        appendConfiguration(sb, boxWidth, presetVariables, inlineAll, configVarToStages);

        List<BypassFlow> bypassFlows = detectBypassFlows(stages, outputToStageIndex);
        Map<BypassFlow, Integer> bypassLanes = assignBypassLanes(bypassFlows);
        int maxLane = bypassLanes.values().stream().mapToInt(i -> i).max().orElse(-1);

        for (int stageIdx = 0; stageIdx < stages.size(); stageIdx++) {
            stageRenderer.appendStageBlock(sb, boxWidth, stages, stageIdx, inlineAll,
                    outputToStageIndex, stageOutputVars, bypassFlows, bypassLanes, maxLane);
        }

        if (!stages.isEmpty()) {
            var lastStage = stages.get(stages.size() - 1);
            List<String> finalOutputs = stageOutputVars.getOrDefault(lastStage.name(), List.of());
            if (!finalOutputs.isEmpty()) {
                sb.append("\n");
                sb.append(GREEN).append(BOLD).append("  ââ Final Outputs ").append("â".repeat(51)).append("â\n");
                for (String output : finalOutputs) {
                    sb.append("  â  ").append(RESET).append(MAGENTA).append("âââ ").append(RESET)
                      .append(GREEN).append(BOLD).append("@").append(output).append(RESET);
                    sb.append(pad(boxWidth - 7 - output.length()));
                    sb.append(GREEN).append(BOLD).append("â\n");
                }
                sb.append("  â").append("â".repeat(boxWidth)).append("â").append(RESET).append("\n");
            }
        }

        sb.append("\n");
        return sb.toString();
    }

    private void appendHeader(StringBuilder sb, String name, int boxWidth) {
        sb.append(CYAN).append("  â").append("â".repeat(boxWidth)).append("â\n");
        sb.append("  â").append(RESET).append(BOLD).append(BG_BLUE).append(WHITE);
        sb.append(centerText("WORKFLOW: " + name, boxWidth));
        sb.append(RESET).append(CYAN).append("â\n");
        sb.append("  â").append("â".repeat(boxWidth)).append("â").append(RESET).append("\n\n");
    }

    private void collectDataFlow(List<WorkflowStage> stages,
            Map<String, Integer> outputToStageIndex, Map<String, List<String>> stageOutputVars) {
        for (int i = 0; i < stages.size(); i++) {
            var stage = stages.get(i);
            List<String> outputs = new ArrayList<>();
            for (var script : stage.scripts()) {
                for (String outVar : script.getOutputs().keySet()) {
                    outputs.add(outVar);
                    outputToStageIndex.put(outVar, i);
                }
            }
            stageOutputVars.put(stage.name(), outputs);
        }
    }

    private Map<String, List<String>> collectConfigUsage(List<WorkflowStage> stages,
            Map<String, Object> presetVariables) {
        Map<String, List<String>> configVarToStages = new HashMap<>();
        for (var configVar : presetVariables.keySet()) {
            List<String> usingStages = new ArrayList<>();
            for (var stage : stages) {
                for (var script : stage.scripts()) {
                    for (var inputExpr : script.getInputs().values()) {
                        if (inputExpr.equals("@" + configVar) && !usingStages.contains(stage.name())) {
                            usingStages.add(stage.name());
                        }
                    }
                }
            }
            if (!usingStages.isEmpty()) {
                configVarToStages.put(configVar, usingStages);
            }
        }
        return configVarToStages;
    }

    private void appendConfiguration(StringBuilder sb, int boxWidth,
            Map<String, Object> presetVariables, boolean inlineAll,
            Map<String, List<String>> configVarToStages) {
        if (!presetVariables.isEmpty() || inlineAll) {
            sb.append(DIM).append("  ââ ").append(RESET).append(YELLOW).append(BOLD)
              .append("Configuration").append(RESET).append(DIM).append(" â").append("â".repeat(50)).append("â\n");

            if (inlineAll) {
                sb.append("  â  ").append(MAGENTA).append("Mode: ").append(RESET)
                  .append(YELLOW).append("INLINE ALL").append(RESET)
                  .append(pad(49)).append(DIM).append("â\n").append(RESET);
            }

            if (!presetVariables.isEmpty()) {
                sb.append("  â  ").append(CYAN).append("Variables:").append(RESET)
                  .append(pad(52)).append(DIM).append("â\n").append(RESET);
                for (var entry : presetVariables.entrySet()) {
                    String varName = entry.getKey();
                    List<String> usingStages = configVarToStages.get(varName);
                    String usageInfo = usingStages != null ? DIM + " â " + CYAN + String.join(", ", usingStages) + RESET : "";
                    String varLine = "     " + GREEN + "@" + varName + RESET + " = " +
                                    YELLOW + formatValue(entry.getValue()) + RESET + usageInfo;
                    sb.append("  â  ").append(varLine)
                      .append(pad(boxWidth - stripAnsi(varLine).length() - 4))
                      .append(DIM).append("â\n").append(RESET);
                }
            }
            sb.append(DIM).append("  â").append("â".repeat(boxWidth)).append("â").append(RESET).append("\n\n");
        }
    }

    private List<BypassFlow> detectBypassFlows(List<WorkflowStage> stages,
            Map<String, Integer> outputToStageIndex) {
        List<BypassFlow> bypassFlows = new ArrayList<>();
        for (int targetIdx = 0; targetIdx < stages.size(); targetIdx++) {
            for (var script : stages.get(targetIdx).scripts()) {
                for (var inputExpr : script.getInputs().values()) {
                    if (inputExpr.startsWith("@")) {
                        String varName = inputExpr.substring(1);
                        Integer sourceIdx = outputToStageIndex.get(varName);
                        if (sourceIdx != null && targetIdx - sourceIdx > 1) {
                            bypassFlows.add(new BypassFlow(varName, sourceIdx, targetIdx));
                        }
                    }
                }
            }
        }
        return bypassFlows;
    }

    private Map<BypassFlow, Integer> assignBypassLanes(List<BypassFlow> bypassFlows) {
        Map<BypassFlow, Integer> bypassLanes = new HashMap<>();
        for (var bypass : bypassFlows) {
            int lane = 0;
            while (true) {
                final int testLane = lane;
                boolean conflict = bypassLanes.entrySet().stream()
                    .anyMatch(e -> e.getValue() == testLane && rangesOverlap(e.getKey(), bypass));
                if (!conflict) {
                    bypassLanes.put(bypass, lane);
                    break;
                }
                lane++;
            }
        }
        return bypassLanes;
    }
}
