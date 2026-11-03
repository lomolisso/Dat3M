package com.dat3m.dartagnan.program.event.arch.ptx;

import com.dat3m.dartagnan.expression.Expression;
import com.dat3m.dartagnan.expression.ExpressionVisitor;
import com.dat3m.dartagnan.expression.integers.IntBinaryOp;
import com.dat3m.dartagnan.program.Register;
import com.dat3m.dartagnan.program.event.EventVisitor;
import com.dat3m.dartagnan.program.event.common.RMWOpResultBase;

import java.util.Set;

/*
    An operation that changes the state of an mbarrier object (see PTXMbarrierState): an arrive-on (arrive), an
    arrive-on that also decrements the expected count (arrive_drop), an expect-tx or a complete-tx. The operand is
    the count of the arrive-on or of the complete-tx; txCount is the expectCount of an expect-tx (zero if there is
    none). The result register receives the phase before the operation (the state token of mbarrier.arrive).
 */
public class PTXMbarrierOp extends RMWOpResultBase {

    public enum Kind { ARRIVE, ARRIVE_DROP, EXPECT_TX, COMPLETE_TX }

    private final Kind kind;
    private Expression txCount;

    public PTXMbarrierOp(Register register, Expression address, Kind kind, Expression count, Expression txCount,
            String mo) {
        super(register, address, IntBinaryOp.ADD, count, mo);
        this.kind = kind;
        this.txCount = txCount;
    }

    protected PTXMbarrierOp(PTXMbarrierOp other) {
        super(other);
        this.kind = other.kind;
        this.txCount = other.txCount;
    }

    public Kind getKind() {
        return kind;
    }

    public Expression getTxCount() {
        return txCount;
    }

    @Override
    public Set<Register.Read> getRegisterReads() {
        return Register.collectRegisterReads(txCount, Register.UsageType.DATA, super.getRegisterReads());
    }

    @Override
    public void transformExpressions(ExpressionVisitor<? extends Expression> exprTransformer) {
        super.transformExpressions(exprTransformer);
        this.txCount = txCount.accept(exprTransformer);
    }

    @Override
    public String defaultString() {
        return String.format("%s = mbarrier_%s_%s(%s, %s, %s)", resultRegister, kind.name().toLowerCase(), mo,
                address, operand, txCount);
    }

    @Override
    public PTXMbarrierOp getCopy() {
        return new PTXMbarrierOp(this);
    }

    @Override
    public <T> T accept(EventVisitor<T> visitor) {
        return visitor.visitPtxMbarrierOp(this);
    }
}
