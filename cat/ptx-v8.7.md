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
- **Choice.** (a), the implicit 1x1x1 cluster of the manual (cluster id 1000000 + CTA id). A test must name the
  cluster of all its threads or of none. With (a), `.cluster` scope behaves like `.cta` in old tests, so their results do not change.
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

### M21. Proxies, aliases and `bar.cta.*` from the PTX 7.5 model

- **Question.** How much of the PTX 7.5 model's treatment of proxies and barriers is kept?
- **Choice.** The proxy rules are kept and extended (M25-M27). Barriers: M28-M31.
- **Tests.** `Nvidia/proxy/*`, `Manual/proxy/*`, `Barrier/*`, `Kamui/mixedproxy/*`.

### M22. State spaces and the owner of shared locations (test dialect)

- **Question.** Litmus locations carry no declaration of their state space. Which space is a location in, which CTA
  owns a shared location, and which accesses are valid?
- **Manual.** 3146-3148: "The shared (.shared) state space is a memory that is owned by an executing CTA and is
  accessible to the threads of all the CTAs within a cluster." 3150-3154: "Additional sub-qualifiers ::cta or
  ::cluster can be specified on instructions with .shared state space to indicate whether the address belongs to
  the shared memory window of the executing CTA or of any CTA in the cluster respectively. ... If no sub-qualifier
  is specified with the .shared state space, then it defaults to ::cta." 2942: "The local state space (.local) is
  private memory for each thread". 2886: "The constant (.const) state space is a read-only memory initialized by the
  host." 2985: kernel parameters are "addressable, read-only variables declared in the .param state space".
  5419-5423 (generic addressing): the other spaces are windows of the generic address space.
- **Choice.** A location is in `.global` unless it is declared `s=0 @cta N` (shared memory of CTA N) or accessed with
  a state space qualifier (`ld`, `st`, `atom`, `red`). A shared location without a declared owner belongs to the
  CTA of the first thread (in program text) that accesses it with `.shared`; a `.local` location to the first thread
  that accesses it. Accesses without a qualifier use generic addressing and may reach any space. The test is
  rejected if a location is used in two spaces, if `.shared`/`.shared::cta` names a location of another CTA (it is
  outside the executing CTA's window), if a thread outside the owner's cluster accesses shared memory, if another
  thread accesses a `.local` location, or if a `.const`/`.param` location is written. Alternative: accept such
  programs and treat them as undefined behaviour; rejected because they are not valid programs at all.
- **Tests.** `Kamui/kamui/ptx87/spaces/*`, `Kamui/kamui/ptx87/ld-st/CoWR+local`, `ld.const+initial`, and every later
  test with shared memory.

### M23. The `.shared::cta` and `.shared::cluster` windows name the same address

- **Question.** The executing CTA's shared memory can be accessed through the `::cta` window and through the
  `::cluster` window. Are the two accesses to the same address, or aliases (distinct virtual addresses of one
  location, which need `fence.proxy.alias`, 6779-6781)?
- **Manual.** 3152-3153: "The addresses in the .shared::cta window also fall within the .shared::cluster window."
  6557: aliases are "Two distinct virtual addresses" that "map to the same memory location".
- **Alternatives.** (a) the same address; (b) aliases.
- **Choice.** (a): 3152-3153 says the `::cta` addresses *are* `::cluster` addresses ("fall within"), not that they
  are mapped to the same location from another address.
- **Tests.** `Kamui/kamui/ptx87/spaces/CoRR+relaxed.cluster+two-windows` (forbidden; allowed under (b)).

### M24. `ld.const` and `cold`

- **Question.** Upstream tests use `cold` for a load through the constant proxy. Is `ld.const` such a load?
- **Manual.** 6775-6776: "Memory operations as defined in Operation types use generic method of memory access, i.e.
  a generic proxy." 6618: every variant of `ld` is a read operation.
- **Choice.** `ld.const` is a weak generic-proxy load from a `.const` location; `cold` keeps its upstream meaning
  (constant proxy). The two are different instructions in the dialect.
- **Tests.** `Kamui/kamui/ptx87/ld-st/ld.const+initial`.

### M25. `fence.proxy.async` and its state space

- **Question.** Which operations does `fence.proxy.async{.global,.shared::cta,.shared::cluster}` order?
- **Manual.** 22653-22660: "The .proxykind qualifier indicates the bi-directional proxy ordering that is established
  between the memory accesses done between the generic proxy and the proxy specified by .proxykind. ... Value
  .async of the .proxykind qualifier specifies that the memory ordering is established between the async proxy and
  the generic proxy. The memory ordering is limited only to operations performed on objects in the state space
  specified. If no state space is specified, then the memory ordering applies on all state spaces."
- **Choice.** A bi-directional proxy fence between the generic and the async proxy, following the PTX 7.5 model's
  rules for proxy fences (M27), limited to operations on locations in `.global`, in any shared memory
  (`.shared::cluster`), or in the shared memory of the fence's CTA (`.shared::cta`). Alternative for
  `.shared::cta`: operations written with a `.shared::cta` qualifier; rejected because the text speaks of objects in
  the space, not of the instructions' qualifiers.
- **Tests.** The `fence.proxy.async` tests of `Kamui/kamui/ptx87/async-bulk/` and `wgmma/` (decided with the
  asynchronous operations).

### M26. Tensormap proxy fences

- **Question.** What do `fence.proxy.tensormap::generic.release` and `.acquire [a], 128` do?
- **Manual.** 22645-22647: "A uni-directional proxy ordering from the from-proxykind to the to-proxykind establishes
  ordering between a prior memory access performed via the from-proxykind and a subsequent memory access performed
  via the to-proxykind." 22662-22664: "A .release proxy fence can form a release sequence that synchronizes with an
  acquire sequence that contains a .acquire proxy fence." 22632-22634: the address operand gives "the memory range
  [addr, addr+size-1] on which the ordering guarantees on the memory accesses across the proxies is to be provided".
  19591: "The tensorMap is accessed in tensormap proxy."
- **Alternatives.** (a) a generic write reaches a later tensormap-proxy read of the same address only through a
  release tensormap fence after the write and an acquire tensormap fence before the read, with base causality in
  between; each fence is also a one-sided pattern fence (like `fence.release`/`fence.acquire`, M2); (b) either fence
  alone orders the proxies.
- **Choice.** (a): the ordering is uni-directional and 22662-22664 pairs the release fence with the acquire fence.
  The acquire fence's address range is not modelled: it orders tensormap reads of every location (the tests use a
  single tensor map).
