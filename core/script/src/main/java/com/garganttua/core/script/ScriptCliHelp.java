package com.garganttua.core.script;

/**
 * Static help/usage/syntax banners for the {@code garganttua-script} CLI.
 *
 * <p>Extracted from {@link Main} so the entry-point class stays focused on argument
 * dispatch and execution; this class holds only the (large, flat) printed reference
 * text. All output goes to {@code System.out} by design — this is deliberate CLI
 * output, not diagnostic logging.</p>
 */
final class ScriptCliHelp {

    private ScriptCliHelp() {
    }

    /** Prints the top-level usage/options banner. */
    static void printUsage(String version) {
        System.out.println("Garganttua Script Engine " + version);
        System.out.println();
        System.out.println("Usage: garganttua-script                            Start interactive console (requires garganttua-console)");
        System.out.println("       garganttua-script [--dump] <script.gs> [args] Execute a script file");
        System.out.println("       garganttua-script [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -c, --console      Start interactive console (REPL, requires garganttua-console)");
        System.out.println("  -d, --dump         Print error dump on crash (variables, context, source)");
        System.out.println("  -h, --help         Show this help message");
        System.out.println("  -v, --version      Show version information");
        System.out.println("  -m, --man          List all available expression functions");
        System.out.println("  -m, --man <name>   Show documentation for a specific function");
        System.out.println("  -s, --syntax       Show script syntax reference");
        System.out.println();
        System.out.println("Interactive Console:");
        System.out.println("  When started without arguments, the console allows you to");
        System.out.println("  enter script statements interactively. Type :help for commands.");
        System.out.println();
        System.out.println("Script Files:");
        System.out.println("  Scripts can start with a shebang line:");
        System.out.println("    #!/usr/bin/env garganttua-script");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  garganttua-script                     # Start console");
        System.out.println("  garganttua-script myscript.gs         # Run script");
        System.out.println("  garganttua-script script.gs arg1 arg2 # Run with arguments");
    }

    /** Prints the full script syntax reference. */
    static void printSyntax() {
        System.out.println("GARGANTTUA SCRIPT SYNTAX REFERENCE");
        System.out.println("===================================");
        System.out.println();
        printSyntaxStatements();
        printSyntaxExpressions();
        printSyntaxExceptions();
        printSyntaxPipes();
        printSyntaxCommentsAndExample();
        System.out.println("Use --man to list all available expression functions.");
    }

    private static void printSyntaxStatements() {
        System.out.println("1. BASIC STATEMENTS");
        System.out.println("-------------------");
        System.out.println();
        System.out.println("  Expression statement:");
        System.out.println("    expression");
        System.out.println();
        System.out.println("  Expression with exit code:");
        System.out.println("    expression -> exitCode");
        System.out.println();
        System.out.println("  Variable assignment (stores result):");
        System.out.println("    varName <- expression");
        System.out.println("    varName <- expression -> exitCode");
        System.out.println();
        System.out.println("  Expression assignment (stores the expression itself):");
        System.out.println("    varName = expression");
        System.out.println();
    }

    private static void printSyntaxExpressions() {
        System.out.println("2. EXPRESSIONS");
        System.out.println("--------------");
        System.out.println();
        System.out.println("  Literals:");
        System.out.println("    \"string\"          String literal");
        System.out.println("    'c'               Character literal");
        System.out.println("    123               Integer literal");
        System.out.println("    12.34             Float literal");
        System.out.println("    true, false       Boolean literals");
        System.out.println("    null              Null value");
        System.out.println();
        System.out.println("  Variable reference:");
        System.out.println("    @varName          Reference to a script variable");
        System.out.println();
        System.out.println("  Script arguments:");
        System.out.println("    @0, @1, @2...     Command-line arguments (0-indexed)");
        System.out.println();
        System.out.println("  Function call:");
        System.out.println("    functionName(arg1, arg2, ...)");
        System.out.println();
        System.out.println("  Method call:");
        System.out.println("    :methodName(target, arg1, arg2, ...)");
        System.out.println();
        System.out.println("  Constructor call:");
        System.out.println("    :(ClassName.class, arg1, arg2, ...)");
        System.out.println();
        System.out.println("  Types:");
        System.out.println("    int, long, double, boolean, char, byte, short, float");
        System.out.println("    String.class, java.util.List.class, etc.");
        System.out.println();
    }

    private static void printSyntaxExceptions() {
        System.out.println("3. EXCEPTION HANDLING");
        System.out.println("---------------------");
        System.out.println();
        System.out.println("  Catch clause (handles exceptions from current step):");
        System.out.println("    expression");
        System.out.println("    ! => handler");
        System.out.println("    ! ExceptionType.class => handler");
        System.out.println("    ! Type1.class, Type2.class => handler");
        System.out.println();
        System.out.println("  Downstream catch (handles exceptions from subsequent steps):");
        System.out.println("    expression");
        System.out.println("    * => handler");
        System.out.println("    * ExceptionType.class => handler");
        System.out.println();
    }

    private static void printSyntaxPipes() {
        System.out.println("4. CONDITIONAL PIPES");
        System.out.println("--------------------");
        System.out.println();
        System.out.println("  Pipe clauses (must be on separate lines):");
        System.out.println("    expression -> 100");
        System.out.println("    | condition => handler -> 200");
        System.out.println("    | => defaultHandler -> 300");
        System.out.println();
        System.out.println("  Example with equals:");
        System.out.println("    print(@0) -> 100");
        System.out.println("    | equals(@0, \"yes\") => doSomething() -> 200");
        System.out.println("    | equals(@0, \"no\") => doOther() -> 201");
        System.out.println("    | => print(\"unknown\") -> 202");
        System.out.println();
    }

    private static void printSyntaxCommentsAndExample() {
        System.out.println("5. COMMENTS");
        System.out.println("-----------");
        System.out.println();
        System.out.println("  // Single-line comment");
        System.out.println("  # Hash comment (also used for shebang)");
        System.out.println("  /* Block comment */");
        System.out.println();
        System.out.println("6. COMPLETE EXAMPLE");
        System.out.println("-------------------");
        System.out.println();
        System.out.println("  #!/usr/bin/env garganttua-script");
        System.out.println("  ");
        System.out.println("  // Variable assignment");
        System.out.println("  greeting <- \"Hello\"");
        System.out.println("  ");
        System.out.println("  // Print with variable and arguments");
        System.out.println("  print(concatenate(@greeting, \" \", @0)) -> 100");
        System.out.println("  ");
        System.out.println("  // Conditional logic");
        System.out.println("  | equals(@0, \"world\") => print(\"Perfect!\") -> 200");
        System.out.println("  | => print(\"Try: ./script.gs world\") -> 201");
        System.out.println();
    }
}
