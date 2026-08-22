# The ADB renderer is launched by class name through app_process, not by Android or app code.
-keep public class com.hilight.core.AdbHelper {
    public static void main(java.lang.String[]);
}

# Shizuku constructs the user service outside this app's process.
-keep public class com.hilight.studio.HiLightUserService {
    public <init>();
    public *;
}
