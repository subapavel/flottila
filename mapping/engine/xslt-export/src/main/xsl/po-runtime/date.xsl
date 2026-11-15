<?xml version="1.0" encoding="UTF-8"?>
<!-- The function family date (ADR-0025 "Families", F-15), #80: each is po:<catalogue name>, its
     args in FunCall order, and it takes its box last because it can raise a data error through
     po:data-error (ADR-0026). A date pattern is a SimpleDateFormat pattern that lowering has
     checked; po:date-picture compiles it to the XPath picture DatePattern.picture writes, and
     po:parse-date reads a date in that picture as FormatDate.parse does (ADR-0003). A date
     without an offset is in the implicit time zone, the offset of current-dateTime(), which the
     processor's caller sets as the runtime's run time; a date is written in that zone, and dates
     are compared as instants, as the runtime's DateFamily does. A date its pattern does not read
     is err:FORG0001 at the box. currentDate writes current-dateTime() itself. The build joins core.xsl and the family modules, the families by
     id, into the helper module po-runtime.xsl, of which this comment is no part. A family module
     uses what core.xsl declares and nothing of another family, and holds core.xsl's root start
     tag, byte for byte. -->
<xsl:stylesheet version="3.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:array="http://www.w3.org/2005/xpath-functions/array"
    xmlns:po="urn:flottila:mapping:po"
    xmlns:err="http://www.w3.org/2005/xqt-errors"
    exclude-result-prefixes="#all">

  <!-- The picture component of each pattern field that DatePattern translates, by its letter and
       the number of letters. -->
  <xsl:variable name="po:date-components" as="map(xs:string, xs:string)" select="map{
      'y4': '[Y0001]', 'M1': '[M1]', 'M2': '[M01]', 'd1': '[D1]', 'd2': '[D01]',
      'H1': '[H1]', 'H2': '[H01]', 'm1': '[m1]', 'm2': '[m01]', 's1': '[s1]', 's2': '[s01]',
      'S3': '[f001]', 'Z1': '[Z0000]', 'X3': '[Z01:01t]'}"/>

  <!-- The digits po:parse-date reads for each picture component, as FormatDate's. -->
  <xsl:variable name="po:date-digits" as="map(xs:string, xs:string)" select="map{
      'Y0001': '[0-9]{4}', 'M1': '[0-9]{1,2}', 'M01': '[0-9]{2}', 'D1': '[0-9]{1,2}',
      'D01': '[0-9]{2}', 'H1': '[0-9]{1,2}', 'H01': '[0-9]{2}', 'm1': '[0-9]{1,2}',
      'm01': '[0-9]{2}', 's1': '[0-9]{1,2}', 's01': '[0-9]{2}', 'f001': '[0-9]{3}',
      'Z0000': '[+-][0-9]{4}', 'Z01:01t': 'Z|[+-][0-9]{2}:[0-9]{2}'}"/>

  <!-- The XPath picture of the SimpleDateFormat pattern $pattern, as DatePattern.picture compiles
       it: quoted text and every character that is no ASCII letter as literal text, its square
       brackets doubled; each run of one pattern letter as its component. -->
  <xsl:function name="po:date-picture" as="xs:string">
    <xsl:param name="pattern" as="xs:string"/>
    <xsl:variable name="parts" as="xs:string*">
      <xsl:for-each select="po:pattern-runs($pattern)">
        <xsl:choose>
          <xsl:when test="?1 eq 'q'">
            <xsl:sequence select="replace(replace(?2, '\[', '[['), '\]', ']]')"/>
          </xsl:when>
          <xsl:otherwise>
            <xsl:analyze-string select="?2" regex="([A-Za-z])\1*">
              <xsl:matching-substring>
                <xsl:sequence
                    select="$po:date-components(substring(., 1, 1) || string-length(.))"/>
              </xsl:matching-substring>
              <xsl:non-matching-substring>
                <xsl:sequence select="replace(replace(., '\[', '[['), '\]', ']]')"/>
              </xsl:non-matching-substring>
            </xsl:analyze-string>
          </xsl:otherwise>
        </xsl:choose>
      </xsl:for-each>
    </xsl:variable>
    <xsl:sequence select="string-join($parts)"/>
  </xsl:function>

  <!-- The date $text writes in $picture, a picture po:date-picture writes, as FormatDate.parse
       reads it: the whole text matches the picture, and its fields make a date and a time of day;
       a field the picture lacks is that of 1970-01-01T00:00:00.000, and the offset, when the
       picture has none, is the implicit time zone. () when $text writes no date. -->
  <xsl:function name="po:parse-date" as="xs:dateTime?">
    <xsl:param name="text" as="xs:string"/>
    <xsl:param name="picture" as="xs:string"/>
    <!-- per token [component name, ''] or ['', literal text] -->
    <xsl:variable name="tokens" as="array(xs:string)*">
      <xsl:analyze-string select="$picture" regex="\[\[|\]\]|\[([^\[\]]*)\]|[^\[\]]+">
        <xsl:matching-substring>
          <xsl:sequence select="
              if (. eq '[[') then ['', '[']
              else if (. eq ']]') then ['', ']']
              else if (starts-with(., '[')) then [regex-group(1), '']
              else ['', .]"/>
        </xsl:matching-substring>
      </xsl:analyze-string>
    </xsl:variable>
    <xsl:variable name="components" as="xs:string*" select="$tokens[?1 ne ''] ! ?1"/>
    <xsl:variable name="regex" as="xs:string" select="
        '^' || string-join($tokens ! (
          if (?1 ne '') then '(' || $po:date-digits(?1) || ')'
          else replace(?2, '([\\|.\-^?*+{}()\[\]$])', '\\$1'))) || '$'"/>
    <xsl:choose>
      <xsl:when test="matches($text, $regex)">
        <xsl:analyze-string select="$text" regex="{$regex}">
          <xsl:matching-substring>
            <xsl:sequence select="po:date-of($components,
                                             for $k in 1 to count($components)
                                             return regex-group($k))"/>
          </xsl:matching-substring>
        </xsl:analyze-string>
      </xsl:when>
      <xsl:otherwise>
        <xsl:sequence select="()"/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:function>

  <!-- The date of the picture components $components, read as the texts $values, one each, as
       FormatDate.parse makes it; () when they make none. -->
  <xsl:function name="po:date-of" as="xs:dateTime?">
    <xsl:param name="components" as="xs:string*"/>
    <xsl:param name="values" as="xs:string*"/>
    <xsl:variable name="letters" as="xs:string*" select="$components ! substring(., 1, 1)"/>
    <xsl:variable name="field" as="function(xs:string, xs:integer) as xs:integer" select="
        function($letter, $default) {
          let $at := index-of($letters, $letter)
          return if (empty($at)) then $default else xs:integer($values[$at])
        }"/>
    <xsl:variable name="zone" as="xs:string?" select="$values[index-of($letters, 'Z')]"/>
    <xsl:variable name="year" as="xs:integer" select="$field('Y', 1970)"/>
    <xsl:variable name="hour" as="xs:integer" select="$field('H', 0)"/>
    <xsl:variable name="lexical" as="xs:string" select="
        format-integer($year, '0000') || '-' || format-integer($field('M', 1), '00') || '-'
        || format-integer($field('D', 1), '00') || 'T' || format-integer($hour, '00') || ':'
        || format-integer($field('m', 0), '00') || ':' || format-integer($field('s', 0), '00')
        || '.' || format-integer($field('f', 0), '000')
        || (if (empty($zone) or $zone eq 'Z') then $zone
            else substring($zone, 1, 3) || ':' || substring(replace($zone, ':', ''), 4))"/>
    <!-- xs:dateTime has a year 0000 and an hour 24 (XSD 1.1), SimpleDateFormat neither -->
    <xsl:sequence select="
        if ($year eq 0 or $hour gt 23 or not($lexical castable as xs:dateTime)) then ()
        else if (empty($zone)) then adjust-dateTime-to-timezone(xs:dateTime($lexical))
        else xs:dateTime($lexical)"/>
  </xsl:function>

  <!-- The date $text writes in the date pattern $pattern for the function $function, or
       err:FORG0001 at $box, as the runtime's DateFamily raises it. -->
  <xsl:function name="po:date" as="xs:dateTime">
    <xsl:param name="function" as="xs:string"/>
    <xsl:param name="text" as="xs:string"/>
    <xsl:param name="pattern" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:variable name="date" as="xs:dateTime?"
        select="po:parse-date($text, po:date-picture($pattern))"/>
    <xsl:sequence select="
        if (exists($date)) then $date
        else po:data-error(xs:QName('err:FORG0001'), $box,
                           $function || ': ''' || $text || ''' is no date in the pattern '
                           || $pattern)"/>
  </xsl:function>

  <!-- currentDate: the run's time, current-dateTime(), written in $targetFormat. The processor
       fixes that value for the whole run and gives it the implicit time zone, as the runtime's
       DateFamily.currentDate writes Frame.now. It cannot raise, so it takes no box. -->
  <xsl:function name="po:currentDate" as="xs:string">
    <xsl:param name="targetFormat" as="xs:string"/>
    <xsl:sequence select="format-dateTime(current-dateTime(), po:date-picture($targetFormat))"/>
  </xsl:function>

  <!-- dateTrans: the date $date read in $sourceFormat, written in $targetFormat in the implicit
       time zone. -->
  <xsl:function name="po:dateTrans" as="xs:string">
    <xsl:param name="date" as="xs:string"/>
    <xsl:param name="sourceFormat" as="xs:string"/>
    <xsl:param name="targetFormat" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="
        format-dateTime(adjust-dateTime-to-timezone(po:date('dateTrans', $date, $sourceFormat,
                                                            $box)),
                        po:date-picture($targetFormat))"/>
  </xsl:function>

  <!-- dateBefore: whether $date1, read in $format1, is before $date2, read in $format2. -->
  <xsl:function name="po:dateBefore" as="xs:string">
    <xsl:param name="date1" as="xs:string"/>
    <xsl:param name="date2" as="xs:string"/>
    <xsl:param name="format1" as="xs:string"/>
    <xsl:param name="format2" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="string(po:date('dateBefore', $date1, $format1, $box)
                                 lt po:date('dateBefore', $date2, $format2, $box))"/>
  </xsl:function>

  <!-- dateAfter: whether $date1, read in $format1, is after $date2, read in $format2. -->
  <xsl:function name="po:dateAfter" as="xs:string">
    <xsl:param name="date1" as="xs:string"/>
    <xsl:param name="date2" as="xs:string"/>
    <xsl:param name="format1" as="xs:string"/>
    <xsl:param name="format2" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="string(po:date('dateAfter', $date1, $format1, $box)
                                 gt po:date('dateAfter', $date2, $format2, $box))"/>
  </xsl:function>

  <!-- compareDates: -1, 0 or 1 as $date1, read in $format1, is before, at or after $date2, read
       in $format2. -->
  <xsl:function name="po:compareDates" as="xs:string">
    <xsl:param name="date1" as="xs:string"/>
    <xsl:param name="date2" as="xs:string"/>
    <xsl:param name="format1" as="xs:string"/>
    <xsl:param name="format2" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:variable name="a" as="xs:dateTime" select="po:date('compareDates', $date1, $format1, $box)"/>
    <xsl:variable name="b" as="xs:dateTime" select="po:date('compareDates', $date2, $format2, $box)"/>
    <xsl:sequence select="if ($a lt $b) then '-1' else if ($a gt $b) then '1' else '0'"/>
  </xsl:function>
</xsl:stylesheet>
