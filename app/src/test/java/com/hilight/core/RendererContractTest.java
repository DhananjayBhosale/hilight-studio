package com.hilight.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class RendererContractTest {

    @Test
    public void compositeServiceVersionCannotReuseReleasedV108Daemon() {
        int serviceVersion = RendererContract.shizukuServiceVersion(10);

        assertEquals(1004, serviceVersion);
        assertNotEquals(9, serviceVersion);
    }

    @Test
    public void currentIdentityIsCompatible() {
        assertTrue(RendererContract.isCompatible(
                RendererContract.CONTRACT_VERSION,
                RendererContract.IMPLEMENTATION_REVISION,
                RendererContract.STATUS_SCHEMA_VERSION,
                RendererContract.CLEAR_ALGORITHM_VERSION
        ));
    }

    @Test
    public void missingOrStaleIdentityIsNotCompatible() {
        assertFalse(RendererContract.isCompatible(-1, -1, -1, -1));
        assertFalse(RendererContract.isCompatible(
                RendererContract.CONTRACT_VERSION,
                RendererContract.IMPLEMENTATION_REVISION - 1,
                RendererContract.STATUS_SCHEMA_VERSION,
                RendererContract.CLEAR_ALGORITHM_VERSION
        ));
    }

    @Test(expected = IllegalArgumentException.class)
    public void serviceVersionRejectsInvalidAppCode() {
        RendererContract.shizukuServiceVersion(0);
    }
}
