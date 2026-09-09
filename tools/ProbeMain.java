import android.os.IBinder;

import java.lang.reflect.Method;

/**
 * Phase 0 verification, run under app_process as the shell UID (2000).
 *
 * shell holds MODIFY_PHONE_STATE on this device, which is the same privilege Shizuku hands the
 * app. So if setDefaultDataSubId works here, it will work from SimSwitch via ShizukuBinderWrapper.
 * This lets the mechanism be proven without installing anything or touching the phone's screen.
 *
 * Everything is reflective and resolved BY NAME. Calling `service call isub <n>` would mean
 * guessing a transaction code that differs per build — on a live phone that could just as easily
 * hit setDefaultVoiceSubId or setSubscriptionEnabled. Name resolution cannot mis-fire.
 *
 *   no args      -> read-only: report current DDS and list the available methods
 *   <subId>      -> actually move the default data subscription
 */
public class ProbeMain {

    public static void main(String[] args) {
        try {
            exemptHiddenApis();

            Object iSub = iSub();
            if (iSub == null) return;

            System.out.println("DDS before : " + getDefaultDataSubId(iSub));

            Method setter = null;
            System.out.println("methods matching *DataSub*:");
            for (Method m : iSub.getClass().getMethods()) {
                if (m.getName().toLowerCase().contains("datasub")) {
                    System.out.println("  " + sig(m));
                    if (m.getName().equals("setDefaultDataSubId") && setter == null) setter = m;
                }
            }

            if (args.length == 0) {
                System.out.println("RESULT: dry run only, nothing changed.");
                System.out.println(setter != null
                        ? "RESULT: setDefaultDataSubId IS present -> mechanism A looks viable."
                        : "RESULT: setDefaultDataSubId NOT present -> mechanism A is dead on this build.");
                return;
            }

            if (setter == null) {
                System.out.println("RESULT: FAIL - no setDefaultDataSubId to call.");
                return;
            }

            int target = Integer.parseInt(args[0]);

            // Signatures drift across builds; fill parameters by type rather than assuming arity.
            Class<?>[] types = setter.getParameterTypes();
            Object[] callArgs = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                if (types[i] == int.class) callArgs[i] = target;
                else if (types[i] == String.class) callArgs[i] = "com.android.shell";
                else if (types[i] == boolean.class) callArgs[i] = Boolean.TRUE;
                else callArgs[i] = null;
            }

            System.out.println("calling " + sig(setter) + " with target=" + target);
            setter.invoke(iSub, callArgs);
            System.out.println("call returned without throwing");

            // The switch is asynchronous - Samsung sets multi_sim_dds_progressing while it runs.
            for (int i = 0; i < 10; i++) {
                Thread.sleep(1000);
                int now = getDefaultDataSubId(iSub);
                if (now == target) {
                    System.out.println("DDS after  : " + now);
                    System.out.println("RESULT: PASS - data subscription moved to " + target);
                    return;
                }
            }
            System.out.println("DDS after  : " + getDefaultDataSubId(iSub));
            System.out.println("RESULT: FAIL - call succeeded but DDS did not move within 10s");

        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            System.out.println("RESULT: FAIL - " + cause);
        }
    }

    private static Object iSub() throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) serviceManager
                .getMethod("getService", String.class).invoke(null, "isub");
        if (binder == null) {
            System.out.println("RESULT: FAIL - ServiceManager.getService(\"isub\") returned null");
            return null;
        }
        Class<?> stub = Class.forName("com.android.internal.telephony.ISub$Stub");
        return stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    private static int getDefaultDataSubId(Object iSub) {
        try {
            return (Integer) iSub.getClass().getMethod("getDefaultDataSubId").invoke(iSub);
        } catch (Throwable t) {
            return -999;
        }
    }

    /** app_process is usually exempt from the hidden-API blocklist, but don't rely on it. */
    private static void exemptHiddenApis() {
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Object runtime = vmRuntime.getMethod("getRuntime").invoke(null);
            vmRuntime.getMethod("setHiddenApiExemptions", String[].class)
                    .invoke(runtime, (Object) new String[]{""});
        } catch (Throwable ignored) {
            // Older/newer platforms may not expose this; the call below will tell us either way.
        }
    }

    private static String sig(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append('(');
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(p[i].getSimpleName());
        }
        return sb.append(')').toString();
    }
}
