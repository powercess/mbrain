package com.powercess.mbrain.control;

import android.content.ClipData;
import android.os.IBinder;
import java.lang.reflect.Method;
import org.json.JSONObject;

/** Keeps the original ClipData exclusively in device RAM. Never serializes or prints it. */
public final class ClipboardDeviceSmoke {
    public static void run() throws Exception {
        if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper();
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "clipboard");
        Object service = Class.forName("android.content.IClipboard$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        int user = (Integer) Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null);
        Method getter = method("getPrimaryClip"), setter = method("setPrimaryClip"), clear = method("clearPrimaryClip");
        ClipData original = (ClipData) getter.invoke(service, arguments(getter, user, null));
        // Invoke production code locally: only pass/fail ever crosses ADB.
        Method operation = PhoneControlMain.class.getDeclaredMethod("clipboard", String.class, JSONObject.class);
        operation.setAccessible(true);
        PhoneControlMain helper = new PhoneControlMain();
        String expected = "MBrain 中文😀 clipboard QA";
        try {
            operation.invoke(helper, "clipboard_set", new JSONObject().put("value", expected));
            JSONObject reply = (JSONObject) operation.invoke(helper, "clipboard_get", new JSONObject());
            if (!expected.equals(reply.optString("text"))) throw new IllegalStateException("Clipboard roundtrip mismatch");
            System.out.println("PASS clipboard Unicode roundtrip (device-local)");
        } finally {
            if (original != null) setter.invoke(service, arguments(setter, user, original));
            else clear.invoke(service, arguments(clear, user, null));
        }
    }
    private static Method method(String name) throws Exception {
        for (Method method : Class.forName("android.content.IClipboard").getMethods()) if (method.getName().equals(name)) return method;
        throw new NoSuchMethodException(name);
    }
    private static Object[] arguments(Method method, int user, ClipData clip) {
        Object[] result = new Object[method.getParameterCount()];
        int strings = 0, ints = 0;
        for (int i = 0; i < result.length; i++) {
            Class<?> type = method.getParameterTypes()[i];
            if (type == String.class) result[i] = strings++ == 0 ? "com.android.shell" : null;
            else if (type == int.class) result[i] = ints++ == 0 ? user : 0;
            else if (type == ClipData.class) result[i] = clip;
            else throw new IllegalStateException("Unknown clipboard signature");
        }
        return result;
    }
}
