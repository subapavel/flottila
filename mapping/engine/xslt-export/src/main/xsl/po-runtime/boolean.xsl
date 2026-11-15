<?xml version="1.0" encoding="UTF-8"?>
<!-- The function family boolean (ADR-0025 "Families", F-15), #77 and #78: each is po:<catalogue
     name>, its args in FunCall order (the inputs, then the params), all single-value functions but
     the context function po:ifWithoutElse. Each value is cast as PO's Bool.toBoolean casts it, as
     PO 7.5 and CPI showed (F-44) and the runtime's BooleanFunctions.truth does: 1 and true in any
     case are true, 0 and false in any case false, and any other value, '' included, raises
     err:FORG0001 at the box, so every function but po:isNil takes its box last (ADR-0026). A truth
     value is written true or false.
     po:isNil is true for the empty string, because the runtime reads xsi:nil as the empty string
     (ADR-0008). The build joins core.xsl and the family modules, the families by id, into the
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

  <!-- The first of $values, in order, that is no boolean, or (). lower-case, like the runtime's
       toLowerCase(Locale.ROOT), maps no character but an ASCII capital to a letter of true or
       false. -->
  <xsl:function name="po:no-boolean" as="xs:string?">
    <xsl:param name="values" as="xs:string*"/>
    <xsl:sequence select="$values[not(. = ('1', '0') or lower-case(.) = ('true', 'false'))][1]"/>
  </xsl:function>

  <!-- $values cast, in order; the first that is no boolean raises err:FORG0001 at $box before any
       is returned, "Cannot cast '<value>' to boolean", as the runtime's BooleanFamily does. -->
  <xsl:function name="po:booleans" as="xs:boolean*">
    <xsl:param name="values" as="xs:string*"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:variable name="wrong" as="xs:string?" select="po:no-boolean($values)"/>
    <xsl:sequence select="if (exists($wrong))
        then po:data-error(xs:QName('err:FORG0001'), $box,
                           'Cannot cast ''' || $wrong || ''' to boolean')
        else $values ! (. eq '1' or lower-case(.) eq 'true')"/>
  </xsl:function>

  <!-- and: true when $a and $b are both true; both are cast, $a first. -->
  <xsl:function name="po:and" as="xs:string">
    <xsl:param name="a" as="xs:string"/>
    <xsl:param name="b" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="let $t := po:booleans(($a, $b), $box) return string($t[1] and $t[2])"/>
  </xsl:function>

  <!-- equals: whether $a and $b cast to the same truth value. -->
  <xsl:function name="po:equals" as="xs:string">
    <xsl:param name="a" as="xs:string"/>
    <xsl:param name="b" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="let $t := po:booleans(($a, $b), $box) return string($t[1] eq $t[2])"/>
  </xsl:function>

  <!-- if: $then when $condition casts to true, else $else. -->
  <xsl:function name="po:if" as="xs:string">
    <xsl:param name="condition" as="xs:string"/>
    <xsl:param name="then" as="xs:string"/>
    <xsl:param name="else" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="if (po:booleans($condition, $box)) then $then else $else"/>
  </xsl:function>

  <!-- ifWithoutElse, as the runtime's BooleanFunctions.ifWithoutElse does it (F-44): the result
       has as many contexts as $then; context i pairs context i of both, and it is empty where
       $condition has no context i; else the longer context sets the slot count and the shorter
       reuses its last value, and an empty one gives SUPPRESS at every slot of the other. SUPPRESS
       in either gives SUPPRESS; else the condition is cast: true gives the $then value, false
       SUPPRESS. Only a condition paired with a value is cast; the first, in slot order, that is no
       boolean raises err:FORG0001 at $box. $keepSuppressValues changes nothing PO 7.5 or CPI
       showed. -->
  <xsl:function name="po:ifWithoutElse" as="array(item()*)">
    <xsl:param name="condition" as="array(item()*)"/>
    <xsl:param name="then" as="array(item()*)"/>
    <xsl:param name="keepSuppressValues" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <!-- per context of $then, its slots as pairs [condition item or (), then item or ()] -->
    <xsl:variable name="contexts" as="array(*)*" select="
        for $i in 1 to array:size($then)
        return
          let $t := array:get($then, $i),
              $c := if ($i le array:size($condition)) then array:get($condition, $i) else (),
              $n := if ($i le array:size($condition)) then max((count($c), count($t))) else 0
          return array {for $j in 1 to $n
                        return [$c[min(($j, count($c)))], $t[min(($j, count($t)))]]}"/>
    <xsl:variable name="cast" as="xs:string*" select="
        for $context in $contexts, $pair in $context?*
        return (if ($pair(1) instance of xs:string and $pair(2) instance of xs:string)
                then $pair(1) else ())"/>
    <xsl:variable name="wrong" as="xs:string?" select="po:no-boolean($cast)"/>
    <xsl:sequence select="if (exists($wrong))
        then po:data-error(xs:QName('err:FORG0001'), $box,
                           'Cannot cast ''' || $wrong || ''' to boolean')
        else array:join(for $context in $contexts
             return [for $pair in $context?*
                     return if (not($pair(1) instance of xs:string
                                    and $pair(2) instance of xs:string)) then $po:SUPPRESS
                            else if (po:booleans($pair(1), $box)) then $pair(2)
                            else $po:SUPPRESS])"/>
  </xsl:function>

  <!-- isNil: true for the empty string. -->
  <xsl:function name="po:isNil" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="string($value eq '')"/>
  </xsl:function>

  <!-- not: true when $value casts to false. -->
  <xsl:function name="po:not" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="string(not(po:booleans($value, $box)))"/>
  </xsl:function>

  <!-- notEquals: whether $a and $b cast to different truth values. -->
  <xsl:function name="po:notEquals" as="xs:string">
    <xsl:param name="a" as="xs:string"/>
    <xsl:param name="b" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="let $t := po:booleans(($a, $b), $box) return string($t[1] ne $t[2])"/>
  </xsl:function>

  <!-- or: true when $a or $b is true; both are cast, $a first. -->
  <xsl:function name="po:or" as="xs:string">
    <xsl:param name="a" as="xs:string"/>
    <xsl:param name="b" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="let $t := po:booleans(($a, $b), $box) return string($t[1] or $t[2])"/>
  </xsl:function>
</xsl:stylesheet>
