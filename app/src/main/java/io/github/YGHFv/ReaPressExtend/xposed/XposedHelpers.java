package io.github.YGHFv.ReaPressExtend.xposed;

import io.github.YGHFv.ReaPressExtend.xposed.callbacks.XCallback;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 反射工具。
 *
 * 刻意**不依赖 `de.robv.android.xposed`**：API 102 明确规定 target 102 的模块不能再调旧式 API，
 * 而这些工具方法本身只是 `java.lang.reflect` 的便利封装，自己实现一份即可，没有兼容包袱。
 */
public final class XposedHelpers {
    private XposedHelpers() {
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) throws ClassNotFoundException {
        if (classLoader != null) {
            // false = 不触发类初始化。挂 hook 只需要 Class 对象，提前初始化目标类会改变宿主启动时序。
            return Class.forName(className, false, classLoader);
        }
        return Class.forName(className);
    }

    public static XposedInterface.HookHandle findAndHookMethod(
            String className,
            ClassLoader classLoader,
            String methodName,
            Object... parameterTypesAndCallback
    ) throws ClassNotFoundException {
        return findAndHookMethod(findClass(className, classLoader), methodName, parameterTypesAndCallback);
    }

    public static XposedInterface.HookHandle findAndHookMethod(
            Class<?> clazz,
            String methodName,
            Object... parameterTypesAndCallback
    ) {
        if (parameterTypesAndCallback.length == 0
                || !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("last argument must be XC_MethodHook");
        }
        XC_MethodHook callback = (XC_MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        Class<?>[] parameterTypes = new Class<?>[parameterTypesAndCallback.length - 1];
        for (int i = 0; i < parameterTypes.length; i++) {
            parameterTypes[i] = resolveParameterType(parameterTypesAndCallback[i], clazz.getClassLoader());
        }
        Method method = findMethodExact(clazz, methodName, parameterTypes);
        return XposedBridge.INSTANCE.hookMethod(method, callback);
    }

    public static XposedInterface.HookHandle findAndHookConstructor(
            Class<?> clazz,
            Object... parameterTypesAndCallback
    ) {
        if (parameterTypesAndCallback.length == 0
                || !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("last argument must be XC_MethodHook");
        }
        XC_MethodHook callback = (XC_MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        Class<?>[] parameterTypes = new Class<?>[parameterTypesAndCallback.length - 1];
        for (int i = 0; i < parameterTypes.length; i++) {
            parameterTypes[i] = resolveParameterType(parameterTypesAndCallback[i], clazz.getClassLoader());
        }
        try {
            return XposedBridge.INSTANCE.hookMethod(clazz.getDeclaredConstructor(parameterTypes), callback);
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(clazz.getName() + "#<init>");
        }
    }

    /** 挂一个类上**所有**同名方法（含不同参数个数/类型）。返回实际挂上的 handle 列表。 */
    public static List<XposedInterface.HookHandle> findAllAndHookMethod(
            Class<?> clazz,
            String methodName,
            XC_MethodHook callback
    ) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        for (Method method : allMethods(clazz)) {
            if (!method.getName().equals(methodName)) continue;
            XposedInterface.HookHandle handle = XposedBridge.INSTANCE.hookMethod(method, callback);
            if (handle != null) handles.add(handle);
        }
        return handles;
    }

    public static Object callMethod(Object target, String methodName, Object... args) throws Exception {
        if (target == null) throw new IllegalArgumentException("target must not be null");
        Class<?> clazz = target instanceof Class<?> ? (Class<?>) target : target.getClass();
        Method method = findMethodBestMatch(clazz, methodName, args);
        return method.invoke(target instanceof Class<?> ? null : target, args);
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) throws Exception {
        Method method = findMethodBestMatch(clazz, methodName, args);
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException(methodName + " is not static");
        }
        return method.invoke(null, args);
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldError(clazz.getName() + "#" + fieldName);
    }

    public static Object getObjectField(Object target, String fieldName) throws IllegalAccessException {
        return findField(target.getClass(), fieldName).get(target);
    }

