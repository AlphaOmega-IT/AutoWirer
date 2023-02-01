package me.blvckbytes.autowirer;

import me.blvckbytes.utilitytypes.FUnsafeConsumer;
import me.blvckbytes.utilitytypes.FUnsafeFunction;
import me.blvckbytes.utilitytypes.Tuple;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.util.*;
import java.util.function.Consumer;

public class AutoWirer {

  private @Nullable Consumer<Exception> exceptionHandler;

  private final Map<Class<?>, ConstructorInfo> singletonConstructors;
  private final List<Tuple<Object, @Nullable ConstructorInfo>> singletonInstances;

  public AutoWirer() {
    this.singletonConstructors = new HashMap<>();
    this.singletonInstances = new ArrayList<>();
  }

  public AutoWirer addSingleton(Class<?> type) {
    Constructor<?>[] constructors = type.getConstructors();
    if (constructors.length != 1)
      throw new IllegalStateException("Auto-wired classes need to have exactly one public constructor");

    Constructor<?> constructor = constructors[0];
    singletonConstructors.put(type, new ConstructorInfo(constructor.getParameterTypes(), constructor::newInstance, null));
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
      Set<Class<?>> encounteredClasses = new HashSet<>();
      for (Class<?> singletonType : singletonConstructors.keySet())
        instantiateClass(singletonType, null, encounteredClasses);

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
      Class<?> existingClass = existing.a.getClass();

      if (type.isAssignableFrom(existingClass)) {
        if (result != null)
          throw new IllegalStateException("Found multiple possible instances of type " + type + " (" + existingClass + ", " + result.getClass() + ")");
        result = existing.a;
      }
    }

    return result;
  }

  private Object instantiateClass(Class<?> type, Class<?> parent, Set<Class<?>> encounteredClasses) throws Exception {
    Object existing = findInstance(type);
    if (existing != null)
      return existing;

    if (!encounteredClasses.add(type))
      throw new IllegalStateException("Circular dependency detected: " + type + " of parent " + parent);

    ConstructorInfo constructorInfo = findConstructorInfo(type);
    if (constructorInfo == null)
      throw new IllegalStateException("Unknown dependency: " + type);

    Object[] argumentValues = new Object[constructorInfo.parameters.length];
    for (int i = 0; i < argumentValues.length; i++)
      argumentValues[i] = instantiateClass(constructorInfo.parameters[i], type, encounteredClasses);

    Object instance = constructorInfo.constructor.apply(argumentValues);
    singletonInstances.add(new Tuple<>(instance, constructorInfo));
    return instance;
  }
}
