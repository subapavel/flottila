<?xml version="1.0" encoding="UTF-8"?>
<!-- Fixture family gamma: its id sorts after core, alpha's and beta's before it. -->
<xsl:stylesheet version="3.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:po="urn:flottila:mapping:po"
    exclude-result-prefixes="#all">

  <xsl:function name="po:cherry" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="upper-case($value)"/>
  </xsl:function>
</xsl:stylesheet>
