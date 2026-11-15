<?xml version="1.0" encoding="UTF-8"?>
<!-- The function family conversions (ADR-0025 "Families", F-15), #83: each is po:<catalogue name>,
     its args in FunCall order, and one that can raise a data error takes its box last and raises
     through po:data-error (ADR-0026), as the runtime's ConversionsFamily does. The build joins
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

  <!-- fixValues: the value of $key in $table, matched exactly. $table is the table's compact
       canonical JSON (ADR-0026 "Table params"), which parse-json reads into a map. A key the table
       lacks gives, by $missing, the key itself (key), $defaultValue (default), or po:FIXVALUES at
       $box (error), as the runtime's ConversionsFamily.fixValues does. The error branch is
       treated as an xs:string, so Saxon checks the result without atomizing it, which would add
       its own words to the message (observed, Saxon 12.5). -->
  <xsl:function name="po:fixValues" as="xs:string">
    <xsl:param name="key" as="xs:string"/>
    <xsl:param name="table" as="xs:string"/>
    <xsl:param name="missing" as="xs:string"/>
    <xsl:param name="defaultValue" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="
        let $value := parse-json($table)($key)
        return if (exists($value)) then $value
               else if ($missing eq 'key') then $key
               else if ($missing eq 'default') then $defaultValue
               else po:data-error(xs:QName('po:FIXVALUES'), $box,
                                  'fixValues: the key &quot;' || $key || '&quot; is not in the table')
                    treat as xs:string"/>
  </xsl:function>
</xsl:stylesheet>