- **Tests.** `Kamui/kamui/ptx87/tensormap/*` (decided with the tensor copies).

### M27. Proxy-preserved base causality for proxies other than generic and alias

- **Question.** The manual's proxy-preserved base causality (6994-7005) has three cases: same address in the generic
  proxy; same address, same proxy and same thread block; aliases with an alias proxy fence on the path. It never
  says how a `fence.proxy` for surfaces, textures, constants or the async proxy orders operations of different
  proxies (gap 8).
- **Manual.** 6779-6781: "A proxy fence is required to synchronize memory operations across different proxies."
  6996-7005 (the three cases).
- **Choice.** Keep the PTX 7.5 model's rules: an operation of one proxy reaches an operation of another at the same
  address when the base causality path goes through a proxy fence of the non-generic proxy in the CTA of that
  operation ("thread block" read as CTA), with the alias rules for virtual aliases. The async proxy is added as one
  more proxy (M25); the tensormap proxy uses M26. No upstream test changes verdict.
- **Tests.** `Nvidia/proxy/*`, `Manual/proxy/*`, `Kamui/mixedproxy/*`, and the async and tensormap tests.

### M28. `bar.cta.sync` in the test dialect and PTX's `bar` forms

- **Question.** Upstream tests write `bar.cta.sync id{, barrier{, count}}`, where the first operand is an instance id
  of the dialect, not PTX's barrier number. PTX's `bar{.cta}.sync a{, b}` takes the barrier first.
- **Manual.** 22255-22264 (syntax: `bar{.cta}.sync a{, b}`, `bar{.cta}.arrive a, b`); 22340-22342: "The optional .cta
  qualifier simply indicates CTA-level applicability of the barrier and it doesn't change the semantics".
- **Choice.** `bar.cta.sync`/`bar.cta.arrive` keep the upstream dialect meaning, so upstream tests are unchanged;
  `bar.sync`, `bar.arrive`, `barrier.sync{.aligned}`, `barrier.arrive{.aligned}` and `barrier.cta.*` have PTX's
  meaning (M29). Tests should use the PTX forms.
- **Tests.** `Barrier/*`, `Manual/*` with `bar.cta.*` (upstream meaning); `Kamui/kamui/ptx87/bar/*` (PTX meaning).

