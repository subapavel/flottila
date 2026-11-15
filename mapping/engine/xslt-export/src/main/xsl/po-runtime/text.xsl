<?xml version="1.0" encoding="UTF-8"?>
<!-- The function family text (ADR-0025 "Families", F-15), #76: each is po:<catalogue name>, its
     args in FunCall order (the inputs, then the params), all single-value functions. A string is
     counted, indexed and compared in code points, which is XPath's own reckoning and the runtime's
     TextFamily's; SAP PO counts UTF-16 units, the deviation in the catalogue. A truth value is
     written true or false, an index or count as an integer. po:substring reads its params in the
     xs:decimal lexical form, XML whitespace around it ignored, anything else is err:FORG0001 at
     its box, which it takes last (ADR-0026). The build joins core.xsl and the family modules, the
     families by id, into the helper module po-runtime.xsl, of which this comment is no part. A
     family module uses what core.xsl declares and nothing of another family, and holds core.xsl's
     root start tag, byte for byte. -->
<xsl:stylesheet version="3.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:array="http://www.w3.org/2005/xpath-functions/array"
    xmlns:po="urn:flottila:mapping:po"
    xmlns:err="http://www.w3.org/2005/xqt-errors"
    exclude-result-prefixes="#all">

  <!-- compare: -1, 0 or 1, the order of $a and $b in code points. -->
  <xsl:function name="po:compare" as="xs:string">
    <xsl:param name="a" as="xs:string"/>
    <xsl:param name="b" as="xs:string"/>
    <xsl:sequence select="string(compare($a, $b,
        'http://www.w3.org/2005/xpath-functions/collation/codepoint'))"/>
  </xsl:function>

  <!-- concat: $a, then $b. -->
  <xsl:function name="po:concat" as="xs:string">
    <xsl:param name="a" as="xs:string"/>
    <xsl:param name="b" as="xs:string"/>
    <xsl:sequence select="$a || $b"/>
  </xsl:function>

  <!-- endsWith: whether $value ends with $suffix; always true for an empty $suffix. -->
  <xsl:function name="po:endsWith" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:param name="suffix" as="xs:string"/>
    <xsl:sequence select="string(ends-with($value, $suffix,
        'http://www.w3.org/2005/xpath-functions/collation/codepoint'))"/>
  </xsl:function>

  <!-- equalsS: whether $a and $b are the same string, under the codepoint collation. -->
  <xsl:function name="po:equalsS" as="xs:string">
    <xsl:param name="a" as="xs:string"/>
    <xsl:param name="b" as="xs:string"/>
    <xsl:sequence select="string(compare($a, $b,
        'http://www.w3.org/2005/xpath-functions/collation/codepoint') eq 0)"/>
  </xsl:function>

  <!-- indexOf: the 0-based code point index of the first $search in $value, -1 for none, 0 for an
       empty $search. -->
  <xsl:function name="po:indexOf" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:param name="search" as="xs:string"/>
    <xsl:sequence select="string(if (contains($value, $search))
                                 then string-length(substring-before($value, $search)) else -1)"/>
  </xsl:function>

  <!-- lastIndexOf: the 0-based code point index of the last $search in $value, -1 for none, the
       length of $value for an empty $search. -->
  <xsl:function name="po:lastIndexOf" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:param name="search" as="xs:string"/>
    <xsl:variable name="found" as="xs:integer*" select="
        if ($search eq '') then string-length($value)
        else (for $i in 0 to string-length($value) - string-length($search)
              return if (substring($value, $i + 1, string-length($search)) eq $search)
                     then $i else ())"/>
    <xsl:sequence select="string(if (empty($found)) then -1 else max($found))"/>
  </xsl:function>

  <!-- length: the number of code points of $value. -->
  <xsl:function name="po:length" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="string(string-length($value))"/>
  </xsl:function>

  <!-- replaceString: every match of $search, taken literally, replaced by $replacement; an empty
       $search leaves $value as it is, as the runtime's TextFamily does. -->
  <xsl:function name="po:replaceString" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:param name="search" as="xs:string"/>
    <xsl:param name="replacement" as="xs:string"/>
    <xsl:sequence select="if ($search eq '') then $value
                          else replace($value, $search, $replacement, 'q')"/>
  </xsl:function>

  <!-- startsWith: whether $value starts with $prefix; always true for an empty $prefix. -->
  <xsl:function name="po:startsWith" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:param name="prefix" as="xs:string"/>
    <xsl:sequence select="string(starts-with($value, $prefix,
        'http://www.w3.org/2005/xpath-functions/collation/codepoint'))"/>
  </xsl:function>

  <!-- substring: the code points of $value from the 0-based position $start for $length, each
       rounded by XPath's round and the range cut to $value. Unless po:decimal reads $start and
       $length both as xs:decimal, it raises err:FORG0001 at $box. -->
  <xsl:function name="po:substring" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:param name="start" as="xs:string"/>
    <xsl:param name="length" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:variable name="at" as="xs:decimal?" select="po:decimal($start)"/>
    <xsl:variable name="span" as="xs:decimal?" select="po:decimal($length)"/>
    <xsl:sequence select="
        if (exists($at) and exists($span))
        then substring($value, $at + 1, $span)
        else po:data-error(xs:QName('err:FORG0001'), $box,
            'substring: the parameters start &quot;' || $start || '&quot; and length &quot;'
            || $length || '&quot; are not both xs:decimal')"/>
  </xsl:function>

  <!-- toLowerCase: $value in lower case, XPath's lower-case. -->
  <xsl:function name="po:toLowerCase" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="lower-case($value)"/>
  </xsl:function>

  <!-- toUpperCase: $value in upper case, XPath's upper-case. -->
  <xsl:function name="po:toUpperCase" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="upper-case($value)"/>
  </xsl:function>

  <!-- trim: $value without the XML whitespace (space, tab, line feed, return) at both ends. -->
  <xsl:function name="po:trim" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="replace($value, '^[ &#9;&#10;&#13;]+|[ &#9;&#10;&#13;]+$', '')"/>
  </xsl:function>
</xsl:stylesheet>
