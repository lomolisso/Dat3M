grammar LitmusPTX;

import LitmusAssertions;

@header{
import com.dat3m.dartagnan.expression.integers.*;
}

main
    :    LitmusLanguage ~(LBrace)* variableDeclaratorList program variableList? assertionFilter? assertionList? EOF
    ;

variableDeclaratorList
    :   LBrace variableDeclarator? (Semi variableDeclarator)* Semi? RBrace Semi?
    ;

variableDeclarator
    :   variableDeclaratorLocation
    |   variableDeclaratorRegister
    |   variableDeclaratorRegisterLocation
    |   variableDeclaratorLocationLocation
    |   variableDeclaratorProxy
    ;

variableDeclaratorLocation
    :   location index? Equals constant
    ;

variableDeclaratorRegister
    :   threadId Colon register index? Equals constant
    ;

variableDeclaratorRegisterLocation
    :   threadId Colon register Equals Amp? location
    ;

variableDeclaratorLocationLocation
    :   location Equals Amp? location
    ;

variableDeclaratorProxy
    :   location At proxyType Aliases location
    ;

proxyType returns [String content]
    :   Generic {$content = "GEN";}
    |   Constant {$content = "CON";}
    |   Texture {$content = "TEX";}
    |   Surface {$content = "SUR";}
    ;

variableList
    :   Locations LBracket variable (Semi variable)* Semi? RBracket
    ;

variable
    :   location
    |   threadId Colon register
    ;

program
    :   threadDeclaratorList instructionList
    ;

threadDeclaratorList
    :   threadScope (Bar threadScope)* Semi
    ;

threadScope
    :   threadId At scopeID
    ;

scopeID
    :   CTA ctaID (Comma CLUSTER clusterID)? Comma GPU gpuID
    ;

instructionList
    :   (instructionRow) +
    ;

instructionRow
    :   instruction (Bar instruction)* Semi
    ;

instruction
    :
    |   ptxInstruction
    |   localInstruction
    |   branchCond
    |   jump
    |   label
    ;

// A PTX instruction: the mnemonic with its qualifiers (e.g. ld.relaxed.cluster.global.b32), then its operands.
// The qualifiers are interpreted by the visitor.
ptxInstruction
    :   Mnemonic (operand (Comma operand)*)?
    |   Load register Comma operand
    ;

operand
    :   register index?
    |   location index?
    |   constant (Colon constant)*
    ;

index
    :   LBracket DigitSequence RBracket
    ;

localInstruction
    :   localAdd
    |   localSub
    |   localMul
    |   localDiv
    ;

localAdd
    :   Add register Comma value Comma value
    ;

localSub
    :   Sub register Comma value Comma value
    ;

localMul
    :   Mul register Comma value Comma value
    ;

localDiv
    :   Div register Comma value Comma value
    ;

branchCond
    :   cond value Comma value Comma Label
    ;

jump
    :   Goto Label
    ;

label
    :   Label Colon
    ;

location
    :   Identifier
    ;

register
    :   Register
    ;

cond returns [IntCmpOp op]
    :   Beq {$op = IntCmpOp.EQ;}
    |   Bne {$op = IntCmpOp.NEQ;}
    |   Bge {$op = IntCmpOp.GTE;}
    |   Ble {$op = IntCmpOp.LTE;}
    |   Bgt {$op = IntCmpOp.GT;}
    |   Blt {$op = IntCmpOp.LT;}
    ;

assertionValue
    :   location index?
    |   threadId Colon register index?
    |   constant
    ;

ctaID returns [int id]
    :   t = DigitSequence {$id = Integer.parseInt($t.text);}
    ;

clusterID returns [int id]
    :   t = DigitSequence {$id = Integer.parseInt($t.text);}
    ;

gpuID returns [int id]
    :   t = DigitSequence {$id = Integer.parseInt($t.text);}
    ;

value
    :   constant
    |   register
    ;

Locations
    :   'locations'
    ;

Register
    :   'r' DigitSequence
    ;

Label
    :   'LC' DigitSequence
    ;

// An instruction name with at least one qualifier, e.g. fence.proxy.async::generic.release.gpu
Mnemonic
    :   [a-z] [a-z0-9_]* ('.' MnemonicPart ('::' MnemonicPart)*)+
    ;

fragment
MnemonicPart
    :   [a-zA-Z0-9_]+
    ;

Load    :   'ld';

CTA     :   'cta';
CLUSTER :   'cluster';
GPU     :   'gpu';

Add     :   'add';
Sub     :   'sub';
Mul     :   'mul';
Div     :   'div';

Generic     :   'generic';
Constant    :   'constant';
Surface     :   'surface';
Texture     :   'texture';
Aliases     :   'aliases';

Beq    :   'beq';
Bne    :   'bne';
Blt    :   'blt';
Bgt    :   'bgt';
Ble    :   'ble';
Bge    :   'bge';

Goto   :   'goto';

LitmusLanguage
    :   'PTX'
    |   'ptx'
    ;
