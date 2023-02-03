/*
 * MIT License
 *
 * Copyright (c) 2023 BlvckBytes
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package me.blvckbytes.autowirer;

import me.blvckbytes.utilitytypes.FUnsafeBiConsumer;
import me.blvckbytes.utilitytypes.FUnsafeConsumer;
import me.blvckbytes.utilitytypes.FUnsafeFunction;
import me.blvckbytes.utilitytypes.Tuple;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.util.*;
import java.util.function.Consumer;

public class AutoWirer implements IAutoWirer {

  private @Nullable Consumer<Exception> exceptionHandler;

  private final Map<Class<?>, ConstructorInfo> singletonConstructors;
  private final List<Tuple<Object, @Nullable ConstructorInfo>> singletonInstances;
  private final List<InstantiationListener> instantiationListeners;
  private final Set<Class<?>> encounteredClasses;

  public AutoWirer() {
    this.singletonConstructors = new HashMap<>();
    this.instantiationListeners = new ArrayList<>();
    this.singletonInstances = new ArrayList<>();
    this.encounteredClasses = new HashSet<>();

    // Support for the AutoWirer itself as a dependency
    this.singletonInstances.add(new Tuple<>(this, null));
  }

  @SuppressWarnings("unchecked")
  public <T> AutoWirer addInstantiationListener(
    Class<T> type, FUnsafeBiConsumer<T, Object[], Exception> creationListener,
    Class<?>... dependencies
  ) {
    this.instantiationListeners.add(new InstantiationListener(type, (FUnsafeBiConsumer<Object, Object[], Exception>) creationListener, dependencies));
    return this;
  }

  public AutoWirer addSingleton(Class<?> type) {
    registerConstructor(type);
    return this;
  }

  @SuppressWarnings("unchecked")
  public <T> AutoWirer addSingleton(Class<T> type, FUnsafeFunction<Object[], T, Exception> generator, @Nullable FUnsafeConsumer<T, Exception> onCleanup, Class<?>... dependencies) {
    singletonConstructors.put(type, new ConstructorInfo(dependencies, generator, (FUnsafeConsumer<Object, Exception>) onCleanup));
    return this;
  }

  public AutoWirer addExistingSingleton(Object value) {
    singletonInstances.add(new Tuple<>(value, null));
    return this;
  }

  public AutoWirer onException(Consumer<Exception> handler) {
    this.exceptionHandler = handler;
    return this;
  }

  public AutoWirer wire(@Nullable Consumer<AutoWirer> success) {
    try {
      for (Class<?> singletonType : singletonConstructors.keySet())
        getOrInstantiateClass(singletonType, null, true);

      for (Tuple<Object, @Nullable ConstructorInfo> data : singletonInstances) {
        Object instance = data.a;
        if (instance instanceof IInitializable)
          ((IInitializable) instance).initialize();
      }

      if (success != null)
        success.accept(this);
      return this;
    } catch (Exception e) {
      if (this.exceptionHandler != null) {
        this.exceptionHandler.accept(e);
        return this;
      }

      e.printStackTrace();
      return this;
    }
  }

  public void cleanup() {
    for (int i = singletonInstances.size() - 1; i >= 0; i--) {
      Tuple<Object, @Nullable ConstructorInfo> data = singletonInstances.remove(i);
      Object instance = data.a;

      if (instance instanceof ICleanable)
        ((ICleanable) instance).cleanup();

      ConstructorInfo constructorInfo = data.b;
      if (constructorInfo != null && constructorInfo.externalCleanup != null) {
        try {
          constructorInfo.externalCleanup.accept(instance);
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    }

    singletonConstructors.clear();
  }

  @Override
  public int getInstancesCount() {
    return singletonInstances.size();
  }

  private @Nullable ConstructorInfo findConstructorInfo(Class<?> type) {
    ConstructorInfo result = null;
    Class<?> resultClass = null;

    for (Map.Entry<Class<?>, ConstructorInfo> entry : singletonConstructors.entrySet()) {
      if (type.isAssignableFrom(entry.getKey())) {
        if (result != null)
          throw new IllegalStateException("Multiple possible constructors of type " + type + " (" + entry.getKey() + ", " + resultClass + ")");

        result = entry.getValue();
        resultClass = entry.getKey();
      }
    }

    return result;
  }

  private @Nullable Object findInstance(Class<?> type) {
    Object result = null;

    for (Tuple<Object, @Nullable ConstructorInfo> existing : singletonInstances) {
      if (type.isInstance(existing.a)) {
        if (result != null)
          throw new IllegalStateException("Found multiple possible instances of type " + type + " (" + existing.a.getClass() + ", " + result.getClass() + ")");
        result = existing.a;
      }
    }

    return result;
  }

  private void callInstantiationListeners(Object instance) throws Exception {
    for (InstantiationListener listener : instantiationListeners) {
      if (!listener.type.isInstance(instance))
        continue;

      Object[] args = new Object[listener.dependencies.length];
      for (int i = 0; i < args.length; i++)
        args[i] = getOrInstantiateClass(listener.dependencies[i], null, true);

      listener.listener.accept(instance, args);
    }
  }

  private Object getOrInstantiateClass(Class<?> type, Class<?> parent, boolean singleton) throws Exception {

    if (singleton) {
      Object existing = findInstance(type);
      if (existing != null)
        return existing;
    }

    if (!encounteredClasses.add(type))
      throw new IllegalStateException("Circular dependency detected: " + type + " of parent " + parent);

    ConstructorInfo constructorInfo = findConstructorInfo(type);
    if (constructorInfo == null)
      throw new IllegalStateException("Unknown dependency: " + type);

    Object[] argumentValues = new Object[constructorInfo.parameters.length];
    for (int i = 0; i < argumentValues.length; i++)
      argumentValues[i] = getOrInstantiateClass(constructorInfo.parameters[i], type, true);

    Object instance = constructorInfo.constructor.apply(argumentValues);
    callInstantiationListeners(instance);

    if (singleton)
      singletonInstances.add(new Tuple<>(instance, constructorInfo));
    return instance;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T getOrInstantiateClass(Class<T> type, boolean singleton) throws Exception {
    registerConstructor(type);
    return (T) getOrInstantiateClass(type, null, singleton);
  }

  private void registerConstructor(Class<?> type) {
    Constructor<?>[] constructors = type.getConstructors();
    if (constructors.length != 1)
      throw new IllegalStateException("Auto-wired classes need to have exactly one public constructor");

    Constructor<?> constructor = constructors[0];
    singletonConstructors.put(type, new ConstructorInfo(constructor.getParameterTypes(), constructor::newInstance, null));
  }
}
