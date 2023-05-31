package com.comphenix.protocol.reflect.accessors;

import com.comphenix.protocol.utility.JavaInternals;
import com.comphenix.protocol.utility.ThreeConsumer;
import com.comphenix.protocol.wrappers.collection.BiFunction;
import lombok.Getter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

@Getter
public class UnsafeFieldAccessor implements FieldAccessor {

    public static final Map<Class<?>, BiFunction<Object, Long, Object>> GET_ACCESSORS =
            new HashMap<Class<?>, BiFunction<Object, Long, Object>>() {
                {
                    put(boolean.class, JavaInternals.UNSAFE::getBoolean);
                    put(byte.class, JavaInternals.UNSAFE::getByte);
                    put(char.class, JavaInternals.UNSAFE::getChar);
                    put(short.class, JavaInternals.UNSAFE::getShort);
                    put(int.class, JavaInternals.UNSAFE::getInt);
                    put(long.class, JavaInternals.UNSAFE::getLong);
                    put(float.class, JavaInternals.UNSAFE::getFloat);
                    put(double.class, JavaInternals.UNSAFE::getDouble);
                    put(Object.class, JavaInternals.UNSAFE::getObject);
                }
            };

    public static final Map<Class<?>, ThreeConsumer<Object, Long, Object>> SET_ACCESSORS =
            new HashMap<Class<?>, ThreeConsumer<Object, Long, Object>>() {

                @SuppressWarnings("unchecked")
                <T> void add(Class<T> clazz, ThreeConsumer<Object, Long, T> consumer) {
                    put(clazz, (ThreeConsumer<Object, Long, Object>) consumer);
                }

                {
                    add(boolean.class, JavaInternals.UNSAFE::putBoolean);
                    add(byte.class, JavaInternals.UNSAFE::putByte);
                    add(char.class, JavaInternals.UNSAFE::putChar);
                    add(short.class, JavaInternals.UNSAFE::putShort);
                    add(int.class, JavaInternals.UNSAFE::putInt);
                    add(long.class, JavaInternals.UNSAFE::putLong);
                    add(float.class, JavaInternals.UNSAFE::putFloat);
                    add(double.class, JavaInternals.UNSAFE::putDouble);
                    add(Object.class, JavaInternals.UNSAFE::putObject);
                }
            };


    private final Field field;
    private final boolean isStatic;

    private final long fieldOffset;
    private final Object staticFieldBase;

    private final BiFunction<Object, Long, Object> getter;
    private final ThreeConsumer<Object, Long, Object> setter;

    public UnsafeFieldAccessor(Field field) {
        this.field = field;
        isStatic = Modifier.isStatic(field.getModifiers());

        fieldOffset = isStatic()
                ? JavaInternals.UNSAFE.staticFieldOffset(field)
                : JavaInternals.UNSAFE.objectFieldOffset(field);

        staticFieldBase = isStatic()
                ? JavaInternals.UNSAFE.staticFieldBase(field)
                : null;

        Class<?> type = field.getType().isPrimitive() ? field.getType() : Object.class;

        getter = GET_ACCESSORS.get(type);
        setter = SET_ACCESSORS.get(type);
    }

    @Override
    public Object get(Object instance) {
        if (isStatic()) {
            instance = staticFieldBase;
        }

        if (instance == null)
            return null;

        return getter.apply(instance, fieldOffset);
//        return JavaInternals.UNSAFE.getObject(instance, fieldOffset)
    }

    @Override
    public void set(Object instance, Object value) {
        if (isStatic()) {
            instance = staticFieldBase;
        }

        if (instance != null) {
            setter.accept(instance, fieldOffset, value);
        }
//        JavaInternals.UNSAFE.putObject(instance, fieldOffset, value);
    }

    public boolean isStatic() {
        return isStatic;
    }

}
