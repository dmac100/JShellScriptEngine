package com.github.dmac100.jshellscriptengine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import javax.script.SimpleScriptContext;

import com.github.dmac100.jshellscriptengine.io.ReaderInputStream;
import com.github.dmac100.jshellscriptengine.io.WriterOutputStream;

import jdk.jshell.Diag;
import jdk.jshell.EvalException;
import jdk.jshell.ExpressionSnippet;
import jdk.jshell.JShell;
import jdk.jshell.JShellException;
import jdk.jshell.Snippet;
import jdk.jshell.Snippet.Status;
import jdk.jshell.SnippetEvent;
import jdk.jshell.SourceCodeAnalysis.CompletionInfo;
import jdk.jshell.VarSnippet;
import jdk.jshell.execution.DirectExecutionControl;
import jdk.jshell.spi.ExecutionControl;
import jdk.jshell.spi.ExecutionControlProvider;
import jdk.jshell.spi.ExecutionEnv;
import jdk.jshell.spi.SPIResolutionException;

public class JShellScriptEngine implements ScriptEngine {
	/**
	 * A DirectExecutionControl to keep evaluation within the current JVM, allowing variables to be sent
	 * back and forth. Overrides some methods to give access to normally protected data.
	 */
	private class DirectExecutionControlExtended extends DirectExecutionControl {
		private String lastClassName;
		private String lastVarName;
		private Object lastValue;
		
		/**
		 * Override to save the class and variable names.
		 */
		@Override
		public String varValue(String className, String varName) throws RunException, EngineTerminationException, InternalException {
			this.lastClassName = className;
			this.lastVarName = varName;
			return super.varValue(className, varName);
		}

		/**
		 * Override to save the return value from the invocation.
		 */
		@Override
		protected String invoke(Method method) throws Exception {
			lastValue = method.invoke(null, new Object[0]);
			return valueString(lastValue);
		}

		/**
		 * Returns the actual value of a variable instead of a String. This calls jshell.varValue(VarSnippet) to cause it to call
		 * varValue(String className, String varName) so we can get the right field to access with reflection.
		 */
		public Object getActualVarValue(VarSnippet varSnippet) throws ReflectiveOperationException {
			jshell.varValue(varSnippet);
			Field field = findClass(lastClassName).getField(lastVarName);
			field.setAccessible(true);
			Object value = field.get(null);
			return value;
		}

		@Override
	    protected String throwConvertedInvocationException(Throwable cause) throws RunException, InternalException {
			if (cause instanceof SPIResolutionException) {
	            SPIResolutionException spire = (SPIResolutionException) cause;
	            throw new ResolutionException(spire.id(), spire.getStackTrace());
	        } else {
	        	// Override to prevent null messages causing NullPointerExceptions.
	            throw new UserException(cause.getMessage() == null ? "<None>" : cause.getMessage(), cause.getClass().getName(), cause.getStackTrace());
	        }
	    }
		
		/**
		 * Returns the last value returned from an invoke call.
		 */
		public Object getLastValue() {
			return lastValue;
		}
	}
	
	/**
	 * Provider of a constant ExecutionControl value.
	 */
	public static class SimpleExecutionControlProvider implements ExecutionControlProvider {
		private final ExecutionControl executionControl;

		public SimpleExecutionControlProvider(ExecutionControl executionControl) {
			this.executionControl = executionControl;
		}

		@Override
		public ExecutionControl generate(ExecutionEnv env, Map<String, String> parameters) throws Throwable {
			return executionControl;
		}

		@Override
		public String name() {
			return "Simple Execution Control Provider";
		}
	}
	
	private final static ThreadLocal<Map<String, Object>> variables = new ThreadLocal<>();
	private final DirectExecutionControlExtended executionControl = new DirectExecutionControlExtended();
	private final JShell jshell = JShell.builder().executionEngine(new SimpleExecutionControlProvider(executionControl), new HashMap<>()).build();
	private ScriptContext context = new SimpleScriptContext();
	
