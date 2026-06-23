package com.garganttua.core.script;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;

import com.garganttua.core.expression.context.IExpressionContext;
import com.garganttua.core.expression.dsl.ExpressionContextBuilder;
import com.garganttua.core.injection.IInjectionContext;
import com.garganttua.core.injection.context.InjectionContext;
import com.garganttua.core.injection.context.dsl.IInjectionContextBuilder;
import com.garganttua.core.reflection.IAnnotationScanner;
import com.garganttua.core.reflection.IReflectionProvider;
import com.garganttua.core.reflection.dsl.IReflectionBuilder;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;
import com.garganttua.core.runtime.dsl.RuntimesBuilder;
import com.garganttua.core.script.context.ScriptContext;

/**
 * Command-line entry point for the Garganttua script engine. Parses CLI flags
 * ({@code --help}, {@code --version}, {@code --console}, {@code --syntax},
 * {@code --man}, {@code --dump}), then either launches the interactive console
 * or executes a {@code .gs} script file and exits with the script's exit code.
 */
public class Main {

    private static final String VERSION = com.garganttua.core.bootstrap.GarganttuaVersion.getVersion();
    private static final String SHEBANG_PREFIX = "#!";
    private static final int EXIT_ERROR = 1;
    private static final int MAX_DUMP_VAR_LENGTH = 120;
    private static final int MAX_EXCEPTION_DEPTH = 10;

    /**
     * CLI dispatcher. With no arguments starts the interactive console;
     * otherwise interprets the first argument as a flag or script path and
     * terminates the JVM via {@link System#exit(int)} with the resulting code.
     *
     * @param args command-line arguments: optional flags followed by a script
     *             file path and its positional arguments
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            startConsole();
            return;
        }
        if (handleSimpleFlag(args[0])) {
            return;
        }
        runScriptFromArgs(args);
    }

    /**
     * Handles the no-script informational flags. Flags that print and exit do so
     * here; {@code --console} returns control to the caller.
     *
     * @return {@code true} if the argument was a flag and was fully handled
     */
    private static boolean handleSimpleFlag(String firstArg) {
        if ("--help".equals(firstArg) || "-h".equals(firstArg)) {
            printUsage();
            return true;
        }
        if ("--version".equals(firstArg) || "-v".equals(firstArg)) {
            System.out.println("garganttua-script " + VERSION);
            return true;
        }
        if ("--console".equals(firstArg) || "-c".equals(firstArg)) {
            startConsole();
            return true;
        }
        if ("--syntax".equals(firstArg) || "-s".equals(firstArg)) {
            printSyntax();
            return true;
        }
        return false;
    }

    @SuppressFBWarnings(value = "DM_EXIT", justification = "CLI entry point: terminate the JVM with the script exit code.")
    @SuppressWarnings("PMD.DoNotTerminateVM") // legitimate CLI exit
    private static void runScriptFromArgs(String... args) {
        if ("--man".equals(args[0]) || "-m".equals(args[0])) {
            System.exit(runManual(args));
        }
        boolean dumpOnError = false;
        String[] filteredArgs = args;
        for (int i = 0; i < args.length; i++) {
            if ("--dump".equals(args[i]) || "-d".equals(args[i])) {
                dumpOnError = true;
                String[] newArgs = new String[args.length - 1];
                System.arraycopy(args, 0, newArgs, 0, i);
                System.arraycopy(args, i + 1, newArgs, i, args.length - i - 1);
                filteredArgs = newArgs;
                break;
            }
        }
        if (filteredArgs.length == 0) {
            printUsage();
            System.exit(EXIT_ERROR);
        }
        File scriptFile = new File(filteredArgs[0]);
        if (!scriptFile.exists()) {
            System.err.println("Error: Script file not found: " + filteredArgs[0]);
            System.exit(EXIT_ERROR);
        }
        String[] scriptArgs = filteredArgs.length > 1
                ? Arrays.copyOfRange(filteredArgs, 1, filteredArgs.length)
                : new String[0];
        System.exit(runScript(scriptFile, scriptArgs, dumpOnError));
    }

    private static int runManual(String... args) {
        try {
            boolean hasFunctionName = args.length > 1;
            if (hasFunctionName) {
                printManual(args[1]);
            } else {
                printManualList();
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Error loading manual: " + e.getMessage());
            return EXIT_ERROR;
        }
    }

    private static int runScript(File scriptFile, String[] scriptArgs, boolean dumpOnError) {
        try {
            return executeScript(scriptFile, scriptArgs, dumpOnError);
        } catch (ScriptException e) {
            System.err.println("Script error: " + e.getMessage());
            if (e.getCause() != null) {
                System.err.println("Caused by: " + e.getCause().getMessage());
            }
            return EXIT_ERROR;
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            return EXIT_ERROR;
        }
    }

    private static IReflectionProvider loadReflectionProvider() {
        try {
            Class<?> providerClass = Class.forName("com.garganttua.core.reflection.runtime.RuntimeReflectionProvider");
            return (IReflectionProvider) providerClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load RuntimeReflectionProvider. "
                    + "Ensure garganttua-runtime-reflection is on the classpath.", e);
        }
    }

