package com.dat3m.dartagnan.parsers.program.visitors;

import com.dat3m.dartagnan.exception.ParsingException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/*
    The qualifiers of a PTX mnemonic, e.g. ld.relaxed.cluster.global.L2::cache_hint.b32.
    An instruction handler takes the qualifiers it understands and then calls checkConsumed(),
    so that a qualifier nobody handles is reported instead of being ignored.
 */
final class PTXQualifiers {

    static final Set<String> TYPES = Set.of(
            "b8", "b16", "b32", "b64", "b128",
            "u8", "u16", "u32", "u64",
            "s8", "s16", "s32", "s64",
            "f16", "f16x2", "bf16", "bf16x2", "f32", "f64");
    static final Set<String> VECTORS = Set.of("v2", "v4", "v8");
    static final Set<String> SCOPES = Set.of("cta", "cluster", "gpu", "sys");
    // Qualifiers that are performance hints only and do not change the memory consistency behavior:
    // cache operators, eviction priorities, cache hints and prefetch sizes.
    static final Set<String> HINTS = Set.of(
            "ca", "cg", "cs", "lu", "cv", "wb", "wt",
            "L1::evict_normal", "L1::evict_unchanged", "L1::evict_first", "L1::evict_last", "L1::no_allocate",
            "L2::evict_normal", "L2::evict_unchanged", "L2::evict_first", "L2::evict_last",
            "L2::cache_hint", "L2::64B", "L2::128B", "L2::256B");

    private final String mnemonic;
    private final String name;
    private final List<String> qualifiers;

    private PTXQualifiers(String mnemonic) {
        this.mnemonic = mnemonic;
        final List<String> parts = Arrays.asList(mnemonic.split("\\."));
        this.name = parts.get(0);
        this.qualifiers = new ArrayList<>(parts.subList(1, parts.size()));
    }

    static PTXQualifiers parse(String mnemonic) {
        return new PTXQualifiers(mnemonic);
    }

    String getName() {
        return name;
    }

    String getMnemonic() {
        return mnemonic;
    }

    // Takes the qualifier if present.
    boolean take(String qualifier) {
        return qualifiers.remove(qualifier);
    }

    // Takes the first qualifier if it is the given one (for multi-part names such as cp.async.bulk.prefetch).
    boolean takeFirst(String qualifier) {
        if (!qualifiers.isEmpty() && qualifiers.get(0).equals(qualifier)) {
            qualifiers.remove(0);
            return true;
        }
        return false;
    }

    // Takes the only qualifier from the given options, or returns null if there is none.
    String takeOne(Collection<String> options) {
        String found = null;
        for (String qualifier : List.copyOf(qualifiers)) {
            if (options.contains(qualifier)) {
                if (found != null) {
                    throw error("Conflicting qualifiers ." + found + " and ." + qualifier);
                }
                found = qualifier;
                qualifiers.remove(qualifier);
            }
        }
        return found;
    }

    void takeAll(Collection<String> options) {
        qualifiers.removeIf(options::contains);
    }

    void checkConsumed() {
        if (!qualifiers.isEmpty()) {
            throw error("Unsupported qualifier ." + String.join(" .", qualifiers));
        }
    }

    ParsingException error(String message) {
        return new ParsingException(message + " in '" + mnemonic + "'");
    }
}
