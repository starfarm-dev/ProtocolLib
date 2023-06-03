package com.comphenix.protocol.utility;

import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import lombok.val;
import sun.misc.Unsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

@UtilityClass
@SuppressWarnings("all")
public class JavaInternals {

    public Unsafe UNSAFE;
    public MethodHandles.Lookup LOOKUP;

    private MethodHandle ADD_OPENS_MH;

    static {
        try {
            val unsafe = Unsafe.class.getDeclaredField("theUnsafe");
            unsafe.setAccessible(true);
            UNSAFE = (Unsafe) unsafe.get(null);
        } catch (Throwable t) {
            throw new RuntimeException("Unsafe not supported on this VM");
        }

        try {
            val lookup = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");

            LOOKUP = (MethodHandles.Lookup) UNSAFE.getObject(
                    UNSAFE.staticFieldBase(lookup),
                    UNSAFE.staticFieldOffset(lookup)
            );
        } catch (Throwable t) {
            throw new RuntimeException("MethodHandles not supported on this VM");
        }

        try {
            val moduleClass = Class.forName("java.lang.Module");
            val moduleField = Class.class.getDeclaredField("module");

            val module = UNSAFE.getObject(
                    Object.class,
                    UNSAFE.objectFieldOffset(moduleField)
            );

            ADD_OPENS_MH = LOOKUP.findVirtual(
                    moduleClass, "implAddOpens",
                    MethodType.methodType(void.class, String.class)
            ).bindTo(module);

            addOpens("java.lang.invoke", "jdk.internal.misc");
        } catch (Throwable t) {

        }
    }

    public void init() {

    }

    @SneakyThrows
    public <T> T allocateInstance(Class<T> type) {
        return (T) UNSAFE.allocateInstance(type);
    }

    @SneakyThrows
    public static Class<?> getCallerClass() {
        return Class.forName(Thread.currentThread().getStackTrace()[2].getClassName());
    }

    public void addOpens(String... modules) {
        if (ADD_OPENS_MH != null) {
            for (String module : modules) {
                try {
                    ADD_OPENS_MH.invoke(module);
                } catch (Throwable t) {
                    throw new RuntimeException("Error while adding open to base module", t);
                }
            }
        }
    }

}
