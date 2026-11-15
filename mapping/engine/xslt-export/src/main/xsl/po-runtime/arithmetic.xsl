<?xml version="1.0" encoding="UTF-8"?>
<!-- The function family arithmetic (ADR-0025 "Families", F-15), the functions of #72, #73 and
     #74: each is po:<catalogue name>, its args in FunCall order, and it takes its box last because
     it can raise a data error through po:data-error (ADR-0026). A number is read by core's po:decimal, the
     xs:decimal lexical form with XML whitespace around it ignored, anything else is err:FORG0001 at
     the box; a result is written in the XPath canonical form, as the runtime's XsDecimal.canonical
     writes it. The build joins core.xsl and the family modules, the families by id, into the
     helper module po-runtime.xsl, of which this comment is no part. A family module uses what
     core.xsl declares and nothing of another family, and holds core.xsl's root start tag, byte for
     byte. -->
<xsl:stylesheet version="3.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:array="http://www.w3.org/2005/xpath-functions/array"
    xmlns:po="urn:flottila:mapping:po"
    xmlns:err="http://www.w3.org/2005/xqt-errors"
    exclude-result-prefixes="#all">

  <!-- The xs:decimal that $text writes for the function $function, read by po:decimal, or
       err:FORG0001 at $box. -->
  <xsl:function name="po:math-number" as="xs:decimal">
    <xsl:param name="function" as="xs:string"/>
    <xsl:param name="text" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:variable name="number" as="xs:decimal?" select="po:decimal($text)"/>
    <xsl:sequence select="
        if (exists($number)) then $number
        else po:data-error(xs:QName('err:FORG0001'), $box,
                           $function || ': ''' || $text || ''' is no xs:decimal')"/>
  </xsl:function>

  <!-- The canonical form of the xs:decimal that the double $result equals exactly, for the
       function $function; err:FORG0001 at $box for NaN and the infinities, which no xs:decimal
       equals (a plain cast would raise FOCA0002). -->
  <xsl:function name="po:math-from-double" as="xs:string">
    <xsl:param name="function" as="xs:string"/>
    <xsl:param name="result" as="xs:double"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="
        if ($result castable as xs:decimal) then string(xs:decimal($result))
        else po:data-error(xs:QName('err:FORG0001'), $box,
                           $function || ': the result is ' || string($result)
                           || ', no xs:decimal')"/>
  </xsl:function>

  <!-- ceil: the least whole number not below $value. -->
  <xsl:function name="po:ceil" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="string(ceiling(po:math-number('ceil', $value, $box)))"/>
  </xsl:function>

  <!-- floor: the greatest whole number not above $value. -->
  <xsl:function name="po:floor" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="string(floor(po:math-number('floor', $value, $box)))"/>
  </xsl:function>

  <!-- power: $base to the power $exponent. Exact for a whole exponent from 0 to 1000, by repeated
       multiplication, which xs:decimal does exactly; any other exponent is math:pow of the two
       doubles, as the runtime's Math.pow (ADR-0005). -->
  <xsl:function name="po:power" as="xs:string">
    <xsl:param name="base" as="xs:string"/>
    <xsl:param name="exponent" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:variable name="b" as="xs:decimal" select="po:math-number('power', $base, $box)"/>
    <xsl:variable name="e" as="xs:decimal" select="po:math-number('power', $exponent, $box)"/>
    <xsl:sequence select="
        if ($e ge 0 and $e le 1000 and $e eq floor($e))
        then string(fold-left(1 to xs:integer($e), xs:decimal(1),
                              function($product as xs:decimal, $i as xs:integer) as xs:decimal {
                                $product * $b }))
        else po:math-from-double('power',
                                 math:pow(xs:double($b), xs:double($e)), $box)"
        xmlns:math="http://www.w3.org/2005/xpath-functions/math"/>
  </xsl:function>

  <!-- round: the nearest whole number, a half toward positive infinity, as fn:round. -->
  <xsl:function name="po:round" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="string(round(po:math-number('round', $value, $box)))"/>
  </xsl:function>

  <!-- sqr: $value times itself, exactly. -->
  <xsl:function name="po:sqr" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:variable name="x" as="xs:decimal" select="po:math-number('sqr', $value, $box)"/>
    <xsl:sequence select="string($x * $x)"/>
  </xsl:function>

  <!-- sqrt: math:sqrt of the double, cast back to xs:decimal (ADR-0005). -->
  <xsl:function name="po:sqrt" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="po:math-from-double('sqrt',
                              math:sqrt(xs:double(po:math-number('sqrt', $value, $box))), $box)"
        xmlns:math="http://www.w3.org/2005/xpath-functions/math"/>
  </xsl:function>

  <!-- $a div $b, as the runtime's ArithmeticFamily.quotient does by ADR-0005 as amended by F-20
       (Saxon-HE's rule): every quotient, one that terminates too, to max(18, scale of $a - scale
       of $b + 18) fractional digits, the last rounded half-down. Zero is err:FOAR0001 at $box. -->
  <xsl:function name="po:arithmetic-quotient" as="xs:string">
    <xsl:param name="a" as="xs:decimal"/>
    <xsl:param name="b" as="xs:decimal"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="
        if ($b eq 0)
        then po:data-error(xs:QName('err:FOAR0001'), $box, 'division by zero')
        else string($a div $b)"/>
  </xsl:function>

  <xsl:function name="po:add" as="xs:string">
    <xsl:param name="a" as="xs:string"/>
    <xsl:param name="b" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="string(po:math-number('add', $a, $box) + po:math-number('add', $b, $box))"/>
  </xsl:function>

  <xsl:function name="po:subtract" as="xs:string">
    <xsl:param name="a" as="xs:string"/>
    <xsl:param name="b" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="string(po:math-number('subtract', $a, $box) - po:math-number('subtract', $b, $box))"/>
  </xsl:function>

  <xsl:function name="po:multiply" as="xs:string">
    <xsl:param name="a" as="xs:string"/>
    <xsl:param name="b" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="string(po:math-number('multiply', $a, $box) * po:math-number('multiply', $b, $box))"/>
  </xsl:function>

  <xsl:function name="po:divide" as="xs:string">
    <xsl:param name="dividend" as="xs:string"/>
    <xsl:param name="divisor" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="po:arithmetic-quotient(po:math-number('divide', $dividend, $box),
                                                 po:math-number('divide', $divisor, $box), $box)"/>
  </xsl:function>

  <xsl:function name="po:equalsA" as="xs:string">
    <xsl:param name="a" as="xs:string"/>
    <xsl:param name="b" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="string(po:math-number('equalsA', $a, $box) eq po:math-number('equalsA', $b, $box))"/>
  </xsl:function>

  <xsl:function name="po:greater" as="xs:string">
    <xsl:param name="a" as="xs:string"/>
    <xsl:param name="b" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="string(po:math-number('greater', $a, $box) gt po:math-number('greater', $b, $box))"/>
  </xsl:function>

  <xsl:function name="po:less" as="xs:string">
    <xsl:param name="a" as="xs:string"/>
    <xsl:param name="b" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="string(po:math-number('less', $a, $box) lt po:math-number('less', $b, $box))"/>
  </xsl:function>

  <xsl:function name="po:max" as="xs:string">
    <xsl:param name="a" as="xs:string"/>
    <xsl:param name="b" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="string(max((po:math-number('max', $a, $box), po:math-number('max', $b, $box))))"/>
  </xsl:function>

  <xsl:function name="po:min" as="xs:string">
    <xsl:param name="a" as="xs:string"/>
    <xsl:param name="b" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="string(min((po:math-number('min', $a, $box), po:math-number('min', $b, $box))))"/>
  </xsl:function>

  <xsl:function name="po:abs" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="string(abs(po:math-number('abs', $value, $box)))"/>
  </xsl:function>

  <xsl:function name="po:neg" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="string(-po:math-number('neg', $value, $box))"/>
  </xsl:function>

  <!-- inv: 1 div the value, by the quotient rule (an assumed reading). -->
  <xsl:function name="po:inv" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="po:arithmetic-quotient(1.0, po:math-number('inv', $value, $box), $box)"/>
  </xsl:function>

  <xsl:function name="po:sign" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="
        let $v := po:math-number('sign', $value, $box)
        return if ($v gt 0) then '1' else if ($v lt 0) then '-1' else '0'"/>
  </xsl:function>

  <!-- formatNum (#74): the number $value formatted with the XPath picture the DecimalFormat
       pattern $pattern compiles to in core.xsl's formatting core (ADR-0003), as the runtime's
       ArithmeticFamily.formatNum does. -->
  <xsl:function name="po:formatNum" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:param name="pattern" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="format-number(po:math-number('formatNum', $value, $box),
                                        po:number-picture($pattern))"/>
  </xsl:function>
</xsl:stylesheet>
