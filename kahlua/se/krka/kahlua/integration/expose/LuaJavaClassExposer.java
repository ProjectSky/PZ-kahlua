/*
 Copyright (c) 2009 Kristofer Karlsson <kristofer.karlsson@gmail.com>

 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in
 all copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 THE SOFTWARE.
 */

package se.krka.kahlua.integration.expose;

import se.krka.kahlua.converter.KahluaConverterManager;
import se.krka.kahlua.integration.annotations.Desc;
import se.krka.kahlua.integration.annotations.LuaConstructor;
import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.integration.expose.caller.ConstructorCaller;
import se.krka.kahlua.integration.expose.caller.MethodCaller;
import se.krka.kahlua.integration.processor.ClassParameterInformation;
import se.krka.kahlua.vm.*;

import java.lang.reflect.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class LuaJavaClassExposer {
	private static final Object DEBUGINFO_KEY = new Object();
	private final KahluaConverterManager manager;
	private final Platform platform;
	private final KahluaTable environment;
	private final KahluaTable classMetatables;
	private final Set<Type> visitedTypes;
	private final KahluaTable autoExposeBase;
	private final Map<Class<?>, Boolean> shouldExposeCache;
	public HashMap<String, Class<?>> TypeMap;

	public LuaJavaClassExposer(KahluaConverterManager manager,
			Platform platform, KahluaTable environment) {
		this(manager, platform, environment, null);
	}

	public LuaJavaClassExposer(KahluaConverterManager manager,
			Platform platform, KahluaTable environment,
			KahluaTable autoExposeBase) {
		this.visitedTypes = new HashSet<Type>();

		this.shouldExposeCache = new HashMap<Class<?>, Boolean>();

		this.TypeMap = new HashMap<String, Class<?>>();

		this.manager = manager;
		this.platform = platform;
		this.environment = environment;
		this.autoExposeBase = autoExposeBase;
		this.classMetatables = KahluaUtil.getClassMetatables(platform,
				environment);

		if (this.classMetatables.getMetatable() == null) {
			KahluaTable mt = platform.newTable();
			mt.rawset("__index", new JavaFunction() {
				public int call(LuaCallFrame callFrame, int nArguments) {
					Object t = callFrame.get(0);
					Object key = callFrame.get(1);
					if (t != LuaJavaClassExposer.this.classMetatables) {
						throw new IllegalArgumentException(
								"Expected classmetatables as the first argument to __index");
					}
					if ((key == null) || (!(key instanceof Class))) {
						return callFrame.pushNil();
					}
					Class<?> clazz = (Class<?>) key;
					if ((!(LuaJavaClassExposer.this.isExposed(clazz)))
							&& (LuaJavaClassExposer.this.shouldExpose(clazz))) {
						LuaJavaClassExposer.this.exposeClass(clazz);
						return callFrame
								.push(LuaJavaClassExposer.this.classMetatables
										.rawget(clazz));
					}
					return callFrame.pushNil();
				}
			});
			this.classMetatables.setMetatable(mt);
		}
	}

	@SuppressWarnings("unchecked")
	public Map<Class<?>, ClassDebugInformation> getClassDebugInformation() {
		Object classMap = environment.rawget(DEBUGINFO_KEY);
		if (classMap == null || !(classMap instanceof Map)) {
			classMap = new HashMap<Class<?>, ClassDebugInformation>();
			environment.rawset(DEBUGINFO_KEY, classMap);
		}
		return (Map<Class<?>, ClassDebugInformation>) classMap;
	}

	public void exposeClass(Class<?> clazz) {
		if ((clazz != null) && (!(isExposed(clazz)))) {
			this.shouldExposeCache.clear();
			readDebugData(clazz);
			setupMetaTables(clazz);

			populateMethods(clazz);
		}
	}

	public void exposeClassUsingJavaEquals(Class<?> clazz) {
		exposeClass(clazz);
		addJavaEquals(getMetaTable(clazz));
	}

	private KahluaTable getMetaTable(Class<?> clazz) {
		return ((KahluaTable) this.classMetatables.rawget(clazz));
	}

	private KahluaTable getIndexTable(KahluaTable metaTable) {
		if (metaTable == null) {
			return null;
		}

		Object indexObject = metaTable.rawget("__index");
		if (indexObject == null) {
			return null;
		}
		if (indexObject instanceof KahluaTable) {
			return ((KahluaTable) indexObject);
		}
		return null;
	}

	/**
	 * Creates a global variable in the environment that points to a function
	 * which calls the specified method on the owner object.
	 * <p/>
	 * The name of the global variable is the same as the name of the method.
	 *
	 * @param environment
	 * @param owner
	 * @param method
	 */
	public void exposeGlobalObjectFunction(KahluaTable environment,
			Object owner, Method method) {
		exposeGlobalObjectFunction(environment, owner, method, method.getName());
	}

	/**
	 * Creates a global variable in the environment that points to a function
	 * which calls the specified method on the owner object.
	 * <p/>
	 * The name of the global variable is the same as methodName
	 *
	 * @param environment
	 * @param owner
	 * @param method
	 * @param methodName
	 *            the name of the method in Lua
	 */
	public void exposeGlobalObjectFunction(KahluaTable environment,
			Object owner, Method method, String methodName) {
		Class<?> clazz = owner.getClass();
		readDebugData(clazz);
		LuaJavaInvoker invoker = getMethodInvoker(clazz, method, methodName,
				owner, false);
		addInvoker(environment, methodName, invoker);
	}

	public void exposeGlobalClassFunction(KahluaTable environment,
			Class<?> clazz, Constructor<?> constructor, String methodName) {
		readDebugData(clazz);
		LuaJavaInvoker invoker = getConstructorInvoker(clazz, constructor,
				methodName);
		addInvoker(environment, methodName, invoker);
	}

	private LuaJavaInvoker getMethodInvoker(Class<?> clazz, Method method,
			String methodName, Object owner, boolean hasSelf) {
		return new LuaJavaInvoker(this, this.manager, clazz, methodName,
				new MethodCaller(method, owner, hasSelf));
	}

	private LuaJavaInvoker getConstructorInvoker(Class<?> clazz,
			Constructor<?> constructor, String methodName) {
		return new LuaJavaInvoker(this, this.manager, clazz, methodName,
				new ConstructorCaller(constructor));
	}

	private LuaJavaInvoker getMethodInvoker(Class<?> clazz, Method method,
			String methodName) {
		return getMethodInvoker(clazz, method, methodName, null, true);
	}

	private LuaJavaInvoker getGlobalInvoker(Class<?> clazz, Method method,
			String methodName) {
		return getMethodInvoker(clazz, method, methodName, null, false);
	}

	public void exposeGlobalClassFunction(KahluaTable environment,
			Class<?> clazz, Method method, String methodName) {
		readDebugData(clazz);
		if (Modifier.isStatic(method.getModifiers()))
			addInvoker(environment, methodName,
					getGlobalInvoker(clazz, method, methodName));
	}

	/**
	 * Exposes an object method for a specific class, which means that that
	 * method will be accessible from all objects in Lua of exactly that class.
	 * <p/>
	 * Usage: If object is of type clazz, then the method can be called from Lua
	 * with: object:methodName(args)
	 *
	 * @param clazz
	 * @param method
	 */
	public void exposeMethod(Class<?> clazz, Method method) {
		exposeMethod(clazz, method, method.getName());
	}

	/**
	 * Exposes an object method for a specific class, which means that that
	 * method will be accessible from all objects in Lua of exactly that class.
	 * <p/>
	 * Usage: If object is of type clazz, then the method can be called from Lua
	 * with: object:methodName(args)
	 *
	 * @param clazz
	 * @param method
	 * @param methodName
	 *            what it should be called in Lua. Does not have to match
	 *            method.getName()
	 */
	public void exposeMethod(Class<?> clazz, Method method, String methodName) {
		readDebugData(clazz);
		if (!(isExposed(clazz))) {
			setupMetaTables(clazz);
		}
		KahluaTable metaTable = getMetaTable(clazz);
		KahluaTable indexTable = getIndexTable(metaTable);

		LuaJavaInvoker newInvoker = getMethodInvoker(clazz, method, methodName);
		addInvoker(indexTable, methodName, newInvoker);
	}

	private void addInvoker(KahluaTable indexTable, String methodName,
			LuaJavaInvoker invoker) {
		// if (methodName.equals("setDir")) {
		// int i = 0;
		// }
		Object current = indexTable.rawget(methodName);
		if (current != null)
			if (current instanceof LuaJavaInvoker) {
				if (current.equals(invoker)) {
					return;
				}
				MultiLuaJavaInvoker multiInvoker = new MultiLuaJavaInvoker();
				multiInvoker.addInvoker((LuaJavaInvoker) current);
				multiInvoker.addInvoker(invoker);
				indexTable.rawset(methodName, multiInvoker);
			} else if (current instanceof MultiLuaJavaInvoker) {
				((MultiLuaJavaInvoker) current).addInvoker(invoker);
			} else
				indexTable.rawset(methodName, invoker);
	}

	public boolean shouldExpose(Class<?> clazz) {
		if (clazz == null) {
			return false;
		}
		Boolean bool = (Boolean) this.shouldExposeCache.get(clazz);
		if (bool != null) {
			return bool.booleanValue();
		}
		if (this.autoExposeBase != null) {
			exposeLikeJavaRecursively(clazz, this.autoExposeBase);
			return true;
		}
		if (isExposed(clazz)) {
			this.shouldExposeCache.put(clazz, Boolean.TRUE);
			return true;
		}
		if (shouldExpose(clazz.getSuperclass())) {
			this.shouldExposeCache.put(clazz, Boolean.TRUE);
			return true;
		}
		for (Class<?> sub : clazz.getInterfaces()) {
			if (shouldExpose(sub)) {
				this.shouldExposeCache.put(clazz, Boolean.TRUE);
				return true;
			}
		}
		this.shouldExposeCache.put(clazz, Boolean.FALSE);
		return false;
	}

	private void setupMetaTables(Class<?> clazz) {
		Class<?> superClazz = clazz.getSuperclass();
		exposeClass(superClazz);

		KahluaTable superMetaTable = getMetaTable(superClazz);

		KahluaTable metatable = this.platform.newTable();
		KahluaTable indexTable = this.platform.newTable();
		metatable.rawset("__index", indexTable);
		if (superMetaTable != null) {
			metatable.rawset("__newindex", superMetaTable.rawget("__newindex"));
		}
		indexTable.setMetatable(superMetaTable);
		this.classMetatables.rawset(clazz, metatable);
	}

	private void addJavaEquals(KahluaTable metatable) {
		metatable.rawset("__eq", new JavaFunction() {
			public int call(LuaCallFrame callFrame, int nArguments) {
				boolean equals = callFrame.get(0).equals(callFrame.get(1));
				callFrame.push(Boolean.valueOf(equals));
				return 1;
			}
		});
	}

	public void exposeGlobalFunctions(Object object) {
		Class<?> clazz = object.getClass();
		readDebugData(clazz);
		for (Method method : clazz.getMethods()) {
			LuaMethod luaMethod = (LuaMethod) AnnotationUtil.getAnnotation(
					method, LuaMethod.class);
			if (luaMethod == null)
				continue;
			String methodName;
			if (luaMethod.name().equals(""))
				methodName = method.getName();
			else {
				methodName = luaMethod.name();
			}
			if (luaMethod.global())
				exposeGlobalObjectFunction(this.environment, object, method,
						methodName);
		}
	}

	public void exposeLikeJava(Class<?> clazz) {
		exposeLikeJava(clazz, this.autoExposeBase);
	}

	public void exposeLikeJava(Class<?> clazz, KahluaTable staticBase) {
		if ((clazz == null) || (isExposed(clazz))) {
			return;
		}
		setupMetaTables(clazz);

		exposeMethods(clazz);
		if ((clazz.isSynthetic()) || (clazz.isAnonymousClass())
				|| (clazz.isPrimitive()) || (Proxy.isProxyClass(clazz))
				|| (clazz.getSimpleName().startsWith("$")))
			return;
		exposeStatics(clazz, staticBase);
	}

	private void exposeStatics(Class<?> clazz, KahluaTable staticBase) {
		String[] packageStructure = clazz.getName().split("\\.");
		KahluaTable container = createTableStructure(staticBase,
				packageStructure);
		container.rawset("class", clazz);
		if (staticBase.rawget(clazz.getSimpleName()) == null) {
			staticBase.rawset(clazz.getSimpleName(), container);
		}
		for (Method method : clazz.getMethods()) {
			String name = method.getName();
			if (Modifier.isPublic(method.getModifiers())) {
				if (Modifier.isStatic(method.getModifiers())) {
					exposeGlobalClassFunction(container, clazz, method, name);
				}
			}
		}
		for (Field field : clazz.getFields()) {
			String name = field.getName();
			if (Modifier.isPublic(field.getModifiers())) {
				if (Modifier.isStatic(field.getModifiers())) {
					try {
						container.rawset(name, field.get(clazz));
					} catch (IllegalAccessException e) {
					}
				}
			}
		}
		for (Constructor<?> constructor : clazz.getConstructors()) {
			int modifiers = constructor.getModifiers();
			if (!Modifier.isInterface(modifiers)
					&& !Modifier.isAbstract(modifiers)
					&& Modifier.isPublic(modifiers)) {
				addInvoker(container, "new",
						getConstructorInvoker(clazz, constructor, "new"));
			}
		}
	}

	private void exposeMethods(Class<?> clazz) {
		for (Method method : clazz.getMethods()) {
			String name = method.getName();
			if (Modifier.isPublic(method.getModifiers())) {
				if (!Modifier.isStatic(method.getModifiers())) {
					exposeMethod(clazz, method, name);
				}
			}
		}
	}

	private KahluaTable createTableStructure(KahluaTable base,
			String[] structure) {
		for (String s : structure) {
			base = KahluaUtil.getOrCreateTable(this.platform, base, s);
		}
		return base;
	}

	private void populateMethods(Class<?> clazz) {
		for (Constructor<?> constructor : clazz.getConstructors()) {
			LuaConstructor annotation = (LuaConstructor) constructor
					.getAnnotation(LuaConstructor.class);
			if (annotation != null) {
				String methodName = annotation.name();
				exposeGlobalClassFunction(this.environment, clazz, constructor,
						methodName);
			}
		}
		for (Method method : clazz.getMethods()) {
			LuaMethod luaMethod = (LuaMethod) AnnotationUtil.getAnnotation(
					method, LuaMethod.class);
			if (luaMethod == null)
				continue;
			String methodName;
			if (luaMethod.name().equals(""))
				methodName = method.getName();
			else {
				methodName = luaMethod.name();
			}
			if (luaMethod.global()) {
				if (Modifier.isStatic(method.getModifiers()))
					exposeGlobalClassFunction(this.environment, clazz, method,
							methodName);
			} else
				exposeMethod(clazz, method, methodName);
		}
	}

	public boolean isExposed(Class<?> clazz) {
		return ((clazz != null) && (getMetaTable(clazz) != null));
	}

	ClassDebugInformation getDebugdata(Class<?> clazz) {
		readDebugDataD(clazz);
		return ((ClassDebugInformation) getClassDebugInformation().get(clazz));
	}

	ClassDebugInformation getDebugdataA(Class<?> clazz) {
		return ((ClassDebugInformation) getClassDebugInformation().get(clazz));
	}

	@SuppressWarnings("unchecked")
	private void readDebugDataD(Class<?> clazz) {
		if (getDebugdataA(clazz) == null) {
			ClassParameterInformation parameterInfo = null;
			try {
				parameterInfo = ClassParameterInformation.getFromStream(clazz);
			} catch (Exception localException) {
			}
			if (parameterInfo == null) {
				parameterInfo = new ClassParameterInformation(clazz);
			}
			ClassDebugInformation debugInfo = new ClassDebugInformation(clazz,
					parameterInfo);

			@SuppressWarnings("rawtypes")
			Map information = getClassDebugInformation();
			information.put(clazz, debugInfo);
		}
	}

	private void readDebugData(Class<?> clazz) {
	}

	@LuaMethod(global = true, name = "definition")
	@Desc("returns a string that describes the object")
	public String getDefinition(Object obj) {
		if (obj == null) {
			return null;
		} else if (obj instanceof LuaJavaInvoker) {
			MethodDebugInformation data = ((LuaJavaInvoker) obj)
					.getMethodDebugData();
			return data.toString();
		} else if (obj instanceof MultiLuaJavaInvoker) {
			StringBuilder builder = new StringBuilder();
			for (LuaJavaInvoker invoker : ((MultiLuaJavaInvoker) obj)
					.getInvokers()) {
				builder.append(invoker.getMethodDebugData().toString());
			}
			return builder.toString();
		} else {
			return KahluaUtil.tostring(obj,
					KahluaUtil.getWorkerThread(platform, environment));
		}
	}

	public void exposeLikeJavaRecursively(Type type) {
		exposeLikeJavaRecursively(type, autoExposeBase);
	}

	public void exposeLikeJavaRecursively(Type type, KahluaTable staticBase) {
		exposeLikeJava(staticBase, visitedTypes, type);
	}

	@SuppressWarnings("rawtypes")
	private void exposeLikeJava(KahluaTable staticBase, Set<Type> visited,
			Type type) {
		if (type == null) {
			return;
		}
		if (visited.contains(type)) {
			return;
		}
		visited.add(type);

		if (type instanceof Class) {
			exposeLikeJavaByClass(staticBase, visited, (Class<?>) type);
		} else if (type instanceof WildcardType) {
			WildcardType wildcardType = (WildcardType) type;
			exposeList(staticBase, visited, wildcardType.getLowerBounds());
			exposeList(staticBase, visited, wildcardType.getUpperBounds());
		} else if (type instanceof ParameterizedType) {
			ParameterizedType parameterizedType = (ParameterizedType) type;
			exposeLikeJava(staticBase, visited, parameterizedType.getRawType());
			exposeLikeJava(staticBase, visited,
					parameterizedType.getOwnerType());
			exposeList(staticBase, visited,
					parameterizedType.getActualTypeArguments());
		} else if (type instanceof TypeVariable) {
			TypeVariable typeVariable = (TypeVariable) type;
			exposeList(staticBase, visited, typeVariable.getBounds());
		} else if (type instanceof GenericArrayType) {
			GenericArrayType genericArrayType = (GenericArrayType) type;
			exposeLikeJava(staticBase, visited,
					genericArrayType.getGenericComponentType());
		}
	}

	private void exposeList(KahluaTable staticBase, Set<Type> visited,
			Type[] types) {
		for (Type t : types) {
			exposeLikeJava(staticBase, visited, t);
		}
	}

	private void exposeLikeJavaByClass(KahluaTable staticBase,
			Set<Type> visited, Class<?> clazz) {
		String t = clazz.toString();
		t = t.substring(t.lastIndexOf(".") + 1);
		this.TypeMap.put(t, clazz);

		exposeList(staticBase, visited, clazz.getInterfaces());
		exposeLikeJava(staticBase, visited, clazz.getGenericSuperclass());
		if (clazz.isArray()) {
			exposeLikeJavaByClass(staticBase, visited, clazz.getComponentType());
		} else {
			exposeLikeJava(clazz, staticBase);
		}
		for (Method method : clazz.getDeclaredMethods()) {
			exposeList(staticBase, visited, method.getGenericParameterTypes());
			exposeList(staticBase, visited, method.getGenericExceptionTypes());
			exposeLikeJava(staticBase, visited, method.getGenericReturnType());
		}
		for (Field field : clazz.getDeclaredFields()) {
			exposeLikeJava(staticBase, visited, field.getGenericType());
		}
		for (Constructor<?> constructor : clazz.getConstructors()) {
			exposeList(staticBase, visited, constructor.getParameterTypes());
			exposeList(staticBase, visited, constructor.getExceptionTypes());
		}
	}
}