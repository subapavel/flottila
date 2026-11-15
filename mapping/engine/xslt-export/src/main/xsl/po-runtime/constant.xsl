<?xml version="1.0" encoding="UTF-8"?>
<!-- The function family constant (ADR-0025 "Families", F-15), #79 and #83: each is po:<catalogue name>,
     its args in FunCall order, and one that can raise a data error takes its box last and raises
     through po:data-error (ADR-0026), as the runtime's ConstantFamily does. The build joins
     core.xsl and the family modules, the families by id, into the helper module po-runtime.xsl,
     of which this comment is no part. A family module uses what core.xsl declares and nothing of
     another family, and holds core.xsl's root start tag, byte for byte. -->
<xsl:stylesheet version="3.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:array="http://www.w3.org/2005/xpath-functions/array"
    xmlns:po="urn:flottila:mapping:po"
    xmlns:err="http://www.w3.org/2005/xqt-errors"
    exclude-result-prefixes="#all">

  <!-- copyValue: a generating block (#217, F-46), one context holding the slot of $values at
       $position, counted from 0 across all its contexts, SUPPRESS included, or one empty context
       when $values has fewer slots, as the runtime's ConstantFunctions.copyValue does. Unless
       $position is an xs:nonNegativeInteger, XML whitespace around it ignored, it raises
       err:FORG0001 at $box before it reads $values, as the runtime's binding does. -->
  <xsl:function name="po:copyValue" as="array(item()*)">
    <xsl:param name="values" as="array(item()*)"/>
    <xsl:param name="position" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="
        if (not($position castable as xs:nonNegativeInteger))
        then po:data-error(xs:QName('err:FORG0001'), $box,
                           'copyValue: the position &quot;' || $position
                           || '&quot; is no xs:nonNegativeInteger')
        else let $at := xs:integer(xs:nonNegativeInteger($position)) + 1
             return [($values?*)[$at]]"/>
  </xsl:function>

  <!-- constant: its value, a generating function (ADR-0006 since #83), as the runtime's
       ConstantFamily.constant gives it. It cannot raise, so it takes no box. -->
  <xsl:function name="po:constant" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="$value"/>
  </xsl:function>
</xsl:stylesheet>
