# PTX ISA 8.7 memory model: interpretation log

This file accompanies `ptx-v8.7.cat`. It records every choice the model makes where the PTX ISA 8.7 manual does not
settle the meaning by itself, and every place where the model departs from the PTX 7.5 model (`ptx-v7.5.cat`).

Line numbers refer to a plain-text rendering of the manual (*Parallel Thread Execution ISA Version 8.7*) in which
the heading of chapter 8, "Memory Consistency Model", is line 6503 and the heading of section 8.10.6, "Causality",
is line 7218. Section numbers are the manual's.

Each entry gives the question, the manual's words, the alternatives, the choice and its reason, and the tests it
affects (paths below `litmus/PTX/`).

## Litmus dialect used by the model

Besides the forms of the PTX 6.0 and 7.5 tests, the PTX parser accepts:

- thread placement with a cluster: `P0@cta 0,cluster 0,gpu 0` (M1);
- instructions written with their PTX qualifiers: `ld.relaxed.cluster.global.b32 r0, x`, `fence.acquire.gpu`,
  `atom.inc r0, x, 3`, ... A qualifier the parser does not know is an error. Defaults follow the manual: `ld`/`st`
  are `.weak`, `atom`/`red` are `.relaxed.gpu`, `fence` is `.acq_rel`, `membar.{cta,gl,sys}` is `fence.sc`;
- `.volatile` (M8), `.mmio` (M9), `ld.global.nc` (M10), `ldu` (M11), performance hints (M12, M13);
- vectors: `st.release.gpu.v2 x, y, 1, 1`, `ld.weak.v2 r0, r1, x, y` (M18);
- granules: a location declared `x[0]=0; x[1]=0;` has two granules; `x` names both, `x[1]` one. A store of one
  value writes it to every granule, `1:2` gives one value per granule; a load of `x` into `r0` fills `r0[0]`,
  `r0[1]`. Conditions may name `x[1]` and `P1:r0[1]` (M14).

## Entries

### M1. The cluster of a thread placed without one

- **Question.** Tests written for PTX 6.0 and 7.5 place threads with `P0@cta 0,gpu 0`. Which cluster are they in?
- **Manual.** 1530-1534: "Specifying cluster level during launch time is optional. If the user specifies the
  cluster dimensions at launch time then it will be treated as explicit cluster launch, otherwise it will be
  treated as implicit cluster launch with default dimension 1x1x1."
- **Alternatives.** (a) every CTA is its own cluster; (b) all threads of a GPU are in cluster 0.
- **Choice.** (a), the implicit 1x1x1 cluster of the manual. A test must name the cluster of all its threads or of
  none. With (a), `.cluster` scope behaves like `.cta` in old tests, so their results do not change.
- **Tests.** None changes verdict with (b): every test that uses `.cluster` names the clusters of its threads.

### M2. `fence.acquire` and `fence.release`