    public static void setObjectField(Object target, String fieldName, Object value) throws IllegalAccessException {
        findField(target.getClass(), fieldName).set(target, value);
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) throws IllegalAccessException {
        return findField(clazz, fieldName).get(null);
    }

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) throws IllegalAccessException {
        findField(clazz, fieldName).set(null, value);
    }

    public static int getIntField(Object target, String fieldName) throws IllegalAccessException {
        return ((Number) findField(target.getClass(), fieldName).get(target)).intValue();
    }

    public static void setIntField(Object target, String fieldName, int value) throws IllegalAccessException {
        findField(target.getClass(), fieldName).setInt(target, value);
    }

    public static boolean getBooleanField(Object target, String fieldName) throws IllegalAccessException {
        return findField(target.getClass(), fieldName).getBoolean(target);
    }

    public static void setBooleanField(Object target, String fieldName, boolean value) throws IllegalAccessException {
        findField(target.getClass(), fieldName).setBoolean(target, value);
    }

    public static Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(methodName, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodError(clazz.getName() + "#" + methodName);
    }

    /**
     * 按实参形状挑最匹配的方法。
     *
     * 用于「知道方法名但不确定重载」的场景（例如宿主的实体类被混淆后参数表变了）。
     * 打分规则：类型完全相同 0 分，可赋值 1 分，实参为 null 也算 1 分；总分最低者胜出。
     */
    public static Method findMethodBestMatch(Class<?> clazz, String methodName, Object... args) {
        Method best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Method method : allMethods(clazz)) {
            if (!method.getName().equals(methodName) || method.getParameterTypes().length != args.length) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (!parametersMatch(parameterTypes, args)) {
                continue;
            }
            int score = distanceScore(parameterTypes, args);
            if (score < bestScore) {
                best = method;
                bestScore = score;
            }
        }
        if (best == null) {
            throw new NoSuchMethodError(clazz.getName() + "#" + methodName);
        }
        best.setAccessible(true);
        return best;
    }

    private static Class<?> resolveParameterType(Object value, ClassLoader classLoader) {
        if (value instanceof Class<?>) return (Class<?>) value;
        if (value instanceof String) {
            try {
                return findClass((String) value, classLoader);
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("class not found: " + value, e);
            }
        }
        throw new IllegalArgumentException("unsupported parameter type: " + value);
    }

    private static List<Method> allMethods(Class<?> clazz) {
        List<Method> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Class<?> current = clazz;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                String key = method.getName() + signature(method.getParameterTypes());
                if (seen.add(key)) {
                    result.add(method);
                }
            }
            current = current.getSuperclass();
        }
        return result;
    }

    private static boolean parametersMatch(Class<?>[] parameterTypes, Object[] args) {
        for (int i = 0; i < parameterTypes.length; i++) {
            Object arg = args[i];
            if (arg == null) continue;
            if (!boxed(parameterTypes[i]).isAssignableFrom(boxed(arg.getClass()))) {
                return false;
            }
        }
        return true;
    }

    private static int distanceScore(Class<?>[] parameterTypes, Object[] args) {
        int score = 0;
        for (int i = 0; i < parameterTypes.length; i++) {
            Object arg = args[i];
            if (arg == null) {
                score += 1;
            } else if (!boxed(parameterTypes[i]).equals(boxed(arg.getClass()))) {
                score += 1;
            }
        }
        return score;
    }

    private static String signature(Class<?>[] parameterTypes) {
        StringBuilder builder = new StringBuilder("(");
        for (Class<?> type : parameterTypes) {
            builder.append(type.getName()).append(';');
        }
        return builder.append(')').toString();
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == Boolean.TYPE) return Boolean.class;
        if (type == Byte.TYPE) return Byte.class;
        if (type == Character.TYPE) return Character.class;
        if (type == Short.TYPE) return Short.class;
        if (type == Integer.TYPE) return Integer.class;
        if (type == Long.TYPE) return Long.class;
        if (type == Float.TYPE) return Float.class;
        if (type == Double.TYPE) return Double.class;
        if (type == Void.TYPE) return Void.class;
        return type;
    }

    /** 供调用方在挂 hook 前做存在性探测，避免用异常控制流程。 */
    public static boolean hasMethod(Class<?> clazz, String methodName) {
        for (Method method : allMethods(clazz)) {
            if (method.getName().equals(methodName)) return true;
        }
        return false;
    }

    /** 同上，但连参数个数一起限定。 */
    public static boolean hasMethod(Class<?> clazz, String methodName, int parameterCount) {
        for (Method method : allMethods(clazz)) {
            if (method.getName().equals(methodName) && method.getParameterTypes().length == parameterCount) {
                return true;
            }
        }
        return false;
    }

    /** 供调用方在挂构造 hook 前做存在性探测。 */
    public static boolean hasConstructor(Class<?> clazz, int parameterCount) {
        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            if (constructor.getParameterTypes().length == parameterCount) return true;
        }
        return false;
    }

    /** 当前类（含父类）声明的全部可执行成员，用于诊断输出。 */
    public static List<Executable> allExecutables(Class<?> clazz) {
        List<Executable> result = new ArrayList<>(allMethods(clazz));
        result.addAll(List.of(clazz.getDeclaredConstructors()));
        return result;
    }
}
