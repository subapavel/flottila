<?xml version="1.0" encoding="UTF-8"?>
<!-- The helper module every portable export imports (ADR-0023): the PO queue semantics as po:
     functions. A queue is an array(item()*) with one member per context; a value is an xs:string
     or $po:SUPPRESS, the marker of ADR-0004 (ADR-0025 since #51). A function of the catalogue is
     po:<catalogue name>, its args in FunCall order (ADR-0025); one that can raise a data error
     takes the id of its function box last, as $box (ADR-0026). Renaming this file, the po
     namespace or a function breaks exports already handed out. -->
<xsl:stylesheet version="3.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:array="http://www.w3.org/2005/xpath-functions/array"
    xmlns:po="urn:flottila:mapping:po"
    xmlns:err="http://www.w3.org/2005/xqt-errors"
    exclude-result-prefixes="#all">

  <!-- SUPPRESS in a queue (ADR-0004): a map, so reading it as a string fails at once (FOTY0013).
       Every other value of a queue is an xs:string, so a function tells SUPPRESS by its type. -->
  <xsl:variable name="po:SUPPRESS" as="map(*)" select="map{'po:marker': 'SUPPRESS'}"/>

  <!-- A SourceQueue (ADR-0004 as amended, F-17): $queue has one context per occurrence of the
       context field; when that field never occurs, $queue has none and the source queue is one
       empty context, as the runtime's Projection gives. -->
  <xsl:function name="po:source-queue" as="array(item()*)">
    <xsl:param name="queue" as="array(item()*)"/>
    <xsl:sequence select="if (array:size($queue) eq 0) then [()] else $queue"/>
  </xsl:function>

  <!-- The occurrences TargetSlots creates of context $parentSlot of $queue (ADR-0006 since #52),
       as the runtime's Node.Slots does: [slot number, value] per created slot. A slot number is
       the slot's position across all contexts of the queue, so a SUPPRESS slot and a truncated
       slot keep theirs. A context the queue lacks, as when the parent has more occurrences than
       the queue has contexts (#31 d1), gives (). A SUPPRESS slot creates nothing and does not count toward $max: at most $max
       of the other slots, () for unbounded. -->
  <xsl:function name="po:slots" as="array(*)*">
    <xsl:param name="queue" as="array(item()*)"/>
    <xsl:param name="parentSlot" as="xs:integer"/>
    <xsl:param name="max" as="xs:integer?"/>
    <xsl:variable name="created" as="array(*)*" select="
        if ($parentSlot gt array:size($queue)) then ()
        else
          let $before := sum((1 to $parentSlot - 1) ! count($queue(.))),
              $context := $queue($parentSlot)
          return (for $j in 1 to count($context) return [$before + $j, $context[$j]])
                 [?2 instance of xs:string]"/>
    <xsl:sequence select="if (exists($max)) then subsequence($created, 1, $max) else $created"/>
  </xsl:function>

  <!-- The occurrences $slots of one parent occurrence, which po:slots gives, unless they are fewer
       than $min: then po:MINOCCURS, before any of them is written, where the runtime raises the
       same error (ADR-0006 since #52). po:slots stops at max, and min is at most max, so they fall
       short of min exactly when the created occurrences do. The error names $mapping, the id of
       the target field mapping, as the runtime's does. The export writes it only when min is
       above 0. -->
  <xsl:function name="po:at-least" as="array(*)*">
    <xsl:param name="slots" as="array(*)*"/>
    <xsl:param name="min" as="xs:integer"/>
    <xsl:param name="mapping" as="xs:string"/>
    <xsl:sequence select="
        if (count($slots) lt $min)
        then error(QName('urn:flottila:mapping:po', 'po:MINOCCURS'),
                   'the target field mapping ' || $mapping || ' creates ' || count($slots)
                   || ' occurrences, fewer than its minOccurs ' || $min)
        else $slots"/>
  </xsl:function>

  <!-- The value each copy of a generating block's TargetRepeat writes (#217, F-46), as the
       runtime's Node.Repeat reads it: the block's first slot, counted across its contexts, when it
       is a value, or () when it is SUPPRESS or the block has no slot, so the copy writes nothing
       and keeps its slot number. -->
  <xsl:function name="po:generated" as="xs:string?">
    <xsl:param name="block" as="array(item()*)"/>
    <xsl:sequence select="($block?*)[1][. instance of xs:string]"/>
  </xsl:function>

  <!-- Lift (ADR-0006): $body once per slot, getting each queue's value there in queue order, as
       the runtime's Lifting.lift does. Context i pairs context i of every queue, and the result has
       as many as the queue with the most; an empty or missing context gives an empty one; else
       the longest sets the slot count and a shorter queue reuses its last value. SUPPRESS among
       the values, reused or not, gives SUPPRESS without calling $body. -->
  <xsl:function name="po:lift" as="array(item()*)">
    <xsl:param name="queues" as="array(item()*)*"/>
    <xsl:param name="body" as="function(xs:string*) as xs:string"/>
    <xsl:sequence select="array:join(
        for $i in 1 to max($queues ! array:size(.))
        return
          let $contexts := $queues ! (if ($i le array:size(.)) then [array:get(., $i)] else [()]),
              $n := if (some $c in $contexts satisfies empty(array:get($c, 1))) then 0
                    else max($contexts ! count(array:get(., 1)))
          return [for $j in 1 to $n
                  return
                    let $in := $contexts ! (let $c := array:get(., 1)
                                            return $c[min(($j, count($c)))])
                    return if (some $v in $in satisfies $v instance of map(*)) then $po:SUPPRESS
                           else $body($in)])"/>
  </xsl:function>

  <!-- The data error $code, a QName such as po:USEONEASMANY or err:FORG0001, that the function
       box $box raises (ADR-0026, F-19): its message is 'the function box ', $box, ': ' and
       $message, as the runtime's is. -->
  <xsl:function name="po:data-error" as="item()*">
    <xsl:param name="code" as="xs:QName"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:param name="message" as="xs:string"/>
    <xsl:sequence select="error($code,
                                'the function box ' || $box || ': ' || $message)"/>
  </xsl:function>

  <!-- The xs:decimal that $text writes, or () when it writes none: the one number rule of the
       standard functions (#82, ADR-0005), as the runtime's XsDecimal.parse reads it. The cast
       takes the xs:decimal lexical form with XML whitespace around it ignored; an exponent, NaN,
       a sign or a point alone and '' are no xs:decimal. A function that meets none raises its own
       data error at its box. -->
  <xsl:function name="po:decimal" as="xs:decimal?">
    <xsl:param name="text" as="xs:string"/>
    <xsl:sequence select="if ($text castable as xs:decimal) then xs:decimal($text) else ()"/>
  </xsl:function>

  <!-- The formatting core (ADR-0003), which several families share: a Java/SAP pattern of a box
       parameter compiled to an XPath picture. Each helper does as its counterpart in engine/xdm
       does, for a pattern that counterpart compiles, which lowering has checked (ADR-0026). -->

  <!-- The runs of the Java/SAP pattern $pattern, as JavaPattern.runs splits it: ['q', text] for
       quoted text, without its quotes and each '' in it read as ', and for '' outside quotes;
       ['p', text] for the unquoted pattern characters between. -->
  <xsl:function name="po:pattern-runs" as="array(xs:string)*">
    <xsl:param name="pattern" as="xs:string"/>
    <xsl:analyze-string select="$pattern" regex="''|'((?:[^']|'')*)'">
      <xsl:matching-substring>
        <xsl:sequence select="['q', if (. eq &quot;''&quot;) then &quot;'&quot;
                                    else replace(regex-group(1), &quot;''&quot;, &quot;'&quot;)]"/>
      </xsl:matching-substring>
      <xsl:non-matching-substring>
        <xsl:sequence select="['p', .]"/>
      </xsl:non-matching-substring>
    </xsl:analyze-string>
  </xsl:function>

  <!-- The XPath picture of the DecimalFormat pattern $pattern, as NumberPattern.picture compiles
       it. Its quoted text holds no character a picture reads, so the quotes go; then the digits
       and separators of each subpattern become po:number-digits of the positive one's. -->
  <xsl:function name="po:number-picture" as="xs:string">
    <xsl:param name="pattern" as="xs:string"/>
    <xsl:variable name="text" as="xs:string"
        select="string-join(po:pattern-runs($pattern) ! ?2)"/>
    <xsl:variable name="number" as="xs:string"
        select="replace(tokenize($text, ';')[1], '^[^#0,.]*([#0,.]+).*$', '$1', 's')"/>
    <xsl:sequence select="replace($text, '[#0,.]+', po:number-digits($number))"/>
  </xsl:function>

  <!-- The picture's digits for $number, the digits and separators of a DecimalFormat subpattern,
       as NumberPattern writes them: DecimalFormat's minimum of integer digits as 0s, at least one
       when $number has no 0 but integer and fraction digits; with grouping, a separator after
       every group of the size of the digits after the last one; then the fraction as it is. -->
  <xsl:function name="po:number-digits" as="xs:string">
    <xsl:param name="number" as="xs:string"/>
    <xsl:variable name="integer" as="xs:string" select="substring-before($number || '.', '.')"/>
    <xsl:variable name="zeros" as="xs:integer" select="
        if (not(contains($number, '0')) and contains($number, '.') and $integer ne '') then 1
        else string-length(replace($integer, '[^0]', ''))"/>
    <xsl:variable name="grouping" as="xs:integer" select="
        if (contains($integer, ',')) then string-length(replace($integer, '^.*,', '')) else 0"/>
    <xsl:sequence select="string-join((
        for $i in reverse(1 to max(($zeros, $grouping + 1)))
        return (if ($i le $zeros) then '0' else '#',
                if ($grouping gt 0 and $i gt 1 and ($i - 1) mod $grouping eq 0) then ',' else ()),
        substring($number, string-length($integer) + 1)))"/>
  </xsl:function>
</xsl:stylesheet>
