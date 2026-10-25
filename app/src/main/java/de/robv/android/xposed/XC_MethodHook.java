package de.robv.android.xposed;

/** 运行时由框架提供；此处仅为编译期桩。 */
public abstract class XC_MethodHook {

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static class MethodHookParam {
        public Object[] args;
        public Object thisObject;
        private Object result;
        private Throwable throwable;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
        }

        public boolean isMethodReturned() {
            return true;
        }
    }

    /** hookAllMethods / hookMethod 的返回值。 */
    public static class Unhook {
        public void unhook() {
        }
    }
}
