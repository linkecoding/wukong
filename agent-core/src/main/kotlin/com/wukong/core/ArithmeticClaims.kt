package com.wukong.core

import java.math.BigDecimal
import java.math.MathContext

/** Reject demonstrably inconsistent basic equations; this does not interpret natural-language goals. */
object ArithmeticClaims {
    private val equation=Regex("([0-9０-９.,+＋\\-－−×÷*＊/／%％()（）\\s]+)[=＝]\\s*([+\\-−]?[0-9０-９]+(?:,[0-9０-９]{3})*(?:[.．][0-9０-９]+)?)([%％]?)")
    fun mismatch(summary:String):String? {
        for(match in equation.findAll(summary)) {
            val leading=match.groupValues[1].indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val before=summary.getOrNull(match.range.first+leading-1)
            val after=summary.getOrNull(match.range.last+1)
            if(before in ('a'..'z')||before in ('A'..'Z')||after in ('a'..'z')||after in ('A'..'Z')||
                after==','&&summary.getOrNull(match.range.last+2)?.isDigit()==true)continue
            val expression=normalize(match.groupValues[1]).trim().trimStart(',').trim().replace(Regex("^\\d+[.)、]\\s+"),"")
            if(expression.startsWith("(")&&Regex("[A-Za-z_][A-Za-z_0-9]*\\s*$").containsMatchIn(summary.substring(0,match.range.first+leading)))continue
            if(!Regex("[0-9)%]\\s*[+\\-*/]").containsMatchIn(expression)||Regex("\\d{4}-\\d{1,2}-\\d{1,2}").matches(expression))continue
            val parsed=try { Parser(expression).parse() } catch(_:ArithmeticException) {
                return "算式 $expression 不能得到有限数值，请核对实际输入和结果说明。"
            } catch(_:IllegalArgumentException) { continue }
            if(parsed.ambiguousPercent)continue // e.g. 100-10% has calculator-dependent commercial semantics.
            val literal=normalize(match.groupValues[2]);val claimed=runCatching { BigDecimal(literal) }.getOrNull() ?: continue
            val percent=match.groupValues[3].isNotBlank()
            val actual=if(percent)claimed.movePointLeft(2) else claimed
            val places=literal.substringAfter('.',"").length+(if(percent)2 else 0)
            val tolerance=BigDecimal.ONE.movePointLeft(places).divide(BigDecimal(2)).max(BigDecimal("0.00000001"))
            if(parsed.value.subtract(actual).abs()>tolerance)
                return "算式 $expression 的计算值应为 ${parsed.value.stripTrailingZeros().toPlainString()}，但结果说明写成 ${match.groupValues[2]}${match.groupValues[3]}。请核对实际输入的算式、界面结果和用户要求后再结束。"
        }
        return null
    }
    private fun normalize(value:String)=buildString {
        value.forEach { c -> append(when(c) {
            in '０'..'９' -> '0'+(c-'０');'×','＊'->'*';'÷','／'->'/';'−','－'->'-';'＋'->'+';'（'->'(';'）'->')';'％'->'%';'．'->'.';else->c
        }) }
    }.replace(Regex("(?<=\\d),(?=\\d{3}(\\D|$))"),"")
    private data class Number(val value:BigDecimal,val percent:Boolean=false,val ambiguousPercent:Boolean=false)
    private class Parser(private val text:String) {
        private var index=0;private var depth=0
        private val context=MathContext.DECIMAL64
        fun parse():Number { require(text.length<=300);val value=sum();space();require(index==text.length);return value }
        private fun space() { while(index<text.length&&text[index].isWhitespace())index++ }
        private fun take(c:Char):Boolean { space();return if(index<text.length&&text[index]==c) { index++;true } else false }
        private fun sum():Number {
            var left=product()
            while(true) {
                val sign=when { take('+')->1;take('-')->-1;else->return left }
                val right=product()
                left=Number(if(sign==1)left.value.add(right.value,context) else left.value.subtract(right.value,context),left.percent||right.percent,left.ambiguousPercent||right.ambiguousPercent||right.percent)
            }
        }
        private fun product():Number {
            var left=atom()
            while(true) {
                val multiply=when { take('*')->true;take('/')->false;else->return left }
                val right=atom()
                left=Number(if(multiply)left.value.multiply(right.value,context) else left.value.divide(right.value,context),left.percent||right.percent,left.ambiguousPercent||right.ambiguousPercent)
            }
        }
        private fun atom():Number {
            require(++depth<=40)
            try {
                if(take('+'))return atom()
                if(take('-'))return atom().let { it.copy(value=it.value.negate()) }
                var value=if(take('('))sum().also { require(take(')')) } else {
                    space();val start=index
                    while(index<text.length&&(text[index].isDigit()||text[index]=='.'))index++
                    require(index>start);Number(BigDecimal(text.substring(start,index),context))
                }
                if(take('%'))value=value.copy(value=value.value.movePointLeft(2),percent=true)
                return value
            } finally { depth-- }
        }
    }
}
