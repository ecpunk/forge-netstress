package com.turdcraft.netstress;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Static registry of all known {@link Scenario}s. This is the one place a
 * new scenario gets added — {@link ServerTickHandler} and
 * {@link NetStressCommand} only ever look scenarios up by name here.
 */
public final class Scenarios {

    private static final Map<String, Scenario> REGISTRY = new LinkedHashMap<>();

    static {
        register(new Scenario(
                "baseline_4k_20hz",
                4096,
                1,
                "SimpleChannel S2C, consume-normally handler, one 4096-byte packet per player per "
                        + "server tick (~20/s/player) -- the Forge #10861 reproducer."));
    }

    private Scenarios() {
    }

    private static void register(Scenario scenario) {
        REGISTRY.put(scenario.name(), scenario);
    }

    public static Scenario byName(String name) {
        return REGISTRY.get(name);
    }

    public static Set<String> names() {
        return Collections.unmodifiableSet(REGISTRY.keySet());
    }
}
