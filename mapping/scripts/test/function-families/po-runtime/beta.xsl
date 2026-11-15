<?xml version="1.0" encoding="UTF-8"?>
<!-- Fixture family beta, with a helper function of its own, po:banana-context. -->
<xsl:stylesheet version="3.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:po="urn:flottila:mapping:po"
    exclude-result-prefixes="#all">

  <xsl:function name="po:banana" as="array(item()*)">
    <xsl:param name="queue" as="array(item()*)"/>
    <xsl:param name="mode" as="xs:string"/>
    <xsl:param name="label" as="xs:string"/>
    <xsl:sequence select="po:banana-context($queue, $mode)"/>
  </xsl:function>

  <xsl:function name="po:banana-context" as="array(item()*)">
    <xsl:param name="queue" as="array(item()*)"/>
    <xsl:param name="mode" as="xs:string"/>
    <xsl:sequence select="if ($mode eq 'eachValue') then $queue else [$po:SUPPRESS]"/>
  </xsl:function>

  <xsl:function name="po:kiwiB" as="xs:string">
    <xsl:param name="value" as="xs:string"/>
    <xsl:sequence select="$value"/>
  </xsl:function>

  <xsl:function name="po:melonBall" as="array(item()*)">
    <xsl:param name="queue" as="array(item()*)"/>
    <xsl:sequence select="[$queue?*]"/>
  </xsl:function>

  <xsl:function name="po:zucchini" as="array(item()*)">
    <xsl:param name="keys" as="array(item()*)"/>
    <xsl:param name="rows" as="map(*)"/>
    <xsl:sequence select="$keys"/>
  </xsl:function>
</xsl:stylesheet>
