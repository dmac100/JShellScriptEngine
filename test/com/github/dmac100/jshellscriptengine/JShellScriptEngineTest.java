package com.github.dmac100.jshellscriptengine;

import static javax.script.ScriptContext.ENGINE_SCOPE;
import static javax.script.ScriptContext.GLOBAL_SCOPE;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.junit.Test;

public class JShellScriptEngineTest {
	private ScriptEngine scriptEngine = new JShellScriptEngineFactory().getScriptEngine();
	
	@Test
	public void expressionResult() throws Exception {
		assertEquals(2, scriptEngine.eval("1 + 1"));
	}
	
	@Test
	public void persistVariable() throws Exception {
		scriptEngine.eval("int x = 2;");
		assertEquals(2, scriptEngine.eval("x"));
	}
	
	@Test
	public void persistList() throws Exception {
		scriptEngine.eval("java.util.List<Integer> x = java.util.Arrays.asList(1, 2, 3)");
		assertEquals(1, scriptEngine.eval("x.get(0)"));
	}
	
	@Test
	public void persistInteger() throws Exception {
		scriptEngine.eval("Integer x = 2;");
		assertEquals(2, scriptEngine.eval("x"));
	}
	
	@Test
	public void persistArray() throws Exception {
		scriptEngine.eval("int[] x = { 1, 2, 3 };");
		assertEquals(1, scriptEngine.eval("x[0]"));
	}
	
	@Test
	public void persistNull() throws Exception {
		scriptEngine.eval("Object x = null;");
		assertEquals(null, scriptEngine.eval("x"));
	}
	
	@Test
	public void persistLambda() throws Exception {
		scriptEngine.eval("java.util.function.Supplier<Integer> x = () -> 1;");
		assertEquals(1, scriptEngine.eval("x.get()"));
	}
	
	@Test
	public void multipleStatements() throws Exception {
		assertEquals(2, scriptEngine.eval("int x = 2; x;"));
	}
	
	@Test
	public void getBindingsValue() throws Exception {
		scriptEngine.eval("int x = 2;");
		assertEquals(2, scriptEngine.getBindings(ENGINE_SCOPE).get("x"));
	}
	
	@Test
	public void setBindingsValue() throws Exception {
		scriptEngine.getBindings(ENGINE_SCOPE).put("x", 2);
		assertEquals(2, scriptEngine.eval("x"));
	}
	
	@Test
	public void setBindingsValueUnderscore() throws Exception {
		scriptEngine.getBindings(ENGINE_SCOPE).put("_", 2);
		assertEquals(2, scriptEngine.eval("__"));
	}
	
	@Test(expected=IOException.class)
	public void throwException() throws Throwable {
		try {
			scriptEngine.eval("throw new java.io.IOException();");
		} catch(ScriptException e) {
			throw e.getCause();
		}
	}
	
	@Test(expected=NullPointerException.class)
	public void throwExceptionNoCause() throws Throwable {
		try {
			scriptEngine.eval("throw new NullPointerException();");
		} catch(ScriptException e) {
			throw e.getCause();
		}
	}
	
	@Test
	public void setWriter() throws Exception {
		ByteArrayOutputStream byteArray = new ByteArrayOutputStream();
		scriptEngine.getContext().setWriter(new PrintWriter(byteArray));
		scriptEngine.eval("System.out.println(123);");
		assertEquals("123", new String(byteArray.toByteArray(), "UTF-8").trim());
	}
	
	@Test(expected=ScriptException.class)
	public void handleCompileErrors() throws Exception {
		scriptEngine.eval("ab-");
	}
	
	@Test
	public void scopes() throws Exception {
		JShellScriptEngineFactory factory = new JShellScriptEngineFactory();
		
		assertEquals(2, factory.getScriptEngine().eval("int x = 2;"));
		assertEquals(null, factory.getScriptEngine().getBindings(GLOBAL_SCOPE).get("x"));
		assertEquals(null, factory.getScriptEngine().getBindings(ENGINE_SCOPE).get("x"));
		
		factory.getScriptEngine().getBindings(GLOBAL_SCOPE).put("x", 1);
		assertEquals(2, factory.getScriptEngine().eval("int x = 2;"));
		assertEquals(2, factory.getScriptEngine().getBindings(GLOBAL_SCOPE).get("x"));
		assertEquals(null, factory.getScriptEngine().getBindings(ENGINE_SCOPE).get("x"));
	}
	
	@Test
	public void globalBindings() throws Exception {
		scriptEngine.setBindings(new SimpleBindings(), GLOBAL_SCOPE);
		scriptEngine.getBindings(GLOBAL_SCOPE).put("x", 2);
		
		assertEquals(2, scriptEngine.eval("x"));
		
		assertEquals(2, scriptEngine.getBindings(GLOBAL_SCOPE).get("x"));
		assertEquals(null, scriptEngine.getBindings(ENGINE_SCOPE).get("x"));
	}
	
	@Test
	public void newVariablesInEngineScope() throws Exception {
		scriptEngine.setBindings(new SimpleBindings(), GLOBAL_SCOPE);
		
		assertEquals(2, scriptEngine.eval("int x = 2;"));
		
		assertEquals(null, scriptEngine.getBindings(GLOBAL_SCOPE).get("x"));
		assertEquals(2, scriptEngine.getBindings(ENGINE_SCOPE).get("x"));
	}
	
	@Test
	public void globalAndEngineBindings() throws Exception {
		scriptEngine.setBindings(new SimpleBindings(), GLOBAL_SCOPE);
		scriptEngine.getBindings(GLOBAL_SCOPE).put("x", 2);
		scriptEngine.getBindings(ENGINE_SCOPE).put("x", 3);
		
		assertEquals(3, scriptEngine.eval("x"));
		assertEquals(4, scriptEngine.eval("x = 4"));
		
		assertEquals(2, scriptEngine.getBindings(GLOBAL_SCOPE).get("x"));
		assertEquals(4, scriptEngine.getBindings(ENGINE_SCOPE).get("x"));
	}
	
	@Test
	public void invalidBindings() throws Exception {
		scriptEngine.setBindings(new SimpleBindings(), GLOBAL_SCOPE);
		scriptEngine.getBindings(GLOBAL_SCOPE).put("a.b", 2);
		
		assertEquals(1, scriptEngine.eval("1"));
	}
}