	public JShellScriptEngine() {
		this(null);
	}
	
	public JShellScriptEngine(Bindings globalBindings) {
		setBindings(globalBindings, ScriptContext.GLOBAL_SCOPE);
	}

	@Override
	public Bindings createBindings() {
		return new SimpleBindings();
	}

	@Override
	public Object eval(String script) throws ScriptException {
		return eval(script, context.getBindings(ScriptContext.ENGINE_SCOPE));
	}

	@Override
	public Object eval(Reader reader) throws ScriptException {
		return eval(readScript(reader));
	}

	@Override
	public Object eval(String script, ScriptContext context) throws ScriptException {
		return eval(script, context, context.getBindings(ScriptContext.ENGINE_SCOPE));
	}

	@Override
	public Object eval(Reader reader, ScriptContext context) throws ScriptException {
		return eval(readScript(reader), context);
	}

	@Override
	public Object eval(String script, Bindings bindings) throws ScriptException {
		return eval(script, context, bindings);
	}

	@Override
	public Object eval(Reader reader, Bindings bindings) throws ScriptException {
		return eval(readScript(reader), bindings);
	}
	
	/**
	 * Evaluates the script against the given context and bindings, then returns the resulting
	 * value or throws a ScriptException to indicate an error.
	 */
	private Object eval(String script, ScriptContext context, Bindings bindings) throws ScriptException {
		InputStream in = System.in;
		PrintStream err = System.err;
		PrintStream out = System.out;
		
		try {
			System.setOut(new PrintStream(new WriterOutputStream(context.getWriter())));
			System.setErr(new PrintStream(new WriterOutputStream(context.getErrorWriter())));
			System.setIn(new ReaderInputStream(context.getReader(), "UTF-8"));
			
			Bindings globalBindings = context.getBindings(ScriptContext.GLOBAL_SCOPE);
			
			writeVariableValues(getCombinedVariables(globalBindings, bindings));
			List<SnippetEvent> events = evalAll(script);
			try {
				for(SnippetEvent event:events) {
					if(event.exception() != null) {
						try {
							throw event.exception();
						} catch(EvalException e) {
							throw new ScriptException(convertJShellException(e));
						} catch(JShellException e) {
							throw new ScriptException(e);
						}
					}
					
					if(event.status() == Status.VALID) {
						Snippet snippet = event.snippet();
						if(snippet instanceof VarSnippet) {
							VarSnippet varSnippet = (VarSnippet) snippet;
							return executionControl.getActualVarValue(varSnippet);
						} else if(snippet instanceof ExpressionSnippet) {
							return executionControl.getLastValue();
						}
					}
					
					if(event.status() == Status.REJECTED) {
						Diag diag = jshell.diagnostics(event.snippet()).findAny().get();
						throw new ScriptException(diag.getPosition() + ": " + diag.getMessage(null));
					}
				}
			} catch(ReflectiveOperationException e) {
				throw new ScriptException(e);
			} finally {
				readVariableValues(globalBindings, bindings);
			}
		} finally {
			System.out.flush();
			System.err.flush();
			
			System.setIn(in);
			System.setOut(out);
			System.setErr(err);
		}
		
		return null;
	}
	
	/**
	 * Split script into snippets and evaluate them all, returning the last result.
	 */
	private List<SnippetEvent> evalAll(String script) throws ScriptException {
		while(true) {
			CompletionInfo completionInfo = jshell.sourceCodeAnalysis().analyzeCompletion(script);
			if(!completionInfo.completeness().isComplete()) {
				throw new ScriptException("Incomplete script");
			}
	
			List<SnippetEvent> result = jshell.eval(completionInfo.source());
			
			script = completionInfo.remaining();
			
			if(script.isEmpty()) {
				return result;
			}
		}
	}

