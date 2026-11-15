<?xml version="1.0" encoding="UTF-8"?>
<!-- The function family context (ADR-0025 "Families", F-15), spec 2's eleven functions: each is
     po:<catalogue name>, its args in FunCall order, and one that can raise a data error takes its
     box last and raises through po:data-error (ADR-0026). The build joins core.xsl and the family
     modules, the families by id, into the helper module po-runtime.xsl, of which this comment is no
     part. A family module uses what core.xsl declares and nothing of another family, and holds
     core.xsl's root start tag, byte for byte. -->
<xsl:stylesheet version="3.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:array="http://www.w3.org/2005/xpath-functions/array"
    xmlns:po="urn:flottila:mapping:po"
    xmlns:err="http://www.w3.org/2005/xqt-errors"
    exclude-result-prefixes="#all">

  <!-- collapseContexts: the first value of each context of $queue, SUPPRESS kept as SUPPRESS, and
       '' for an empty context, all in one context, as the runtime's
       ContextFunctions.collapseContexts does (observed, F-47). A queue with no value and at most
       one context, one empty context or none, gives one empty context: PO writes no value for it
       (observed, F-47). -->
  <xsl:function name="po:collapseContexts" as="array(item()*)">
    <xsl:param name="queue" as="array(item()*)"/>
    <xsl:sequence select="if (empty($queue?*) and array:size($queue) le 1) then [()]
                          else [for $c in 1 to array:size($queue) return ($queue($c)[1], '')[1]]"/>
  </xsl:function>

  <!-- createIf: per value of $queue, cast as PO's Bool.toBoolean does (ADR-0006, amended with
       #133, F-37): '' where it is 1 or true in any case, $po:SUPPRESS where it is 0 or false in any
       case, and $po:SUPPRESS where the slot is SUPPRESS; the contexts stay. The first value, in
       order, that is none of those, '' included, raises po:CREATEIF at $box, "Cannot cast '<value>'
       to boolean", as the runtime's ContextFunctions.createIf does. lower-case, like the
       runtime's toLowerCase(Locale.ROOT), maps no character but an ASCII capital to a letter of
       true or false. -->
  <xsl:function name="po:createIf" as="array(item()*)">
    <xsl:param name="queue" as="array(item()*)"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:variable name="wrong" as="xs:string?"
        select="($queue?*[. instance of xs:string]
                 [not(. = ('1', '0') or lower-case(.) = ('true', 'false'))])[1]"/>
    <xsl:sequence select="if (exists($wrong))
        then po:data-error(xs:QName('po:CREATEIF'), $box,
                           'Cannot cast ''' || $wrong || ''' to boolean')
        else array:join(for $c in 1 to array:size($queue)
             return [$queue($c) ! (if (not(. instance of xs:string)) then $po:SUPPRESS
                                   else if (. eq '1' or lower-case(.) eq 'true') then ''
                                   else $po:SUPPRESS)])"/>
  </xsl:function>

  <!-- exists: per context of $queue, one context holding 'true' for each of its values, SUPPRESS
       and '' included, or 'false' once if it is empty, as the runtime's ContextFunctions.exists
       does (observed, F-48). A queue with no context at all gives none. -->
  <xsl:function name="po:exists" as="array(item()*)">
    <xsl:param name="queue" as="array(item()*)"/>
    <xsl:sequence select="array:join(for $c in 1 to array:size($queue)
        return [if (empty($queue($c))) then 'false' else $queue($c) ! 'true'])"/>
  </xsl:function>

  <!-- formatByExample: every value of $values, in order, cut into the contexts of $example, empty
       ones included, as the runtime's ContextFunctions.formatByExample does; a SUPPRESS of $values
       stays at its slot, and a SUPPRESS of $example counts as a value (an assumed reading). Unless
       both hold as many values, empty contexts not counted, it raises po:FORMATBYEXAMPLE, where
       the runtime refuses the same inputs (#2 §4: the SAP documentation raises an exception). -->
  <xsl:function name="po:formatByExample" as="array(item()*)">
    <xsl:param name="values" as="array(item()*)"/>
    <xsl:param name="example" as="array(item()*)"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:variable name="all" as="item()*" select="$values?*"/>
    <xsl:choose>
      <xsl:when test="count($all) ne count($example?*)">
        <xsl:sequence select="po:data-error(xs:QName('po:FORMATBYEXAMPLE'), $box,
            'formatByExample: its inputs do not hold as many values')"/>
      </xsl:when>
      <xsl:otherwise>
        <xsl:iterate select="1 to array:size($example)">
          <xsl:param name="offset" as="xs:integer" select="0"/>
          <xsl:param name="result" as="array(item()*)" select="[]"/>
          <xsl:on-completion select="$result"/>
          <xsl:variable name="size" as="xs:integer" select="count($example(.))"/>
          <xsl:next-iteration>
            <xsl:with-param name="offset" select="$offset + $size"/>
            <xsl:with-param name="result"
                select="array:append($result, subsequence($all, $offset + 1, $size))"/>
          </xsl:next-iteration>
        </xsl:iterate>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:function>

  <!-- mapWithDefault: every empty context of $queue replaced by one holding $default, every other
       context kept, as the runtime's ContextFunctions.mapWithDefault does; a context of SUPPRESS
       only is not empty (observed, F-48), '' is a value. A queue with no context at all gives
       none. -->
  <xsl:function name="po:mapWithDefault" as="array(item()*)">
    <xsl:param name="queue" as="array(item()*)"/>
    <xsl:param name="default" as="xs:string"/>
    <xsl:sequence select="array:join(for $c in 1 to array:size($queue)
        return [if (empty($queue($c))) then $default else $queue($c)])"/>
  </xsl:function>

  <!-- removeContexts: every value of $queue, in order, in one context, SUPPRESS kept at its slot.
       A queue with no context at all gives one empty context, as the runtime's
       ContextFunctions.removeContexts does. -->
  <xsl:function name="po:removeContexts" as="array(item()*)">
    <xsl:param name="queue" as="array(item()*)"/>
    <xsl:sequence select="[$queue?*]"/>
  </xsl:function>

  <!-- replaceValue: $replacement at every slot of $value, SUPPRESS included (F-45), as the runtime's
       ContextFunctions.replaceValue does; the contexts stay, an empty one stays empty, and a queue
       with no context at all gives none. -->
  <xsl:function name="po:replaceValue" as="array(item()*)">
    <xsl:param name="value" as="array(item()*)"/>
    <xsl:param name="replacement" as="xs:string"/>
    <xsl:sequence select="array:join(for $c in 1 to array:size($value)
                                     return [$value($c) ! $replacement])"/>
  </xsl:function>

  <!-- Whether the queue values $a and $b are equal, as the runtime's Objects.equals compares them:
       two strings when eq, under the default Unicode codepoint collation, which is String.equals;
       SUPPRESS equals SUPPRESS only. -->
  <xsl:function name="po:same-value" as="xs:boolean">
    <xsl:param name="a" as="item()"/>
    <xsl:param name="b" as="item()"/>
    <xsl:sequence select="if ($a instance of xs:string and $b instance of xs:string) then $a eq $b
                          else not($a instance of xs:string or $b instance of xs:string)"/>
  </xsl:function>

  <!-- sort: each context of $queue in the order of $comparison and $order, as the runtime's
       ContextFunctions.sort does: the queue sorted by itself. Its data error is po:SORT. -->
  <xsl:function name="po:sort" as="array(item()*)">
    <xsl:param name="queue" as="array(item()*)"/>
    <xsl:param name="comparison" as="xs:string"/>
    <xsl:param name="order" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="po:sort-contexts('sort', $queue, $queue, $comparison, $order, $box)"/>
  </xsl:function>

  <!-- sortByKey: each context of $values in the order of the keys at the same places of $keys, as
       the runtime's ContextFunctions.sortByKey does. Its data error is po:SORTBYKEY. -->
  <xsl:function name="po:sortByKey" as="array(item()*)">
    <xsl:param name="keys" as="array(item()*)"/>
    <xsl:param name="values" as="array(item()*)"/>
    <xsl:param name="comparison" as="xs:string"/>
    <xsl:param name="order" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:sequence select="po:sort-contexts('sortByKey', $keys, $values, $comparison, $order, $box)"/>
  </xsl:function>

  <!-- Each context of $values sorted by $keys, for the function $function. Its data error is
       po:<$function upper-cased>, one code per function (ADR-0025, #52), raised where the
       runtime's ContextFunctions raises it: for unequal numbers of contexts first, then context
       by context for a context with more or fewer keys than values, or, in a numeric sort, for a
       key that is no xs:decimal, even alone in its context. A SUPPRESS counts as a key or a value,
       and a SUPPRESS key is never cast; in a numeric sortByKey it is a data error too (#2 §3:
       numeric keys must not be ResultList.SUPPRESS), in a numeric sort the greatest key. -->
  <xsl:function name="po:sort-contexts" as="array(item()*)">
    <xsl:param name="function" as="xs:string"/>
    <xsl:param name="keys" as="array(item()*)"/>
    <xsl:param name="values" as="array(item()*)"/>
    <xsl:param name="comparison" as="xs:string"/>
    <xsl:param name="order" as="xs:string"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:variable name="contexts" as="xs:integer" select="array:size($values)"/>
    <xsl:variable name="defect" as="xs:string?" select="
        if (array:size($keys) ne $contexts) then 'the keys and the values differ in their contexts'
        else (for $c in 1 to $contexts
              return if (count($keys($c)) ne count($values($c)))
                     then 'a context has more or fewer keys than values'
                     else if ($comparison eq 'numeric'
                              and (some $k in $keys($c)[. instance of xs:string]
                                   satisfies not($k castable as xs:decimal)))
                     then 'a numeric sort meets a key that is no xs:decimal'
                     else if ($function eq 'sortByKey' and $comparison eq 'numeric'
                              and (some $k in $keys($c) satisfies not($k instance of xs:string)))
                     then 'a numeric key is SUPPRESS' else ())[1]"/>
    <xsl:sequence select="
        if (exists($defect))
        then po:data-error(QName('urn:flottila:mapping:po', 'po:' || upper-case($function)), $box,
                   $function || ': ' || $defect)
        else array:join(for $c in 1 to $contexts
                        return [po:sortByKey-context($keys($c), $values($c), $comparison, $order)])"/>
  </xsl:function>

  <!-- One context of sortByKey. fn:sort is stable; descending sorts the reversed positions and
       reverses the result, so that equal keys keep their input order either way. A SUPPRESS key
       is the greatest key, as in the runtime's ContextFunctions (observed, F-47): its sort key is
       2, and every other key's is 1 followed by po:sort-key. -->
  <xsl:function name="po:sortByKey-context" as="item()*">
    <xsl:param name="keys" as="item()*"/>
    <xsl:param name="values" as="item()*"/>
    <xsl:param name="comparison" as="xs:string"/>
    <xsl:param name="order" as="xs:string"/>
    <xsl:variable name="key" as="function(xs:integer) as xs:anyAtomicType*"
        select="function($i as xs:integer) as xs:anyAtomicType* {
                  if ($keys[$i] instance of xs:string) then (1, po:sort-key($keys[$i], $comparison))
                  else 2 }"/>
    <xsl:variable name="positions" as="xs:integer*" select="
        if ($order eq 'descending') then reverse(sort(reverse(1 to count($keys)), (), $key))
        else sort(1 to count($keys), (), $key)"/>
    <xsl:sequence select="for $i in $positions return $values[$i]"/>
  </xsl:function>

  <!-- The sort key of $value, which fn:sort compares item by item, a shorter key first when it is
       a prefix, and never through a collation:
       - numeric: the xs:decimal;
       - caseInsensitive: per code point, po:fold-case, in code point order, as Java's
         String.compareToIgnoreCase compares;
       - otherwise, lexicographic: per code point, U+E000 to U+FFFF moved above U+10FFFF, so that
         the keys compare as Java's String.compareTo compares UTF-16 code units, where a
         supplementary character's high surrogate (D800-DBFF) comes before U+E000. -->
  <xsl:function name="po:sort-key" as="xs:anyAtomicType*">
    <xsl:param name="value" as="xs:string"/>
    <xsl:param name="comparison" as="xs:string"/>
    <xsl:sequence select="
        if ($comparison eq 'numeric') then xs:decimal($value)
        else if ($comparison eq 'caseInsensitive')
        then string-to-codepoints($value) ! po:fold-case(.)
        else string-to-codepoints($value) ! (if (. ge 57344 and . le 65535) then . + 1114112 else .)"/>
  </xsl:function>

  <!-- Java's Character.toLowerCase(Character.toUpperCase($codepoint)), the simple case mappings.
       XPath's upper-case and lower-case are the full mappings; where one gives several characters
       (ß to SS) the simple mapping leaves the character as it is. U+0130 is the one character whose
       full lower case has two characters (i and U+0307) but whose simple lower case is i. The
       processor's Unicode version must be the runtime's for the two to agree everywhere. -->
  <xsl:function name="po:fold-case" as="xs:integer">
    <xsl:param name="codepoint" as="xs:integer"/>
    <xsl:variable name="char" as="xs:string" select="codepoints-to-string($codepoint)"/>
    <xsl:variable name="upper" as="xs:string"
        select="(upper-case($char)[string-length(.) eq 1], $char)[1]"/>
    <xsl:sequence select="if ($codepoint eq 304) then 105
        else string-to-codepoints((lower-case($upper)[string-length(.) eq 1], $upper)[1])"/>
  </xsl:function>

  <!-- splitByValue: each context of $queue split on its own, as the runtime's
       ContextFunctions.splitByEachValue, splitByValueChange and splitByEmptyValue do. The input's
       context boundaries stay, an empty context stays one empty context, and a SUPPRESS stays a
       value of its own in every mode. -->
  <xsl:function name="po:splitByValue" as="array(item()*)">
    <xsl:param name="queue" as="array(item()*)"/>
    <xsl:param name="mode" as="xs:string"/>
    <xsl:sequence select="array:join(for $c in 1 to array:size($queue)
                                     return po:splitByValue-context($queue($c), $mode))"/>
  </xsl:function>

  <!-- One context of splitByValue, as an array of the contexts it gives. emptyValue: every ""
       ends a context and starts one, and is dropped; SUPPRESS is no "". eachValue: every value
       starts a context. Otherwise, valueChange: a value starts a context when it is not the same
       value as the one before it (po:same-value). -->
  <xsl:function name="po:splitByValue-context" as="array(item()*)">
    <xsl:param name="context" as="item()*"/>
    <xsl:param name="mode" as="xs:string"/>
    <xsl:variable name="n" as="xs:integer" select="count($context)"/>
    <xsl:choose>
      <xsl:when test="$mode eq 'emptyValue'">
        <xsl:variable name="cuts" as="xs:integer*"
            select="0, (for $i in 1 to $n return $i[po:same-value($context[$i], '')]), $n + 1"/>
        <xsl:sequence select="array:join(for $k in 1 to count($cuts) - 1
            return [subsequence($context, $cuts[$k] + 1, $cuts[$k + 1] - $cuts[$k] - 1)])"/>
      </xsl:when>
      <xsl:when test="$n eq 0">
        <xsl:sequence select="[()]"/>
      </xsl:when>
      <xsl:otherwise>
        <xsl:variable name="starts" as="xs:integer*" select="1, for $i in 2 to $n
            return $i[$mode eq 'eachValue' or not(po:same-value($context[$i], $context[$i - 1]))]"/>
        <xsl:variable name="ends" as="xs:integer*" select="tail($starts), $n + 1"/>
        <xsl:sequence select="array:join(for $k in 1 to count($starts)
            return [subsequence($context, $starts[$k], $ends[$k] - $starts[$k])])"/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:function>

  <!-- useOneAsMany: the one value of context k of $value once per value of context k of
       $occurrences, in order, cut into the contexts of $contexts, as the runtime's
       ContextFunctions.useOneAsMany does; a SUPPRESS value is repeated as SUPPRESS, and a SUPPRESS
       of $occurrences counts as an occurrence. Unless $value has as many contexts as $occurrences,
       each holding one value, SUPPRESS included, and $contexts as many values as $occurrences, it
       raises po:USEONEASMANY, where the runtime refuses the same inputs (observed, F-48), checked
       in the same order and said in the same words, with the count that failed (ADR-0025). -->
  <xsl:function name="po:useOneAsMany" as="array(item()*)">
    <xsl:param name="value" as="array(item()*)"/>
    <xsl:param name="occurrences" as="array(item()*)"/>
    <xsl:param name="contexts" as="array(item()*)"/>
    <xsl:param name="box" as="xs:string"/>
    <xsl:variable name="wrong" as="xs:integer?"
        select="(1 to array:size($value))[count($value(.)) ne 1][1]"/>
    <xsl:sequence select="
        if (array:size($value) ne array:size($occurrences))
        then po:data-error(xs:QName('po:USEONEASMANY'), $box,
                   'useOneAsMany needs as many contexts in its first input as in its second: the'
                   || ' first has ' || array:size($value) || ' contexts, the second '
                   || array:size($occurrences))
        else if (exists($wrong))
        then po:data-error(xs:QName('po:USEONEASMANY'), $box,
                   'useOneAsMany needs one value in each context of its first input: its context '
                   || $wrong || ' has ' || count($value($wrong)) || ' values')
        else
          let $repeated := for $k in 1 to array:size($occurrences)
                           return $occurrences($k) ! $value($k)
          return
            if (count($repeated) ne count($contexts?*))
            then po:data-error(xs:QName('po:USEONEASMANY'), $box,
                     'useOneAsMany needs as many slots in its third input as in its second: the'
                     || ' third has ' || count($contexts?*) || ' slots, the second '
                     || count($repeated))
            else array:join(
              for $c in 1 to array:size($contexts)
              return [subsequence($repeated,
                                  sum(for $j in 1 to $c - 1 return count($contexts($j))) + 1,
                                  count($contexts($c)))])"/>
  </xsl:function>
</xsl:stylesheet>
