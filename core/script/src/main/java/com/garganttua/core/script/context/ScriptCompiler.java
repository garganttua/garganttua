package com.garganttua.core.script.context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import com.garganttua.core.expression.context.IExpressionContext;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.runtime.IRuntime;
import com.garganttua.core.runtime.IRuntimeStep;
import com.garganttua.core.runtime.dsl.IRuntimeBuilder;
import com.garganttua.core.runtime.dsl.IRuntimesBuilder;
import com.garganttua.core.script.ScriptException;
import com.garganttua.core.script.antlr4.ScriptLexer;
import com.garganttua.core.script.antlr4.ScriptParser;
import com.garganttua.core.script.nodes.IScriptNode;
import com.garganttua.core.script.nodes.StatementBlock;

/**
 * Turns {@code .gs} source into an immutable {@link IRuntime}: block pre-processing, ANTLR4 parsing,
 * step compilation and runtime assembly.
 *
 * <p>Extracted from {@link ScriptContext}, which owns the script's <em>state</em> — source, last-run
 * variables, included scripts, observers — while this class owns the <em>translation</em>. The two
 * were entangled in one type that had grown past the 500-line gate; the seam is where the mutable
 * per-run state stops being needed.
 *
 * <p>Stateless between calls, but not reusable across contexts: it captures the expression context,
 * the runtimes-builder factory and the initial variables of the context that created it. A fresh
 * {@link IRuntimesBuilder} is pulled from the factory per {@link #compile(String)}, so no builder
 * state leaks between independent compilations.
 *
 * @since 3.0.0-ALPHA11
 */
final class ScriptCompiler {

    private final IExpressionContext expressionContext;
    private final Supplier<IRuntimesBuilder> runtimesBuilderFactory;
    private final Map<String, Object> initialVariables;

    ScriptCompiler(IExpressionContext expressionContext,
                   Supplier<IRuntimesBuilder> runtimesBuilderFactory,
                   Map<String, Object> initialVariables) {
        this.expressionContext = expressionContext;
        this.runtimesBuilderFactory = runtimesBuilderFactory;
        this.initialVariables = initialVariables;
    }

    /**
     * Compiles {@code source} into a runnable runtime.
     *
     * @param source the {@code .gs} source
     * @return the compiled runtime
     * @throws ScriptException if the source parses to no statement
     */
    IRuntime<Object[], Object> compile(String source) throws ScriptException {
        // Register variable types before parsing so expressions can resolve method calls
        for (Map.Entry<String, Object> entry : this.initialVariables.entrySet()) {
            if (entry.getValue() != null) {
                this.expressionContext.registerVariableType(entry.getKey(),
                        IClass.getClass(entry.getValue().getClass()));
            }
        }

        // Pre-process block expressions before ANTLR4 parsing
        BlockExpressionPreprocessor preprocessor = new BlockExpressionPreprocessor();
        String processedSource = preprocessor.preprocess(source);
        Map<String, String> blockSources = preprocessor.getBlockSources();

        // Compile each block into a StatementBlock
        Map<String, StatementBlock> compiledBlocks = new LinkedHashMap<>();
        for (Map.Entry<String, String> blockEntry : blockSources.entrySet()) {
            List<IScriptNode> blockStatements = parseStatements(blockEntry.getValue());
            compiledBlocks.put(blockEntry.getKey(), new StatementBlock(blockStatements));
        }

        List<IScriptNode> statements = parseStatements(processedSource);
        if (statements.isEmpty()) {
            throw new ScriptException("Failed to compile script: no statements found");
        }

        ScriptStepFactory stepFactory = new ScriptStepFactory();
        Map<String, IRuntimeStep<?, Object[], Object>> steps = stepFactory.compile(statements);

        return buildRuntime(steps, compiledBlocks);
    }

    private IRuntime<Object[], Object> buildRuntime(Map<String, IRuntimeStep<?, Object[], Object>> steps,
                                                    Map<String, StatementBlock> compiledBlocks) {
        // Create a fresh RuntimesBuilder for this compilation via the factory
        IRuntimesBuilder runtimesBuilder = this.runtimesBuilderFactory.get();

        @SuppressWarnings("unchecked")
        IClass<Object[]> inputType = (IClass<Object[]>) (IClass<?>) IClass.getClass(Object[].class);
        IClass<Object> outputType = IClass.getClass(Object.class);

        IRuntimeBuilder<Object[], Object> runtimeBuilder = runtimesBuilder
                .runtime("script", inputType, outputType);

        // Add pre-compiled steps
        for (Map.Entry<String, IRuntimeStep<?, Object[], Object>> entry : steps.entrySet()) {
            runtimeBuilder.step(entry.getKey(), entry.getValue());
        }

        // Add compiled blocks as variables
        for (Map.Entry<String, StatementBlock> blockEntry : compiledBlocks.entrySet()) {
            runtimeBuilder.variable(blockEntry.getKey(), blockEntry.getValue());
        }

        // Add initial variables
        for (Map.Entry<String, Object> entry : this.initialVariables.entrySet()) {
            runtimeBuilder.variable(entry.getKey(), entry.getValue());
        }

        Map<String, IRuntime<?, ?>> runtimes = runtimesBuilder.build();
        @SuppressWarnings("unchecked")
        IRuntime<Object[], Object> scriptRuntime = (IRuntime<Object[], Object>) runtimes.get("script");
        return scriptRuntime;
    }

    private List<IScriptNode> parseStatements(String source) {
        ScriptLexer lexer = new ScriptLexer(CharStreams.fromString(source));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        ScriptParser parser = new ScriptParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new ScriptErrorListener());
        ScriptParser.ScriptContext tree = parser.script();
        ScriptNodeVisitor visitor = new ScriptNodeVisitor(this.expressionContext);
        visitor.visit(tree);
        return visitor.getStatements();
    }
}
