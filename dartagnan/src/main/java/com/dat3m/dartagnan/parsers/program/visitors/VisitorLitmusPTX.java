package com.dat3m.dartagnan.parsers.program.visitors;

import com.dat3m.dartagnan.configuration.Arch;
import com.dat3m.dartagnan.exception.ParsingException;
import com.dat3m.dartagnan.expression.Expression;
import com.dat3m.dartagnan.expression.ExpressionFactory;
import com.dat3m.dartagnan.expression.integers.IntBinaryOp;
import com.dat3m.dartagnan.expression.integers.IntLiteral;
import com.dat3m.dartagnan.expression.type.IntegerType;
import com.dat3m.dartagnan.expression.type.TypeFactory;
import com.dat3m.dartagnan.parsers.LitmusPTXBaseVisitor;
import com.dat3m.dartagnan.parsers.LitmusPTXParser.*;
import com.dat3m.dartagnan.parsers.program.utils.ProgramBuilder;
import com.dat3m.dartagnan.program.Program;
import com.dat3m.dartagnan.program.Register;
import com.dat3m.dartagnan.program.event.Event;
import com.dat3m.dartagnan.program.event.EventFactory;
import com.dat3m.dartagnan.program.event.Tag;
import com.dat3m.dartagnan.program.event.arch.ptx.PTXAtomOp;
import com.dat3m.dartagnan.program.event.arch.ptx.PTXMbarrierOp;
import com.dat3m.dartagnan.program.event.arch.ptx.PTXMbarrierState;
import com.dat3m.dartagnan.program.event.arch.ptx.PTXRedOp;
import com.dat3m.dartagnan.program.event.arch.ptx.PTXWrappingOp;
import com.dat3m.dartagnan.program.event.core.GenericVisibleEvent;
import com.dat3m.dartagnan.program.event.core.InstructionBoundary;
import com.dat3m.dartagnan.program.event.core.Label;
import com.dat3m.dartagnan.program.memory.MemoryObject;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class VisitorLitmusPTX extends LitmusPTXBaseVisitor<Object> {
    private final ProgramBuilder programBuilder = ProgramBuilder.forArch(Program.SourceLanguage.LITMUS, Arch.PTX);
    private final ExpressionFactory expressions = programBuilder.getExpressionFactory();
    private final TypeFactory types = programBuilder.getTypeFactory();
    private final IntegerType archType = types.getArchType();
    // Size in bytes of one granule of a location
    private final int granuleSize = types.getMemorySizeInBytes(archType);
    // Number of granules of the locations declared as x[0], x[1], ...
    private final Map<String, Integer> granules = new HashMap<>();
    private final PTXStateSpaces spaces = new PTXStateSpaces();
    private int mainThread;
    private int threadCount = 0;
    // Number of barrier instructions of each kind executed so far by each thread (thread -> kind -> count)
    private final Map<Integer, Map<String, Integer>> barrierCounts = new HashMap<>();
    // Number of mbarrier waits parsed so far (for unique label and register names)
    private int waitCount = 0;
    // Number of fresh registers and of asynchronous operations created so far
    private int asyncCount = 0;
    private int asyncOperationCount = 0;
    // The locations of each multimem address
    private final Map<String, List<String>> multimem = new HashMap<>();
    // Grid of each thread (only when the test places threads in grids)
    private final Map<Integer, Integer> grids = new HashMap<>();
    // Threads that executed wgmma.fence
    private final Set<Integer> wgmmaFenced = new java.util.HashSet<>();
    // Committed async-groups of each thread, per kind ("AG": cp.async, "BAG": bulk operations)
    private final Map<Integer, Map<String, Integer>> committedGroups = new HashMap<>();
    // Ids of the clusters of threads placed without one
    private static final int IMPLICIT_CLUSTER = 1_000_000;

    private static final Set<String> LOAD_SEMS = Set.of("weak", "relaxed", "acquire", "volatile", "mmio");
    private static final Set<String> STORE_SEMS = Set.of("weak", "relaxed", "release", "volatile", "mmio");
    private static final Set<String> RMW_SEMS = Set.of("relaxed", "acquire", "release", "acq_rel");
    // Load modes of tensor copies (19534-19560): they only change how coordinates map to addresses (M54)
    private static final Set<String> TENSOR_MODES = Set.of("tile", "tile::gather4", "tile::scatter4", "im2col",
            "im2col::w", "im2col::w::128", "im2col_no_offs");
    private static final Set<String> FENCE_SEMS = Set.of("sc", "acq_rel", "acquire", "release");
    private static final Set<String> SPACES = PTXStateSpaces.QUALIFIERS;
    private static final Set<String> RMW_OPS = Set.of(
            "add", "sub", "mul", "div", "and", "or", "xor", "min", "max", "inc", "dec", "exch", "cas");

    public VisitorLitmusPTX() {
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Entry point

    @Override
    public Object visitMain(MainContext ctx) {
        visitThreadDeclaratorList(ctx.program().threadDeclaratorList());
        declareGranules(ctx.variableDeclaratorList());
        visitVariableDeclaratorList(ctx.variableDeclaratorList());
        observeStateSpaces(ctx.program().instructionList());
        visitInstructionList(ctx.program().instructionList());
        VisitorLitmusAssertions.parseAssertions(programBuilder, ctx.assertionList(), ctx.assertionFilter());
        return programBuilder.build();
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Variable declarator list

    // A location declared as x[0]=..; x[1]=..; has several granules: it is one memory object with one
    // granule-sized element per granule.
    private void declareGranules(VariableDeclaratorListContext ctx) {
        for (VariableDeclaratorContext declarator : ctx.variableDeclarator()) {
            VariableDeclaratorLocationContext location = declarator.variableDeclaratorLocation();
            if (location != null && location.index() != null) {
                granules.merge(location.location().getText(), parseIndex(location.index()) + 1, Math::max);
            }
        }
        granules.forEach((name, count) -> {
            MemoryObject object = programBuilder.getOrNewVirtualMemoryObject(name, count * granuleSize);
            programBuilder.setElementSize(object, granuleSize);
            for (int i = 0; i < count; i++) {
                object.setInitialValue(i * granuleSize, expressions.makeZero(archType));
            }
        });
    }

    @Override
    public Object visitVariableDeclaratorLocation(VariableDeclaratorLocationContext ctx) {
        String name = ctx.location().getText();
        IntLiteral value = (IntLiteral) ctx.constant().accept(this);
        if (ctx.ctaID() != null) {
            spaces.declareShared(name, ctx.ctaID().id);
        }
        if (!granules.containsKey(name)) {
            programBuilder.initVirLocEqCon(name, value);
            return null;
        }
        MemoryObject object = programBuilder.getMemoryObject(name);
        if (ctx.index() != null) {
            object.setInitialValue(parseIndex(ctx.index()) * granuleSize, value);
        } else {
            for (int i = 0; i < granules.get(name); i++) {
                object.setInitialValue(i * granuleSize, value);
            }
        }
        return null;
    }

    @Override
    public Object visitVariableDeclaratorMbarrier(VariableDeclaratorMbarrierContext ctx) {
        String name = ctx.location().getText();
        MemoryObject object = programBuilder.getOrNewVirtualMemoryObject(name);
        long state = ctx.constant() == null ? 0 : PTXMbarrierState.initial(Long.parseLong(ctx.constant().getText()));
        object.setInitialValue(0, expressions.makeValue(state, archType));
        if (ctx.ctaID() != null) {
            spaces.declareShared(name, ctx.ctaID().id);
        }
        return null;
    }

    @Override
    public Object visitVariableDeclaratorMultimem(VariableDeclaratorMultimemContext ctx) {
        final List<String> targets = ctx.location().subList(1, ctx.location().size()).stream()
                .map(LocationContext::getText).toList();
        multimem.put(ctx.location(0).getText(), targets);
        return null;
    }

    @Override
    public Object visitVariableDeclaratorRegister(VariableDeclaratorRegisterContext ctx) {
        String name = registerName(ctx.register(), ctx.index());
        programBuilder.initRegEqConst(ctx.threadId().id, name,
                (IntLiteral) ctx.constant().accept(this), ctx.getStart().getLine());
        return null;
    }

    @Override
    public Object visitVariableDeclaratorRegisterLocation(VariableDeclaratorRegisterLocationContext ctx) {
        programBuilder.initRegEqLocPtr(ctx.threadId().id, ctx.register().getText(), ctx.location().getText(),
                archType, ctx.getStart().getLine());
        return null;
    }

    @Override
    public Object visitVariableDeclaratorLocationLocation(VariableDeclaratorLocationLocationContext ctx) {
        programBuilder.initVirLocEqLoc(ctx.location(0).getText(), ctx.location(1).getText());
        return null;
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Proxy declarator list
    @Override
    public Object visitVariableDeclaratorProxy(VariableDeclaratorProxyContext ctx) {
        if (ctx.proxyType().content.equals(Tag.PTX.GEN)) {
            programBuilder.initVirLocEqLocAliasGen(ctx.location(0).getText(),
                    ctx.location(1).getText());
        } else {
            programBuilder.initVirLocEqLocAliasProxy(ctx.location(0).getText(),
                    ctx.location(1).getText());
        }
        return null;
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Thread declarator list (on top of instructions)
    @Override
    public Object visitThreadDeclaratorList(ThreadDeclaratorListContext ctx) {
        long withCluster = ctx.threadScope().stream().filter(t -> t.scopeID().clusterID() != null).count();
        if (withCluster != 0 && withCluster != ctx.threadScope().size()) {
            throw new ParsingException("Either every thread or no thread must name its cluster");
        }
        for (ThreadScopeContext threadScopeContext : ctx.threadScope()) {
            ScopeIDContext scopeID = threadScopeContext.scopeID();
            int ctaID = scopeID.ctaID().id;
            int gpuID = scopeID.gpuID().id;
            // Without a cluster, each CTA forms its own cluster (an implicit 1x1x1 cluster launch).
            int clusterID = scopeID.clusterID() != null ? scopeID.clusterID().id : IMPLICIT_CLUSTER + ctaID;
            // NB: the order of scopeIDs is important
            programBuilder.newScopedThread(Arch.PTX, threadScopeContext.threadId().id, gpuID, clusterID, ctaID);
            spaces.addThread(threadCount, gpuID, clusterID, ctaID);
            if (scopeID.gridID() != null) {
                if (scopeID.gridID().id >= Tag.PTX.GRIDS) {
                    throw new ParsingException("At most " + Tag.PTX.GRIDS + " grids");
                }
                grids.put(threadCount, scopeID.gridID().id);
            }
            threadCount++;
        }
        return null;
    }

    // The state space qualifiers of all loads, stores and atomic operations decide the space and the owner of the
    // locations they access, before any event is built.
    private void observeStateSpaces(InstructionListContext ctx) {
        for (InstructionRowContext row : ctx.instructionRow()) {
            for (int i = 0; i < threadCount; i++) {
                PtxInstructionContext instruction = row.instruction(i).ptxInstruction();
                if (instruction == null || instruction.Mnemonic() == null) {
                    continue;
                }
                PTXQualifiers q = PTXQualifiers.parse(instruction.Mnemonic().getText());
                if (!Set.of("ld", "ldu", "st", "atom", "red", "mbarrier", "ldmatrix", "stmatrix").contains(q.getName())) {
                    continue;
                }
                String space = q.takeOne(SPACES);
                if (q.getName().equals("mbarrier")) {
                    // mbarrier objects are in shared memory
                    space = "shared";
                }
                for (OperandContext operand : instruction.operand()) {
                    if (operand.location() != null) {
                        spaces.observe(operand.location().getText(), space, i);
                    }
                }
            }
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Instruction list (the program itself)
    @Override
    public Object visitConstant(ConstantContext ctx) {
        return ExpressionFactory.getInstance().parseValue(ctx.getText(), archType);
    }

    @Override
    public Object visitRegister(RegisterContext ctx) {
        return programBuilder.getOrNewRegister(mainThread, ctx.getText(), archType);
    }

    @Override
    public Object visitInstructionRow(InstructionRowContext ctx) {
        for (int i = 0; i < threadCount; i++) {
            mainThread = i;
            visitInstruction(ctx.instruction(i));
        }
        return null;
    }

    @Override
    public Object visitPtxInstruction(PtxInstructionContext ctx) {
        List<OperandContext> operands = ctx.operand();
        if (ctx.Load() != null) {
            // ld r, v puts a value into a register; ld r, x is a weak load
            Register register = (Register) ctx.register().accept(this);
            OperandContext operand = operands.get(0);
            if (operand.location() == null) {
                return append(List.of(EventFactory.newLocal(register, parseValues(operand).get(0))), ctx);
            }
            Slot slot = parseSingleSlot(PTXQualifiers.parse("ld"), operand, null, false);
            Event load = EventFactory.PTX.newLoad(register, slot.address(), Tag.PTX.WEAK);
            load.addTags(Tag.PTX.GEN);
            slot.addFootprint(load);
            return append(List.of(load), ctx);
        }
        PTXQualifiers qualifiers = PTXQualifiers.parse(ctx.Mnemonic().getText());
        if (qualifiers.getName().equals("mbarrier") && qualifiers.takeFirst("test_wait")) {
            return append(parseMbarrierWait(qualifiers, operands), ctx, false);
        }
        if (qualifiers.getName().equals("mbarrier") && qualifiers.takeFirst("try_wait")) {
            return append(parseMbarrierWait(qualifiers, operands), ctx, false);
        }
        List<Event> events = switch (qualifiers.getName()) {
            case "ld", "ldu", "tld", "suld", "cold" -> parseLoad(qualifiers, operands);
            case "st", "sust" -> qualifiers.takeFirst("async") ? parseAsyncStore(qualifiers, operands)
                    : parseStore(qualifiers, operands);
            case "atom", "suatom" -> parseAtom(qualifiers, operands);
            case "red", "sured" -> qualifiers.takeFirst("async") ? parseAsyncReduction(qualifiers, operands)
                    : parseRed(qualifiers, operands);
            case "fence", "membar" -> parseFence(qualifiers, operands);
            case "bar", "barrier" -> parseBarrier(qualifiers, operands);
            case "prefetch", "prefetchu", "applypriority" -> parseCacheControl(qualifiers, operands);
            case "createpolicy" -> parseCreatePolicy(qualifiers, operands);
            case "mbarrier" -> parseMbarrier(qualifiers, operands);
            case "multimem" -> parseMultimem(qualifiers, operands);
            case "wgmma" -> parseWgmma(qualifiers, operands);
            case "griddepcontrol" -> parseGridDependencyControl(qualifiers, operands);
            case "ldmatrix", "stmatrix" -> parseMatrixAccess(qualifiers, operands);
            case "tensormap" -> parseTensormap(qualifiers, operands);
            case "discard" -> parseDiscard(qualifiers, operands);
            case "cp" -> parseCopy(qualifiers, operands);
            default -> throw new ParsingException("Unsupported instruction " + qualifiers.getMnemonic());
        };
        if (events.stream().anyMatch(e -> e.hasTag(Tag.PTX.AOP))) {
            // The events of an asynchronous operation are told apart by a tag of their own, not by an instruction
            // boundary: Dartagnan's po does not order the events of one instruction, and the model needs their order.
            if (asyncOperationCount >= Tag.PTX.ASYNC_OPERATIONS) {
                throw qualifiers.error("Too many asynchronous operations");
            }
            final String operation = Tag.PTX.asyncOperation(asyncOperationCount++);
            events.stream().filter(e -> e.hasTag(Tag.PTX.AOP)).forEach(e -> e.addTags(operation));
            return append(events, ctx, false);
        }
        if (qualifiers.getName().equals("tensormap")) {
            return append(events, ctx, false);
        }
        return append(events, ctx);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Loads and stores

    private List<Event> parseLoad(PTXQualifiers q, List<OperandContext> operands) {
        final String proxy = switch (q.getName()) {
            case "tld" -> Tag.PTX.TEX;
            case "suld" -> Tag.PTX.SUR;
            case "cold" -> Tag.PTX.CON;
            default -> Tag.PTX.GEN;
        };
        final boolean unified = q.getName().equals("ldu");
        final boolean nonCoherent = q.take("nc");
        final String space = q.takeOne(SPACES);
        final Semantics sem = parseSemantics(q, unified ? Set.of() : LOAD_SEMS);
        if (nonCoherent && (!sem.mo.equals(Tag.PTX.WEAK) || !"global".equals(space))) {
            throw q.error("Non-coherent loads are weak loads from .global");
        }
        q.takeAll(PTXQualifiers.HINTS);
        final int width = parseVectorWidth(q);
        q.takeOne(PTXQualifiers.TYPES);
        q.checkConsumed();
        checkOperandCount(q, operands, 2 * width);
        final List<Event> events = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            OperandContext target = operands.get(i);
            List<Slot> slots = parseSlots(operands.get(width + i), space, false);
            List<Register> registers = parseTargetRegisters(q, target, slots.size());
            for (int j = 0; j < slots.size(); j++) {
                Event load = EventFactory.PTX.newLoad(registers.get(j), slots.get(j).address(), sem.mo);
                sem.addTags(load);
                // ld.global.nc and ldu read through the non-coherent texture path (M10, M11)
                load.addTags(nonCoherent || unified ? Tag.PTX.TEX : proxy);
                slots.get(j).addFootprint(load);
                events.add(load);
            }
        }
        return events;
    }

    private List<Event> parseStore(PTXQualifiers q, List<OperandContext> operands) {
        final String proxy = q.getName().equals("sust") ? Tag.PTX.SUR : Tag.PTX.GEN;
        final String space = q.takeOne(SPACES);
        final Semantics sem = parseSemantics(q, STORE_SEMS);
        q.takeAll(PTXQualifiers.HINTS);
        final int width = parseVectorWidth(q);
        q.takeOne(PTXQualifiers.TYPES);
        q.checkConsumed();
        checkOperandCount(q, operands, 2 * width);
        final List<Event> events = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            List<Slot> slots = parseSlots(operands.get(i), space, true);
            List<Expression> values = parseValues(operands.get(width + i));
            if (values.size() != 1 && values.size() != slots.size()) {
                throw q.error("Expected one value or one value per granule");
            }
            for (int j = 0; j < slots.size(); j++) {
                Expression value = values.get(values.size() == 1 ? 0 : j);
                Event store = EventFactory.PTX.newStore(slots.get(j).address(), value, sem.mo);
                sem.addTags(store);
                store.addTags(proxy);
                slots.get(j).addFootprint(store);
                events.add(store);
            }
        }
        return events;
    }

    // The memory order and scope of a load or store, with their defaults (.weak without scope).
    // .volatile is relaxed at system scope, .mmio must be written .mmio.relaxed.sys.
    private Semantics parseSemantics(PTXQualifiers q, Set<String> allowed) {
        final boolean mmio = allowed.contains("mmio") && q.take("mmio");
        final String sem = q.takeOne(allowed);
        final String scope = q.takeOne(PTXQualifiers.SCOPES);
        if (mmio) {
            if (!"relaxed".equals(sem) || !"sys".equals(scope)) {
                throw q.error(".mmio must be used with .relaxed.sys");
            }
            return new Semantics(Tag.PTX.RLX, Tag.PTX.SYS, Tag.PTX.MMIO);
        }
        if ("volatile".equals(sem)) {
            if (scope != null) {
                throw q.error(".volatile takes no scope");
            }
            return new Semantics(Tag.PTX.RLX, Tag.PTX.SYS, Tag.PTX.VOL);
        }
        if (sem == null || sem.equals("weak")) {
            if (scope != null) {
                throw q.error("A weak operation takes no scope");
            }
            return new Semantics(Tag.PTX.WEAK, null, null);
        }
        if (scope == null) {
            throw q.error("A strong operation needs a scope");
        }
        return new Semantics(parseMo(sem), parseScope(scope), null);
    }

    private record Semantics(String mo, String scope, String kind) {
        void addTags(Event event) {
            if (scope != null) {
                event.addTags(scope);
            }
            if (kind != null) {
                event.addTags(kind);
            }
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Atomic operations

    // atom{.sem}{.scope}{.space}{.vN}.op{.noftz}.type d, a, b{, c}: one atomic operation. With .vN (.global only),
    // N registers, N locations and N values, one atomic operation per element (22878-22880, M53). Floating-point
    // operations compute on the litmus values (M52).
    private List<Event> parseAtom(PTXQualifiers q, List<OperandContext> operands) {
        final String proxy = q.getName().equals("suatom") ? Tag.PTX.SUR : Tag.PTX.GEN;
        final String mo = parseMo(defaultIfNull(q.takeOne(RMW_SEMS), "relaxed"));
        final String scope = parseScope(defaultIfNull(q.takeOne(PTXQualifiers.SCOPES), "gpu"));
        final String space = q.takeOne(SPACES);
        final String op = parseRmwOperation(q, false);
        final int width = parseVectorWidth(q);
        q.takeAll(PTXQualifiers.HINTS);
        final String type = q.takeOne(PTXQualifiers.TYPES);
        q.checkConsumed();
        checkVectorRmw(q, width, op, space);
        final int values = op.equals("cas") ? 2 : 1;
        checkOperandCount(q, operands, (2 + values) * width);
        final List<Event> events = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            final Register result = parseTargetRegisters(q, operands.get(i), 1).get(0);
            final Slot slot = parseSingleSlot(q, operands.get(width + i), space, true);
            final Expression value = parseSingleValue(q, operands.get(2 * width + i));
            final Event atom = switch (op) {
                case "cas" -> EventFactory.PTX.newAtomCAS(slot.address(), result, value,
                        parseSingleValue(q, operands.get(3 * width + i)), mo, scope);
                case "exch" -> EventFactory.PTX.newAtomExch(slot.address(), result, value, mo, scope);
                default -> {
                    PTXAtomOp atomOp = (PTXAtomOp) EventFactory.PTX.newAtomOp(slot.address(), result, value,
                            parseOperator(op, type), mo, scope);
                    atomOp.setWrappingOp(parseWrappingOp(op));
                    yield atomOp;
                }
            };
            atom.addTags(proxy);
            slot.addFootprint(atom);
            events.add(atom);
        }
        return events;
    }

    // red{.sem}{.scope}{.space}{.vN}.op{.noftz}.type a, b: as atom, without a result
    private List<Event> parseRed(PTXQualifiers q, List<OperandContext> operands) {
        final String proxy = q.getName().equals("sured") ? Tag.PTX.SUR : Tag.PTX.GEN;
        final String mo = parseMo(defaultIfNull(q.takeOne(RMW_SEMS), "relaxed"));
        final String scope = parseScope(defaultIfNull(q.takeOne(PTXQualifiers.SCOPES), "gpu"));
        final String space = q.takeOne(SPACES);
        final String op = parseRmwOperation(q, true);
        final int width = parseVectorWidth(q);
        q.takeAll(PTXQualifiers.HINTS);
        final String type = q.takeOne(PTXQualifiers.TYPES);
        q.checkConsumed();
        checkVectorRmw(q, width, op, space);
        checkOperandCount(q, operands, 2 * width);
        final List<Event> events = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            final Slot slot = parseSingleSlot(q, operands.get(i), space, true);
            final Expression value = parseSingleValue(q, operands.get(width + i));
            final PTXRedOp red = (PTXRedOp) EventFactory.PTX.newRedOp(slot.address(), value,
                    parseOperator(op, type), mo, scope);
            red.setWrappingOp(parseWrappingOp(op));
            red.addTags(proxy);
            slot.addFootprint(red);
            events.add(red);
        }
        return events;
    }

    // The operation of atom or red; .noftz (required by the half-precision additions, 22902-22903) only with add
    private static String parseRmwOperation(PTXQualifiers q, boolean reduction) {
        final String op = q.takeOne(RMW_OPS);
        if (op == null || (reduction && (op.equals("cas") || op.equals("exch")))) {
            throw q.error("Missing or unsupported operation");
        }
        if (q.take("noftz") && !op.equals("add") && !op.equals("min") && !op.equals("max")) {
            throw q.error(".noftz needs a floating-point add, min or max");
        }
        return op;
    }

    // Vector atomic operations: .global only (22815-22817), arithmetic operations only (22831-22870)
    private static void checkVectorRmw(PTXQualifiers q, int width, String op, String space) {
        if (width == 1) {
            return;
        }
        if (space != null && !space.equals("global")) {
            throw q.error("Vector atomic operations need .global");
        }
        if (!Set.of("add", "min", "max").contains(op)) {
            throw q.error("Unsupported vector atomic operation ." + op);
        }
    }

    // inc and dec use ADD and SUB as their nominal operator, the event computes the wrapping operation.
    private static IntBinaryOp parseOperator(String op, String type) {
        final boolean signed = type != null && type.startsWith("s");
        return switch (op) {
            case "add", "inc" -> IntBinaryOp.ADD;
            case "sub", "dec" -> IntBinaryOp.SUB;
            case "mul" -> IntBinaryOp.MUL;
            case "div" -> IntBinaryOp.DIV;
            case "and" -> IntBinaryOp.AND;
            case "or" -> IntBinaryOp.OR;
            case "xor" -> IntBinaryOp.XOR;
            case "min" -> signed ? IntBinaryOp.SMIN : IntBinaryOp.UMIN;
            case "max" -> signed ? IntBinaryOp.SMAX : IntBinaryOp.UMAX;
            default -> throw new ParsingException("Unsupported atomic operation " + op);
        };
    }

    private static PTXWrappingOp parseWrappingOp(String op) {
        return switch (op) {
            case "inc" -> PTXWrappingOp.INC;
            case "dec" -> PTXWrappingOp.DEC;
            default -> null;
        };
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Fences and barriers

    private List<Event> parseFence(PTXQualifiers q, List<OperandContext> operands) {
        final Event fence = EventFactory.PTX.newFence(q.getMnemonic().toLowerCase());
        if (q.take("proxy")) {
            return parseProxyFence(q, operands, fence);
        }
        checkOperandCount(q, operands, 0);
        if (q.getName().equals("membar")) {
            // membar is a synonym for fence.sc; the levels cta, gl and sys are the scopes cta, gpu and sys
            final String level = q.takeOne(Set.of("cta", "gl", "sys"));
            q.checkConsumed();
            if (level == null) {
                throw q.error("Missing level");
            }
            fence.addTags(Tag.PTX.SC, level.equals("gl") ? Tag.PTX.GPU : parseScope(level), Tag.PTX.GEN);
            return List.of(fence);
        }
        final boolean mbarrierInit = q.take("mbarrier_init");
        final String restrict = q.takeOne(Set.of("sync_restrict::shared::cta", "sync_restrict::shared::cluster"));
        final String sem = defaultIfNull(q.takeOne(FENCE_SEMS), "acq_rel");
        final String scope = q.takeOne(PTXQualifiers.SCOPES);
        q.checkConsumed();
        if (scope == null) {
            throw q.error("Missing scope");
        }
        fence.addTags(parseMo(sem), parseScope(scope), Tag.PTX.GEN);
        if (mbarrierInit || restrict != null) {
            addRestriction(q, fence, mbarrierInit ? "mbarrier_init" : restrict, sem, scope);
        }
        return List.of(fence);
    }

    // .mbarrier_init and .sync_restrict::shared::cta need .release.cluster, .sync_restrict::shared::cluster needs
    // .acquire.cluster
    private static void addRestriction(PTXQualifiers q, Event fence, String restrict, String sem, String scope) {
        final boolean release = !restrict.equals("sync_restrict::shared::cluster");
        if (!sem.equals(release ? "release" : "acquire") || !scope.equals("cluster")) {
            throw q.error("." + restrict + " needs ." + (release ? "release" : "acquire") + ".cluster");
        }
        fence.addTags(switch (restrict) {
            case "mbarrier_init" -> Tag.PTX.RESTRICT_MBAR_INIT;
            case "sync_restrict::shared::cta" -> Tag.PTX.RESTRICT_CTA;
            default -> Tag.PTX.RESTRICT_CLUSTER;
        });
    }

    // fence.proxy.{alias,surface,texture,constant}, fence.proxy.async{.global,.shared::cta,.shared::cluster}
    // (bi-directional between the generic proxy and the named one), fence.proxy.tensormap::generic.release.scope
    // and fence.proxy.tensormap::generic.acquire.scope a, 128 (uni-directional, from generic to tensormap).
    private List<Event> parseProxyFence(PTXQualifiers q, List<OperandContext> operands, Event fence) {
        if (q.take("tensormap::generic")) {
            final String sem = q.takeOne(Set.of("release", "acquire"));
            final String scope = q.takeOne(PTXQualifiers.SCOPES);
            q.checkConsumed();
            if (sem == null || scope == null) {
                throw q.error("Expected .release or .acquire and a scope");
            }
            // The acquire fence names the tensor map it applies to; the address range is not modelled.
            checkOperandCount(q, operands, sem.equals("acquire") ? 2 : 0);
            fence.addTags(Tag.PTX.TMAP_FENCE, parseMo(sem), parseScope(scope), Tag.PTX.GEN);
            return List.of(fence);
        }
        checkOperandCount(q, operands, 0);
        if (q.take("async::generic")) {
            // fence.proxy.async::generic.{release,acquire}.sync_restrict::shared::{cta,cluster}.cluster: a restricted
            // release or acquire fence that also orders generic-proxy accesses before async-proxy accesses
            final String restrict = q.takeOne(Set.of("sync_restrict::shared::cta", "sync_restrict::shared::cluster"));
            final String sem = q.takeOne(Set.of("release", "acquire"));
            final String scope = q.takeOne(PTXQualifiers.SCOPES);
            q.checkConsumed();
            if (restrict == null || sem == null || scope == null) {
                throw q.error("Expected .release or .acquire, a .sync_restrict qualifier and a scope");
            }
            fence.addTags(parseMo(sem), parseScope(scope), Tag.PTX.GEN, Tag.PTX.ASYNC_FROM_GEN,
                    restrict.endsWith("cta") ? Tag.PTX.SHARED_CTA : Tag.PTX.SHARED);
            addRestriction(q, fence, restrict, sem, scope);
            return List.of(fence);
        }
        if (q.take("async")) {
            final String space = q.takeOne(Set.of("global", "shared::cta", "shared::cluster"));
            q.checkConsumed();
            fence.addTags(Tag.PTX.ASYNC);
            if (space != null) {
                fence.addTags(switch (space) {
                    case "global" -> Tag.PTX.GLOBAL;
                    case "shared::cta" -> Tag.PTX.SHARED_CTA;
                    default -> Tag.PTX.SHARED;
                });
            }
            return List.of(fence);
        }
        final String kind = q.takeOne(Set.of("alias", "constant", "surface", "texture"));
        q.checkConsumed();
        if (kind == null) {
            throw q.error("Missing proxy kind");
        }
        fence.addTags(switch (kind) {
            case "alias" -> Tag.PTX.ALIAS;
            case "constant" -> Tag.PTX.CON;
            case "surface" -> Tag.PTX.SUR;
            default -> Tag.PTX.TEX;
        });
        return List.of(fence);
    }

    private List<Event> parseBarrier(PTXQualifiers q, List<OperandContext> operands) {
        if (q.getName().equals("bar") && q.takeFirst("cta")) {
            return parseCtaBarrier(q, operands);
        }
        if (q.getName().equals("bar") && q.takeFirst("warp")) {
            return parseWarpBarrier(q, operands);
        }
        if (q.getName().equals("barrier") && q.takeFirst("cluster")) {
            return parseClusterBarrier(q, operands);
        }
        return parseNamedBarrier(q, operands);
    }

    // The dialect's bar.cta.sync id{, barrier{, count}} and bar.cta.arrive, whose first operand is an instance id
    private List<Event> parseCtaBarrier(PTXQualifiers q, List<OperandContext> operands) {
        final String mode = q.takeOne(Set.of("sync", "arrive"));
        q.checkConsumed();
        if (mode == null || operands.isEmpty() || operands.size() > 3) {
            throw q.error("Expected bar.cta.sync or bar.cta.arrive with one to three operands");
        }
        final String instanceId = operands.get(0).getText();
        final Event barrier;
        if (operands.size() == 1) {
            barrier = EventFactory.newControlBarrier(mode, instanceId, Tag.PTX.CTA);
        } else {
            Expression id = parseSingleValue(q, operands.get(1));
            Expression quorum = operands.size() > 2 ? parseSingleValue(q, operands.get(2)) : null;
            barrier = EventFactory.newNamedBarrier(mode, instanceId, Tag.PTX.CTA, id, quorum);
        }
        return withBarrierWait(barrier, mode);
    }

    // bar.sync a{, b}, bar.arrive a, b, barrier{.cta}.sync{.aligned} a{, b}, barrier{.cta}.arrive{.aligned} a, b:
    // a is the barrier (0 to 15), b the number of participating threads (all threads of the CTA without b).
    // The k-th use of a barrier by a thread belongs to the k-th instance of the barrier.
    private List<Event> parseNamedBarrier(PTXQualifiers q, List<OperandContext> operands) {
        if (q.getName().equals("barrier")) {
            q.take("cta");
            q.take("aligned");
        }
        final String mode = q.takeOne(Set.of("sync", "arrive"));
        q.checkConsumed();
        if (mode == null || operands.isEmpty() || operands.size() > 2 || (mode.equals("arrive") && operands.size() != 2)) {
            throw q.error("Expected sync with a barrier and an optional thread count, or arrive with both");
        }
        if (operands.get(0).constant().size() != 1) {
            throw q.error("The barrier must be a constant");
        }
        final int resource = Integer.parseInt(operands.get(0).getText());
        if (resource < 0 || resource > 15) {
            throw q.error("Barriers are numbered 0 to 15");
        }
        final Expression id = expressions.makeValue(resource, archType);
        final Expression quorum = operands.size() > 1 ? parseSingleValue(q, operands.get(1)) : null;
        final String instanceId = "bar" + resource + "#" + nextBarrierInstance("bar" + resource);
        final Event barrier = EventFactory.newNamedBarrier(mode, instanceId, Tag.PTX.CTA, id, quorum);
        return withBarrierWait(barrier, mode);
    }

    // An arrive only signals; the wait of a sync acts as a fence.acq_rel.cta of the thread after its own arrival
    // (22300-22304, M29)
    private List<Event> withBarrierWait(Event barrier, String mode) {
        if (mode.equals("arrive")) {
            barrier.addTags(Tag.PTX.ARRIVE);
            return List.of(barrier);
        }
        final Event fence = EventFactory.PTX.newFence("fence");
        fence.addTags(Tag.PTX.ACQ_REL, Tag.PTX.CTA, Tag.PTX.GEN);
        return List.of(barrier, fence);
    }

    // bar.warp.sync mask: bit k of the mask is thread Pk; the threads of the mask synchronize.
    private List<Event> parseWarpBarrier(PTXQualifiers q, List<OperandContext> operands) {
        if (!q.take("sync")) {
            throw new ParsingException("Unsupported instruction " + q.getMnemonic());
        }
        q.checkConsumed();
        checkOperandCount(q, operands, 1);
        if (operands.get(0).constant().size() != 1) {
            throw q.error("The member mask must be a constant");
        }
        // Bits of threads the test does not have stand for lanes without a litmus thread: -1 names every thread (M30)
        final long mask = Long.decode(operands.get(0).getText()) & ((1L << threadCount) - 1);
        if ((mask & (1L << mainThread)) == 0) {
            throw q.error("Thread P" + mainThread + " is not in the member mask");
        }
        for (int k = 0; k < 64; k++) {
            if ((mask & (1L << k)) != 0 && spaces.getCta(k) != spaces.getCta(mainThread)) {
                throw q.error("The member mask names P" + k + ", which is not a thread of the CTA of P" + mainThread);
            }
        }
        final String instanceId = "warp" + mask + "#" + nextBarrierInstance("warp" + mask);
        return List.of(EventFactory.newNamedBarrier("warp_sync", instanceId, Tag.PTX.CTA,
                expressions.makeValue(mask, archType), expressions.makeValue(Long.bitCount(mask), archType)));
    }

    // barrier.cluster.arrive{.release,.relaxed}{.aligned} and barrier.cluster.wait{.acquire}{.aligned}: the k-th
    // arrive and the k-th wait of every thread of the cluster belong to the k-th instance of the cluster barrier.
    private List<Event> parseClusterBarrier(PTXQualifiers q, List<OperandContext> operands) {
        checkOperandCount(q, operands, 0);
        final String mode = q.takeOne(Set.of("arrive", "wait"));
        q.take("aligned");
        final String sem = mode == null ? null
                : q.takeOne(mode.equals("arrive") ? Set.of("release", "relaxed") : Set.of("acquire"));
        q.checkConsumed();
        if (mode == null) {
            throw q.error("Expected barrier.cluster.arrive or barrier.cluster.wait");
        }
        final String instanceId = "cluster#" + nextBarrierInstance("cluster_" + mode);
        final Event barrier = EventFactory.newControlBarrier("cluster_" + mode, instanceId, Tag.PTX.CLUSTER);
        barrier.addTags(Tag.PTX.BAR_CLUSTER, Tag.PTX.CLUSTER);
        if (mode.equals("arrive")) {
            barrier.addTags(Tag.PTX.ARRIVE, parseMo(defaultIfNull(sem, "release")));
        } else {
            barrier.addTags(Tag.PTX.ACQ);
        }
        return List.of(barrier);
    }

    private int nextBarrierInstance(String kind) {
        return barrierCounts.computeIfAbsent(mainThread, t -> new HashMap<>()).merge(kind, 1, Integer::sum) - 1;
    }

    // ----------------------------------------------------------------------------------------------------------------
    // mbarrier objects

    // mbarrier.init b, count; mbarrier.inval b; mbarrier.arrive{.expect_tx}{.sem}{.scope}{.space} {r,} b{, count};
    // mbarrier.arrive_drop ...; mbarrier.expect_tx b, txCount; mbarrier.complete_tx b, txCount.
    private List<Event> parseMbarrier(PTXQualifiers q, List<OperandContext> operands) {
        final String operation = Set.of("init", "inval", "arrive", "arrive_drop", "expect_tx", "complete_tx").stream()
                .filter(q::takeFirst).findFirst().orElse(null);
        if (operation == null) {
            throw new ParsingException("Unsupported instruction " + q.getMnemonic());
        }
        final String space = q.takeOne(SPACES);
        q.take("b64");
        if (operation.equals("init") || operation.equals("inval")) {
            q.checkConsumed();
            checkOperandCount(q, operands, operation.equals("init") ? 2 : 1);
            final Slot slot = parseMbarrierSlot(q, operands.get(0), space, true);
            final Expression state = operation.equals("init")
                    ? PTXMbarrierState.initial(parseSingleValue(q, operands.get(1)), archType)
                    : expressions.makeZero(archType);
            final Event store = EventFactory.PTX.newStore(slot.address(), state, Tag.PTX.WEAK);
            store.addTags(Tag.PTX.GEN);
            if (operation.equals("init")) {
                store.addTags(Tag.PTX.MBAR_INIT);
            }
            slot.addFootprint(store);
            return List.of(store);
        }
        final boolean expectTx = operation.startsWith("arrive") && q.take("expect_tx");
        q.take("noComplete");
        final boolean arrive = operation.startsWith("arrive");
        final String sem = q.takeOne(arrive ? Set.of("release", "relaxed") : Set.of("relaxed"));
        final String scope = q.takeOne(Set.of("cta", "cluster"));
        q.checkConsumed();
        // An arrive may name the register that receives the state token
        final boolean hasToken = arrive && !operands.isEmpty() && operands.get(0).register() != null;
        final List<OperandContext> rest = hasToken ? operands.subList(1, operands.size()) : operands;
        if (rest.isEmpty() || rest.size() > 2 || (!arrive || expectTx) && rest.size() != 2) {
            throw q.error("Unexpected operands");
        }
        final Slot slot = parseMbarrierSlot(q, rest.get(0), space, false);
        final Expression one = expressions.makeOne(archType);
        final Expression zero = expressions.makeZero(archType);
        final Expression amount = rest.size() > 1 ? parseSingleValue(q, rest.get(1)) : one;
        final PTXMbarrierOp.Kind kind = switch (operation) {
            case "arrive" -> PTXMbarrierOp.Kind.ARRIVE;
            case "arrive_drop" -> PTXMbarrierOp.Kind.ARRIVE_DROP;
            case "expect_tx" -> PTXMbarrierOp.Kind.EXPECT_TX;
            default -> PTXMbarrierOp.Kind.COMPLETE_TX;
        };
        final Expression count = arrive ? (expectTx ? one : amount) : zero;
        final Expression txCount = (expectTx || !arrive) ? amount : zero;
        final Register old = newMbarrierStateRegister();
        final PTXMbarrierOp op = new PTXMbarrierOp(old, slot.address(), kind, count, txCount,
                parseMo(defaultIfNull(sem, arrive ? "release" : "relaxed")));
        op.addTags(Tag.PTX.GEN, parseScope(defaultIfNull(scope, "cta")));
        slot.addFootprint(op);
        final List<Event> events = new ArrayList<>(List.of(op));
        if (hasToken) {
            // the state token is the phase before the arrive-on
            events.add(EventFactory.newLocal(parseTargetRegisters(q, operands.get(0), 1).get(0),
                    PTXMbarrierState.phase(old)));
        }
        events.addAll(newValidityCheck(old));
        if (arrive) {
            events.addAll(newArriveCheck(old, count));
        }
        return events;
    }

    // Executes an undefined-behaviour event if the mbarrier state read is not a valid object
    private List<Event> newValidityCheck(Register state) {
        return newUndefinedUnless(PTXMbarrierState.isValid(state));
    }

    // Executes an undefined-behaviour event if an arrive-on takes the pending arrival count below zero: its valid
    // range starts at 0 (23989-23993)
    private List<Event> newArriveCheck(Register state, Expression count) {
        return newUndefinedUnless(PTXMbarrierState.canArrive(state, count));
    }

    private List<Event> newUndefinedUnless(Expression condition) {
        final Label defined = programBuilder.getOrCreateLabel(mainThread, "__mbarrier_valid" + waitCount++);
        return List.of(EventFactory.newJump(condition, defined),
                new GenericVisibleEvent("undefined_behaviour", Tag.PTX.UB), defined);
    }

    private Register newMbarrierStateRegister() {
        return programBuilder.getOrNewRegister(mainThread, "__mbarrier_old" + asyncCount++, archType);
    }

    // mbarrier.test_wait{.parity}{.sem}{.scope}{.space} r, b, p and mbarrier.try_wait ...: r = 1 iff the phase given by
    // the parity p (or by the state token p) completed. The wait is an acquire only when it returns True: it reads the
    // state with .acquire and finds the phase complete, or reads it with .relaxed and finds it incomplete.
    private List<Event> parseMbarrierWait(PTXQualifiers q, List<OperandContext> operands) {
        final boolean parity = q.take("parity");
        final String sem = defaultIfNull(q.takeOne(Set.of("acquire", "relaxed")), "acquire");
        final String scope = parseScope(defaultIfNull(q.takeOne(Set.of("cta", "cluster")), "cta"));
        final String space = q.takeOne(SPACES);
        q.take("b64");
        q.checkConsumed();
        if (operands.size() != 3 && operands.size() != 4) {
            throw q.error("Expected a result register, the mbarrier, a parity or state token and an optional time hint");
        }
        final Register result = parseTargetRegisters(q, operands.get(0), 1).get(0);
        final Slot slot = parseMbarrierSlot(q, operands.get(1), space, true);
        final Expression phase = parseSingleValue(q, operands.get(2));
        final int id = waitCount++;
        final Register state = programBuilder.getOrNewRegister(mainThread, "__mbarrier_state" + id, archType);
        final Expression complete = parity ? PTXMbarrierState.parityComplete(state, phase)
                : PTXMbarrierState.tokenComplete(state, phase);
        final List<Event> events = new ArrayList<>();
        final Label end = programBuilder.getOrCreateLabel(mainThread, "__mbarrier_wait_end" + id);
        if (sem.equals("acquire")) {
            final Label relaxed = programBuilder.getOrCreateLabel(mainThread, "__mbarrier_wait_relaxed" + id);
            events.add(EventFactory.newJump(programBuilder.newConstant(types.getBooleanType()), relaxed));
            events.add(newMbarrierRead(state, slot, Tag.PTX.ACQ, scope));
            events.add(EventFactory.newAssume(complete));
            events.add(EventFactory.newGoto(end));
            events.add(relaxed);
            events.add(newMbarrierRead(state, slot, Tag.PTX.RLX, scope));
            events.add(EventFactory.newAssume(expressions.makeNot(complete)));
        } else {
            events.add(newMbarrierRead(state, slot, Tag.PTX.RLX, scope));
        }
        events.add(end);
        events.add(EventFactory.newLocal(result,
                expressions.makeITE(complete, expressions.makeOne(archType), expressions.makeZero(archType))));
        events.addAll(newValidityCheck(state));
        return events;
    }

    private Event newMbarrierRead(Register state, Slot slot, String mo, String scope) {
        final Event load = EventFactory.PTX.newLoad(state, slot.address(), mo);
        load.addTags(Tag.PTX.GEN, scope);
        slot.addFootprint(load);
        return load;
    }

    // The mbarrier operand; init, inval and the waits only act on an object of the executing thread's CTA.
    private Slot parseMbarrierSlot(PTXQualifiers q, OperandContext operand, String space, boolean local) {
        final Slot slot = parseSingleSlot(q, operand, space, false);
        if (!spaces.isShared(operand.getText())) {
            throw q.error("An mbarrier object must be in shared memory");
        }
        if (local && !spaces.isInOwnCta(operand.getText(), mainThread)) {
            throw q.error("This operation needs an mbarrier object in the shared memory of the executing CTA");
        }
        return new Slot(slot.address(), withFootprint(slot.tags(), Tag.PTX.MBAR));
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Cache control: these instructions only move data between cache levels, they produce no memory event.

    private List<Event> parseCacheControl(PTXQualifiers q, List<OperandContext> operands) {
        switch (q.getName()) {
            case "prefetch" -> {
                if (q.take("tensormap")) {
                    // prefetch{.const,.param}.tensormap: for a later tensor copy (17583-17585)
                    q.takeOne(Set.of("const", "param"));
                } else {
                    q.takeOne(Set.of("global", "local"));
                    if (q.takeOne(Set.of("L1", "L2", "L2::evict_last", "L2::evict_normal")) == null) {
                        throw q.error("Missing level");
                    }
                }
            }
            case "prefetchu" -> {
                if (!q.take("L1")) {
                    throw q.error("Missing level");
                }
            }
            default -> {
                q.takeOne(SPACES);
                if (!q.take("L2::evict_normal")) {
                    throw q.error("Missing eviction priority");
                }
            }
        }
        q.checkConsumed();
        if (operands.isEmpty() || operands.get(0).location() == null || operands.size() > 2) {
            throw q.error("Expected an address operand");
        }
        return List.of();
    }

    // createpolicy writes an opaque cache policy into its register
    private List<Event> parseCreatePolicy(PTXQualifiers q, List<OperandContext> operands) {
        if (q.takeOne(Set.of("range", "fractional", "cvt")) == null) {
            throw q.error("Missing policy kind");
        }
        q.takeOne(SPACES);
        q.takeAll(Set.of("L2", "L2::evict_last", "L2::evict_normal", "L2::evict_first", "L2::evict_unchanged"));
        q.take("b64");
        q.checkConsumed();
        if (operands.isEmpty()) {
            throw q.error("Missing destination register");
        }
        final Register policy = parseTargetRegisters(q, operands.get(0), 1).get(0);
        return List.of(EventFactory.newLocal(policy, expressions.makeZero(archType)));
    }

    private List<Event> parseCopy(PTXQualifiers q, List<OperandContext> operands) {
        if (q.takeFirst("reduce")) {
            if (!q.takeFirst("async") || !q.takeFirst("bulk")) {
                throw new ParsingException("Unsupported instruction " + q.getMnemonic());
            }
            return parseBulkReduction(q, operands);
        }
        if (!q.takeFirst("async")) {
            throw new ParsingException("Unsupported instruction " + q.getMnemonic());
        }
        if (q.takeFirst("bulk")) {
            if (q.takeFirst("prefetch")) {
                // cp.async.bulk.prefetch{.tensor.Nd} is a hint to prefetch data into the L2 cache
                if (parseTensorShape(q)) {
                    q.takeOne(TENSOR_MODES);
                }
                q.take("L2");
                q.takeOne(SPACES);
                q.take("L2::cache_hint");
                q.checkConsumed();
                if (operands.isEmpty() || operands.get(0).location() == null || operands.size() > 3) {
                    throw q.error("Expected an address operand");
                }
                return List.of();
            }
            if (q.takeFirst("commit_group")) {
                q.checkConsumed();
                checkOperandCount(q, operands, 0);
                commitGroup("BAG");
                return List.of();
            }
            if (q.takeFirst("wait_group")) {
                final boolean readOnly = q.take("read");
                q.checkConsumed();
                return List.of(newAsyncWait("BAG", parseCount(q, operands), readOnly));
            }
            return parseBulkCopy(q, operands);
        }
        if (q.takeFirst("commit_group")) {
            q.checkConsumed();
            checkOperandCount(q, operands, 0);
            commitGroup("AG");
            return List.of();
        }
        if (q.takeFirst("wait_group")) {
            q.checkConsumed();
            return List.of(newAsyncWait("AG", parseCount(q, operands), false));
        }
        if (q.takeFirst("wait_all")) {
            q.checkConsumed();
            checkOperandCount(q, operands, 0);
            commitGroup("AG");
            return List.of(newAsyncWait("AG", 0, false));
        }
        if (q.takeFirst("mbarrier")) {
            return parseCpAsyncMbarrierArrive(q, operands);
        }
        return parseCpAsync(q, operands);
    }

    // cp.async.{ca,cg}.shared{::cta}.global d, g{, size}: a weak copy in the generic proxy, asynchronous to the thread
    private List<Event> parseCpAsync(PTXQualifiers q, List<OperandContext> operands) {
        q.takeOne(Set.of("ca", "cg"));
        final String dstSpace = q.takeOne(Set.of("shared", "shared::cta"));
        final boolean global = q.take("global");
        q.takeAll(PTXQualifiers.HINTS);
        q.checkConsumed();
        if (dstSpace == null || !global || operands.size() < 2 || operands.size() > 4) {
            throw q.error("Expected cp.async.{ca,cg}.shared.global d, g{, size}");
        }
        final Slot dst = parseSingleSlot(q, operands.get(0), dstSpace, true);
        final Slot src = parseSingleSlot(q, operands.get(1), "global", false);
        return newAsyncCopy(src, List.of(dst), Tag.PTX.GEN, List.of(Tag.PTX.CPASYNC, groupTag(q, "AG")));
    }

    // cp.async.mbarrier.arrive{.noinc}{.shared{::cta}} b: an asynchronous release arrive-on, after all prior cp.async
    // operations of the thread. Without .noinc the pending count is first incremented by 1: no net change (M43).
    private List<Event> parseCpAsyncMbarrierArrive(PTXQualifiers q, List<OperandContext> operands) {
        if (!q.takeFirst("arrive")) {
            throw new ParsingException("Unsupported instruction " + q.getMnemonic());
        }
        final boolean noinc = q.take("noinc");
        final String space = q.takeOne(Set.of("shared", "shared::cta"));
        q.take("b64");
        q.checkConsumed();
        checkOperandCount(q, operands, 1);
        final Slot slot = parseMbarrierSlot(q, operands.get(0), space, true);
        final Expression count = noinc ? expressions.makeOne(archType) : expressions.makeZero(archType);
        final Register old = newMbarrierStateRegister();
        final PTXMbarrierOp arrive = new PTXMbarrierOp(old, slot.address(), PTXMbarrierOp.Kind.ARRIVE,
                count, expressions.makeZero(archType), Tag.PTX.REL);
        arrive.addTags(Tag.PTX.GEN, Tag.PTX.CTA, Tag.PTX.AOP, Tag.PTX.CPASYNC_ARRIVE);
        slot.addFootprint(arrive);
        final List<Event> events = new ArrayList<>(List.of(arrive));
        events.addAll(newValidityCheck(old));
        events.addAll(newArriveCheck(old, count));
        return events;
    }

    // cp.async.bulk.{shared::cluster,shared::cta}.global.mbarrier::complete_tx::bytes{.multicast::cluster} d, g, b
    // cp.async.bulk.shared::cluster.shared::cta.mbarrier::complete_tx::bytes d, s, b (M55)
    // cp.async.bulk.global.shared::cta{.bulk_group} g, s
    // cp.async.bulk.tensor.Nd.shared::cluster.global.mode.mbarrier::complete_tx::bytes d, g, b, tm, r
    // cp.async.bulk.tensor.Nd.global.shared::cta.mode.bulk_group g, s, tm, r (M54)
    private List<Event> parseBulkCopy(PTXQualifiers q, List<OperandContext> operands) {
        final boolean tensor = parseTensorShape(q);
        final String dstSpace = q.takeFirst("shared::cluster") ? "shared::cluster"
                : q.takeFirst("shared::cta") ? "shared::cta"
                : q.takeFirst("global") ? "global" : null;
        if (dstSpace == null) {
            throw q.error("Expected a destination state space");
        }
        final String srcSpace = q.takeFirst("global") ? "global" : q.takeFirst("shared::cta") ? "shared::cta" : null;
        if (srcSpace == null || srcSpace.equals(dstSpace)
                || (dstSpace.equals("global") && !srcSpace.equals("shared::cta"))
                || (dstSpace.equals("shared::cta") && !srcSpace.equals("global"))) {
            throw q.error("Unsupported source state space");
        }
        if (tensor) {
            q.takeOne(TENSOR_MODES);
        }
        q.takeAll(PTXQualifiers.HINTS);
        if (dstSpace.equals("global")) {
            q.take("bulk_group");
            q.checkConsumed();
            if (tensor ? operands.size() != 4 : operands.size() < 2 || operands.size() > 3) {
                throw q.error(tensor ? "Expected cp.async.bulk.tensor.Nd.global.shared::cta g, s, tm, r"
                        : "Expected cp.async.bulk.global.shared::cta.bulk_group g, s{, size}");
            }
            final String group = groupTag(q, "BAG");
            final List<Event> events = new ArrayList<>();
            if (tensor) {
                final Event map = newTensorMapRead(q, operands.get(2), operands.get(3));
                map.addTags(group);
                events.add(map);
            }
            final Slot dst = parseSingleSlot(q, operands.get(0), "global", true);
            final Slot src = parseSingleSlot(q, operands.get(1), "shared::cta", false);
            events.addAll(newAsyncCopy(src, List.of(dst), Tag.PTX.ASYNC, List.of(group)));
            return events;
        }
        if (!q.take("mbarrier::complete_tx::bytes")) {
            throw q.error("Missing completion mechanism .mbarrier::complete_tx::bytes");
        }
        final boolean fromShared = srcSpace.equals("shared::cta");
        final boolean multicast = !fromShared && q.take("multicast::cluster");
        q.checkConsumed();
        if (fromShared && (tensor || !dstSpace.equals("shared::cluster"))) {
            throw q.error("Expected cp.async.bulk.shared::cluster.shared::cta d, s, b");
        }
        if (operands.size() != (tensor ? 5 : 3)) {
            throw q.error("Unexpected operands");
        }
        final List<OperandContext> dstOperands = multicastOperands(q, operands.get(0), multicast);
        final List<OperandContext> barrierOperands = multicastOperands(q, operands.get(2), multicast);
        if (dstOperands.size() != barrierOperands.size()) {
            throw q.error("Expected one mbarrier per destination");
        }
        final List<Event> events = new ArrayList<>();
        if (tensor) {
            events.add(newTensorMapRead(q, operands.get(3), operands.get(4)));
        }
        final Slot src = parseSingleSlot(q, operands.get(1), srcSpace, false);
        final List<Slot> dsts = new ArrayList<>();
        for (OperandContext dst : dstOperands) {
            dsts.add(parseSingleSlot(q, dst, "shared::cluster", true));
        }
        events.addAll(newAsyncCopy(src, dsts, Tag.PTX.ASYNC, List.of()));
        final List<Event> checks = new ArrayList<>();
        for (int i = 0; i < dstOperands.size(); i++) {
            final List<Event> completeTx = newCompleteTx(q, dstOperands.get(i), barrierOperands.get(i));
            events.add(completeTx.get(0));
            checks.addAll(completeTx.subList(1, completeTx.size()));
        }
        if (fromShared && spaces.isInOwnCta(dstOperands.get(0).getText(), mainThread)) {
            // The destination "has to be in the shared memory of a different CTA within the cluster" (19067-19068)
            checks.add(new GenericVisibleEvent("undefined_behaviour", Tag.PTX.UB_STATIC));
        }
        events.addAll(checks);
        return events;
    }

    // .tensor.Nd of a tensor copy, reduction or prefetch
    private static boolean parseTensorShape(PTXQualifiers q) {
        if (!q.takeFirst("tensor")) {
            return false;
        }
        if (q.takeOne(Set.of("1d", "2d", "3d", "4d", "5d")) == null) {
            throw q.error("Missing tensor dimension");
        }
        return true;
    }

    // A tensor copy reads its tensor map through the tensormap proxy (19591); the register gets the value it read
    private Event newTensorMapRead(PTXQualifiers q, OperandContext tensorMap, OperandContext target) {
        final Slot map = parseSingleSlot(q, tensorMap, null, false);
        final Register value = parseTargetRegisters(q, target, 1).get(0);
        final Event read = EventFactory.PTX.newLoad(value, map.address(), Tag.PTX.WEAK);
        read.addTags(Tag.PTX.TMAP, Tag.PTX.AOP);
        map.addFootprint(read);
        return read;
    }

    // cp.reduce.async.bulk.global.shared::cta.bulk_group.op.type g, s and
    // cp.reduce.async.bulk.tensor.Nd.global.shared::cta.op.mode.bulk_group g, s, tm, r (M54): reads s and performs a
    // relaxed .gpu reduction on g, both in the async proxy (19394-19398, 19956-19959)
    private List<Event> parseBulkReduction(PTXQualifiers q, List<OperandContext> operands) {
        final boolean tensor = parseTensorShape(q);
        if (!q.takeFirst("global") || !q.takeFirst("shared::cta")) {
            throw q.error("Expected cp.reduce.async.bulk.global.shared::cta");
        }
        if (tensor) {
            q.takeOne(TENSOR_MODES);
        }
        q.take("bulk_group");
        final String op = q.takeOne(RMW_OPS);
        final String type = q.takeOne(PTXQualifiers.TYPES);
        q.take("noftz");
        q.takeAll(PTXQualifiers.HINTS);
        q.checkConsumed();
        if (op == null || op.equals("cas") || op.equals("exch")
                || (tensor ? operands.size() != 4 : operands.size() < 2 || operands.size() > 3)) {
            throw q.error("Expected a reduction operation, the destination and the source"
                    + (tensor ? ", the tensor map and a register" : ""));
        }
        final String group = groupTag(q, "BAG");
        final List<Event> events = new ArrayList<>();
        if (tensor) {
            final Event map = newTensorMapRead(q, operands.get(2), operands.get(3));
            map.addTags(group);
            events.add(map);
        }
        final Slot dst = parseSingleSlot(q, operands.get(0), "global", true);
        final Slot src = parseSingleSlot(q, operands.get(1), "shared::cta", false);
        final Register value = newAsyncRegister();
        final Event read = EventFactory.PTX.newLoad(value, src.address(), Tag.PTX.WEAK);
        read.addTags(Tag.PTX.ASYNC, Tag.PTX.AOP, group);
        src.addFootprint(read);
        final PTXRedOp red = (PTXRedOp) EventFactory.PTX.newRedOp(dst.address(), value, parseOperator(op, type),
                Tag.PTX.RLX, Tag.PTX.GPU);
        red.setWrappingOp(parseWrappingOp(op));
        red.addTags(Tag.PTX.ASYNC, Tag.PTX.AOP, group);
        dst.addFootprint(red);
        events.add(read);
        events.add(red);
        return events;
    }

    // st.async{.weak}{.cluster}{.shared::cluster}{.mbarrier::complete_tx::bytes}.type d, v, b: a weak store into the
    // shared memory of another CTA, completed by a release complete-tx at .cluster scope on b.
    // st.async.release.{gpu,sys}.global.type f, v (sm_100): a release store (M45).
    private List<Event> parseAsyncStore(PTXQualifiers q, List<OperandContext> operands) {
        if (q.take("release")) {
            final String scope = defaultIfNull(q.takeOne(Set.of("gpu", "sys")), "sys");
            q.take("global");
            q.takeOne(PTXQualifiers.TYPES);
            q.checkConsumed();
            checkOperandCount(q, operands, 2);
            final Slot dst = parseSingleSlot(q, operands.get(0), "global", true);
            final Event store = EventFactory.PTX.newStore(dst.address(), parseSingleValue(q, operands.get(1)),
                    Tag.PTX.REL);
            store.addTags(Tag.PTX.GEN, parseScope(scope));
            dst.addFootprint(store);
            return List.of(store);
        }
        q.take("weak");
        q.take("cluster");
        q.take("shared::cluster");
        q.take("mbarrier::complete_tx::bytes");
        q.takeOne(PTXQualifiers.TYPES);
        q.checkConsumed();
        checkOperandCount(q, operands, 3);
        final Slot dst = parseSingleSlot(q, operands.get(0), "shared::cluster", true);
        final Event store = EventFactory.PTX.newStore(dst.address(), parseSingleValue(q, operands.get(1)),
                Tag.PTX.WEAK);
        store.addTags(Tag.PTX.GEN, Tag.PTX.AOP);
        dst.addFootprint(store);
        final List<Event> events = new ArrayList<>(List.of(store));
        events.addAll(newCompleteTx(q, operands.get(0), operands.get(2)));
        return events;
    }

    // red.async.relaxed.cluster{.shared::cluster}.mbarrier::complete_tx::bytes.op.type d, v, b: a relaxed .cluster
    // reduction on the shared memory of another CTA, completed by a release complete-tx at .cluster scope on b
    private List<Event> parseAsyncReduction(PTXQualifiers q, List<OperandContext> operands) {
        q.take("relaxed");
        q.take("cluster");
        q.take("shared::cluster");
        q.take("mbarrier::complete_tx::bytes");
        final String op = q.takeOne(RMW_OPS);
        final String type = q.takeOne(PTXQualifiers.TYPES);
        q.checkConsumed();
        checkOperandCount(q, operands, 3);
        if (op == null || op.equals("cas") || op.equals("exch")) {
            throw q.error("Missing or unsupported reduction operation");
        }
        final Slot dst = parseSingleSlot(q, operands.get(0), "shared::cluster", true);
        final PTXRedOp red = (PTXRedOp) EventFactory.PTX.newRedOp(dst.address(), parseSingleValue(q, operands.get(1)),
                parseOperator(op, type), Tag.PTX.RLX, Tag.PTX.CLUSTER);
        red.setWrappingOp(parseWrappingOp(op));
        red.addTags(Tag.PTX.GEN, Tag.PTX.AOP);
        dst.addFootprint(red);
        final List<Event> events = new ArrayList<>(List.of(red));
        events.addAll(newCompleteTx(q, operands.get(0), operands.get(2)));
        return events;
    }

    // A weak copy: one read of the source, one write per destination, all asynchronous
    private List<Event> newAsyncCopy(Slot src, List<Slot> dsts, String proxy, List<String> tags) {
        final Register value = newAsyncRegister();
        final List<Event> events = new ArrayList<>();
        final Event read = EventFactory.PTX.newLoad(value, src.address(), Tag.PTX.WEAK);
        read.addTags(proxy, Tag.PTX.AOP);
        read.addTags(tags);
        src.addFootprint(read);
        events.add(read);
        for (Slot dst : dsts) {
            final Event write = EventFactory.PTX.newStore(dst.address(), value, Tag.PTX.WEAK);
            write.addTags(proxy, Tag.PTX.AOP);
            write.addTags(tags);
            dst.addFootprint(write);
            events.add(write);
        }
        return events;
    }

    // The complete-tx of an asynchronous operation on the mbarrier b, which must be in the CTA of the destination d:
    // one transaction per written location, .release at .cluster scope (19150-19152, 17241-17244)
    private List<Event> newCompleteTx(PTXQualifiers q, OperandContext destination, OperandContext barrier) {
        final Slot slot = parseMbarrierSlot(q, barrier, "shared::cluster", false);
        final String dst = destination.location().getText();
        if (spaces.isShared(dst) && spaces.getOwner(dst) != spaces.getOwner(barrier.getText())) {
            throw q.error("The mbarrier must be in the CTA of the destination");
        }
        final Register old = newMbarrierStateRegister();
        final PTXMbarrierOp completeTx = new PTXMbarrierOp(old, slot.address(),
                PTXMbarrierOp.Kind.COMPLETE_TX, expressions.makeZero(archType), expressions.makeOne(archType),
                Tag.PTX.REL);
        completeTx.addTags(Tag.PTX.GEN, Tag.PTX.CLUSTER, Tag.PTX.AOP, Tag.PTX.COMPLETION);
        slot.addFootprint(completeTx);
        final List<Event> events = new ArrayList<>(List.of(completeTx));
        events.addAll(newValidityCheck(old));
        return events;
    }

    private List<OperandContext> multicastOperands(PTXQualifiers q, OperandContext operand, boolean multicast) {
        if (operand.multicastTargets() == null) {
            return List.of(operand);
        }
        if (!multicast) {
            throw q.error("Several destinations need .multicast::cluster");
        }
        final List<OperandContext> result = new ArrayList<>();
        for (LocationContext location : operand.multicastTargets().location()) {
            result.add(newLocationOperand(operand, location));
        }
        return result;
    }

    // An operand naming only the given location (for the parts of d0+d1)
    private static OperandContext newLocationOperand(OperandContext parent, LocationContext location) {
        final OperandContext operand = new OperandContext((ParserRuleContext) parent.getParent(), parent.invokingState);
        operand.addChild(location);
        return operand;
    }

    private Register newAsyncRegister() {
        return programBuilder.getOrNewRegister(mainThread, "__async" + asyncCount++, archType);
    }

    // The tag of the async-group that the next operation of this kind joins
    private String groupTag(PTXQualifiers q, String kind) {
        final int group = committedGroups.computeIfAbsent(mainThread, t -> new HashMap<>()).getOrDefault(kind, 0);
        if (group >= Tag.PTX.ASYNC_GROUPS) {
            throw q.error("Too many async-groups");
        }
        return Tag.PTX.asyncGroup(kind, group);
    }

    private void commitGroup(String kind) {
        committedGroups.computeIfAbsent(mainThread, t -> new HashMap<>()).merge(kind, 1, Integer::sum);
    }

    // A wait for all but the most recent `pending` committed async-groups of the kind (only their reads if readOnly)
    private Event newAsyncWait(String kind, int pending, boolean readOnly) {
        final int committed = committedGroups.computeIfAbsent(mainThread, t -> new HashMap<>()).getOrDefault(kind, 0);
        final Event wait = new GenericVisibleEvent("async_wait", Tag.PTX.COMPLETION);
        for (int k = 0; k < committed - pending; k++) {
            wait.addTags((readOnly ? "WAITR_" : "WAIT_") + Tag.PTX.asyncGroup(kind, k));
        }
        return wait;
    }

    private static int parseCount(PTXQualifiers q, List<OperandContext> operands) {
        checkOperandCount(q, operands, 1);
        if (operands.get(0).constant().size() != 1) {
            throw q.error("The number of pending groups must be a constant");
        }
        return Integer.parseInt(operands.get(0).getText());
    }

    // ----------------------------------------------------------------------------------------------------------------
    // multimem, wgmma, griddepcontrol, ldmatrix/stmatrix, tensormap, discard

    // multimem.st{.sem}{.scope}{.global}.type m, v; multimem.ld_reduce{.sem}{.scope}{.global}.op.type r, m;
    // multimem.red{.sem}{.scope}{.global}.op.type m, v: one operation per location of m, in no stated order (M47)
    private List<Event> parseMultimem(PTXQualifiers q, List<OperandContext> operands) {
        final String operation = Set.of("st", "ld_reduce", "red").stream().filter(q::takeFirst).findFirst()
                .orElseThrow(() -> new ParsingException("Unsupported instruction " + q.getMnemonic()));
        q.take("global");
        checkOperandCount(q, operands, 2);
        final OperandContext address = operands.get(operation.equals("ld_reduce") ? 1 : 0);
        final List<String> targets = multimem.get(address.getText());
        if (targets == null) {
            throw q.error(address.getText() + " is not a multimem address");
        }
        final List<Event> events = new ArrayList<>();
        if (operation.equals("red")) {
            final String mo = parseMo(defaultIfNull(q.takeOne(Set.of("relaxed", "release")), "relaxed"));
            final String scope = parseScope(defaultIfNull(q.takeOne(PTXQualifiers.SCOPES), "sys"));
            final String op = q.takeOne(RMW_OPS);
            final String type = q.takeOne(PTXQualifiers.TYPES);
            q.checkConsumed();
            final Expression value = parseSingleValue(q, operands.get(1));
            for (String target : targets) {
                final Slot slot = new Slot(targetAddress(target), spaces.access(target, "global", mainThread, true));
                final PTXRedOp red = (PTXRedOp) EventFactory.PTX.newRedOp(slot.address(), value,
                        parseOperator(op, type), mo, scope);
                red.setWrappingOp(parseWrappingOp(op));
                red.addTags(Tag.PTX.GEN);
                slot.addFootprint(red);
                events.add(red);
            }
            return events;
        }
        final Semantics sem = parseSemantics(q, operation.equals("st") ? Set.of("weak", "relaxed", "release")
                : Set.of("weak", "relaxed", "acquire"));
        if (operation.equals("st")) {
            q.takeOne(PTXQualifiers.TYPES);
            q.checkConsumed();
            final Expression value = parseSingleValue(q, operands.get(1));
            for (String target : targets) {
                final Slot slot = new Slot(targetAddress(target), spaces.access(target, "global", mainThread, true));
                final Event store = EventFactory.PTX.newStore(slot.address(), value, sem.mo());
                sem.addTags(store);
                store.addTags(Tag.PTX.GEN);
                slot.addFootprint(store);
                events.add(store);
            }
            return events;
        }
        final String op = q.takeOne(RMW_OPS);
        final String type = q.takeOne(PTXQualifiers.TYPES);
        q.checkConsumed();
        if (op == null) {
            throw q.error("Missing reduction operation");
        }
        final Register result = parseTargetRegisters(q, operands.get(0), 1).get(0);
        Expression reduced = null;
        for (String target : targets) {
            final Slot slot = new Slot(targetAddress(target), spaces.access(target, "global", mainThread, false));
            final Register value = newAsyncRegister();
            final Event load = EventFactory.PTX.newLoad(value, slot.address(), sem.mo());
            sem.addTags(load);
            load.addTags(Tag.PTX.GEN);
            slot.addFootprint(load);
            events.add(load);
            reduced = reduced == null ? value : expressions.makeIntBinary(reduced, parseOperator(op, type), value);
        }
        events.add(EventFactory.newLocal(result, reduced));
        return events;
    }

    private Expression targetAddress(String location) {
        return programBuilder.getOrNewVirtualMemoryObject(location);
    }

    // wgmma.fence, wgmma.mma_async r, a, c (reads the shared matrices a and c in the async proxy; r gets the value of
    // a), wgmma.commit_group, wgmma.wait_group N (M48)
    private List<Event> parseWgmma(PTXQualifiers q, List<OperandContext> operands) {
        final String operation = Set.of("fence", "mma_async", "commit_group", "wait_group").stream()
                .filter(q::takeFirst).findFirst()
                .orElseThrow(() -> new ParsingException("Unsupported instruction " + q.getMnemonic()));
        q.take("sync");
        q.take("aligned");
        switch (operation) {
            case "fence" -> {
                // Orders the earlier instructions of the thread before the next wgmma.mma_async operations (M48)
                q.checkConsumed();
                checkOperandCount(q, operands, 0);
                wgmmaFenced.add(mainThread);
                return List.of(new GenericVisibleEvent("wgmma_fence", Tag.PTX.WGMMA_FENCE));
            }
            case "commit_group" -> {
                q.checkConsumed();
                checkOperandCount(q, operands, 0);
                commitGroup("WG");
                return List.of();
            }
            case "wait_group" -> {
                q.checkConsumed();
                return List.of(newAsyncWait("WG", parseCount(q, operands), false));
            }
            default -> {
                q.takeAll(q.remaining());
                checkOperandCount(q, operands, 3);
                final String group = groupTag(q, "WG");
                final List<Event> events = new ArrayList<>();
                final Register value = parseTargetRegisters(q, operands.get(0), 1).get(0);
                for (int i = 1; i < 3; i++) {
                    final Slot slot = parseSingleSlot(q, operands.get(i), "shared::cta", false);
                    final Event read = EventFactory.PTX.newLoad(i == 1 ? value : newAsyncRegister(), slot.address(),
                            Tag.PTX.WEAK);
                    read.addTags(Tag.PTX.ASYNC, Tag.PTX.AOP, Tag.PTX.WGMMA, group);
                    slot.addFootprint(read);
                    events.add(read);
                }
                if (!wgmmaFenced.contains(mainThread)) {
                    // wgmma.fence must precede the first wgmma.mma_async (33424-33428)
                    events.add(new GenericVisibleEvent("undefined_behaviour", Tag.PTX.UB_STATIC));
                }
                return events;
            }
        }
    }

    // griddepcontrol.wait: every operation of the prerequisite grid is visible (M49); launch_dependents: no effect
    private List<Event> parseGridDependencyControl(PTXQualifiers q, List<OperandContext> operands) {
        final String action = q.takeOne(Set.of("wait", "launch_dependents"));
        q.checkConsumed();
        checkOperandCount(q, operands, 0);
        if (action == null) {
            throw q.error("Expected .wait or .launch_dependents");
        }
        if (action.equals("launch_dependents")) {
            return List.of();
        }
        return List.of(new GenericVisibleEvent("griddepcontrol_wait",
                Tag.PTX.gridWait(grids.getOrDefault(mainThread, 0))));
    }

    // ldmatrix.sync.aligned.shape.num{.trans}.shared{::cta}.type r, s and stmatrix ... s, v: weak accesses (29869,
    // 30063), one location per thread in the test dialect
    private List<Event> parseMatrixAccess(PTXQualifiers q, List<OperandContext> operands) {
        final boolean load = q.getName().equals("ldmatrix");
        final String space = q.takeOne(Set.of("shared", "shared::cta"));
        q.takeAll(q.remaining());
        checkOperandCount(q, operands, 2);
        if (load) {
            final Slot slot = parseSingleSlot(q, operands.get(1), space, false);
            final Event read = EventFactory.PTX.newLoad(parseTargetRegisters(q, operands.get(0), 1).get(0),
                    slot.address(), Tag.PTX.WEAK);
            read.addTags(Tag.PTX.GEN);
            slot.addFootprint(read);
            return List.of(read);
        }
        final Slot slot = parseSingleSlot(q, operands.get(0), space, true);
        final Event write = EventFactory.PTX.newStore(slot.address(), parseSingleValue(q, operands.get(1)),
                Tag.PTX.WEAK);
        write.addTags(Tag.PTX.GEN);
        slot.addFootprint(write);
        return List.of(write);
    }

    // tensormap.replace....global... tm, v: a weak generic write of the whole tensor map (20511-20512);
    // tensormap.cp_fenceproxy.global.shared::cta.tensormap::generic.release.scope.sync.aligned tm, s, 128: a weak
    // generic copy of s to tm followed by a release tensormap proxy fence (24994-24999)
    private List<Event> parseTensormap(PTXQualifiers q, List<OperandContext> operands) {
        if (q.takeFirst("replace")) {
            q.takeAll(q.remaining());
            checkOperandCount(q, operands, 2);
            final Slot slot = parseSingleSlot(q, operands.get(0), null, true);
            final Event write = EventFactory.PTX.newStore(slot.address(), parseSingleValue(q, operands.get(1)),
                    Tag.PTX.WEAK);
            write.addTags(Tag.PTX.GEN);
            slot.addFootprint(write);
            return List.of(write);
        }
        if (!q.takeFirst("cp_fenceproxy") || !q.take("global") || !q.take("shared::cta")
                || !q.take("tensormap::generic") || !q.take("release")) {
            throw new ParsingException("Unsupported instruction " + q.getMnemonic());
        }
        final String scope = q.takeOne(PTXQualifiers.SCOPES);
        q.take("sync");
        q.take("aligned");
        q.checkConsumed();
        checkOperandCount(q, operands, 3);
        if (scope == null) {
            throw q.error("Missing scope");
        }
        final Slot dst = parseSingleSlot(q, operands.get(0), "global", true);
        final Slot src = parseSingleSlot(q, operands.get(1), "shared::cta", false);
        final Register value = newAsyncRegister();
        final Event read = EventFactory.PTX.newLoad(value, src.address(), Tag.PTX.WEAK);
        read.addTags(Tag.PTX.GEN);
        src.addFootprint(read);
        final Event write = EventFactory.PTX.newStore(dst.address(), value, Tag.PTX.WEAK);
        write.addTags(Tag.PTX.GEN);
        dst.addFootprint(write);
        final Event fence = EventFactory.PTX.newFence("fence.proxy.tensormap::generic.release");
        fence.addTags(Tag.PTX.TMAP_FENCE, Tag.PTX.REL, parseScope(scope), Tag.PTX.GEN);
        return List.of(read, write, fence);
    }

    // discard.global.L2 a, 128: the location has an undetermined value afterwards (17688-17691, M50)
    private List<Event> parseDiscard(PTXQualifiers q, List<OperandContext> operands) {
        q.take("global");
        if (!q.take("L2")) {
            throw q.error("Missing level .L2");
        }
        q.checkConsumed();
        checkOperandCount(q, operands, 2);
        final Slot slot = parseSingleSlot(q, operands.get(0), "global", true);
        final Event write = EventFactory.PTX.newStore(slot.address(), programBuilder.newConstant(archType),
                Tag.PTX.WEAK);
        write.addTags(Tag.PTX.GEN);
        slot.addFootprint(write);
        return List.of(write);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Control flow and local computations

    @Override
    public Object visitLocalAdd(LocalAddContext ctx) {
        Register rd = (Register) ctx.register().accept(this);
        Expression lhs = (Expression) ctx.value(0).accept(this);
        Expression rhs = (Expression) ctx.value(1).accept(this);
        Expression exp = expressions.makeAdd(lhs, rhs);
        return append(List.of(EventFactory.newLocal(rd, exp)), ctx);
    }

    @Override
    public Object visitLocalSub(LocalSubContext ctx) {
        Register rd = (Register) ctx.register().accept(this);
        Expression lhs = (Expression) ctx.value(0).accept(this);
        Expression rhs = (Expression) ctx.value(1).accept(this);
        Expression exp = expressions.makeSub(lhs, rhs);
        return append(List.of(EventFactory.newLocal(rd, exp)), ctx);
    }

    @Override
    public Object visitLocalMul(LocalMulContext ctx) {
        Register rd = (Register) ctx.register().accept(this);
        Expression lhs = (Expression) ctx.value(0).accept(this);
        Expression rhs = (Expression) ctx.value(1).accept(this);
        Expression exp = expressions.makeMul(lhs, rhs);
        return append(List.of(EventFactory.newLocal(rd, exp)), ctx);
    }

    @Override
    public Object visitLocalDiv(LocalDivContext ctx) {
        Register rd = (Register) ctx.register().accept(this);
        Expression lhs = (Expression) ctx.value(0).accept(this);
        Expression rhs = (Expression) ctx.value(1).accept(this);
        Expression exp = expressions.makeDiv(lhs, rhs, true);
        return append(List.of(EventFactory.newLocal(rd, exp)), ctx);
    }

    @Override
    public Object visitLabel(LabelContext ctx) {
        return append(List.of(programBuilder.getOrCreateLabel(mainThread, ctx.Label().getText())), ctx);
    }

    @Override
    public Object visitBranchCond(BranchCondContext ctx) {
        Label label = programBuilder.getOrCreateLabel(mainThread, ctx.Label().getText());
        Expression lhs = (Expression) ctx.value(0).accept(this);
        Expression rhs = (Expression) ctx.value(1).accept(this);
        Expression expr = expressions.makeIntCmp(lhs, ctx.cond().op, rhs);
        return append(List.of(EventFactory.newJump(expr, label)), ctx);
    }

    @Override
    public Object visitJump(JumpContext ctx) {
        Label label = programBuilder.getOrCreateLabel(mainThread, ctx.Label().getText());
        return append(List.of(EventFactory.newGoto(label)), ctx);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Operands

    // One granule accessed by an instruction. The footprint tag tells whether the instruction accesses every
    // granule of a location with several granules (WHOLE) or only one of them (PART).
    private record Slot(Expression address, List<String> tags) {
        void addFootprint(Event event) {
            event.addTags(tags);
        }
    }

    // x accesses every granule of x, x[k] only the granule k. The space is the instruction's state space qualifier.
    private List<Slot> parseSlots(OperandContext operand, String space, boolean write) {
        if (operand.location() == null) {
            throw new ParsingException("Expected a location instead of " + operand.getText());
        }
        final String name = operand.location().getText();
        if (multimem.containsKey(name)) {
            throw new ParsingException("Only multimem instructions may access the multimem address " + name);
        }
        final List<String> spaceTags = spaces.access(name, space, mainThread, write);
        final MemoryObject base = programBuilder.getOrNewVirtualMemoryObject(name);
        final int count = granules.getOrDefault(name, 1);
        if (operand.index() != null) {
            final int index = parseIndex(operand.index());
            if (index >= count) {
                throw new ParsingException("Location " + name + " has no granule " + index);
            }
            return List.of(new Slot(granuleAddress(base, index), withFootprint(spaceTags, count > 1 ? Tag.PTX.PART : null)));
        }
        final List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            slots.add(new Slot(granuleAddress(base, i), withFootprint(spaceTags, count > 1 ? Tag.PTX.WHOLE : null)));
        }
        return slots;
    }

    private static List<String> withFootprint(List<String> tags, String footprint) {
        if (footprint == null) {
            return tags;
        }
        final List<String> all = new ArrayList<>(tags);
        all.add(footprint);
        return all;
    }

    private Slot parseSingleSlot(PTXQualifiers q, OperandContext operand, String space, boolean write) {
        final List<Slot> slots = parseSlots(operand, space, write);
        if (slots.size() != 1) {
            throw q.error("Expected a single granule instead of " + operand.getText());
        }
        return slots.get(0);
    }

    private Expression granuleAddress(MemoryObject base, int index) {
        if (index == 0) {
            return base;
        }
        return expressions.makeAdd(base, expressions.makeValue(index * granuleSize, (IntegerType) base.getType()));
    }

    // The registers written by an access to the given number of granules: r, or r[0], r[1], ... for several
    private List<Register> parseTargetRegisters(PTXQualifiers q, OperandContext operand, int count) {
        if (operand.register() == null) {
            throw q.error("Expected a register instead of " + operand.getText());
        }
        if (count == 1) {
            return List.of(programBuilder.getOrNewRegister(mainThread,
                    registerName(operand.register(), operand.index()), archType));
        }
        if (operand.index() != null) {
            throw q.error("Expected a whole register for an access to several granules");
        }
        final List<Register> registers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            registers.add(programBuilder.getOrNewRegister(mainThread,
                    operand.register().getText() + "[" + i + "]", archType));
        }
        return registers;
    }

    // A register, a constant, or one constant per granule (1:2)
    private List<Expression> parseValues(OperandContext operand) {
        if (operand.register() != null) {
            return List.of(programBuilder.getOrNewRegister(mainThread,
                    registerName(operand.register(), operand.index()), archType));
        }
        if (operand.location() != null || operand.fraction() != null) {
            throw new ParsingException("Expected an integer value instead of " + operand.getText());
        }
        return operand.constant().stream().map(c -> (Expression) c.accept(this)).toList();
    }

    private Expression parseSingleValue(PTXQualifiers q, OperandContext operand) {
        final List<Expression> values = parseValues(operand);
        if (values.size() != 1) {
            throw q.error("Expected a single value instead of " + operand.getText());
        }
        return values.get(0);
    }

    private static String registerName(RegisterContext register, IndexContext index) {
        return index == null ? register.getText() : register.getText() + "[" + parseIndex(index) + "]";
    }

    private static int parseIndex(IndexContext index) {
        return Integer.parseInt(index.DigitSequence().getText());
    }

    private static int parseVectorWidth(PTXQualifiers q) {
        final String vector = q.takeOne(PTXQualifiers.VECTORS);
        return vector == null ? 1 : Integer.parseInt(vector.substring(1));
    }

    private static void checkOperandCount(PTXQualifiers q, List<OperandContext> operands, int count) {
        if (operands.size() != count) {
            throw q.error("Expected " + count + " operands instead of " + operands.size());
        }
    }

    private static String parseMo(String sem) {
        return switch (sem) {
            case "weak" -> Tag.PTX.WEAK;
            case "relaxed" -> Tag.PTX.RLX;
            case "acquire" -> Tag.PTX.ACQ;
            case "release" -> Tag.PTX.REL;
            case "acq_rel" -> Tag.PTX.ACQ_REL;
            case "sc" -> Tag.PTX.SC;
            default -> throw new ParsingException("Unsupported memory order " + sem);
        };
    }

    private static String parseScope(String scope) {
        return switch (scope) {
            case "cta" -> Tag.PTX.CTA;
            case "cluster" -> Tag.PTX.CLUSTER;
            case "gpu" -> Tag.PTX.GPU;
            case "sys" -> Tag.PTX.SYS;
            default -> throw new ParsingException("Unsupported scope " + scope);
        };
    }

    private static String defaultIfNull(String value, String defaultValue) {
        return value != null ? value : defaultValue;
    }

    // Appends the events of one instruction; several memory events are grouped as one instruction (si)
    private Object append(List<Event> events, ParserRuleContext ctx) {
        return append(events, ctx, events.stream().filter(e -> e.hasTag(Tag.MEMORY)).count() > 1);
    }

    // The instruction boundary ends after the last memory event; local computations and control flow that follow
    // (e.g. the validity check of an mbarrier operation) stay outside it.
    private Object append(List<Event> events, ParserRuleContext ctx, boolean group) {
        final int line = ctx.getStart().getLine();
        if (!grids.isEmpty()) {
            final String grid = Tag.PTX.grid(grids.getOrDefault(mainThread, 0));
            events.forEach(e -> e.addTags(grid));
        }
        int last = events.size() - 1;
        while (group && last > 0 && !events.get(last).hasTag(Tag.MEMORY)) {
            last--;
        }
        InstructionBoundary begin = null;
        if (group) {
            begin = EventFactory.newInstructionBegin();
            programBuilder.addChild(mainThread, begin, line);
        }
        for (int i = 0; i < events.size(); i++) {
            programBuilder.addChild(mainThread, events.get(i), line);
            if (group && i == last) {
                programBuilder.addChild(mainThread, EventFactory.newInstructionEnd(begin), line);
            }
        }
        return null;
    }
}
