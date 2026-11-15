$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$records=@(Get-ChildItem (Join-Path $repo 'corpus/observations') -Filter preparation.json -Recurse | Where-Object { $_.FullName -match '\\cpi\\api-preparation\\[^\\]+\\preparation.json$' })
$items=@()
$resources=0
foreach($file in $records){
 $r=Get-Content -Raw $file.FullName|ConvertFrom-Json
 if($r.status -ne 'API_CREATED_READBACK_VERIFIED' -or $r.simulation -ne 'NOT_RUN'){throw 'Unexpected preparation status'}
 $dir=$file.DirectoryName
 $a=[IO.Compression.ZipFile]::OpenRead((Join-Path $dir 'upload.zip'))
 $b=[IO.Compression.ZipFile]::OpenRead((Join-Path $dir 'readback.zip'))
 try{
  foreach($e in $a.Entries|Where-Object {$_.FullName -match '\.(mmap|xsd)$'}){
   $reader=[IO.StreamReader]::new($e.Open());$left=$reader.ReadToEnd();$reader.Dispose()
   $other=$b.GetEntry($e.FullName);if(!$other){throw 'Missing exported resource'}
   $reader=[IO.StreamReader]::new($other.Open());$right=$reader.ReadToEnd();$reader.Dispose()
   if($left -cne $right){throw 'Resource differs from readback'}
   [xml]$xml=$left
   # The tenant marker is supplied locally via $env:CPI_TENANT_MARKER (owner, 2026-11-13, #213); unset skips that pattern.
   if($env:CPI_TENANT_MARKER -and $left -match [regex]::Escape($env:CPI_TENANT_MARKER)){throw 'Sensitive metadata detected'}
   if($left -match 'Bearer\s+[a-zA-Z0-9]|https://[^\s<]*hana\.ondemand'){throw 'Sensitive metadata detected'}
   if($e.FullName -match '\.mmap$'){
    if(!$xml.SelectSingleNode('//textInfo/textObj/texts/text')){throw 'Missing editor text metadata'}
    if($left -cne (Get-Content -Raw (Join-Path $dir 'mapping.mmap'))){throw 'Local mmap differs from archive'}
   }
   $resources++
  }
 }finally{$a.Dispose();$b.Dispose()}
 $rel=$file.FullName.Substring($repo.Length+1).Replace('\','/')
 $set=$rel.Split('/')[2]
 $items += [ordered]@{observation=$set;artifactId=$r.artifactId;cases=@($r.cases);evidence=$rel;request=('corpus/observations/'+$set+'/request.md');status=$r.status;simulation='NOT_RUN';editorValidation=$r.editorValidation}
}
$uncovered=@()
foreach($set in @('obs-boolean','obs-constant','obs-statistic','obs-text','obs-arithmetic','obs-date')){
 $request=Get-Content -Raw (Join-Path $repo ('corpus/observations/'+$set+'/request.md'))
 $wanted=@([regex]::Matches($request,'fn/[A-Za-z]+/\d{3}')|ForEach-Object Value|Sort-Object -Unique)
 $covered=@($items|Where-Object observation -eq $set|ForEach-Object cases)
 foreach($case in $wanted){if($case -notin $covered){$uncovered+=($set+':'+$case)}}
}
$expected=@('obs-statistic:fn/counter/001','obs-statistic:fn/counter/002','obs-statistic:fn/counter/003')
if(@(Compare-Object $expected $uncovered).Count){throw ('Unexpected uncovered cases: '+($uncovered -join ', '))}
$index=[ordered]@{
 date='2026-11-12';packageId='flottilaobservations';mappingCount=$items.Count;caseCount=@($items|ForEach-Object cases).Count
 status='API_PREPARATION_COMPLETE_WITH_EXCEPTIONS';simulation='NOT_RUN';verification=('VERIFIED_PASS: '+$resources+' mmap/XSD resources parse and match downloaded readback exactly')
 editorValidation='Sample imports ctx-1/r2 and boolean-if opened and rendered; the other artifacts have not individually been editor-validated. No simulation results are implied.'
 exceptions=@(
  @{cases=$expected;reason='CPI counter has no input port; requested source-to-counter graph is not drawable as specified.'},
  @{cases=@('obs-target-creation-2:mc-opt/minOccurs-004.xml');reason='Requested in-place change of spec2_nested_grp_row_val remains blocked by existing locked/unsaved editor state; not bypassed or replaced by an independent mapping.'},
  @{cases=@('obs-ifwithoutelse:iwe-1','obs-shared-box:shared-1','obs-shared-box:shared-2');reason='Existing counter input-port blockers remain; historical records unchanged.'},
  @{cases=@('obs-structures:JSON conversion');reason='Adapter/JSON conversion requires an iFlow and remains outside this mapping-only scope.'}
 )
 caveats=@('replaceString fixed pattern/replacement are supplied using native constant input boxes; see per-mapping nativeRepresentation.','The old spec2_sort_by_key_001_api pilot is superseded by api_spec2_sort_by_key because its initial text metadata was stripped.','api_ctx_1_r2 is a diagnostic helper used for native parameter exports, currently containing currentDate. It is not a prepared observation and is excluded from counts.','Existing observation outputs and meta.json run histories were not changed. Input XML files and exact runtime capture requirements remain in each linked request.')
 mappings=@($items|Sort-Object observation,artifactId)
}
$index|ConvertTo-Json -Depth 12|Set-Content (Join-Path $PSScriptRoot 'preparation-index.json') -Encoding utf8
Write-Output ('VERIFIED_PASS mappings='+$items.Count+' cases='+$index.caseCount+' resources='+$resources+'; only expected three counter cases absent from six new function sets')
