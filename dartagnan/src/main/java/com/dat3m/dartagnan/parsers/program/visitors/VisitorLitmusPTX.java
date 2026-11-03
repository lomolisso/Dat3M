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
    // Ids of the clusters of threads placed without one
    private static final int IMPLICIT_CLUSTER = 1_000_000;

    private static final Set<String> LOAD_SEMS = Set.of("weak", "relaxed", "acquire", "volatile", "mmio");
    private static final Set<String> STORE_SEMS = Set.of("weak", "relaxed", "release", "volatile", "mmio");
    private static final Set<String> RMW_SEMS = Set.of("relaxed", "acquire", "release", "acq_rel");
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
                if (!Set.of("ld", "ldu", "st", "atom", "red", "mbarrier").contains(q.getName())) {
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
            case "st", "sust" -> parseStore(qualifiers, operands);
            case "atom", "suatom" -> parseAtom(qualifiers, operands);
            case "red", "sured" -> parseRed(qualifiers, operands);
            case "fence", "membar" -> parseFence(qualifiers, operands);
            case "bar", "barrier" -> parseBarrier(qualifiers, operands);
            case "prefetch", "prefetchu", "applypriority" -> parseCacheControl(qualifiers, operands);
            case "createpolicy" -> parseCreatePolicy(qualifiers, operands);
            case "mbarrier" -> parseMbarrier(qualifiers, operands);
            case "cp" -> parseCopy(qualifiers, operands);
            default -> throw new ParsingException("Unsupported instruction " + qualifiers.getMnemonic());
        };
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
                // ld.global.nc reads through the non-coherent texture path
                load.addTags(nonCoherent ? Tag.PTX.TEX : proxy);
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

    private List<Event> parseAtom(PTXQualifiers q, List<OperandContext> operands) {
        final String proxy = q.getName().equals("suatom") ? Tag.PTX.SUR : Tag.PTX.GEN;
        final String mo = parseMo(defaultIfNull(q.takeOne(RMW_SEMS), "relaxed"));
        final String scope = parseScope(defaultIfNull(q.takeOne(PTXQualifiers.SCOPES), "gpu"));
        final String space = q.takeOne(SPACES);
        final String op = q.takeOne(RMW_OPS);
        if (op == null) {
            throw q.error("Missing operation");
        }
        q.takeAll(PTXQualifiers.HINTS);
        final String type = q.takeOne(PTXQualifiers.TYPES);
        q.checkConsumed();
        checkOperandCount(q, operands, op.equals("cas") ? 4 : 3);
        final Register result = parseTargetRegisters(q, operands.get(0), 1).get(0);
        final Slot slot = parseSingleSlot(q, operands.get(1), space, true);
        final Expression value = parseSingleValue(q, operands.get(2));
        final Event atom = switch (op) {
            case "cas" -> EventFactory.PTX.newAtomCAS(slot.address(), result, value,
                    parseSingleValue(q, operands.get(3)), mo, scope);
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
        return List.of(atom);
    }

    private List<Event> parseRed(PTXQualifiers q, List<OperandContext> operands) {
        final String proxy = q.getName().equals("sured") ? Tag.PTX.SUR : Tag.PTX.GEN;
        final String mo = parseMo(defaultIfNull(q.takeOne(RMW_SEMS), "relaxed"));
        final String scope = parseScope(defaultIfNull(q.takeOne(PTXQualifiers.SCOPES), "gpu"));
        final String space = q.takeOne(SPACES);
        final String op = q.takeOne(RMW_OPS);
        if (op == null || op.equals("cas") || op.equals("exch")) {
            throw q.error("Missing or unsupported reduction operation");
        }
        q.takeAll(PTXQualifiers.HINTS);
        final String type = q.takeOne(PTXQualifiers.TYPES);
        q.checkConsumed();
        checkOperandCount(q, operands, 2);
        final Slot slot = parseSingleSlot(q, operands.get(0), space, true);
        final Expression value = parseSingleValue(q, operands.get(1));
        final PTXRedOp red = (PTXRedOp) EventFactory.PTX.newRedOp(slot.address(), value, parseOperator(op, type),
                mo, scope);
        red.setWrappingOp(parseWrappingOp(op));
        red.addTags(proxy);
        slot.addFootprint(red);
        return List.of(red);
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
        final String sem = defaultIfNull(q.takeOne(FENCE_SEMS), "acq_rel");
        final String scope = q.takeOne(PTXQualifiers.SCOPES);
        q.checkConsumed();
        if (scope == null) {
            throw q.error("Missing scope");
        }
        fence.addTags(parseMo(sem), parseScope(scope), Tag.PTX.GEN);
        return List.of(fence);
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
        if (mode.equals("arrive")) {
            barrier.addTags(Tag.PTX.ARRIVE);
        }
        return List.of(barrier);
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
        if (mode.equals("arrive")) {
            barrier.addTags(Tag.PTX.ARRIVE);
        }
        return List.of(barrier);
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
        final long mask = Long.decode(operands.get(0).getText());
        if ((mask & (1L << mainThread)) == 0) {
            throw q.error("Thread P" + mainThread + " is not in the member mask");
        }
        for (int k = 0; k < 64; k++) {
            if ((mask & (1L << k)) != 0 && (k >= threadCount || spaces.getCta(k) != spaces.getCta(mainThread))) {
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
        final Register result = hasToken ? parseTargetRegisters(q, operands.get(0), 1).get(0)
                : programBuilder.getOrNewRegister(mainThread, "__mbarrier_token", archType);
        final PTXMbarrierOp op = new PTXMbarrierOp(result, slot.address(), kind, count, txCount,
                parseMo(defaultIfNull(sem, arrive ? "release" : "relaxed")));
        op.addTags(Tag.PTX.GEN, parseScope(defaultIfNull(scope, "cta")));
        slot.addFootprint(op);
        return List.of(op);
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
        final Label valid = programBuilder.getOrCreateLabel(mainThread, "__mbarrier_wait_valid" + id);
        events.add(EventFactory.newJump(PTXMbarrierState.isValid(state), valid));
        events.add(new GenericVisibleEvent("undefined_behaviour", Tag.PTX.UB));
        events.add(valid);
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
                q.takeOne(Set.of("global", "local"));
                if (q.takeOne(Set.of("L1", "L2", "L2::evict_last", "L2::evict_normal")) == null) {
                    throw q.error("Missing level");
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
        if (q.takeFirst("async") && q.takeFirst("bulk") && q.takeFirst("prefetch") && q.takeFirst("L2")) {
            // cp.async.bulk.prefetch is a hint to prefetch data into the L2 cache
            q.takeOne(SPACES);
            q.take("L2::cache_hint");
            q.checkConsumed();
            if (operands.isEmpty() || operands.get(0).location() == null || operands.size() > 3) {
                throw q.error("Expected an address operand");
            }
            return List.of();
        }
        throw new ParsingException("Unsupported instruction " + q.getMnemonic());
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
        if (operand.location() != null) {
            throw new ParsingException("Expected a value instead of " + operand.getText());
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

    private Object append(List<Event> events, ParserRuleContext ctx, boolean group) {
        final int line = ctx.getStart().getLine();
        InstructionBoundary begin = null;
        if (group) {
            begin = EventFactory.newInstructionBegin();
            programBuilder.addChild(mainThread, begin, line);
        }
        for (Event event : events) {
            programBuilder.addChild(mainThread, event, line);
        }
        if (group) {
            programBuilder.addChild(mainThread, EventFactory.newInstructionEnd(begin), line);
        }
        return null;
    }
}
