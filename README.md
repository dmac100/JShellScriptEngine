# JShellScriptEngine

A JSR-223 ScriptEngine for Java that allows execution of Java snippets via the JShell repl in Java 9.

This runs JShell scripts in the current JVM, allowing the passing of variables back and forth.

### Usage

    ScriptEngine engine = new ScriptEngineManager().getEngineByName("jshell");
    assertEquals("Hello World!", (engine.eval("\"Hello World!\"")));

### Build

Build with ant:

    ant jar
    
Then copy `dist/jshellscriptengine.jar` into your classpath.