    // Loaded reflectively (like the reflection provider above) so the script
    // module keeps NO compile-time coupling to the org.reflections-backed
    // scanner. This stops garganttua-reflections (and org.reflections) leaking
    // onto the compile/runtime classpath of every downstream consumer — full-AOT
    // and native consumers get their scanner from the AOT starter instead. The
    // JVM CLI fat-JAR still bundles it (garganttua-reflections is a runtime dep).
    private static IAnnotationScanner loadAnnotationScanner() {
        try {
            Class<?> scannerClass = Class.forName("com.garganttua.core.reflections.ReflectionsAnnotationScanner");
            return (IAnnotationScanner) scannerClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load ReflectionsAnnotationScanner. "
                    + "Ensure garganttua-reflections is on the classpath.", e);
        }
    }

    private static int executeScript(File scriptFile, String[] args, boolean dumpOnError)
            throws ScriptException, IOException {
        IReflectionBuilder reflectionBuilder = ReflectionBuilder.builder()
                .withProvider(loadReflectionProvider())
                .withScanner(loadAnnotationScanner());

        // Build the reflection chain first: this installs the global IReflection
        // (IClass.setReflection) that InjectionContext.builder() resolves at construction.
        reflectionBuilder.build();

        // Build injection context
        IInjectionContextBuilder injectionContextBuilder = InjectionContext.builder()
                .provide(reflectionBuilder)
                .autoDetect(true)
                .withPackage("com.garganttua.core.runtime");

        // Build expression context with dependency on injection context
        ExpressionContextBuilder expressionContextBuilder = ExpressionContextBuilder.builder();
        expressionContextBuilder
                .withPackage("com.garganttua")
                .autoDetect(true)
                .provide(injectionContextBuilder);

        // Build injection context first
        IInjectionContext injectionContext = injectionContextBuilder.build();

        // Initialize lifecycle BEFORE building expression context
        injectionContext.onInit().onStart();

        // Now build expression context
        IExpressionContext expressionContext = expressionContextBuilder.build();

        ScriptContext script = new ScriptContext(expressionContext, () -> RuntimesBuilder.builder().provide(injectionContextBuilder), null);

        String scriptContent = readScriptFile(scriptFile);
        script.load(scriptContent);
        script.compile();

        int exitCode = script.execute((Object[]) args);

        if (script.hasAborted() && dumpOnError) {
            printErrorDump(System.err, script, scriptFile, scriptContent, args);
        }

        return exitCode;
    }

    private static void printErrorDump(PrintStream out, ScriptContext script,
                                        File scriptFile, String scriptContent, String... args) {
        out.println();
        out.println("╔══════════════════════════════════════════════════════════════════════╗");
        out.println("║  SCRIPT ERROR DUMP                                                  ║");
        out.println("╠══════════════════════════════════════════════════════════════════════╣");
        out.println("║  File: " + scriptFile.getAbsolutePath());
        printExceptionChain(out, script);
        printErrorContext(out, script);
        printDumpArguments(out, args);
        printDumpVariables(out, script);
        printScriptSource(out, scriptContent);
        out.println("╚══════════════════════════════════════════════════════════════════════╝");
    }

    private static void printExceptionChain(PrintStream out, ScriptContext script) {
        script.getLastException().ifPresent(ex -> {
            out.println("║");
            out.println("║  Exception chain:");
            Throwable t = ex;
            int depth = 0;
            while (t != null && depth < MAX_EXCEPTION_DEPTH) {
                out.println("║    " + "  ".repeat(depth) + t.getClass().getSimpleName() + ": " + t.getMessage());
                t = t.getCause();
                depth++;
            }
        });
    }

    private static void printErrorContext(PrintStream out, ScriptContext script) {
        out.println("║");
        out.println("║  Error context:");
        printErrorVar(out, script, "_scriptErrorLine", "Line");
        printErrorVar(out, script, "_scriptErrorSource", "Source");
        printErrorVar(out, script, "_scriptErrorStep", "Step");
        printErrorVar(out, script, "_scriptErrorType", "Type");
        printErrorVar(out, script, "_scriptErrorMessage", "Message");
    }

    private static void printErrorVar(PrintStream out, ScriptContext script, String varName, String label) {
        script.getVariable(varName, com.garganttua.core.reflection.IClass.getClass(Object.class))
                .ifPresent(v -> out.println("║    " + label + ": " + v));
    }

    private static void printDumpArguments(PrintStream out, String... args) {
        if (args != null && args.length > 0) {
            out.println("║");
            out.println("║  Arguments:");
            for (int i = 0; i < args.length; i++) {
                out.println("║    @" + i + " = " + args[i]);
            }
        }
    }

    private static void printDumpVariables(PrintStream out, ScriptContext script) {
        out.println("║");
        out.println("║  Variables:");
        Map<String, Object> vars = script.getAllVariables();
        if (vars.isEmpty()) {
            out.println("║    (none)");
            return;
        }
        for (var entry : vars.entrySet()) {
            if (entry.getKey().startsWith("_scriptError")) {
                continue;
            }
            String val = entry.getValue() != null ? entry.getValue().toString() : "null";
            if (val.length() > MAX_DUMP_VAR_LENGTH) {
                val = val.substring(0, MAX_DUMP_VAR_LENGTH) + "...";
            }
            out.println("║    " + entry.getKey() + " = " + val);
        }
    }

    private static void printScriptSource(PrintStream out, String scriptContent) {
        out.println("║");
        out.println("║  Script source:");
        String[] lines = scriptContent.split("\n");
        for (int i = 0; i < lines.length; i++) {
            out.printf("║    %3d │ %s%n", i + 1, lines[i]);
        }
    }

    private static String readScriptFile(File file) throws IOException {
        String content = Files.readString(file.toPath());
        return stripShebang(content);
    }

    private static String stripShebang(String content) {
        if (content.startsWith(SHEBANG_PREFIX)) {
            int newlineIndex = content.indexOf('\n');
            if (newlineIndex >= 0) {
                return content.substring(newlineIndex + 1);
            }
            return "";
        }
        return content;
    }

    @SuppressFBWarnings(value = "DM_EXIT", justification = "CLI: exit non-zero when the console module is missing or fails to start.")
    @SuppressWarnings("PMD.DoNotTerminateVM") // legitimate CLI exit
    private static void startConsole() {
        try {
            Class<?> consoleClass = Class.forName("com.garganttua.core.console.ScriptConsole");
            Object console = consoleClass.getDeclaredConstructor().newInstance();
            consoleClass.getMethod("start").invoke(console);
        } catch (ClassNotFoundException e) {
            System.err.println("Console module not available.");
            System.err.println("Install garganttua-console or use: gs <script.gs>");
            printUsage();
            System.exit(EXIT_ERROR);
        } catch (Exception e) {
            System.err.println("Error starting console: " + e.getMessage());
            System.exit(EXIT_ERROR);
        }
    }

    private static void printUsage() {
        ScriptCliHelp.printUsage(VERSION);
    }

    private static IExpressionContext buildExpressionContext() {
        IReflectionBuilder reflectionBuilder = ReflectionBuilder.builder()
                .withProvider(loadReflectionProvider())
                .withScanner(loadAnnotationScanner());

        IInjectionContextBuilder injectionContextBuilder = InjectionContext.builder()
                .provide(reflectionBuilder)
                .autoDetect(true)
                .withPackage("com.garganttua.core.runtime");

        ExpressionContextBuilder expressionContextBuilder = ExpressionContextBuilder.builder();
        expressionContextBuilder
                .withPackage("com.garganttua")
                .autoDetect(true)
                .provide(injectionContextBuilder);

        IInjectionContext injectionContext = injectionContextBuilder.build();
        injectionContext.onInit().onStart();

        return expressionContextBuilder.build();
    }

    private static void printManualList() {
        IExpressionContext expressionContext = buildExpressionContext();
        System.out.println(expressionContext.man());
    }

    @SuppressFBWarnings(value = "DM_EXIT", justification = "CLI: exit non-zero when no manual entry matches.")
    @SuppressWarnings("PMD.DoNotTerminateVM") // legitimate CLI exit
    private static void printManual(String functionNameOrIndex) {
        IExpressionContext expressionContext = buildExpressionContext();
        String manual = resolveManual(expressionContext, functionNameOrIndex);
        if (manual == null) {
            System.err.println("No documentation found for: " + functionNameOrIndex);
            System.err.println();
            System.err.println("Use --man to list all available functions.");
            System.exit(EXIT_ERROR);
        }
        System.out.println(manual);
    }

    private static String resolveManual(IExpressionContext expressionContext, String functionNameOrIndex) {
        try {
            int index = Integer.parseInt(functionNameOrIndex);
            return expressionContext.man(index);
        } catch (NumberFormatException e) {
            return expressionContext.man(functionNameOrIndex); // not an index, try as function name
        }
    }

    private static void printSyntax() {
        ScriptCliHelp.printSyntax();
    }
}