### M29. Barrier instances and thread counts

- **Question.** Litmus threads are single threads, but barriers count threads in multiples of the warp size, and a
  barrier is reused: which executions of `bar.sync a, b` belong together?
- **Manual.** 22273-22274: "Each CTA instance has sixteen barriers numbered 0..15." 22280-22284: "Operand b specifies
  the number of threads participating in the barrier. If no thread count is specified, all threads in the CTA
  participate in the barrier. When specifying a thread count, the value must be a multiple of the warp size."
  22297-22298: "When a barrier completes, the waiting threads are restarted without delay, and the barrier is
  reinitialized so that it can be immediately reused." 22301-22304: "guarantees that when the barrier completes,
  prior memory accesses requested by this thread are performed relative to all threads participating in the
  barrier."
- **Choice.** Each litmus thread counts as one participant (the warp-size rule is not applied). The k-th use of
  barrier a by a thread joins the k-th instance of barrier a. Without a count, every thread of the CTA that uses the
  instance must arrive; with a count, the instance completes when that many threads arrived (Dartagnan's named
  barriers), and a thread blocks at an instance that never completes. Synchronization (6927-6928): an arrive or
  sync synchronizes with every sync of the same instance; an arrive is never the target.
- **Tests.** `Kamui/kamui/ptx87/bar/*` (`bar.sync-count+hang`: forbidden, the waiting thread never loads),
  `Kamui/kamui/ptx87/sizes/mixed-size+bar.sync`.

### M30. `bar.warp.sync`

- **Question.** `bar.warp.sync` orders memory among its threads, but the warp is not a scope and the instruction is
  not in 8.9.4.
- **Manual.** 22448-22450: "bar.warp.sync also guarantee memory ordering among threads participating in barrier.
  Thus, threads within warp that wish to communicate via memory can store to memory, execute bar.warp.sync, and then
  safely read values stored by other threads in warp." 6767-6768: "the warp is not a scope". 22446: "The behavior of
  bar.warp.sync is undefined if the executing thread is not in the membermask."
- **Alternatives.** (a) the members synchronize like `bar.sync` among them; (b) no memory effect, since 8.9.4 does not
  list it.
- **Choice.** (a), as 22448-22450 promises. In the dialect bit k of the mask is thread Pk; all members must be in the
  executing thread's CTA, and the executing thread must be a member (otherwise the test is rejected).
- **Tests.** `Kamui/kamui/ptx87/bar/MP+bar.warp.sync` (forbidden), `MP+bar.warp.sync+non-member` (allowed).

### M31. `barrier.cluster` arrive and wait

- **Question.** How do `barrier.cluster.arrive{.release,.relaxed}` and `barrier.cluster.wait` synchronize, and does a
  memory fence before a `.relaxed` arrive form a release pattern?
- **Manual.** 6930: "A barrier.cluster.arrive operation synchronizes with a barrier.cluster.wait operation."
  22506-22509: "The barrier.cluster.wait instruction guarantees that when it completes the execution, memory accesses
  (except asynchronous operations) requested, in program order, prior to the preceding barrier.cluster.arrive by all
  threads in the cluster are complete and visible to the executing thread." 22511-22512: "There is no memory
  ordering and visibility guarantee for memory accesses requested by the executing thread, in program order, after
  barrier.cluster.arrive and prior to barrier.cluster.wait." 22514-22516: "The optional .relaxed qualifier on
  barrier.cluster.arrive specifies that there are no memory ordering and visibility guarantees provided for the
  memory accesses performed prior to barrier.cluster.arrive." 22520-22522: `.release` and `.acquire` are the
  defaults. 6840-6842: "a memory fence followed by a strong write on M" is a release pattern.
- **Alternatives.** For a fence before a relaxed arrive: (a) it synchronizes with the wait like a release pattern;
  (b) no synchronization, since the arrive is not a write.
