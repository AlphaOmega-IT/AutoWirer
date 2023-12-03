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

import me.blvckbytes.utilitytypes.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AutoWirer implements IAutoWirer {

	private final Logger logger = Logger.getLogger(this.getClass().getName());
  private @Nullable Consumer<Exception> exceptionHandler;

  private final Map<Class<?>, ConstructorInfo> singletonConstructors = new HashMap<>();
  private final List<Tuple<Object, @Nullable ConstructorInfo>> singletonInstances = new ArrayList<>();
  private final List<Object> existingSingletonsToCallListenersOn = new ArrayList<>();
  private final List<InstantiationListener> instantiationListeners = new ArrayList<>();
  private final Set<Class<?>> encounteredClasses = new HashSet<>();

  public AutoWirer() {
    // Support for the AutoWirer itself as a dependency
    this.singletonInstances.add(new Tuple<>(this, null));
  }

  @SuppressWarnings("unchecked")
  public <T> AutoWirer addInstantiationListener(
    final @NotNull Class<T> clazz,
		final @NotNull FUnsafeBiConsumer<T, Object[], Exception> creationListener,
    final @NotNull Class<?>... dependencies
  ) {
    this.instantiationListeners.add(
			new InstantiationListener(
				clazz,
				(FUnsafeBiConsumer<Object, Object[], Exception>) creationListener,
				dependencies
			)
		);
    return this;
  }

  public AutoWirer addSingleton(
		final @NotNull Class<?> clazz
	) {
    this.registerConstructor(clazz);
    return this;
  }

  @SuppressWarnings("unchecked")
  public <T> AutoWirer addSingleton(
		final @NotNull Class<T> clazz,
		final @NotNull FUnsafeFunction<Object[], T, Exception> generator,
		final @Nullable FUnsafeConsumer<T, Exception> onCleanup,
		final @NotNull Class<?>... dependencies
	) {
    this.singletonConstructors.put(
			clazz,
			new ConstructorInfo(
				dependencies,
				generator,
				(FUnsafeConsumer<Object, Exception>) onCleanup
			)
		);
    return this;
  }

  public AutoWirer addExistingSingleton(
		final @NotNull Object value
	) {
    return this.addExistingSingleton(value, false);
  }

  public AutoWirer addExistingSingleton(
		final @NotNull Object value,
		final Boolean callInstantiationListeners
	) {
    this.singletonInstances.add(new Tuple<>(value, null));

    if (
			callInstantiationListeners
		) this.existingSingletonsToCallListenersOn.add(value);

    return this;
  }

  public AutoWirer onException(
		final @NotNull Consumer<Exception> handler
	) {
    this.exceptionHandler = handler;
    return this;
  }

  public AutoWirer wire(
		final @Nullable Consumer<AutoWirer> success
	) {
		Class<?> checkSingleton = null;
		Object checkExistingSingleton = null;
    try {
      for (
				final Class<?> singletonType : this.singletonConstructors.keySet()
			) {
				checkSingleton = singletonType;
				this.getOrInstantiateClass(singletonType, null, true);
			}

      for (
				final Object existingSingleton : this.existingSingletonsToCallListenersOn
			) {
				checkExistingSingleton = existingSingleton;
				this.callInstantiationListeners(existingSingleton);
			}

      for (
				final Tuple<Object, @Nullable ConstructorInfo> data : this.singletonInstances
			) {
        final Object instance = data.a;
        if (instance instanceof IInitializable)
          ((IInitializable) instance).initialize();
      }

      if (
				success != null
			) success.accept(this);
      return this;
    } catch (
			final Exception exception
		) {
      if (this.exceptionHandler != null) {
        this.exceptionHandler.accept(exception);
        return this;
      }

      this.logger.log(
				Level.SEVERE,
				"Exception occurred in checkSingleton: " + checkSingleton + "or checkExistingSingleton: " + checkExistingSingleton, exception);
      return this;
    }
  }

  public void cleanup() {
    this.executeAndCollectExceptions(executor -> {
      for (
				int i = this.singletonInstances.size() - 1; i >= 0; i--
			) {
        final Tuple<Object, @Nullable ConstructorInfo> data = this.singletonInstances.remove(i);
        final Object instance = data.a;

        if (
					instance instanceof ICleanable iCleanable
				) executor.accept(() -> ((ICleanable) instance).cleanup());

        final ConstructorInfo constructorInfo = data.b;

        if (
					constructorInfo == null ||
					constructorInfo.externalCleanup == null
				) continue;

        executor.accept(() -> constructorInfo.externalCleanup.accept(instance));
      }

      this.singletonConstructors.clear();
    });
  }

  @Override
  public int getInstancesCount() {
    return this.singletonInstances.size();
  }

  /**
   * Provides a consumer for unsafe runnables, which are immediately executed within
   * a try-catch block. Thrown exceptions are collected and thrown at the end.
   */
  private void executeAndCollectExceptions(
		final Consumer<Consumer<FUnsafeRunnable<Exception>>> executor
	) {
    final List<Exception> thrownExceptions = new ArrayList<>();
    executor.accept(task -> {
      try {
        task.run();
      } catch (
				final Exception exception
			) {
        thrownExceptions.add(exception);
      }
    });

    if (
			thrownExceptions.isEmpty()
		) return;

    // Throw the first thrown exception and add the remaining as suppressed
    final Exception exception = new Exception(thrownExceptions.get(0));

    for (
			int i = 1; i < thrownExceptions.size(); i++
		) exception.addSuppressed(thrownExceptions.get(i));

    this.logger.log(Level.SEVERE, "Exception: ", exception);
  }

  private @Nullable ConstructorInfo findConstructorInfo(
		final Class<?> clazz
	) {
    ConstructorInfo result = null;

		for (
			final Map.Entry<Class<?>, ConstructorInfo> entry : singletonConstructors.entrySet()
		) {
      if (
				clazz.isAssignableFrom(entry.getKey())
			) {
				result = entry.getValue();
			}
    }

    return result;
  }

  @Override
  public <T> @NotNull Optional<T> findInstance(
		final Class<T> clazz
	) {
    Object result = null;

    for (
			final Tuple<Object, @Nullable ConstructorInfo> existing : this.singletonInstances
		) {
      if (
				clazz.isInstance(existing.a)
			) {
				if (
					result != null
				) {
					this.logger.severe(
						"Found multiple possible instances of clazz " + clazz + " (" + existing.a.getClass() + ", " + result.getClass() + ")"
					);
					return Optional.empty();
				};
        result = existing.a;
      }
    }

		if (
			result == null
		)
			return Optional.empty();

    return Optional.of((T) result);
  }

  private void callInstantiationListeners(
		final @NotNull Object instance
	) {
    for (
			final InstantiationListener listener : this.instantiationListeners
		) {
      if (
				! listener.type.isInstance(instance)
			) continue;

      final Object[] args = new Object[listener.dependencies.length];
      for (
				int i = 0; i < args.length; i++
			) args[i] = this.getOrInstantiateClass(
				listener.dependencies[i],
				null,
				true
			);

			try {
				listener.listener.accept(
					instance,
					args
				);
			} catch (
				final Exception exception
			) {
				this.logger.log(
					Level.SEVERE,
					"Exception in listener of type " + listener.type + ": ", exception
				);
			}
    }
  }

  private @Nullable Object getOrInstantiateClass(
		final @NotNull Class<?> clazz,
		final @Nullable Class<?> parentClazz,
		final Boolean singleton
	) {
    if (
			singleton
		) {
      final Optional<?> existing = this.findInstance(clazz);
      if (existing.isPresent())
        return existing;
    }

    if (
			! this.encounteredClasses.add(clazz)
		) {
			this.logger.severe(
				"Circular dependency detected: " + clazz + " of parent class " + parentClazz
			);
			return null;
		}

    final ConstructorInfo constructorInfo = this.findConstructorInfo(clazz);
    if (
			constructorInfo == null
		) {
			this.logger.severe(
				"Unknown dependency of " + clazz + "."
			);
			return null;
		}

    final Object[] argumentValues = new Object[constructorInfo.parameters.length];
    for (
			int i = 0; i < argumentValues.length; i++
		) {
			argumentValues[i] = this.getOrInstantiateClass(
				constructorInfo.parameters[i],
				parentClazz,
				true
			);

			if (argumentValues[i] instanceof Optional<?> optionalObject)
				argumentValues[i] = optionalObject.orElse(null);
		}

		Object instance = null;
    try {
			instance = constructorInfo.constructor.apply(argumentValues);
		} catch (
			final Exception exception
		) {
			this.logger.log(
				Level.SEVERE,
				"Exception in getOrInstantiateClass " + constructorInfo + ": ", exception
			);
		}
		
		if (instance == null)
			return null;
		
    this.callInstantiationListeners(instance);

    if (singleton)
      this.singletonInstances.add(
				new Tuple<>(instance, constructorInfo)
			);
		
    return instance;
  }

  @Override
  public <T> T getOrInstantiateClass(
		final Class<T> clazz,
		final boolean singleton
	) {
    this.registerConstructor(clazz);
    return (T) this.getOrInstantiateClass(clazz, null, singleton);
  }

  private void registerConstructor(
		final @NotNull Class<?> clazz
	) {
    final Constructor<?>[] constructors = clazz.getConstructors();
    if (constructors.length != 1) {
			this.logger.severe(
				"Auto-wired class: " + clazz + " needs to have exactly one public constructor"
			);
			return;
		}

    final Constructor<?> constructor = constructors[0];
		this.singletonConstructors.put(
			clazz,
			new ConstructorInfo(
				constructor.getParameterTypes(),
				constructor::newInstance,
				null
			)
		);
  }
}