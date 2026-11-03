package com.dat3m.dartagnan.parsers.program.visitors;

import com.dat3m.dartagnan.exception.ParsingException;
import com.dat3m.dartagnan.program.event.Tag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
    The state space of every location of a PTX litmus test, and the checks of the accesses to it.

    A location is in .global unless the test declares it in the shared memory of a CTA (s=0 @cta 1) or an
    instruction accesses it with a state space qualifier. A shared location without a declared owner belongs to
    the CTA of the first thread that accesses it with .shared; a .local location belongs to the first thread that
    accesses it.
 */
final class PTXStateSpaces {

    enum Space { GLOBAL, SHARED, LOCAL, CONST, PARAM }

    static final Set<String> QUALIFIERS = Set.of(
            "global", "shared", "shared::cta", "shared::cluster", "local", "const", "param");

    // gpu, cluster and cta of each thread
    private record Place(int gpu, int cluster, int cta) {}

    private final Map<Integer, Place> places = new HashMap<>();
    private final Map<String, Space> spaces = new HashMap<>();
    // Owning CTA of each shared location, owning thread of each local location
    private final Map<String, Integer> owners = new HashMap<>();

    void addThread(int thread, int gpu, int cluster, int cta) {
        places.put(thread, new Place(gpu, cluster, cta));
    }

    void declareShared(String location, int cta) {
        setSpace(location, Space.SHARED);
        owners.put(location, cta);
    }

    // Called for every access before the events are built, so that owners follow the first access in program text.
    void observe(String location, String qualifier, int thread) {
        if (qualifier == null) {
            return;
        }
        final Space space = toSpace(qualifier);
        setSpace(location, space);
        if (space == Space.SHARED) {
            owners.putIfAbsent(location, places.get(thread).cta());
        } else if (space == Space.LOCAL) {
            owners.putIfAbsent(location, thread);
        }
    }

    // Checks an access of the thread to the location and returns the space tags of its events.
    List<String> access(String location, String qualifier, int thread, boolean write) {
        final Space space = spaces.getOrDefault(location, Space.GLOBAL);
        if (qualifier != null && toSpace(qualifier) != space) {
            throw new ParsingException(String.format("Location %s is in %s, not in .%s",
                    location, space.name().toLowerCase(), qualifier));
        }
        final Place place = places.get(thread);
        final List<String> tags = new ArrayList<>();
        switch (space) {
            case GLOBAL -> tags.add(Tag.PTX.GLOBAL);
            case SHARED -> {
                final int owner = owners.get(location);
                final Place ownerPlace = findCta(owner, place);
                if (ownerPlace.gpu() != place.gpu() || ownerPlace.cluster() != place.cluster()) {
                    throw new ParsingException(String.format("Thread P%d is not in the cluster of CTA %d, owner of %s",
                            thread, owner, location));
                }
                if ("shared::cta".equals(qualifier) || "shared".equals(qualifier)) {
                    if (owner != place.cta()) {
                        throw new ParsingException(String.format(
                                "Location %s is in the shared memory of CTA %d, outside the .shared::cta window of P%d",
                                location, owner, thread));
                    }
                }
                tags.add(Tag.PTX.SHARED);
                if (owner == place.cta()) {
                    tags.add(Tag.PTX.SHARED_CTA);
                }
            }
            case LOCAL -> {
                if (owners.get(location) != thread) {
                    throw new ParsingException(String.format("Location %s is local to P%d", location,
                            owners.get(location)));
                }
                tags.add(Tag.PTX.LOCAL);
            }
            case CONST, PARAM -> {
                if (write) {
                    throw new ParsingException(String.format("Location %s is read-only", location));
                }
                tags.add(space == Space.CONST ? Tag.PTX.CONST : Tag.PTX.PARAM);
            }
        }
        return tags;
    }

    // Whether the location is in the shared memory of the thread's own CTA
    boolean isInOwnCta(String location, int thread) {
        return spaces.get(location) == Space.SHARED && owners.get(location) == places.get(thread).cta();
    }

    boolean isShared(String location) {
        return spaces.get(location) == Space.SHARED;
    }

    int getCta(int thread) {
        return places.get(thread).cta();
    }

    // The place of the owner CTA; a CTA without threads is assumed to be in the cluster of the accessing thread.
    private Place findCta(int cta, Place accessor) {
        return places.values().stream().filter(p -> p.cta() == cta).findFirst()
                .orElse(new Place(accessor.gpu(), accessor.cluster(), cta));
    }

    private void setSpace(String location, Space space) {
        final Space old = spaces.putIfAbsent(location, space);
        if (old != null && old != space) {
            throw new ParsingException(String.format("Location %s is accessed both in .%s and in .%s",
                    location, old.name().toLowerCase(), space.name().toLowerCase()));
        }
    }

    private static Space toSpace(String qualifier) {
        return switch (qualifier) {
            case "global" -> Space.GLOBAL;
            case "shared", "shared::cta", "shared::cluster" -> Space.SHARED;
            case "local" -> Space.LOCAL;
            case "const" -> Space.CONST;
            case "param" -> Space.PARAM;
            default -> throw new ParsingException("Unsupported state space ." + qualifier);
        };
    }
}
