package com.dat3m.dartagnan.program.event.arch.ptx;

// The wrapping operations of atom and red, which have no IntBinaryOp counterpart:
//   inc(r, s) = (r >= s) ? 0 : r + 1
//   dec(r, s) = (r == 0 || r > s) ? s : r - 1
// Both compare unsigned values.
public enum PTXWrappingOp {
    INC, DEC;

    public String getName() {
        return switch (this) {
            case INC -> "inc";
            case DEC -> "dec";
        };
    }
}