- **Question.** `fence.acquire` and `fence.release` are new in 8.7 (release notes, 43455: "Extends fence instruction
  to add support for .acquire and .release qualifiers"), but Table 20 does not list them as memory fences, and the
  release and acquire patterns are defined with a "memory fence".
- **Manual.** 6653-6655: "memory fence operation: A membar, fence.sc or fence.acq_rel instruction." 6840-6842:
  "Or a memory fence followed by a strong write on M in program order. E.g.: fence; st.relaxed [M];" 6857-6859:
  "Or a strong read on M followed by a memory fence in program order". 6675-6677: a synchronizing operation is "A
  barrier instruction, fence operation, release operation or acquire operation." 22565 lists
  `fence{.sem}.scope` with `.sem = { .sc, .acq_rel, .acquire, .release }` (22587).
- **Alternatives.** (a) no synchronizing effect, since they are not memory fences; (b) both behave as
  `fence.acq_rel`; (c) one-sided: `fence.release` is the fence of release patterns only, `fence.acquire` of
  acquire patterns only; neither takes part in Fence-SC order.
- **Choice.** (c). The names and the "release"/"acquire" semantics used throughout the manual make (a) implausible,
  and (b) would give `fence.release` an acquire effect the name denies. Both are strong operations with a scope.
- **Tests.** `Kamui/kamui/families/*/*fence-rel-acq*`.

### M3. `fence.sc` as the fence of a pattern

- **Question.** The PTX 7.5 model accepts only `fence.acq_rel` as the fence of release and acquire patterns. Does
  `fence.sc` also form patterns?
- **Manual.** 6653-6655 (a memory fence is "A membar, fence.sc or fence.acq_rel instruction"), 6840-6842 and
  6857-6859 (patterns with "a memory fence"). The manual's own MP example (7242-7250, 7265-7266) uses `fence.sys`,
  which defaults to `fence.acq_rel` (22613-22614).
- **Alternatives.** (a) follow the manual: `fence.sc`, `fence.acq_rel` and `membar` form patterns; (b) keep 7.5.
- **Choice.** (a); the manual is explicit. This is a departure from the 7.5 model, not an open question.
- **Tests.** No test in the corpus changes verdict with (b): in the MP, SB, IRIW and WRC tests the `fence.sc`
  pairs already synchronize through Fence-SC order. Tests that mix `fence.sc` with `fence.acq_rel` would.

### M4. Fence-SC order: total per scope or partial

- **Question.** The instruction section says `fence.sc` forms a total order per scope; chapter 8 says an acyclic
  partial order over morally strong pairs.
- **Manual.** 22617-22619: "Instances of fence.sc with sufficient scope always synchronize by forming a total order
  per scope, determined at runtime. This total order can be constrained further by other synchronization in the
  program." 6916-6917: "The Fence-SC order is an acyclic partial order, determined at runtime, that relates every
  pair of morally strong fence.sc operations."
- **Alternatives.** (a) order every morally strong pair, acyclic together with causality (both texts agree on
  morally strong pairs); (b) a partial order that may leave morally strong pairs unordered.
- **Choice.** (a): Dartagnan's `sync_fence` relates every pair of `fence.sc` whose scopes include each other, in one
  direction; the Fence-SC axiom (M5) keeps it consistent with causality. (b) contradicts "relates every pair".
- **Tests.** `Kamui/kamui/families/SB/*fence-sc*`, `Kamui/kamui/families/IRIW/*fence-sc*`.

### M5. Causality order between fences in the Fence-SC axiom

- **Question.** The Fence-SC axiom compares Fence-SC order with causality order, but causality order is built from
  proxy-preserved base causality order, which is defined for memory operations only. Taken literally the axiom
  never applies (this is how the 7.5 model behaves).
- **Manual.** 7058-7060: "Fence-SC order cannot contradict causality order. For a pair of morally strong fence.sc
  operations F1 and F2, if F1 precedes F2 in causality order, then F1 must precede F2 in Fence-SC order."
  6996-6997: "A memory operation X precedes a memory operation Y in proxy-preserved base causality order if X
  precedes Y in base causality order, and: ...". 6980: base causality relates any operations.
- **Alternatives.** (a) for fences, causality order is base causality order; (b) literal reading, axiom vacuous.
- **Choice.** (a): the axiom is stated for fences, so it must mean an order that relates fences; the proxy
  conditions of proxy-preserved causality only make sense for memory operations.
- **Tests.** No test in the corpus changes verdict with (b); it matters when causality between two `fence.sc` runs
  through other synchronization (for example a third thread with release and acquire patterns).

### M6. `red` in observation chains

- **Question.** Observation order passes through atomic operations. Table 20 says `red` is an atomic operation but
  not a read, and the RMW atomicity axiom speaks of an atomic operation that "reads". Can a `red` be the middle of
  an observation chain?
- **Manual.** 6611-6619: "atomic operation: atom or red instruction." "read operation: All variants of ld
  instruction and atom instruction (but not red instruction)." 6905-6912: "Observation order relates a write W to a
  read R through an optional sequence of atomic read-modify-write operations. ... For some atomic operation Z, W
  precedes Z and Z precedes R in observation order." 7076-7080: "When an atomic operation A and a write W overlap
  and are morally strong ... A reads any byte from a write W' that precedes W in coherence order."
- **Alternatives.** (a) yes, every atomic operation (atom and red); (b) only atom.
- **Choice.** (a): 6905 calls the chain elements "atomic read-modify-write operations", and 7080 treats a `red` as
  reading the value it modifies. Table 20's "not a read" is read as: `red` does not return a value (it cannot end
  an acquire pattern).
