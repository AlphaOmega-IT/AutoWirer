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

	/**
	 * Adds an instantiation listener for a specific class with a creation listener and dependencies.
	 *
	 * @param clazz The class for which the instantiation listener is added
	 * @param creationListener The listener function for creating instances of the class
	 * @param dependencies The classes on which the listener function depends
	 * @param <T> The type of the class
	 * @return The AutoWirer instance with the instantiation listener added
	 */
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

	/**
	 * Adds a singleton class to the `AutoWirer` for dependency injection.
	 *
	 * @param clazz The class to be added as a singleton
	 * @return The `AutoWirer` instance with the singleton class added
	 */
	public synchronized AutoWirer addSingleton(
			final @NotNull Class<?> clazz
	) {
		this.registerConstructor(clazz);
		return this;
	}

	/**
	 * Adds a singleton instance with a generator function and cleanup operation to the `AutoWirer` for dependency injection.
	 *
	 * @param clazz The class for which to add a singleton instance
	 * @param generator The function that generates an instance of the class
	 * @param onCleanup The cleanup operation to be performed on the instance
	 * @param dependencies The classes on which the singleton instance depends
	 * @param <T> The type of the class
	 * @return The `AutoWirer` instance with the singleton instance added
	 */
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

	/**
	 * Adds an existing singleton instance to the `AutoWirer` for dependency injection.
	 *
	 * @param value The existing singleton instance to add
	 * @return The `AutoWirer` instance with the existing singleton added
	 */
	public AutoWirer addExistingSingleton(
			final @NotNull Object value
	) {
		return this.addExistingSingleton(value, false);
	}

	/**
	 * Adds an existing singleton instance to the `AutoWirer` for dependency injection.
	 *
	 * @param value The existing singleton instance to add
	 * @param callInstantiationListeners Flag indicating whether to call instantiation listeners on the added instance
	 * @return The `AutoWirer` instance with the existing singleton added
	 */
    public synchronized AutoWirer addExistingSingleton(final @NotNull Object value, final boolean callInstantiationListeners) {
		this.singletonInstances.add(new Tuple<>(value, null));

        if (callInstantiationListeners) {
            this.existingSingletonsToCallListenersOn.add(value);
        }

        return this;
    }

	/**
	 * Sets the exception handler for the AutoWirer instance.
	 *
	 * @param handler The consumer function to handle exceptions
	 * @return The `AutoWirer` instance with the exception handler set
	 */
	public AutoWirer onException(
			final @NotNull Consumer<Exception> handler
	) {
		this.exceptionHandler = handler;
		return this;
	}

	/**
	 * Wires the dependencies by instantiating singleton classes, calling instantiation listeners,
	 * and initializing instances implementing the `IInitializable` interface.
	 *
	 * @param success Consumer function to be executed upon successful wiring
	 * @return The `AutoWirer` instance
	 */
    public AutoWirer wire(final @Nullable Consumer<AutoWirer> success) {
        try {
			this.singletonConstructors.keySet().parallelStream().forEach(singletonType -> this.getOrInstantiateClass(singletonType, null, true));
			this.existingSingletonsToCallListenersOn.parallelStream().forEach(this::callInstantiationListeners);

			this.singletonInstances.parallelStream().forEach(data -> {
				Object instance = data.a;
				if (instance instanceof IInitializable) {
					((IInitializable) instance).initialize();
				}
			});

            if (success != null) {
                success.accept(this);
            }
        } catch (Exception exception) {
            if (this.exceptionHandler != null) {
                this.exceptionHandler.accept(exception);
            } else {
                this.logger.log(Level.SEVERE, "Exception occurred in checkSingleton or checkExistingSingleton: ", exception);
            }
        }
        return this;
    }

	/**
	 * Executes cleanup operations for all singleton instances, calling their 'cleanup' methods if they implement the 'ICleanable' interface.
	 * Additionally, performs external cleanup if specified in the 'ConstructorInfo' associated with each instance.
	 */
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
    final Exception exception = new Exception(thrownExceptions.getFirst());

    for (
			int i = 1; i < thrownExceptions.size(); i++
	) exception.addSuppressed(thrownExceptions.get(i));

    this.logger.log(Level.SEVERE, "Exception: ", exception);
  }

	/**
	 * Finds the ConstructorInfo for a given class by checking if the class is assignable from the keys in singletonConstructors.
	 *
	 * @param clazz The class for which to find the ConstructorInfo
	 * @return An Optional containing the ConstructorInfo if found, otherwise empty
	 */
	private Optional<ConstructorInfo> findConstructorInfo(
		final @NotNull Class<?> clazz
	) {
		return singletonConstructors.values().stream()
			.filter(info -> clazz.isAssignableFrom(info.getClass()))
			.findFirst();
	}


	/**
	 * Finds an instance of the specified class if it exists within the singleton instances.
	 * If multiple instances are found, logs a severe message and returns an empty Optional.
	 * If no instance is found, returns an empty Optional.
	 *
	 * @param clazz The class to find an instance of
	 * @param <T>   The type of the class
	 * @return An Optional containing the found instance if present, otherwise an empty Optional
	 */
    @Override
    public <T> @NotNull Optional<T> findInstance(Class<T> clazz) {
		Object result = null;
        for (Tuple<Object, ConstructorInfo> existing : this.singletonInstances) {
            if (clazz.isInstance(existing.a)) {
                if (result != null) {
                    logger.severe("Found multiple possible instances of clazz " + clazz + " (" + existing.a.getClass() + ", " + result.getClass() + ")");
                    return Optional.empty();
                }
                result = existing.a;
            }
        }

        return Optional.ofNullable(clazz.cast(result));
    }

	/**
	 * Calls the instantiation listeners for the given instance.
	 * <p>
	 * Iterates through the list of instantiation listeners and executes the listener's action
	 * if the listener's type is an instance of the provided instance.
	 *
	 * @param instance the object instance for which the listeners are called
	 */
    private void callInstantiationListeners(final @NotNull Object instance) {
        for (final InstantiationListener listener : this.instantiationListeners) {
            if (!listener.type.isInstance(instance)) {
                continue;
            }

            Object[] args = new Object[listener.dependencies.length];
            for (int i = 0; i < args.length; i++) {
                args[i] = this.getOrInstantiateClass(listener.dependencies[i], null, true);
            }

            try {
                listener.listener.accept(instance, args);
            } catch (Exception exception) {
                this.logger.log(Level.SEVERE, "Exception in listener of type " + listener.type + ": ", exception);
            }
        }
    }

	/**
	 * Retrieves an existing instance or instantiates a new instance of the specified class.
	 * If the class is set as a singleton, it checks for an existing instance first.
	 * Handles dependencies, circular dependencies, and instantiation listeners.
	 *
	 * @param clazz The class to retrieve or instantiate
	 * @param parentClazz The parent class (if any) that triggered the instantiation
	 * @param singleton Flag indicating if the class should be treated as a singleton
	 * @return The retrieved or newly instantiated object, or null if instantiation fails
	 */
    private @Nullable Object getOrInstantiateClass(
            final @NotNull Class<?> clazz,
            final @Nullable Class<?> parentClazz,
            final boolean singleton
    ) {
        if (singleton) {
            Optional<?> existing = this.findInstance(clazz);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        Optional<ConstructorInfo> constructorInfo = this.findConstructorInfo(clazz);
        if (constructorInfo.isEmpty()) {
            this.logger.severe(
                    "Unknown dependency of " + clazz + "."
            );
            return null;
        }

		if (!this.encounteredClasses.add(clazz)) {
			this.logger.severe(
					"Circular dependency detected: " + clazz + " of parent class " + parentClazz
			);
			return null;
		}

        Object[] argumentValues = new Object[constructorInfo.get().parameters.length];
        for (int i = 0; i < argumentValues.length; i++) {
            argumentValues[i] = this.getOrInstantiateClass(
                    constructorInfo.get().parameters[i],
                    parentClazz,
                    true
            );

            if (argumentValues[i] instanceof Optional) {
                argumentValues[i] = ((Optional<?>) argumentValues[i]).orElse(null);
            }
        }

        Object instance = null;
        try {
            instance = constructorInfo.get().constructor.apply(argumentValues);
        } catch (Exception exception) {
            this.logger.log(
                    Level.SEVERE,
                    "Exception in getOrInstantiateClass " + constructorInfo + ": " + clazz, exception
            );
        } finally {
			this.encounteredClasses.remove(clazz);
		}

        if (instance == null) {
            return null;
        }

        this.callInstantiationListeners(instance);

        if (singleton) {
            this.singletonInstances.add(
                    new Tuple<>(instance, constructorInfo.get())
            );
        }

        return instance;
    }

	/**
	 * Retrieves an instance of a class or instantiates it if necessary.
	 *
	 * @param clazz The class to retrieve an instance of or instantiate.
	 * @param singleton Flag indicating if the instance should be a singleton.
	 * @param <T> The type of the class.
	 * @return An instance of the specified class.
	 */
	@Override
    public <T> T getOrInstantiateClass(final Class<T> clazz, final boolean singleton) {
        registerConstructor(clazz);
        return clazz.cast(getOrInstantiateClass(clazz, null, singleton));
    }

	/**
	 * Registers a constructor for a given class to be used for autowiring.
	 *
	 * @param clazz The class for which the constructor will be registered.
	 */
    private void registerConstructor(final @NotNull Class<?> clazz) {
        Constructor<?>[] constructors = clazz.getDeclaredConstructors();
        if (constructors.length != 1) {
            logger.severe("Auto-wired class: " + clazz + " needs to have exactly one public constructor");
            return;
        }

        Constructor<?> constructor = constructors[0];
        singletonConstructors.computeIfAbsent(clazz, key -> new ConstructorInfo(
                constructor.getParameterTypes(),
                constructor::newInstance,
                null
        ));
    }
}