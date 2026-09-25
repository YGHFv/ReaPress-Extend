package io.github.YGHFv.ReaPressExtend.xposed;

import io.github.YGHFv.ReaPressExtend.xposed.callbacks.XCallback;
import java.lang.reflect.Executable;
import java.util.HashMap;
import java.util.Map;

/**
 * 旧式 Xposed 的 before/after 回调形状。
 *
 * 保留它的唯一理由是：现有 hook 代码都按这个形状写，而 libxposed API 102 用的是拦截器链。
 * [XposedBridge] 里做了一层适配，把链式 API 包装成这个形状，于是 hook 代码不必改写法。
 */
public class XC_MethodHook {
    public final int priority;

    public XC_MethodHook() {
        this(XCallback.PRIORITY_DEFAULT);
    }

    public XC_MethodHook(int priority) {
        this.priority = priority;
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public final void callBeforeHookedMethod(MethodHookParam param) throws Throwable {
        beforeHookedMethod(param);
    }

    public final void callAfterHookedMethod(MethodHookParam param) throws Throwable {
        afterHookedMethod(param);
    }

    public static class MethodHookParam {
        public Executable method;
        public Object thisObject;
        public Object[] args;

        private Object result;
        private Throwable throwable;
        private boolean returnEarly;
        private final Map<String, Object> extras = new HashMap<>();

        public MethodHookParam(Executable method, Object thisObject, Object[] args) {
            this.method = method;
            this.thisObject = thisObject;
            this.args = args;
        }

        public Object getResult() {
            return result;
        }

        /** 调用即代表「不再走原方法」，并把返回值定为 [result]。 */
        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.returnEarly = true;
        }

        public Object getObjectExtra(String key) {
            return extras.get(key);
        }

        public void setObjectExtra(String key, Object value) {
            extras.put(key, value);
        }

        public boolean isReturnEarly() {
            return returnEarly;
        }

        /** 由适配层回填原方法真正的返回值；**不会**置 returnEarly。 */
        public void setResultFromOriginal(Object result) {
            this.result = result;
            this.throwable = null;
        }

        /** 由适配层回填原方法抛出的异常；**不会**置 returnEarly。 */
        public void setThrowableFromOriginal(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
        }
    }
}
