package com.hilight.core;

/**
 * Stable identity shared by every privileged renderer host.
 *
 * <p>The APK version alone is not sufficient: Shizuku can keep a daemon alive while an APK with
 * the same version code is replaced. Increment {@link #IMPLEMENTATION_REVISION} whenever code that
 * runs in the privileged renderer changes. ADB and root expose these constants through status;
 * Shizuku also folds the implementation revision into its user-service version so stale code is
 * destroyed before the app can replay state.</p>
 */
public final class RendererContract {

    /** Shape and meaning of the renderer identity fields. */
    public static final int CONTRACT_VERSION = 1;

    /** Monotonic revision of code loaded into the privileged renderer process. */
    public static final int IMPLEMENTATION_REVISION = 4;

    /** Version of the JSON status document emitted by {@link Engine#status()}. */
    public static final int STATUS_SCHEMA_VERSION = 6;

    /** Identifies the bounded post-release black-clear strategy under test in v1.0.9. */
    public static final int CLEAR_ALGORITHM_VERSION = ForcedBlackClear.STRATEGY_VERSION;

    private static final int SERVICE_VERSION_BASE = 100;

    private RendererContract() {}

    /**
     * Version passed to Shizuku for the daemon lifecycle.
     *
     * <p>Revision values occupy the final two decimal digits. Thus APK code 10 and renderer
     * revision 4 request service version 1004, which cannot reuse v1.0.8's service version 9 or any
     * pre-final v1.0.9 candidate exercised during device regression testing.</p>
     */
    public static int shizukuServiceVersion(int appVersionCode) {
        if (appVersionCode <= 0) {
            throw new IllegalArgumentException("appVersionCode must be positive");
        }
        if (IMPLEMENTATION_REVISION <= 0 || IMPLEMENTATION_REVISION >= SERVICE_VERSION_BASE) {
            throw new IllegalStateException("renderer revision must be between 1 and 99");
        }
        return Math.addExact(
                Math.multiplyExact(appVersionCode, SERVICE_VERSION_BASE),
                IMPLEMENTATION_REVISION
        );
    }

    /** Exact match required before a host may treat a renderer as current. */
    public static boolean isCompatible(
            int contractVersion,
            int implementationRevision,
            int statusSchemaVersion,
            int clearAlgorithmVersion
    ) {
        return contractVersion == CONTRACT_VERSION
                && implementationRevision == IMPLEMENTATION_REVISION
                && statusSchemaVersion == STATUS_SCHEMA_VERSION
                && clearAlgorithmVersion == CLEAR_ALGORITHM_VERSION;
    }
}
