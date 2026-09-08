package com.transit.service;
import java.util.function.Consumer;
public final class GatewaySyncProgress {
 private static final ThreadLocal<Consumer<String>> listener=new ThreadLocal<>();
 private GatewaySyncProgress(){}
 public static void bind(Consumer<String> callback){listener.set(callback);}
 public static void clear(){listener.remove();}
 public static void phase(String phase){var callback=listener.get();if(callback!=null)callback.accept(phase);}
}
