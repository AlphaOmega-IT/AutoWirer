package me.blvckbytes.autowirer;

public interface IAutoWirer {

  <T> T getOrInstantiateClass(Class<T> type, boolean singleton) throws Exception;

  int getInstancesCount();

}
