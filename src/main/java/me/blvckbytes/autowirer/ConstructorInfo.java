package me.blvckbytes.autowirer;

import lombok.AllArgsConstructor;
import me.blvckbytes.utilitytypes.FUnsafeConsumer;
import me.blvckbytes.utilitytypes.FUnsafeFunction;
import org.jetbrains.annotations.Nullable;

@AllArgsConstructor
public class ConstructorInfo {

  public final Class<?>[] parameters;
  public final FUnsafeFunction<Object[], ?, Exception> constructor;
  public final @Nullable FUnsafeConsumer<Object, Exception> externalCleanup;

}