- **Tests.** None in the corpus has a `red` between a write and a read of an observation chain.

### M7. Sequential consistency per location over pairwise morally strong sets

- **Question.** The axiom applies to "any set of overlapping memory operations that are pairwise morally strong". A
  cycle whose consecutive operations are morally strong may contain two operations that are not.
- **Manual.** 7179-7183: "Within any set of overlapping memory operations that are pairwise morally strong,
  communication order cannot contradict program order, i.e., a concatenation of program order between
  overlapping operations and morally strong relations in communication order cannot result in a cycle."
- **Alternatives.** (a) forbid cycles whose consecutive edges are morally strong (as the PTX 6.0 model); (b) also
  require every pair on the cycle to be morally strong.
- **Choice.** (a): the second half of the sentence states the axiom edge by edge ("morally strong relations in
  communication order"). (a) is stronger than (b) only for cycles with operations at different scopes; no test in
  the corpus distinguishes them.
- **Tests.** No test in the corpus changes verdict with (b), nor without the axiom at all (the PTX 7.5 model has
  none): in `Kamui/kamui/families/CoRR/*`, `Kamui/kamui/hand/CoRR+volatile+diff-cluster` and
  `Kamui/kamui/ptx87/ld-st/CoRR+mmio` the Causality axiom forbids the same outcomes.

### M8. `.volatile`

- **Question.** Do the ordering constraints of volatile instructions add ordering to the model?
- **Manual.** 6704-6705: "The semantics of volatile operations are equivalent to a relaxed memory operation with
  system-scope but with the following extra implementation-specific constraints". 6712-6713: "Volatile
  instructions are not re-ordered around other volatile instructions, but the memory operations of those
  instructions may be re-ordered around each other."
- **Alternatives.** (a) relaxed `.sys`, the constraints are about the compiler; (b) also order volatile operations
  of a thread among themselves.
- **Choice.** (a): 6713 says the memory operations may be re-ordered, so the model sees relaxed `.sys` operations.
- **Tests.** `Kamui/kamui/hand/MP+volatile+diff-cluster`, `Kamui/kamui/hand/CoRR+volatile+diff-cluster`,
  `Kamui/mixedproxy/CoMP_volatile`.

### M9. `.mmio`

- **Question.** What do the implementation-specific properties of mmio operations mean for the model?
- **Manual.** 6687-6688: "For formal specification of semantics of mmio operation from Memory Consistency Model
  perspective, it is equivalent to the semantics of a strong operation." 6692-6699 (never combined, never cached,
  "the surrounding locations may also be loaded"). 16634-16635: "Only .sys thread scope is valid for ld.mmio
  operation. The qualifiers .mmio and .relaxed must be specified together."
- **Alternatives.** (a) a relaxed `.sys` operation; (b) also model the extra loads of surrounding locations.
- **Choice.** (a). The extra loads are reads whose values are discarded, so they cannot change an outcome.
- **Tests.** `Kamui/kamui/ptx87/ld-st/CoRR+mmio`, `Kamui/kamui/ptx87/ld-st/MP+mmio`.

### M10. `ld.global.nc`

- **Question.** The model does not apply to `ld.global.nc`. What may it read?
- **Manual.** 6521-6522: "The memory consistency model does not apply to texture (including ld.global.nc) and
  surface accesses." 16824-16825: "Load register variable d from the location specified by the source address
  operand a in the global state space, and optionally cache in non-coherent read-only cache."
