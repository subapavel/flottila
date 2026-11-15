<?xml version="1.0" encoding="UTF-8"?>
<!-- Fixture core of a helper module: what every family module may use. -->
<xsl:stylesheet version="3.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:po="urn:flottila:mapping:po"
    exclude-result-prefixes="#all">

  <xsl:variable name="po:SUPPRESS" as="map(*)" select="map{'po:marker': 'SUPPRESS'}"/>

  <xsl:function name="po:source-queue" as="array(item()*)">
    <xsl:param name="queue" as="array(item()*)"/>
    <xsl:sequence select="$queue"/>
  </xsl:function>
</xsl:stylesheet>