	/**
	 * Returns the original exception to the type original.getExceptionClassName() if possible, otherwise
	 * returns the original exception.
	 */
	private static Exception convertJShellException(EvalException original) {
		try {
			Class<?> exceptionClass = Class.forName(original.getExceptionClassName());
			if( Exception.class.isAssignableFrom(exceptionClass)) {
				Constructor<?> constructor = exceptionClass.getConstructor(String.class, Throwable.class);
				Exception exception = (Exception) constructor.newInstance(original.getMessage(), original.getCause());
				exception.setStackTrace(original.getStackTrace());
				return exception;
			}
		} catch(ReflectiveOperationException e2) {
		}
		return original;
	}

	/**
	 * Writes the variables into variables in JShell.
	 */
	private void writeVariableValues(Map<String, Object> variables) {
		JShellScriptEngine.variables.set(variables);
		variables.forEach((name, value) -> {
			if(name.equals("_")) {
				name = "__";
			}
			String type = getDeclaredType(value);
			String command = String.format("%s %s = (%s) %s.getBindingValue(\"%s\");", type, name, type, JShellScriptEngine.class.getName(), name);
			List<SnippetEvent> events = jshell.eval(command);
			for(SnippetEvent event:events) {
				if(event.status() == Status.REJECTED) {
					Diag diag = jshell.diagnostics(event.snippet()).findAny().get();
					throw new RuntimeException(diag.getPosition() + ": " + diag.getMessage(null));
				}
			}
		});
	}
	
	/**
	 * Returns the string to declare a type of clazz.
	 */
	private static String getDeclaredType(Object value) {
		if(value == null) {
			return "java.lang.Object";
		}
		
		Class<?> clazz = value.getClass();
		
		if((clazz.getModifiers() & Modifier.PRIVATE) > 0) {
			clazz = clazz.getSuperclass();
		}
		
		return clazz.getCanonicalName();
	}

	/**
	 * Called from script to retrieve values from the bindings.
	 */
	public static Object getBindingValue(String name) {
		return variables.get().get(name);
	}

	/**
	 * Reads the variables from JShell into bindings.
	 */
	private void readVariableValues(Bindings globalBindings, Bindings engineBindings) {
		jshell.variables().forEach(varSnippet -> {
			try {
				String name = varSnippet.name();
				Object value = executionControl.getActualVarValue(varSnippet);
				if(globalBindings != null && !engineBindings.containsKey(name) && globalBindings.containsKey(name)) {
					globalBindings.put(name, value);
				} else {
					engineBindings.put(name, value);
				}
			} catch(Exception e) {
				e.printStackTrace();
			}
		});
	}
	
	private static Map<String, Object> getCombinedVariables(Bindings globalBindings, Bindings engineBindings) {
		if(globalBindings == null) {
			return engineBindings;
		}
		
		Map<String, Object> variables = new HashMap<>();
		variables.putAll(globalBindings);
		variables.putAll(engineBindings);
		return variables;
	}

	@Override
	public Object get(String key) {
		return getBindings(ScriptContext.ENGINE_SCOPE).get(key);
	}

	@Override
	public Bindings getBindings(int scope) {
		return context.getBindings(scope);
	}

	@Override
	public ScriptContext getContext() {
		return context;
	}

	@Override
	public ScriptEngineFactory getFactory() {
		return new JShellScriptEngineFactory();
	}

	@Override
	public void put(String key, Object value) {
		 getBindings(ScriptContext.ENGINE_SCOPE).put(key, value);
	}

	@Override
	public void setBindings(Bindings bindings, int scope) {
		context.setBindings(bindings, scope);
	}

	@Override
	public void setContext(ScriptContext context) {
		this.context = context;
	}
	
	/**
	 * Returns the whole contents of reader.
	 */
	private static String readScript(Reader reader) throws ScriptException {
		try {
			StringBuilder s = new StringBuilder();
			BufferedReader bufferedReader = new BufferedReader(reader);
			String line;
			while((line = bufferedReader.readLine()) != null) {
				s.append(line);
				s.append("\n");
			}
			return s.toString();
		} catch(IOException e) {
			throw new ScriptException(e);
		}
	}
}