- **Alternatives.** (a) a weak read through the texture proxy (as `tld`); (b) a weak generic read; (c) any value.
- **Choice.** (a): 6521 groups it with texture accesses, and the PTX 7.5 model already gives texture reads a
  meaning (they need a proxy fence to see generic writes). (c) is not expressible: reads return written values.
- **Tests.** `Kamui/kamui/ptx87/ld-st/CoWR+ld.global.nc`, `Kamui/kamui/ptx87/ld-st/MP+ld.global.nc` (both allowed).

### M11. `ldu`

- **Question.** `ldu` loads "read-only data"; the manual says nothing about its memory consistency semantics.
- **Manual.** 16927-16929: "Load read-only data into register variable d from the location specified by the source
  address operand a in the global state space, where the address is guaranteed to be the same across all threads
  in the warp."
- **Alternatives.** (a) a weak generic read; (b) like `ld.global.nc`; (c) undefined if the location is written.
- **Choice.** (a): unlike `ld.global.nc`, `ldu` is not excluded from the model by 6521, and it has no qualifier that
  would make it strong. "Read-only" is read as a performance contract, not as a change of semantics.
- **Tests.** `Kamui/kamui/ptx87/ld-st/MP+ldu`.

### M12. Cache operators, including `.ca`

- **Question.** Cache operators are hints only, yet `.ca` is said to return stale L1 data.
- **Manual.** 15572-15574: "Cache operators on load or store instructions are treated as performance hints only.
  The use of a cache operator on an ld or st instruction does not change the memory consistency behavior of the
  program." 15593-15595: "If one thread stores to global memory via one L1 cache, and a second thread loads that
  address via a second L1 cache with ld.ca, the second thread may get stale L1 cache data".
- **Alternatives.** (a) no effect; (b) `.ca` loads are non-coherent (like `ld.global.nc`).
- **Choice.** (a): 15572-15574 is a normative statement about the model; the stale-data remark describes weak loads
  that are not synchronized, which the model already allows to read stale values. The same holds for eviction
  priorities (15696), `L2::cache_hint` (16673-16674) and prefetch sizes (16664).
- **Tests.** `Kamui/kamui/ptx87/hints/*`, `Kamui/kamui/ptx87/ld-st/MP+cache-operators`.

### M13. `prefetch`, `prefetchu`, `applypriority`, `createpolicy`, `cp.async.bulk.prefetch`

- **Question.** Do these instructions access memory in the sense of the model?
- **Manual.** 17585: "The prefetch instruction brings the cache line containing the specified address in global or
  local memory state space into the specified cache level." 17659-17660 (`applypriority`): "If the data pointed to
  by address a is not already present in the specified cache level, then the data will be prefetched before
  applying the specified priority." 17740: createpolicy "creates a cache eviction policy". 19426
  (`cp.async.bulk.prefetch`): "Provides a hint to the system to initiate the asynchronous prefetch of data to the
  cache." None of them is a read or a write in Table 20 (6616-6625).
- **Alternatives.** (a) no memory event; (b) a weak read whose value is discarded.
- **Choice.** (a). (b) could not change an outcome either. `createpolicy` writes an opaque value into its register.
- **Tests.** `Kamui/kamui/ptx87/hints/MP+prefetch*`, `MP+applypriority`, `MP+bulk-prefetch`.

### M14. Granules and footprints in the test dialect

- **Question.** How do the granules of the dialect map to the model's memory locations, and what does a single
  value stored to a location with several granules mean?
- **Manual.** 6536-6540: "Each PTX memory instruction specifies an address operand and a data type. ... The
  physical address and the size of the data type together define a physical memory location". 6550-6553: two
  memory operations overlap "when they specify the same virtual address and the corresponding memory locations
  overlap. The overlap is said to be complete when both memory locations are identical, and it is said to be
  partial otherwise."
- **Alternatives.** For the footprint: (a) the operand (`x`: every granule, `x[k]`: one); (b) the type qualifier.
  For one value: (c) written to every granule; (d) split little-endian over the granules.
