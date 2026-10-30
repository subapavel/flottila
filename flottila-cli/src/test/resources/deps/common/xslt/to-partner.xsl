<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="3.0">
  <xsl:include href="lib.xsl"/>
  <xsl:output method="xml"/>
  <xsl:template match="/Invoice">
    <PartnerInvoice number="{Id}"><xsl:call-template name="by"/></PartnerInvoice>
  </xsl:template>
</xsl:stylesheet>
