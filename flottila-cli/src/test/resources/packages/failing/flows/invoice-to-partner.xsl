<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="3.0">
  <xsl:output method="xml" indent="yes"/>
  <xsl:template match="/Invoice">
    <PartnerInvoice number="{Id}" currency="{Currency}">
      <Total><xsl:value-of select="sum(Lines/Line/(Qty * Price))"/></Total>
      <xsl:for-each select="Lines/Line">
        <Item sku="{Sku}" amount="{Qty * Price}"/>
      </xsl:for-each>
    </PartnerInvoice>
  </xsl:template>
</xsl:stylesheet>
