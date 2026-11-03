package com.dat3m.dartagnan.program.event.arch.ptx;

import com.dat3m.dartagnan.expression.Expression;
import com.dat3m.dartagnan.expression.integers.IntBinaryOp;
import com.dat3m.dartagnan.program.event.EventVisitor;
import com.dat3m.dartagnan.program.event.common.RMWOpBase;

public class PTXRedOp extends RMWOpBase {

    // If set, the operation is this wrapping operation instead of the binary operator.
    private PTXWrappingOp wrappingOp;

    public PTXRedOp(Expression address, Expression value, IntBinaryOp op, String mo) {
        super(address, op, value, mo);
    }

    protected PTXRedOp(PTXRedOp other) {
        super(other);
        this.wrappingOp = other.wrappingOp;
    }

    public PTXWrappingOp getWrappingOp() {
        return wrappingOp;
    }

    public void setWrappingOp(PTXWrappingOp wrappingOp) {
        this.wrappingOp = wrappingOp;
    }

    private String getOperationName() {
        return wrappingOp != null ? wrappingOp.getName() : operator.getName();
    }

    @Override
    public String defaultString() {
        return String.format("red_%s(%s, %s)", getOperationName(), operand, address);
    }

    @Override
    public PTXRedOp getCopy(){
        return new PTXRedOp(this);
    }

    @Override
    public <T> T accept(EventVisitor<T> visitor) {
        return visitor.visitPtxRedOp(this);
    }
}
