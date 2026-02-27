package com.camerainterceptor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.camerainterceptor.state.HookState;
import com.camerainterceptor.state.HookState.InjectionMode;

import org.junit.Before;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * Unit test to verify the logic for per-app mode resolution.
 * Since XSharedPreferences is hard to mock, we test the logic via HookState
 * integration.
 */
public class ModeResolutionTest {

    @Before
    public void setUp() {
        HookState.reset();
    }

    @Test
    public void testModeReset() {
        HookState.setInjectionMode(InjectionMode.DEEP_SURFACE);
        HookState.reset();
        assertEquals(InjectionMode.SAFE, HookState.getInjectionMode());
        assertFalse(HookState.hasValidResolution());
    }

    @Test
    public void testResolutionStorage() {
        HookState.setTargetResolution(1920, 1080);
        assertEquals(1920, HookState.getTargetWidth());
        assertEquals(1080, HookState.getTargetHeight());
        assertTrue(HookState.hasValidResolution());
    }

    @Test
    public void testModeLogicSimulation() {
        // Simulating the logic in HookDispatcher.loadInjectionConfiguration()
        String packageName = "com.test.app";
        Set<String> deepApps = new HashSet<>();
        Set<String> safeApps = new HashSet<>();

        deepApps.add(packageName);

        // Logic: if in deepApps -> DEEP
        boolean isDeep = deepApps.contains(packageName);
        HookState.setInjectionMode(isDeep ? InjectionMode.DEEP_SURFACE : InjectionMode.SAFE);
        assertEquals(InjectionMode.DEEP_SURFACE, HookState.getInjectionMode());

        // Logic: if not in deepApps, check safeApps (implied)
        deepApps.clear();
        safeApps.add(packageName);
        isDeep = deepApps.contains(packageName);
        HookState.setInjectionMode(isDeep ? InjectionMode.DEEP_SURFACE : InjectionMode.SAFE);
        assertEquals(InjectionMode.SAFE, HookState.getInjectionMode());
    }
}