- **Choice.** The k-th arrive and k-th wait of all threads of the cluster form the k-th instance. A `.release` arrive
  synchronizes with every wait of its instance (not with arrives; waits do not synchronize with each other, so
  accesses between a thread's arrive and wait are not ordered). For a `.relaxed` arrive, (a): a memory fence before
  it, whose scope includes the waiting thread, synchronizes with the wait. (b) would make `fence; arrive.relaxed`,
  the pattern the manual's own example uses with restricted fences (22738-22755), useless. In Dartagnan an arrive
  also waits until every thread of the cluster reached the instance (the encoding of control barriers); with
  straight-line tests this changes nothing.
- **Tests.** `Kamui/kamui/ptx87/barrier-cluster/*`, `Kamui/kamui/hand/MP+barrier.cluster*`
  (`MP+barrier.cluster-relaxed+fence+same-cluster` forbidden, `MP+barrier.cluster-relaxed+fence.cta` allowed).

### M32. mbarrier objects as atomic operations on one location

- **Question.** Chapter 8 does not mention mbarrier objects except in two examples of patterns, and 8.9.4 does not say
  how arrivals synchronize with waits (gaps 4, 5). What memory operations are the mbarrier instructions?
- **Manual.** 6834: release pattern "E.g.: st.release [M]; or atom.acq_rel [M]; or mbarrier.arrive.release [M];".
  6851: acquire pattern "E.g.: ld.acquire [M]; or atom.acq_rel [M]; or mbarrier.test_wait.acquire [M];".
  24535-24536: "mbarrier.arrive_drop operation with .release qualifier forms the release pattern". 24417-24418: the
  state returned by `mbarrier.arrive` captures "the phase of the mbarrier object prior to the arrive-on operation".
  24799-24802: when a wait with acquire semantics returns True, "All memory accesses (except async operations)
  requested prior, in program order, to mbarrier.arrive having release semantics during the completed phase by the
  participating threads of the CTA are performed and are visible to the executing thread." 24065-24086 (phase
  completion), 24087-24104 (arrive-on).
- **Alternatives.** (a) the object is one memory location, and `arrive`, `arrive_drop`, `arrive.expect_tx`,
  `expect_tx`, `complete_tx` are atomic read-modify-write operations of its whole state (with the instruction's
  semantics and scope), `test_wait`/`try_wait` reads of it; (b) a special synchronization relation from every arrival
  of a phase to the waits that see the phase complete.
- **Choice.** (a): it is what the examples of 6834 and 6851 say, and chapter 8 then gives the rest: a wait that reads
  the state written by the last arrival of a phase is preceded in observation order by every earlier arrival of the
  phase (each one read the previous state), so the release arrivals of all threads synchronize with the acquiring
  wait; relaxed arrivals do not; the scopes decide whether a remote arrival (another CTA, `.shared::cluster`)
  synchronizes. The state (valid, phase, expected count, pending count, tx-count) is packed into one integer; in this
  implementation each count must stay below 64 and the tx-count between -32 and 31. Defaults: arrivals `.release`,
  `expect_tx`/`complete_tx` `.relaxed`, waits `.acquire`, scope `.cta`; transaction counts are in copied locations
  (test dialect).
- **Tests.** `Kamui/kamui/ptx87/mbarrier/*`, `Kamui/kamui/hand/MP+*mbarrier*`. With (b) the remote-arrival tests could
  differ: `MP+mbarrier-remote-arrive.cta` and `MP+mbarrier-remote-arrive+wait.cta` are allowed here because a `.cta`
  scope does not include the other CTA.

### M33. mbarrier waits

- **Question.** A wait reads the object whether or not it returns True; when is it an acquire, and what is the state
  token?
- **Manual.** 24784-24785: "When mbarrier.test_wait and mbarrier.try_wait operations with .acquire qualifier returns
  True, they form the acquire pattern". 24746-24750: `test_wait` is non-blocking, `try_wait` "potentially blocking"
  until the phase completes or a time limit. 24757-24762: the tested phase is given by a state token "returned by an
  mbarrier.arrive instruction on the same mbarrier object during the current or the immediately preceding phase" or
  by a parity.
- **Choice.** A `.acquire` wait reads the state with acquire semantics when the tested phase is complete, and with
  relaxed semantics otherwise (Dartagnan picks one branch and assumes the matching outcome). `try_wait` behaves like
  `test_wait`: the time limit is not observable in a litmus test. The token is the phase number read by the arrive;
  a token wait returns True when the phase differs from it, a parity wait when the phase's parity differs from the
  given one. Using a token or parity from an older phase is not detected.
- **Tests.** `Kamui/kamui/ptx87/mbarrier/mbarrier-wait-false+no-sync`, `MP+mbarrier-token`, `mbarrier-parity-1`,
  `mbarrier-phase-incomplete`; `hand/MP+mbarrier-rel-acq-try_wait.cta+same-cta`.

### M34. mbarrier initialization, invalidation and other rules

