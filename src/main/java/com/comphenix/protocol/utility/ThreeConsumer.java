package com.comphenix.protocol.utility;

@FunctionalInterface
public interface ThreeConsumer<F, S, T> {

    void accept(F f, S s, T t);

}
