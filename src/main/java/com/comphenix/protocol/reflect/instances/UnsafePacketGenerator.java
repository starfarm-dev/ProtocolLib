package com.comphenix.protocol.reflect.instances;

import com.comphenix.protocol.utility.JavaInternals;
import com.comphenix.protocol.utility.MinecraftReflection;
import lombok.SneakyThrows;
import org.jetbrains.annotations.Nullable;

public class UnsafePacketGenerator implements InstanceProvider{

    public static final UnsafePacketGenerator INSTANCE = new UnsafePacketGenerator();

    @Override
    @SneakyThrows
    public Object create(@Nullable Class<?> type) {
        if (type == null || !MinecraftReflection.getPacketClass().isAssignableFrom(type))
            return null;

        try {
            return JavaInternals.UNSAFE.allocateInstance(type);
        } catch (Throwable throwable) {
            return null;
        }
    }

}
