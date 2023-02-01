package me.blvckbytes.autowirer;

public interface ICleanable {

  // Called before disabling in reverse order of wiring
  void cleanup();

}
