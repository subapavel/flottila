<?xml version="1.0" encoding="UTF-8"?>
<!-- Fixture family alpha: melon is not portable, so it has no po: function. -->
<xsl:stylesheet version="3.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:po="urn:flottila:mapping:po"
    exclude-result-prefixes="#all">

  <xsl:function name="po:apple" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:param name="replacement" as="xs:string"/>
    <xsl:sequence select="$replacement"/>
  </xsl:function>

  <xsl:function name="po:kiwia" as="array(item()*)">
    <xsl:param name="queue" as="array(item()*)"/>
    <xsl:sequence select="$queue"/>
  </xsl:function>
</xsl:stylesheet>