- **Question.** `mbarrier.init` and `mbarrier.inval` have no stated memory semantics (gap 19); some rules have no
  stated consequence.
- **Manual.** 24162-24169: init sets the phase to 0, both counts to `count`, the tx-count to 0. 24178-24181: init on a
  valid object is undefined. 23925-23926: "Performing any mbarrier operation except mbarrier.init on an uninitialized
  mbarrier object results in undefined behavior." 24027-24029: "For each phase of the mbarrier object, at least one
  test_wait or try_wait operation must be performed which returns True for waitComplete before an arrive-on operation
  in the subsequent phase." 24421-24423: an arrive on a remote object "cannot return a value. Sink symbol '_' is
  mandatory".
- **Choice.** `init` and `inval` are weak writes of the initial and of an invalid state (generic proxy); the
  declaration `b=mbarrier(n)` gives the initial state before the threads start, `b=mbarrier()` an invalid one. The
  object must be in shared memory; `init`, `inval` and the waits must name an object of the executing CTA (otherwise
  the test is rejected). Not modelled: init on a valid object, the rule of 24027-24029, and the sink rule (the dialect
  lets a remote arrive return the token).
- **Tests.** `Kamui/kamui/ptx87/mbarrier-init/*`.

### M35. Undefined behaviour from misusing an mbarrier object

- **Question.** An operation on an invalid or uninitialized object is undefined behaviour. Should it switch the axioms
  off, like a mixed-size data race (M16)?
- **Manual.** 23925-23926, 24224-24225 (operations on an object that is not valid are undefined).
- **Alternatives.** (a) the axioms do not apply to such an execution; (b) the execution is reported (flag
  `undefined-behaviour` with `--property=cat_spec`) and still constrained by the axioms.
- **Choice.** (b). Whether the object is valid depends on the value the operation reads. With (a), an execution in
  which an arrive reads a stale invalid state (after `mbarrier.init` in the same thread) would make itself undefined
  and thereby allow the stale read: the axioms would never constrain mbarrier objects initialized in a thread. A
  mixed-size data race is different: it is defined by causality, not by a value. Values still come from writes, so
  `r0 == 7` outcomes stay forbidden.
- **Tests.** `Kamui/kamui/ptx87/mbarrier-init/mbarrier-init+same-thread` (forbidden; allowed under (a)),
  `mbarrier-init+inval+use` (forbidden, flagged).

### M36. Restricted fences

- **Question.** Which operations do `fence.release.sync_restrict::shared::cta.cluster`,
  `fence.acquire.sync_restrict::shared::cluster.cluster` and `fence.mbarrier_init.release.cluster` order, and are
  they the fences of release and acquire patterns although Table 20 does not list them (gap 7)?
- **Manual.** 22621-22630: "Qualifiers .op_restrict and .sync_restrict restrict the class of memory operations for
  which the fence instruction provides the memory ordering guarantees. When .op_restrict is .mbarrier_init, the
  synchronizing effect of the fence only applies to the prior mbarrier.init operations executed by the same thread
  on mbarrier objects in .shared::cta state space. When .sync_restrict is .sync_restrict::shared::cta, .sem must be
  .release, and the effect of the fence only applies to operations performed on objects in .shared::cta state space.
  Likewise, when .sync_restrict is .sync_restrict::shared::cluster, .sem must be .acquire, and the effect of the
  fence only applies to operations performed on objects in .shared::cluster state space. When either
  .sync_restrict::shared::cta or .sync_restrict::shared::cluster is present, the .scope must be specified as
  .cluster." 22738-22755: the manual's example pairs these fences with relaxed mbarrier arrivals, relaxed waits and
  relaxed cluster barrier arrivals.
- **Alternatives.** For "objects in .shared::cta": (a) locations in the shared memory of the fence's CTA, however
  they are accessed; (b) accesses written with a `.shared::cta` qualifier. For the fence itself: (c) one-sided
  release/acquire pattern fences (as M2) whose effect is limited to those operations; (d) no synchronizing effect,
  since Table 20 does not list them.