- **Choice.** (a) and (c). The dialect gives a granule no byte size, so type qualifiers cannot be compared with it
  (a `.b32` store covers the two granules of `x`, a `.b128` CAS one granule). Each granule is one model location;
  an access to every granule is one operation, modelled as one event per granule within one instruction (`si`).
  Two operations overlap completely when they access the same granules, partially when one accesses all
  granules and the other one of them (`WHOLE`/`PART` tags). With (d), "st x, 1" would leave the upper granule 0 and
  most mixed-size tests would test nothing.
- **Tests.** `Kamui/kamui/ptx87/sizes/*`.

### M15. Operations on several granules: atomicity and coherence

- **Question.** A wide operation is split into granule events. Which properties hold across its granules?
- **Manual.** 7066-7072: "Conflicting morally strong operations are performed with single-copy atomicity. When a
  read R and a write W are morally strong, then the following two communications cannot both exist in the same
  execution, for the set of bytes accessed by both R and W: R reads any byte from W. R reads any byte from any write
  W' which precedes W in coherence order." 7023-7025: "There exists a partial transitive order that relates
  overlapping write operations ... Two overlapping write operations are related in coherence order if they are
  morally strong or if they are related in causality order."
- **Alternatives.** (a) the operation is the unit: for morally strong pairs, a read does not mix granules of a write
  and of an older write, and two writes are ordered the same way on all granules; (b) granules are independent.
- **Choice.** (a), as the axioms are stated for operations and bytes. Pairs that are not morally strong (e.g. weak
  ones) get no such guarantee, so they may tear. The granule events of one operation are unordered in program order.
- **Tests.** `Kamui/kamui/ptx87/sizes/tearing+relaxed` (forbidden), `Kamui/kamui/ptx87/sizes/tearing+weak` (allowed).

### M16. Mixed-size data races

- **Question.** With a mixed-size data race the axioms do not apply. In which executions, and what may reads return?
- **Manual.** 6811-6813: "The axioms in the memory consistency model do not apply if a PTX program contains one or
  more mixed-size data-races. But these axioms are sufficient to describe the behavior of a PTX program with only
  uniform-size data-races." 6511-6512: "In the absence of any constraint, each read operation returns the value
  committed by some write operation to the same memory location, including the initial write to that memory
  location." 6803-6804: a data race is a pair of conflicting operations "not related in causality order and ... not
  morally strong".
- **Alternatives.** (a) per execution: an execution with a mixed-size data race is not constrained by the axioms,
  but its reads still return values written by some write; (b) per program: if any execution has such a race, no
  execution is constrained; (c) reads may return any value.
- **Choice.** (a). (b) quantifies over all executions, which an axiomatic check of one execution cannot express;
  (c) contradicts 6511-6512, which holds "in the absence of any constraint". The model reports such races with the
  flag `mixed-size-data-race` (`--property=cat_spec`).
- **Tests.** `Kamui/kamui/ptx87/sizes/mixed-size+race` (forbidden: no write of 7), `mixed-size+flag`.

### M17. Atomicity of mixed-size read-modify-write operations

- **Question.** Partial overlap makes two atomic operations not morally strong. Are they still atomic?
- **Manual.** 6817-6821: "In any program with or without mixed-size data-races, the following property holds for
  every pair of overlapping atomic operations A1 and A2 such that each specifies a scope that includes the other:
  Either the read-modify-write operation specified by A1 is performed completely before A2 is initiated, or vice
  versa. This property holds irrespective of whether the two operations A1 and A2 overlap partially or completely."
- **Alternatives.** (a) atomicity per shared granule for atomic pairs whose scopes include each other, also with a
  mixed-size race; (b) none.
- **Choice.** (a), as stated. Atomic operations in the dialect access one granule, so the property is checked on
  that granule.
- **Tests.** None in the corpus yet.

### M18. Vector operations

- **Question.** In which order are the elements of a vector access performed, and does a release element order its
  sibling elements?
