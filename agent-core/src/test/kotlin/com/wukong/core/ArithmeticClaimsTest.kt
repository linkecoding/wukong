package com.wukong.core

import org.junit.Assert.*
import org.junit.Test

class ArithmeticClaimsTest {
    @Test fun rejectsWrongGroupingClaimEvenWhenASecondEquationIsCorrect() {
        assertNotNull(ArithmeticClaims.mismatch("(128×3+59×2)×0.85 = 484.3；500−484.3 = 15.7"))
        assertNull(ArithmeticClaims.mismatch("128×3+59×2=502，再 502×0.85=426.7，500−426.7=73.3"))
    }
    @Test fun supportsSignsParenthesesFullWidthAndUnambiguousPercentages() {
        assertNull(ArithmeticClaims.mismatch("（１２８×３＋５９×２）×0.85＝426.7"))
        assertNull(ArithmeticClaims.mismatch("(128*3+59*2)*85%=426.7"))
        assertNull(ArithmeticClaims.mismatch("-3*(-2)=6，7÷20=35%"))
        assertNotNull(ArithmeticClaims.mismatch("500-426.7=-73.3"))
    }
    @Test fun toleratesRoundingButDoesNotPretendToValidateUnsupportedStatements() {
        assertNull(ArithmeticClaims.mismatch("1÷3=0.33"))
        assertNull(ArithmeticClaims.mismatch("100-10%=90"))
        assertNull(ArithmeticClaims.mismatch("Android 17；安全更新2026-07-05；00:03.69；预算426.7元"))
        assertNull(ArithmeticClaims.mismatch("sin(30)=0.5；sqrt (4*4)=4；sqrt(4*4)=4；1,000+500=1,500"))
        assertNull(ArithmeticClaims.mismatch("1+0.5=1,5"))
        assertNull(ArithmeticClaims.mismatch("x=1+2=3"))
        assertNotNull(ArithmeticClaims.mismatch("1/0=0"))
        assertNotNull(ArithmeticClaims.mismatch("1. （１２８×３＋５９×２）×0.85＝484.3"))
    }
}