- **Choice.** (a) and (c). A `.sync_restrict::shared::cta` release fence orders the operations before it on
  locations in the shared memory of its CTA (its own and those that reach it through base causality from threads of
  its CTA); a `.mbarrier_init` fence the `mbarrier.init` operations of its thread before it; a
  `.sync_restrict::shared::cluster` acquire fence the operations after it on locations in any shared memory. Global
  memory is never covered. (a) follows "objects in ... state space"; (d) would contradict 22622 ("restrict the
  class of memory operations for which the fence ... provides the memory ordering guarantees").
- **Tests.** `Kamui/kamui/ptx87/restrict/*`, `barrier-cluster/MP+barrier.cluster-relaxed+fence-restrict-cta{,+global}`,
  `mbarrier/MP+fence-restrict-cta+mbarrier-relaxed-remote`, `mbarrier/MP+mbarrier-try_wait-relaxed+fence-restrict*`,
  `mbarrier-init/mbarrier-init+op-restrict*`. Under (d) the forbidden ones become allowed; no test distinguishes (a)
  from (b).

### M37. Restricted fences and transitivity

- **Question.** After a restricted fence synchronizes, does base causality stay transitive?
- **Manual.** 6976 and 7018-7019: base causality is transitive ("accounts for the cumulativity of synchronizing
  operations"); 22621-22630 (the restriction).
- **Choice.** The restriction applies to the operations the fence orders, not to what follows: an operation covered
  by a restricted release fence precedes everything the synchronization reaches, transitively; a restricted acquire
  fence passes on only the covered operations after it, which then order further operations as usual. In this
  implementation a base causality path contains at most one restricted release fence and at most one restricted
  acquire fence (enough for every test in the corpus).
- **Tests.** `Kamui/kamui/ptx87/restrict/MP+fence-restrict-cta+not-transitive` (allowed: the global store before the
  fence is not ordered).

### M38. `fence.proxy.async::generic` with `.sync_restrict`

- **Question.** The manual gives `fence.proxy.async::generic.release.sync_restrict::shared::cta.cluster` and the
  acquire form (22580-22581, example 22738-22755) without separate text.
- **Manual.** 22645-22647 (uni-directional proxy ordering), 22662-22664 (release and acquire proxy fences), 22621-22630.
- **Choice.** A restricted release or acquire fence (M36) that also orders, uni-directionally, generic-proxy accesses
  before it and async-proxy accesses after it, for locations in the shared memory named by its `.sync_restrict`
  qualifier.
- **Tests.** None in the corpus.

### M39. Asynchronous operations and program order

- **Question.** Asynchronous operations have no place in causality order (gap 1): how are they ordered with the
  instructions of their thread?
- **Manual.** 6884-6890: the operations of `cp.async`, `cp.async.bulk`, `cp.reduce.async.bulk` and
  `wgmma.mma_async` "are ordered after prior instructions in the same thread (except in the case of
  wgmma.mma_async), but they are not part of the program order for that thread". 6891-6894: the loads and stores of a
  `cp.async` "are ordered with respect to each other, but not to those of any other cp.async instructions initiated
  by the same thread, nor any other instruction subsequently issued by the thread with the exception of
  cp.async.commit_group or cp.async.mbarrier.arrive". 6897-6901: the implicit complete-tx "is ordered only with
  respect to the memory operations performed by the same asynchronous instruction, and in particular it does not
  transitively establish ordering with respect to prior instructions from the issuing thread."
- **Choice.** The events of an asynchronous operation (its reads, writes, complete-tx, or the arrive-on of
  `cp.async.mbarrier.arrive`) are created in the issuing thread but removed from program order; they are ordered
  among themselves (read, write, complete-tx), after the waits that complete them (M40), and the arrive-on of
  `cp.async.mbarrier.arrive` after all prior `cp.async` of its thread. "Ordered after prior instructions" is an
  issue order from the earlier instructions to the operation's own accesses, used only as the last step of
  proxy-preserved base causality: an operation's accesses see the writes before it (with a proxy fence where the
  proxies differ), but nothing reaches other threads through it. The same applies to `st.async` and `red.async`.
  Barriers therefore never cover asynchronous operations (22507).
- **Tests.** `Kamui/kamui/ptx87/async-bulk/TMA-load+complete-tx-not-transitive` (allowed),
  `cp-async/MP+cp.async.mbarrier.arrive+not-transitive` (allowed), `st-async/MP+st.async+not-transitive` (allowed),
  `barrier-cluster/MP+barrier.cluster+async-copy` (allowed), `cp-async/cp.async+no-wait` (allowed).

### M40. Completion through async-groups

- **Question.** `cp.async.wait_group`/`wait_all` and `cp.async.bulk.wait_group` make the completed operations'
  writes visible "to the executing thread". Is that visibility cumulative (passed on by a later release)?