- **Manual.** 6569-6572: "Memory operations with a vector data type are modelled as a set of equivalent memory
  operations with a scalar data type, executed in an unspecified order on the elements in the vector." 6844-6845:
  "Any memory synchronization established by a release pattern only affects operations occurring in program order
  before the first instruction in that pattern."
- **Alternatives.** (a) elements unordered; a release element covers operations of earlier instructions only;
  (b) elements in operand order.
- **Choice.** (a): "unspecified order"; the siblings are part of the pattern's instruction, not before it.
- **Tests.** `Kamui/kamui/ptx87/sizes/MP+vector-release` (allowed), `MP+vector-release+data` (forbidden).

### M19. `.b128` accesses

- **Question.** Scalars are at most 64 bits, but `atom.cas.b128` and `ld/st.b128` exist.
- **Manual.** 6569-6570: "operations executed on memory locations with scalar data types, which have a maximum size
  and alignment of 64 bits". 22772: `atom{.sem}{.scope}{.space}.cas.b128 d, [a], b, c`. 23023: `atom.shared.b128.cas
  d, a, b, c; // 128-bit atom`.
- **Alternatives.** (a) one operation (atomic as a whole); (b) two 64-bit halves.
- **Choice.** (a): the `atom` description presents it as one atomic operation. In the dialect the footprint comes
  from the operand (M14), so a `.b128` CAS on a one-granule location is one operation.
- **Tests.** `Kamui/kamui/ptx87/rmw/cas.b128`.

### M20. Details of `atom` and `red` operations

- **Question.** Signedness of `.min`/`.max` without a type; the dialect's `sub` (and upstream's `mul`, `div`),
  which PTX `atom` does not have; upstream's `red.acq_rel`, which PTX `red` does not have.
- **Manual.** 22785-22788: `.op = { .and, .or, .xor, .cas, .exch, .add, .inc, .dec, .min, .max }`. 22790: `.type =
  { .b32, .b64, .u32, .u64, .s32, .s64, .f32, .f64 }`. 22924-22925: `inc(r, s) = (r >= s) ? 0 : r+1;` `dec(r, s) =
  (r==0 || r > s) ? s : r-1;`. 23066: `red` has `.sem = {.relaxed, .release}`.
- **Choice.** `.min`/`.max` compare signed values for `.s*` types and unsigned values otherwise; `inc`/`dec` compare
  unsigned values. `sub`, `mul`, `div` and `red.acq_rel`/`red.acquire` are accepted for compatibility with the
  existing tests, with the obvious meaning.
- **Tests.** `Kamui/kamui/ptx87/rmw/rmw-ops+values`; `Nvidia/*`, `Manual/*` tests with `sub` or `red.acq_rel`.

### M21. Proxies, aliases and `bar.cta.*` (provisional)

- **Question.** These belong to later parts of the work (state spaces and proxies; barriers).
- **Choice.** Until then the model keeps the PTX 7.5 definitions unchanged: same-proxy, proxy-preserved base
  causality with alias and proxy fences, and barrier synchronization `syncbar & scta`.
- **Tests.** `Nvidia/proxy/*`, `Manual/proxy/*`, `Barrier/*`, `Kamui/mixedproxy/*`.

## Tests Dartagnan cannot decide

The results below are the same with the PTX 7.5 model; they are limits of Dartagnan, not of the model.

- `CADP/*` are liveness tests (spin loops, condition `exists 0==0`), checked with `--property=termination` as in
  `PTXv7_5-Liveness-expected.csv`. 70 of the 483 are decided (all listed in `PTXv8_7-Liveness-expected.csv`). 407
  are `UNKNOWN` ("Not fully unrolled loops"): their spin loops use `atom.exch`, so they are not side-effect free and
  Dartagnan cannot bound them. 6 more (`2_threads_4_instructions/{62,63,132,133,144,145}_simple`) are rejected
  before verification: "Cannot normalize loop with loop-entering backjump (overlapping loops?)".
- `Manual/MICRO24-Fig4b-correct` is `UNKNOWN` with `--property=program_spec` at bound 1 (spin loop); it is not in
  `PTXv7_5-expected.csv` either.
