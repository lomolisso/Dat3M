package com.dat3m.dartagnan.program.event.arch.ptx;

import com.dat3m.dartagnan.expression.Expression;
import com.dat3m.dartagnan.expression.ExpressionFactory;
import com.dat3m.dartagnan.expression.integers.IntBinaryOp;
import com.dat3m.dartagnan.expression.type.IntegerType;

/*
    The state of an mbarrier object, packed into the integer stored at its location:

        valid * B^4 + phase * B^3 + expected * B^2 + pending * B + (txCount + OFFSET)

    valid is 1 for an initialized object and 0 otherwise; phase is the current phase; expected and pending are the
    expected and pending arrival counts; txCount is the tx-count, stored with an offset so that it may be negative.
    Every field must stay in [0, B): counts and phases up to 63, tx-counts from -32 to 31.
 */
public final class PTXMbarrierState {

    // Small enough for the whole state to fit in 31 bits (Dartagnan converts some constants to int)
    private static final long B = 1L << 6;
    private static final long OFFSET = B / 2;
    private static final ExpressionFactory expressions = ExpressionFactory.getInstance();

    private PTXMbarrierState() {}

    // The state after mbarrier.init with the given count: phase 0, both counts set, tx-count 0.
    // A count outside the valid range is clamped into the field, so the object stays valid (M34): the Alloy model
    // keeps such a program defined too.
    public static Expression initial(Expression count, IntegerType type) {
        final Expression c = expressions.makeCast(count, type, true);
        final Expression clamped = expressions.makeITE(expressions.makeLT(c, zero(type), true), zero(type),
                expressions.makeITE(expressions.makeLT(c, value(B, type), true), c, value(B - 1, type)));
        return pack(one(type), zero(type), clamped, clamped, value(OFFSET, type), type);
    }

    public static long initial(long count) {
        final long c = Math.max(0, Math.min(B - 1, count));
        return B * B * B * B + c * B * B + c * B + OFFSET;
    }

    public static Expression isValid(Expression state) {
        final IntegerType type = (IntegerType) state.getType();
        return expressions.makeNEQ(expressions.makeIntBinary(state, IntBinaryOp.UDIV, value(B * B * B * B, type)),
                zero(type));
    }

    public static Expression phase(Expression state) {
        return field(state, 3);
    }

    // True iff an arrive-on with the given count keeps the pending arrival count of the state at or above zero
    public static Expression canArrive(Expression state, Expression count) {
        final IntegerType type = (IntegerType) state.getType();
        return expressions.makeLTE(expressions.makeCast(count, type, false), field(state, 1), false);
    }

    // waitComplete for the phase whose parity is given: true iff that phase is the preceding (completed) one
    public static Expression parityComplete(Expression state, Expression parity) {
        final IntegerType type = (IntegerType) state.getType();
        final Expression phaseParity = expressions.makeIntBinary(phase(state), IntBinaryOp.UREM, value(2, type));
        return expressions.makeNEQ(phaseParity, expressions.makeCast(parity, type, false));
    }

    // waitComplete for the phase whose state token (the phase an arrive read) is given
    public static Expression tokenComplete(Expression state, Expression token) {
        final IntegerType type = (IntegerType) state.getType();
        return expressions.makeNEQ(phase(state), expressions.makeCast(token, type, false));
    }

    // An expect-tx with expectCount, then an arrive-on with count (if arrive), after decrementing the expected count
    // by count (if drop); complete-tx with completeCount subtracts from the tx-count. The phase completes when both
    // the pending count and the tx-count are zero, after which the pending count is the expected count again.
    public static Expression next(Expression state, PTXMbarrierOp.Kind kind, Expression count, Expression txCount) {
        final IntegerType type = (IntegerType) state.getType();
        final Expression c = expressions.makeCast(count, type, false);
        final Expression tx = expressions.makeCast(txCount, type, false);
        final Expression expected = field(state, 2);
        final Expression pending = field(state, 1);
        final Expression txField = field(state, 0);
        final boolean arrive = kind == PTXMbarrierOp.Kind.ARRIVE || kind == PTXMbarrierOp.Kind.ARRIVE_DROP;
        final Expression newExpected = kind == PTXMbarrierOp.Kind.ARRIVE_DROP ? expressions.makeSub(expected, c) : expected;
        final Expression newPending = arrive ? expressions.makeSub(pending, c) : pending;
        final Expression newTx = kind == PTXMbarrierOp.Kind.COMPLETE_TX ? expressions.makeSub(txField, tx)
                : expressions.makeAdd(txField, tx);
        final Expression valid = expressions.makeIntBinary(state, IntBinaryOp.UDIV, value(B * B * B * B, type));
        if (kind == PTXMbarrierOp.Kind.EXPECT_TX) {
            return pack(valid, phase(state), newExpected, newPending, newTx, type);
        }
        final Expression complete = expressions.makeAnd(expressions.makeEQ(newPending, zero(type)),
                expressions.makeEQ(newTx, value(OFFSET, type)));
        final Expression nextPhase = expressions.makeITE(complete, expressions.makeAdd(phase(state), one(type)), phase(state));
        final Expression nextPending = expressions.makeITE(complete, newExpected, newPending);
        return pack(valid, nextPhase, newExpected, nextPending, newTx, type);
    }

    private static Expression field(Expression state, int index) {
        final IntegerType type = (IntegerType) state.getType();
        long divisor = 1;
        for (int i = 0; i < index; i++) {
            divisor *= B;
        }
        return expressions.makeIntBinary(expressions.makeIntBinary(state, IntBinaryOp.UDIV, value(divisor, type)),
                IntBinaryOp.UREM, value(B, type));
    }

    private static Expression pack(Expression valid, Expression phase, Expression expected, Expression pending,
            Expression tx, IntegerType type) {
        Expression result = tx;
        result = expressions.makeAdd(result, expressions.makeMul(expressions.makeCast(pending, type, false), value(B, type)));
        result = expressions.makeAdd(result, expressions.makeMul(expressions.makeCast(expected, type, false), value(B * B, type)));
        result = expressions.makeAdd(result, expressions.makeMul(phase, value(B * B * B, type)));
        return expressions.makeAdd(result, expressions.makeMul(valid, value(B * B * B * B, type)));
    }

    private static Expression value(long v, IntegerType type) {
        return expressions.makeValue(v, type);
    }

    private static Expression zero(IntegerType type) {
        return expressions.makeZero(type);
    }

    private static Expression one(IntegerType type) {
        return expressions.makeOne(type);
    }
}