- **Manual.** 18706-18714: a commit "creates a per-thread async-group containing all prior asynchronous operations
  ... All async-groups committed by an executing thread always complete in the order in which they were committed."
  18959-18965: "Writes performed by cp.async operations are made visible to the executing thread only after: The
  completion of cp.async.wait_all or The completion of cp.async.wait_group on the cp.async-group in which the
  cp.async belongs to or mbarrier.test_wait returns True". 18973-18974: the waits do "not provide any ordering and
  visibility guarantees for any other memory operation apart from cp.async." 20216-20220: with `.read`, the wait
  only covers "reading from the tensormap" and "the reading from their source locations".
- **Alternatives.** (a) the completed operations precede the wait in program order, so base causality carries them
  further (a later release of the waiting thread covers them); (b) visible only to the waiting thread.
- **Choice.** (a): once a write is visible to the thread, a release pattern of that thread "makes prior operations
  from the current thread visible" (6826-6827), and the transitivity of base causality applies. The commit groups
  are computed per thread and kind (non-bulk, bulk); `wait_group N` completes all but the N most recent committed
  groups, `wait_all` commits and waits for everything; `.read` completes only the source reads. Up to 8 groups per
  thread and kind.
- **Tests.** `Kamui/kamui/ptx87/async-bulk/TMA-store+wait_group+release` (forbidden; allowed under (b)),
  `TMA-store+wait_group.read+release`, `TMA-store+wait_group-1*`, `TMA-store+wait_group.read+source-reuse`,
  `Kamui/kamui/ptx87/cp-async/cp.async+wait_*`, `Kamui/kamui/hand/CoWR*async-bulk*`.

### M41. The implicit generic-async proxy fence at completion

- **Question.** How do the async-proxy writes of a bulk copy become visible to generic-proxy reads?
- **Manual.** 18763-18765: "The completion of a cp{.reduce}.async.bulk operation is followed by an implicit
  generic-async proxy fence. So the result of the asynchronous operation is made visible to the generic proxy as
  soon as its completion is observed." 31554-31556 (the same for `wgmma.mma_async`).
- **Choice.** A completion event (the complete-tx of the operation, or a wait that completes its group) on the base
  causality path from an async-proxy access to a generic-proxy access at the same address acts as a proxy fence.
- **Tests.** Removing this rule makes ten tests allowed: `async-bulk/TMA-load+mbarrier+*` (4), `TMA-multicast`,
  `TMA-store+wait_group+release`, `TMA-store+wait_group-1+older`, `TMA-store+wait_group.read+source-reuse`,
  `cp.reduce.async.bulk+wait_group`, `hand/CoWR+fence.proxy.async+async-bulk+same-cta`.

### M42. Who performs the complete-tx of an asynchronous operation

- **Question.** Morally strong operations need scopes that include each other's threads. The complete-tx of a copy
  has `.release` at `.cluster` scope, but a `.cta` wait (or a `.cta` arrive) in the mbarrier's CTA does not include
  the issuing thread of another CTA.
- **Manual.** 19150-19152: "the complete-tx operation on the mbarrier has .release semantics at the .cluster scope".
  19130-19136 (multicast): "The mbarrier signal is also multicast to the same CTA-relative offset as mbar in the
  shared memory of the destination CTA." 6788-6790 (morally strong scopes).
- **Alternatives.** (a) the complete-tx is morally strong with every operation on its mbarrier object (it is
  performed on behalf of the CTA that owns the object, and the object is only accessible from its cluster); (b) it
  is performed by the issuing thread, so a `.cta` operation of another CTA is not morally strong with it.
- **Choice.** (a): the signal is delivered to the destination CTA's mbarrier, and with (b) a copy into another CTA
  could never be observed by that CTA's default (`.cta`) waits.
- **Tests.** `Kamui/kamui/ptx87/async-bulk/TMA-multicast`, `st-async/MP+st.async`, `st-async/MP+red.async`
  (forbidden; allowed under (b)).

### M43. `cp.async.mbarrier.arrive`

- **Question.** What does `cp.async.mbarrier.arrive{.noinc}` do to the mbarrier, and with which semantics?
- **Manual.** 24634-24637: "Causes an arrive-on operation to be triggered by the system on the mbarrier object upon
  the completion of all prior cp.async operations initiated by the executing thread." 24640-24644: without `.noinc`
  "the pending count of the mbarrier object is incremented by 1 prior to the asynchronous arrive-on operation. This
  results in a zero-net change for the pending count". 24803-24805 (test_wait guarantee for cp.async).
