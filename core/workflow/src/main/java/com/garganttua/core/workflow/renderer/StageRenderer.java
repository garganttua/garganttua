package com.garganttua.core.workflow.renderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.garganttua.core.workflow.WorkflowScript;
import com.garganttua.core.workflow.WorkflowStage;
import com.garganttua.core.workflow.chaining.CodeAction;

/**
 * Renders the per-stage and per-script box blocks of a workflow diagram, including
 * bypass-lane columns. Extracted from {@link WorkflowRenderer} to keep each file within size limits.
 */
// AvoidDuplicateLiterals: box-drawing report builder — repeated ANSI/box fragments are layout, not logic.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class StageRenderer extends AbstractRenderer {

    protected void appendStageBlock(StringBuilder sb, int boxWidth, List<WorkflowStage> stages,
            int stageIdx, boolean inlineAll, Map<String, Integer> outputToStageIndex,
            Map<String, List<String>> stageOutputVars, List<BypassFlow> bypassFlows,
            Map<BypassFlow, Integer> bypassLanes, int maxLane) {
            final int currentStageIdx = stageIdx;
            var stage = stages.get(stageIdx);
            boolean isFirst = stageIdx == 0;
            boolean isLast = stageIdx == stages.size() - 1;

            // Collect inputs
            List<String> stageInputs = new ArrayList<>();
            for (var script : stage.scripts()) {
                for (var entry : script.getInputs().entrySet()) {
                    String inputExpr = entry.getValue();
                    if (inputExpr.startsWith("@")) {
                        String varName = inputExpr.substring(1);
                        stageInputs.add(varName);
                    }
                }
            }

            String bypassCol = buildBypassColumn(stageIdx, bypassFlows, bypassLanes, maxLane);

            appendStageArrows(sb, stages, currentStageIdx, isFirst, stageInputs, outputToStageIndex, bypassFlows, bypassLanes, bypassCol);

            // Stage box
            String stageColor = getStageColor(stageIdx);
            appendStageBox(sb, boxWidth, stage, stageIdx, stageColor, bypassCol);

            // Scripts
            for (int scriptIdx = 0; scriptIdx < stages.get(stageIdx).scripts().size(); scriptIdx++) {
                appendScriptBlock(sb, boxWidth, stages.get(stageIdx), scriptIdx, inlineAll, stageColor, bypassCol);
            }

            sb.append(stageColor).append("  â").append("â".repeat(boxWidth)).append("â")
              .append(RESET).append(bypassCol).append("\n");

            appendStageOutputArrows(sb, stages, stageIdx, currentStageIdx, isLast, stage, stageOutputVars, bypassFlows, bypassLanes, maxLane);
    }

    private void appendStageBox(StringBuilder sb, int boxWidth, WorkflowStage stage, int stageIdx,
            String stageColor, String bypassCol) {
            sb.append(stageColor).append("  â").append("â".repeat(boxWidth)).append("â")
              .append(RESET).append(bypassCol).append("\n");
            sb.append(stageColor).append("  â").append(RESET).append(BOLD).append(stageColor);
            String stageTitle = "  STAGE " + (stageIdx + 1) + ": " + stage.name().toUpperCase(java.util.Locale.ROOT) + "  ";
            sb.append(stageTitle).append(RESET).append(stageColor);
            sb.append(pad(boxWidth - stageTitle.length())).append("â")
              .append(RESET).append(bypassCol).append("\n");

            // Stage condition
            if (stage.condition() != null && !stage.condition().isEmpty()) {
                boxLine(sb, stageColor, bypassCol, boxWidth, "  " + YELLOW + "â¡ when: " + RESET + CYAN + truncate(stage.condition(), 55) + RESET);
            }

            // Stage wrap
            if (stage.hasWrap()) {
                boxLine(sb, stageColor, bypassCol, boxWidth, "  " + MAGENTA + "â³ wrap: " + RESET + CYAN + truncate(stage.wrapExpression(), 55) + RESET);
            }

            // Stage catch
            if (stage.catchExpression() != null && !stage.catchExpression().isEmpty()) {
                boxLine(sb, stageColor, bypassCol, boxWidth, "  " + RED + "! catch: " + RESET + YELLOW + truncate(stage.catchExpression(), 55) + RESET);
            }

            // Stage catchDownstream
            if (stage.catchDownstreamExpression() != null && !stage.catchDownstreamExpression().isEmpty()) {
                boxLine(sb, stageColor, bypassCol, boxWidth, "  " + RED + "* catchDownstream: " + RESET + YELLOW + truncate(stage.catchDownstreamExpression(), 45) + RESET);
            }

            sb.append(stageColor).append("  â").append("â".repeat(boxWidth)).append("â¤")
              .append(RESET).append(bypassCol).append("\n");
    }

    private void appendStageArrows(StringBuilder sb, List<WorkflowStage> stages, int currentStageIdx,
            boolean isFirst, List<String> stageInputs, Map<String, Integer> outputToStageIndex,
            List<BypassFlow> bypassFlows, Map<BypassFlow, Integer> bypassLanes, String bypassCol) {
            // Input arrows
            if (!isFirst) {
                List<String> directInputs = stageInputs.stream()
                    .filter(v -> {
                        Integer src = outputToStageIndex.get(v);
                        return src != null && currentStageIdx - src == 1;
                    })
                    .toList();

                if (!directInputs.isEmpty()) {
                    sb.append(GREEN).append("                         â").append(RESET).append(bypassCol).append("\n");
                    for (String input : directInputs) {
                        sb.append(GREEN).append("                         ââ ").append(RESET)
                          .append(DIM).append("@").append(input).append(RESET).append(bypassCol).append("\n");
                    }
                    sb.append(GREEN).append("                         â¼").append(RESET).append(bypassCol).append("\n");
                }
            }

            // Bypass arrivals
            List<BypassFlow> endingBypasses = bypassFlows.stream()
                .filter(b -> b.endsAt(currentStageIdx))
                .toList();

            for (var bypass : endingBypasses) {
                int lane = bypassLanes.get(bypass);
                sb.append(YELLOW).append("  â°").append("â".repeat(3 + lane * 4)).append("â¶ ")
                  .append(DIM).append("@").append(bypass.variable()).append(" (bypass from ")
                  .append(stages.get(bypass.sourceStage()).name()).append(")").append(RESET).append("\n");
            }
    }

    private void appendStageOutputArrows(StringBuilder sb, List<WorkflowStage> stages, int stageIdx,
            int currentStageIdx, boolean isLast, WorkflowStage stage,
            Map<String, List<String>> stageOutputVars, List<BypassFlow> bypassFlows,
            Map<BypassFlow, Integer> bypassLanes, int maxLane) {
            // Output arrows
            if (!isLast) {
                List<String> outputs = stageOutputVars.getOrDefault(stage.name(), List.of());
                List<BypassFlow> startingBypasses = bypassFlows.stream()
                    .filter(b -> b.startsAt(currentStageIdx))
                    .toList();

                if (!outputs.isEmpty()) {
                    String bypassColPipe = buildBypassColumn(stageIdx + 1, bypassFlows, bypassLanes, maxLane);
                    sb.append(GREEN).append("                         â").append(RESET).append(bypassColPipe).append("\n");

                    for (String output : outputs) {
                        BypassFlow startingBypass = startingBypasses.stream()
                            .filter(b -> b.variable().equals(output))
                            .findFirst()
                            .orElse(null);

                        if (startingBypass != null) {
                            int lane = bypassLanes.get(startingBypass);
                            String targetName = stages.get(startingBypass.targetStage()).name();
                            sb.append(GREEN).append("                         ââââ¶ ").append(RESET)
                              .append(DIM).append("@").append(output).append(RESET)
                              .append(YELLOW).append(" â".repeat(lane + 1)).append("â®")
                              .append(DIM).append(" (to ").append(targetName).append(")").append(RESET).append("\n");
                        } else {
                            sb.append(GREEN).append("                         ââââ¶ ").append(RESET)
                              .append(DIM).append("@").append(output).append(RESET).append(bypassColPipe).append("\n");
                        }
                    }
                }
            }
    }


    protected void appendScriptBlock(StringBuilder sb, int boxWidth, WorkflowStage stage,
            int scriptIdx, boolean inlineAll, String stageColor, String bypassCol) {
                var script = stage.scripts().get(scriptIdx);
                String scriptName = script.getName() != null ? script.getName() : "script-" + scriptIdx;
                String sourceTag = script.isFile() ? (script.isInline() || inlineAll ? "inline" : "include") : "inline";

                sb.append(stageColor).append("  â").append(RESET);
                sb.append("  ").append(WHITE).append(BOLD).append("â ").append(RESET)
                  .append(WHITE).append(scriptName).append(RESET)
                  .append(DIM).append(" [").append(sourceTag).append("]").append(RESET);
                sb.append(pad(boxWidth - 6 - scriptName.length() - sourceTag.length() - 3));
                sb.append(stageColor).append("â").append(RESET).append(bypassCol).append("\n");

                if (script.getDescription() != null && !script.getDescription().isEmpty()) {
                    boxLine(sb, stageColor, bypassCol, boxWidth, INDENT + DIM + ITALIC + truncate(script.getDescription(), boxWidth - 8) + RESET);
                }

                // Script condition
                if (script.getCondition() != null && !script.getCondition().isEmpty()) {
                    boxLine(sb, stageColor, bypassCol, boxWidth, INDENT + YELLOW + "â¡ when: " + RESET + CYAN + truncate(script.getCondition(), 55) + RESET);
                }

                appendScriptInputs(sb, boxWidth, script, stageColor, bypassCol);

                appendScriptOutputs(sb, boxWidth, script, stageColor, bypassCol);

                // Error handling - catch
                if (script.getCatchExpression() != null) {
                    boxLine(sb, stageColor, bypassCol, boxWidth, INDENT + RED + "! " + RESET + YELLOW + truncate(script.getCatchExpression(), 60) + RESET);
                }

                // Error handling - catchDownstream
                if (script.getCatchDownstreamExpression() != null) {
                    boxLine(sb, stageColor, bypassCol, boxWidth, INDENT + RED + "* " + RESET + YELLOW + truncate(script.getCatchDownstreamExpression(), 60) + RESET);
                }

                appendScriptCodeActions(sb, boxWidth, script, stageColor, bypassCol);

                if (scriptIdx < stage.scripts().size() - 1) {
                    sb.append(stageColor).append("  â").append(RESET).append(DIM)
                      .append("  ").append("Â·".repeat(boxWidth - 4)).append(RESET)
                      .append(stageColor).append("â").append(RESET).append(bypassCol).append("\n");
                }
    }

    private void appendScriptInputs(StringBuilder sb, int boxWidth, WorkflowScript script, String stageColor, String bypassCol) {
                // Inputs
                if (!script.getInputs().isEmpty()) {
                    sb.append(stageColor).append("  â").append(RESET).append(DIM)
                      .append("    Inputs:").append(RESET)
                      .append(pad(boxWidth - 11))
                      .append(stageColor).append("â").append(RESET).append(bypassCol).append("\n");

                    int inputIndex = 0;
                    for (var entry : script.getInputs().entrySet()) {
                        sb.append(stageColor).append("  â").append(RESET);
                        String inputLine = "      " + CYAN + "@" + inputIndex + RESET + " " +
                                          GREEN + entry.getKey() + RESET + DIM + " â " + RESET +
                                          YELLOW + entry.getValue() + RESET;
                        sb.append(inputLine).append(pad(boxWidth - stripAnsi(inputLine).length()));
                        sb.append(stageColor).append("â").append(RESET).append(bypassCol).append("\n");
                        inputIndex++;
                    }
                }
    }

    private void appendScriptOutputs(StringBuilder sb, int boxWidth, WorkflowScript script, String stageColor, String bypassCol) {
                // Outputs
                if (!script.getOutputs().isEmpty()) {
                    sb.append(stageColor).append("  â").append(RESET).append(DIM)
                      .append("    Outputs:").append(RESET)
                      .append(pad(boxWidth - 12))
                      .append(stageColor).append("â").append(RESET).append(bypassCol).append("\n");

                    int outputIndex = 0;
                    for (var entry : script.getOutputs().entrySet()) {
                        sb.append(stageColor).append("  â").append(RESET);
                        String outputLine = "      " + MAGENTA + "[" + outputIndex + "]" + RESET + " " +
                                           GREEN + entry.getKey() + RESET + DIM + " â @" + RESET +
                                           CYAN + entry.getValue() + RESET;
                        sb.append(outputLine).append(pad(boxWidth - stripAnsi(outputLine).length()));
                        sb.append(stageColor).append("â").append(RESET).append(bypassCol).append("\n");
                        outputIndex++;
                    }
                }
    }

    private void appendScriptCodeActions(StringBuilder sb, int boxWidth, WorkflowScript script, String stageColor, String bypassCol) {
                // Code actions
                if (!script.getCodeActions().isEmpty()) {
                    for (var codeEntry : script.getCodeActions().entrySet()) {
                        if (codeEntry.getValue() != CodeAction.CONTINUE) {
                            sb.append(stageColor).append("  â").append(RESET);
                            String codeLine = INDENT + MAGENTA + "â© onCode(" + codeEntry.getKey() + ") â "
                                    + RESET + YELLOW + codeEntry.getValue().name() + RESET;
                            sb.append(codeLine).append(pad(boxWidth - stripAnsi(codeLine).length()));
                            sb.append(stageColor).append("â").append(RESET).append(bypassCol).append("\n");
                        }
                    }
                }
    }

    protected String buildBypassColumn(int stageIdx, List<BypassFlow> bypasses,
                                      Map<BypassFlow, Integer> lanes, int maxLane) {
        if (maxLane < 0) return "";
        StringBuilder col = new StringBuilder("  ");
        for (int lane = 0; lane <= maxLane; lane++) {
            final int currentLane = lane;
            BypassFlow activeBypass = bypasses.stream()
                .filter(b -> lanes.getOrDefault(b, -1) == currentLane && b.isActive(stageIdx))
                .findFirst()
                .orElse(null);
            if (activeBypass != null) {
                col.append(YELLOW).append("â").append(RESET).append("   ");
            } else {
                col.append(INDENT);
            }
        }
        return col.toString();
    }

    protected String getStageColor(int index) {
        String[] colors = {BLUE, MAGENTA, CYAN, GREEN, YELLOW};
        return colors[index % colors.length];
    }

    /**
     * Emits one content line inside a stage box: left border, the (ANSI-aware) padded
     * content, then the right border and the bypass column.
     */
    protected void boxLine(StringBuilder sb, String stageColor, String bypassCol, int boxWidth, String line) {
        sb.append(stageColor).append("  â").append(RESET);
        sb.append(line).append(pad(boxWidth - stripAnsi(line).length()));
        sb.append(stageColor).append("â").append(RESET).append(bypassCol).append("\n");
    }
}