- **Choice.** An asynchronous arrive-on with release semantics at `.cta` scope, ordered after all prior `cp.async`
  of the thread and nothing else; with `.noinc` it decrements the pending count, without it the net change is zero
  (one RMW with count 0; the intermediate increment is not modelled).
- **Tests.** `Kamui/kamui/ptx87/cp-async/*mbarrier.arrive*`.

### M44. Proxies, units and operands of asynchronous operations

- **Question.** Several details are not stated or only implied (gaps 14, 15).
- **Manual.** 18756: "The cp{.reduce}.async.bulk operations are performed in the asynchronous proxy". 18831:
  "cp.async is treated as a weak memory operation". 19394-19398: "Each reduction operation performed by the
  cp.reduce.async.bulk has individually .relaxed.gpu memory ordering semantics. The load operations in
  cp.reduce.async.bulk are treated as weak memory operation". 19591: the tensor map "is accessed in tensormap
  proxy". 17239-17244 (`st.async`: weak store, release complete-tx at `.cluster`).
- **Choice.** Bulk copies, bulk reductions and tensor copies access memory in the async proxy (their reductions are
  relaxed `.gpu` RMWs there, so they are not morally strong with generic atomics); `cp.async`, `st.async` and
  `red.async` use the generic proxy (no text puts them in the async proxy); the complete-tx and the arrive-on access
  the mbarrier like the other mbarrier operations (generic). A tensor copy first reads its tensor map in the
  tensormap proxy. Transaction counts count written locations (test dialect). The mbarrier of a copy must be in the
  CTA of its destination; `cp.async.bulk.global.shared::cta` without `.bulk_group` is read as the bulk-group form.
- **Tests.** `Kamui/kamui/ptx87/async-bulk/cp.reduce.async.bulk+generic-atomic` (allowed),
  `2+cp.reduce.async.bulk` (forbidden), `TMA-load+stale-source` (allowed without a proxy fence).

### M45. `st.async` without a completion mechanism and `st.async.release`

- **Question.** Without a completion mechanism, an `st.async`/`red.async` "synchronizes with the end of the CTA",
  which chapter 8 does not define (gap 13). `st.async.release` (sm_100) is a release store.
- **Manual.** 17229-17230 and 23394-23395 ("the completion of the store synchronizes with the end of the CTA").
  17273-17276: "This is a release store to global memory. The store operation is a strong memory operation with
  .release semantics at the scope specified by .scope."
- **Choice.** `st.async.release.{gpu,sys}.global` is a release store in program order (its release semantics
  order the thread's prior operations). The forms without a completion mechanism are accepted with the default
  `.mbarrier::complete_tx::bytes` only when an mbarrier operand is given; the end of the CTA is not modelled.
- **Tests.** `Kamui/kamui/ptx87/st-async/MP+st.async.release` (forbidden).

### M46. Undefined behaviour of asynchronous copies (not modelled)

- **Question.** Modifying the source or reading the destination before completion is undefined.
- **Manual.** 18689-18691: "Once an asynchronous copy operation is initiated, modifying the source memory location
  or tensor descriptor or reading from the destination memory location before the asynchronous operation completes,
  exhibits undefined behavior."
- **Choice.** Not detected: such programs are decided by the axioms (the copy may then read or write either value).
- **Tests.** `Kamui/kamui/ptx87/cp-async/cp.async+no-wait` reads the destination before completion (allowed either
  way).

## Tests Dartagnan cannot decide

The results below are the same with the PTX 7.5 model; they are limits of Dartagnan, not of the model.

- `CADP/*` are liveness tests (spin loops, condition `exists 0==0`), checked with `--property=termination` as in
  `PTXv7_5-Liveness-expected.csv`. 70 of the 483 are decided (all listed in `PTXv8_7-Liveness-expected.csv`). 407
  are `UNKNOWN` ("Not fully unrolled loops"): their spin loops use `atom.exch`, so they are not side-effect free and
  Dartagnan cannot bound them. 6 more (`2_threads_4_instructions/{62,63,132,133,144,145}_simple`) are rejected
  before verification: "Cannot normalize loop with loop-entering backjump (overlapping loops?)".
- `Manual/MICRO24-Fig4b-correct` is `UNKNOWN` with `--property=program_spec` at bound 1 (spin loop); it is not in
  `PTXv7_5-expected.csv` either.
