param([switch]$Upload,[string]$Only='*',[string]$Revision='',[switch]$Repair)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$template=Join-Path $repo 'corpus/observations/obs-context-functions/cpi/api-pilot-sortByKey-001/spec2_sort_by_key_001_api.zip'
$defs=[Collections.Generic.List[object]]::new()
function Esc([string]$v){[Security.SecurityElement]::Escape($v)}
function S([string]$path,[string]$ctx){'<brick type="Src" gid="0" path="'+$path+'" context="'+$ctx+'"><viewData x="20" y="40"/></brick>'}
function B([string]$name,[string]$value){'<bindings><param name="'+$name+'"><value>'+(Esc $value)+'</value></param></bindings>'}
function F([string]$name,[string[]]$argsIn,[string]$bindings=''){
 $v='<brick type="Func" fns="dflt" fname="'+$name+'"><viewData x="260" y="80"/>'
 for($i=0;$i -lt $argsIn.Count;$i++){$v+='<arg pin="'+$i+'">'+$argsIn[$i]+'</arg>'}
 $v+$bindings+'</brick>'
}
function D([string]$path,[string]$expr){'<brick type="Dst" gid="0" path="'+$path+'"><viewData x="600" y="80"/><arg>'+$expr+'</arg><group/></brick>'}
function K([string]$v){F 'const' @() (B 'value' $v)}
function I([string]$condition,[string]$value,[bool]$keep=$false){F 'ifWithoutElse' @($condition,$value) $(if($keep){B 'keepss' 'true'}else{''})}
function Add([string]$set,[string]$name,[string]$graph,[string[]]$cases,[string]$target='tgt-list.xsd',[string]$root='Out'){
 $defs.Add([pscustomobject]@{set=$set;name=$name;id=('api_'+$name.Replace('-','_'));graph=$graph;cases=$cases;target=$target;root=$root})
}
$order=S '/Orders/Order' '/Orders'
$sku=S '/Orders/Order/Line/Sku' '/Orders'
$qty=S '/Orders/Order/Line/Qty' '/Orders'
$tag=S '/Orders/Order/Tag' '/Orders'
$skuO=S '/Orders/Order/Line/Sku' '/Orders/Order'
$qtyO=S '/Orders/Order/Line/Qty' '/Orders/Order'
$tagO=S '/Orders/Order/Tag' '/Orders/Order'
$skuL=S '/Orders/Order/Line/Sku' '/Orders/Order/Line'
$qtyL=S '/Orders/Order/Line/Qty' '/Orders/Order/Line'
$parent=D '/Out/Item' $order
Add 'obs-context-functions' 'ctx-1' (D '/Out/All' (F 'removeContexts' @($skuL))) @('ctx-1/f1.xml')
Add 'obs-context-functions' 'spec2-suppress-sort-by-key' (D '/Out/All' (F 'sortByKey' @((F 'createIf' @($tag)),$sku) '<bindings><param name="comparator"><value><sort_comp type="cs"/></value></param><param name="order"><value><sort_order asc="true"/></value></param></bindings>')) @('fn/sortByKey/006')
$splitEmpty=F 'SplitByValue' @((F 'removeContexts' @($tagO))) (B 'type' '1')
Add 'obs-context-functions' 'spec2-split-by-value-empty' (D '/Out/All' $splitEmpty) @('fn/splitByValue/011','fn/splitByValue/012','fn/splitByValue/013','fn/splitByValue/014')
Add 'obs-context-functions' 'spec2-split-by-value-empty-flat' (D '/Out/All' (F 'removeContexts' @($splitEmpty))) @('fn/splitByValue/015','fn/splitByValue/016')
Add 'obs-context-functions' 'spec2-suppress-split-empty-collapse' (D '/Out/All' (F 'collapseContexts' @((F 'SplitByValue' @((F 'createIf' @($tag))) (B 'type' '1'))))) @('fn/splitByValue/021')
foreach($fn in @('and','or','equals','notEquals')){Add 'obs-boolean' ('boolean-'+$fn) (D '/Out/All' (F $fn @($sku,$qty))) @('fn/'+$fn+'/001')}
foreach($fn in @('not','isNil')){Add 'obs-boolean' ('boolean-'+$fn) (D '/Out/All' (F $fn @($sku))) @('fn/'+$fn+'/001')}
# Native CPI if: value, condition, else (palette description and native Save).
Add 'obs-boolean' 'boolean-if' (D '/Out/All' (F 'iF' @($qty,$sku,$tag))) @('fn/if/001')
Add 'obs-boolean' 'boolean-ifWithoutElse-item' ((D '/Out/Item' (I $qty $sku))+(D '/Out/Item/V' $skuL)) @('fn/ifWithoutElse/001')
Add 'obs-boolean' 'boolean-ifWithoutElse-item-keep' ((D '/Out/Item' (I $qty $sku $true))+(D '/Out/Item/V' $skuL)) @('fn/ifWithoutElse/002')
Add 'obs-boolean' 'boolean-ifWithoutElse-orders' ($parent+(D '/Out/Item/V' (I $qtyO $skuO))+(D '/Out/Item/W' (I $qtyO $skuO $true))) @('fn/ifWithoutElse/003')
Add 'obs-boolean' 'boolean-ifWithoutElse-tags' ($parent+(D '/Out/Item/V' (I $qtyO $tagO))+(D '/Out/Item/W' (I $qtyO $tagO $true))) @('fn/ifWithoutElse/004')
Add 'obs-boolean' 'boolean-ifWithoutElse-suppress' ((D '/Out/Item' (I $sku (F 'createIf' @($qty))))+(D '/Out/Item/V' $qtyL)) @('fn/ifWithoutElse/005')
Add 'obs-boolean' 'boolean-ifWithoutElse-suppress-condition' ((D '/Out/Item' (I (I $sku $qty $true) $qty))+(D '/Out/Item/V' $qtyL)) @('fn/ifWithoutElse/007')
Add 'obs-boolean' 'boolean-ifWithoutElse-empty-keep' ((D '/Out/Item' (F 'removeContexts' @((I $qtyO $tagO $true))))+(D '/Out/Item/V' $skuL)) @('fn/ifWithoutElse/008')
Add 'obs-boolean' 'boolean-ifWithoutElse-collapse' (D '/Out/All' (F 'collapseContexts' @((I $qtyO $skuL)))) @('fn/ifWithoutElse/009')
Add 'obs-boolean' 'boolean-ifWithoutElse-collapse-tags' (D '/Out/All' (F 'collapseContexts' @((I $qtyO $tag)))) @('fn/ifWithoutElse/010')
Add 'obs-constant' 'constant-item' ($parent+(D '/Out/Item/V' (K 'x'))) @('fn/constant/003')
Add 'obs-constant' 'constant-concat' ($parent+(D '/Out/Item/V' (F 'concat' @($skuO,(K '-x'))))+(D '/Out/Item/W' (F 'concat' @((K 'y-'),$skuO)))) @('fn/constant/004')
Add 'obs-constant' 'constant-concat-constants' ($parent+(D '/Out/Item/V' (F 'concat' @((K 'a'),(K 'b'))))+(D '/Out/All' (F 'concat' @((K 'a'),(K 'b'))))) @('fn/constant/005')
Add 'obs-constant' 'constant-count' ($parent+(D '/Out/Item/V' (F 'count' @((K 'x'))))+(D '/Out/Item/W' (K 'w'))) @('fn/constant/006')
Add 'obs-constant' 'constant-gen-numbering' ((D '/Gens/Grp' $order)+(D '/Gens/Grp/Gen' (K 'g'))+(D '/Gens/Grp/Gen/V' $qtyL)) @('fn/constant/007') 'tgt-gen.xsd' 'Gens'
Add 'obs-constant' 'constant-empty' (D '/Out/All' (K '')) @('fn/constant/008')
foreach($fn in @('sum','average','count')){
 $src=if($fn -eq 'count'){$skuO}else{$qtyO}
 $case=if($fn -eq 'average'){'003'}else{'002'}
 Add 'obs-statistic' ('stat-'+$fn) ($parent+(D '/Out/Item/V' (F $fn @($src)))) @('fn/'+$fn+'/'+$case)
 $src=F 'createIf' @($tagO)
 if($fn -ne 'count'){$src=F 'replaceValue' @($src) (B 'value' '5')}
 $case=if($fn -eq 'average'){'004'}else{'003'}
 Add 'obs-statistic' ('stat-'+$fn+'-suppress') ($parent+(D '/Out/Item/V' (F $fn @($src)))) @('fn/'+$fn+'/'+$case)
}
foreach($fn in @('concat','equalsS','startsWith','endsWith')){
 $native=switch($fn){'equalsS' {'stringEquals'} 'startsWith' {'startWith2'} 'endsWith' {'endWith'} default {$fn}}
 Add 'obs-text' ('text-'+$fn) (D '/Out/All' (F $native @($sku,$qty))) @('fn/'+$fn+'/001')
}
foreach($fn in @('toUpperCase','toLowerCase','trim')){Add 'obs-text' ('text-'+$fn) (D '/Out/All' (F $fn @($sku))) @('fn/'+$fn+'/001')}
Add 'obs-arithmetic' 'arith-inv' (D '/Out/All' (F 'inv' @($qty))) @('fn/inv/001')
Add 'obs-lift-reuse' 'lift-1' ($parent+(D '/Out/Item/V' (F 'concat' @($tagO,$skuO)))) @('lift-1/l1.xml')
Add 'obs-useoneasmany' 'spec2-use-one-as-many-root-value' (D '/Out/All' (F 'removeContexts' @((F 'useOneAsMany' @((S '/Orders/Order/Id' '/Orders'),$skuO,$skuL))))) @('fn/useOneAsMany/003')
Add 'obs-structures' 'str-attr' ((D '/Flagged/@flag' (F 'createIf' @($tagO)))+(D '/Flagged/@code' (S '/Orders/Order/Id' '/Orders/Order'))+(D '/Flagged/V' (S '/Orders/Order/Id' '/Orders'))) @('target/attribute/001','target/attribute/002','target/attribute/003') 'tgt-attr.xsd' 'Flagged'

# Parameter encodings below come from native CPI editor Save/API exports.
foreach($row in @(@('each','0'),@('change','2'))){
 $split=F 'SplitByValue' @((F 'removeContexts' @($tagO))) (B 'type' $row[1])
 $cases=if($row[0] -eq 'each'){@('fn/splitByValue/004','fn/splitByValue/005')}else{@('fn/splitByValue/006','fn/splitByValue/007','fn/splitByValue/008','fn/splitByValue/009','fn/splitByValue/010')}
 Add 'obs-context-functions' ('spec2-split-by-value-'+$row[0]) (D '/Out/All' $split) $cases
 $split=F 'SplitByValue' @((F 'createIf' @($tag))) (B 'type' $row[1])
 $case=if($row[0] -eq 'each'){'019'}else{'020'}
 Add 'obs-context-functions' ('spec2-suppress-split-'+$row[0]+'-collapse') (D '/Out/All' (F 'collapseContexts' @($split))) @('fn/splitByValue/'+$case)
}
Add 'obs-context-functions' 'spec3-suppress-split-each-item' ((D '/Out/Item' $tag)+(D '/Out/Item/V' (F 'SplitByValue' @((F 'createIf' @($tag))) (B 'type' '0')))) @('fn/splitByValue/022')
Add 'obs-context-functions' 'spec2-sort-by-key' (D '/Out/All' (F 'removeContexts' @((F 'sortByKey' @($skuO,$qtyO) '<bindings><param name="comparator"><value><sort_comp type="cs"/></value></param><param name="order"><value><sort_order asc="true"/></value></param></bindings>')))) @('fn/sortByKey/001','fn/sortByKey/004')
foreach($row in @(@('case-insensitive','cis','true','002'),@('numeric-descending','fn','false','003'))){
 $bindings='<bindings><param name="comparator"><value><sort_comp type="'+$row[1]+'"/></value></param><param name="order"><value><sort_order asc="'+$row[2]+'"/></value></param></bindings>'
 Add 'obs-context-functions' ('spec2-sort-by-key-'+$row[0]) (D '/Out/All' (F 'removeContexts' @((F 'sortByKey' @($skuO,$qtyO) $bindings)))) @('fn/sortByKey/'+$row[3])
}
function FixBinding([string]$strategy,[string]$defaultValue,[string]$table){'<bindings><param name="vmdefault"><value>'+(Esc $defaultValue)+'</value></param><param name="vmstrategy"><value>'+$strategy+'</value></param><param name="table"><value><properties>'+$table+'</properties></value></param></bindings>'}
$ad='<property name="a">A</property><property name="d">D</property>'
foreach($row in @(@('fix-values-key','2','',$ad,'002'),@('fix-values-exception','0','',$ad,'004'),@('fix-values-default','1','?',$ad,'003'),@('fix-values-default-empty','1','','<property name="a">A</property>','005'),@('fix-values-empty-table','1','-','','006'),@('fix-values-exact','1','?','<property name="a">A</property><property name=" b">B</property>','008'),@('fix-values-suppress','0','','<property name="">E</property>','007'))){
 $src=if($row[0] -eq 'fix-values-suppress'){F 'createIf' @($tagO)}else{$skuO}
 Add 'obs-constant' $row[0] ($parent+(D '/Out/Item/V' (F 'FixValues' @($src) (FixBinding $row[1] $row[2] $row[3])))) @('fn/fixValues/'+$row[4])
}
$indexBinding='<bindings><param name="start"><value>1</value></param><param name="inc"><value>1</value></param><param name="type"><value>0</value></param></bindings>'
foreach($row in @(@('a-bb','a','bb','001'),@('dollar','$','\$1','002'),@('empty-search','','-','003'),@('aa-b','aa','b','004'))){
 Add 'obs-text' ('text-replaceString-'+$row[0]) (D '/Out/All' (F 'replaceString' @($sku,(K $row[1]),(K $row[2])))) @('fn/replaceString/'+$row[3])
}
Add 'obs-statistic' 'stat-index-default' ($parent+(D '/Out/Item/V' (F 'index' @($skuO) $indexBinding))) @('fn/index/003')
Add 'obs-statistic' 'stat-index-suppress' ($parent+(D '/Out/Item/V' (F 'index' @((F 'createIf' @($tagO))) $indexBinding))) @('fn/index/004')
foreach($pair in @(@('copy-value','1','001'),@('copy-value-default','0','002'),@('copy-value-beyond','3','003'),@('copy-value-suppress','1','004'))){
 $src=if($pair[0] -eq 'copy-value-suppress'){F 'createIf' @($tagO)}else{$skuO}
 Add 'obs-constant' $pair[0] ($parent+(D '/Out/Item/V' (F 'CopyValue' @($src) (B 'nnumber' $pair[1])))) @('fn/copyValue/'+$pair[2])
}
$arith=Get-Content -Raw (Join-Path $repo 'corpus/observations/obs-arithmetic/request.md')
foreach($row in [regex]::Matches($arith,'\| `fn/formatNum/(\d+)` \| `[^`]+` \| `([^`]+)`')){
 $binding=('<bindings><param name="nformat"><value>'+(Esc $row.Groups[2].Value)+'</value></param><param name="separator"><value>.</value></param></bindings>')
 Add 'obs-arithmetic' ('fmt-'+$row.Groups[1].Value) (D '/Out/All' (F 'formatNumber' @($qty) $binding)) @('fn/formatNum/'+$row.Groups[1].Value)
}
function DateBinding([string]$inputFormat,[string]$outputFormat){'<bindings><param name="iform"><value>'+(Esc $inputFormat)+'</value></param><param name="oform"><value>'+(Esc $outputFormat)+'</value></param><param name="calend"><value><calend_props><fd>1</fd><md>1</md><le>true</le></calend_props></value></param></bindings>'}
function Current([string]$format){F 'currentDate' @() ('<bindings><param name="oform"><value>'+(Esc $format)+'</value></param><param name="calend"><value><calend_props><fd>1</fd><md>1</md><le>true</le></calend_props></value></param></bindings>')}
Add 'obs-date' 'current-date' (D '/Out/All' (Current 'yyyy/MM/dd')) @('fn/currentDate/001')
Add 'obs-date' 'current-date-time' (D '/Out/All' (Current "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")) @('fn/currentDate/002')
Add 'obs-date' 'current-date-item' ($parent+(D '/Out/Item/V' (Current 'yyyyMMdd'))) @('fn/currentDate/003')
Add 'obs-date' 'current-date-concat' ($parent+(D '/Out/Item/V' (F 'concat' @($skuO,(Current "'@'HH:mm:ss.SSS"))))) @('fn/currentDate/004')
$dates=Get-Content -Raw (Join-Path $repo 'corpus/observations/obs-date/request.md')
foreach($row in [regex]::Matches($dates,'\| `fn/dateTrans/(\d+)` \| `[^`]+` \| `([^`]+)` to `([^`]+)`')){
 Add 'obs-date' ('date-trans-'+$row.Groups[1].Value) (D '/Out/All' (F 'TransformDate' @($sku) (DateBinding $row.Groups[2].Value $row.Groups[3].Value))) @('fn/dateTrans/'+$row.Groups[1].Value)
}
Add 'obs-date' 'date-trans-006' (D '/Out/All' (F 'TransformDate' @($sku) (DateBinding 'yyyy/MM/dd' 'yyyy/MM/dd'))) @('fn/dateTrans/006')
Add 'obs-date' 'date-after-001' (D '/Out/All' (F 'DateAfter' @($sku,$qty) (DateBinding 'yyyyMMdd' 'dd.MM.yyyy'))) @('fn/dateAfter/001')
Add 'obs-date' 'date-before-001' (D '/Out/All' (F 'DateBefore' @($sku,$qty) (DateBinding 'yyyyMMdd' 'dd.MM.yyyy'))) @('fn/dateBefore/001')
foreach($row in [regex]::Matches($dates,'\| `fn/compareDates/(\d+)` \| `[^`]+` \| `([^`]+)`(?:, `([^`]+)`| both)')){
 $second=if($row.Groups[3].Success){$row.Groups[3].Value}else{$row.Groups[2].Value}
 Add 'obs-date' ('compare-dates-'+$row.Groups[1].Value) (D '/Out/All' (F 'CompareDates' @($sku,$qty) (DateBinding $row.Groups[2].Value $second))) @('fn/compareDates/'+$row.Groups[1].Value)
}

if($Upload){
 $key=(Get-Content -Raw -LiteralPath 'C:/Users/pavol/.codex/secrets/flottila-observations-key.json'|ConvertFrom-Json).oauth
 foreach($url in @($key.url,$key.tokenurl)){if(([uri]$url).Scheme -ne 'https' -or ([uri]$url).Host -notmatch '\.hana\.ondemand\.com$'){throw 'Unexpected service host'}}
 $token=Invoke-RestMethod -Method Post -Uri $key.tokenurl -Body @{grant_type='client_credentials';client_id=$key.clientid;client_secret=$key.clientsecret} -TimeoutSec 30
 $base=$key.url.TrimEnd('/')+'/api/v1'
 $headers=@{Authorization=('Bearer '+$token.access_token);Accept='application/json';'X-CSRF-Token'='Fetch'}
 $csrf=Invoke-WebRequest -Uri ($base+"/IntegrationPackages('flottilaobservations')") -Headers $headers -SessionVariable session -TimeoutSec 30
 $headers['X-CSRF-Token']=($csrf.Headers['X-CSRF-Token']|Select-Object -First 1)
 $existing=(Invoke-RestMethod -Uri ($base+"/IntegrationPackages('flottilaobservations')/MessageMappingDesigntimeArtifacts") -Headers $headers -WebSession $session -TimeoutSec 30).d.results
}
foreach($def in $defs){
 if($def.name -notlike $Only){continue}
 $dir=Join-Path $repo ('corpus/observations/'+$def.set+'/cpi/api-preparation/'+$def.name)
 if($Revision){$def.id+='_'+$Revision;$dir=Join-Path $dir $Revision}
 $null=New-Item -ItemType Directory -Force -Path $dir
 if($Repair -and (Test-Path (Join-Path $dir 'readback.zip')) -and !(Test-Path (Join-Path $dir 'initial-import'))){
  $audit=Join-Path $dir 'initial-import';$null=New-Item -ItemType Directory $audit
  foreach($file in @('upload.zip','readback.zip','mapping.mmap','preparation.json')){Copy-Item (Join-Path $dir $file) $audit}
 }
 $work=Join-Path ([IO.Path]::GetTempPath()) ('flottila-'+[guid]::NewGuid())
 Expand-Archive -LiteralPath $template -DestinationPath $work
 $old=Join-Path $work 'src/main/resources/mapping/spec2_sort_by_key_001_api.mmap'
 [xml]$map=Get-Content -Raw -LiteralPath $old
 $map.SelectSingleNode('//transformation').InnerXml=$def.graph
 {
  $textInfo=$map.SelectSingleNode('//textInfo')
  $textInfo.SetAttribute('loadedL','EN')
  $textInfo.InnerXml='<textObj id="00000000000000000000000000000001" masterL="EN" type="0"><texts lang="EN"><text label=""/><text label="00000000000000000000000000000002"/></texts></textObj>'
 }.Invoke()
 $link=$map.SelectSingleNode('//lnkRole[@role="TARGET_IFR_MESS"]/lnk/key')
 $link.SelectNodes('elem')[0].InnerText=$def.target
 $link.SelectNodes('elem')[2].InnerText=$def.root
 if($def.target -ne 'tgt-list.xsd'){
  Copy-Item -LiteralPath (Join-Path $repo ('corpus/structures/observations/'+$def.target)) -Destination (Join-Path $work 'src/main/resources/xsd')
  Remove-Item -LiteralPath (Join-Path $work 'src/main/resources/xsd/tgt-list.xsd')
 }
 $map.Save($old)
 Rename-Item -LiteralPath $old -NewName ($def.id+'.mmap')
 foreach($rel in @('.project','META-INF/MANIFEST.MF')){$p=Join-Path $work $rel;[IO.File]::WriteAllText($p,((Get-Content -Raw $p).Replace('spec2_sort_by_key_001_api',$def.id)),[Text.UTF8Encoding]::new($false))}
 [IO.File]::WriteAllText((Join-Path $work 'metainfo.prop'),'description=API preparation only; editor verification and simulation pending',[Text.UTF8Encoding]::new($false))
 Copy-Item (Join-Path $work ('src/main/resources/mapping/'+$def.id+'.mmap')) (Join-Path $dir 'mapping.mmap')
 $zip=Join-Path $dir 'upload.zip'
 Compress-Archive -Path (Join-Path $work '*') -DestinationPath $zip -Force
 $record=[ordered]@{artifactId=$def.id;logicalMapping=$def.name;cases=$def.cases;status='PREPARED_LOCALLY';simulation='NOT_RUN';editorValidation='NOT_RUN';notes='No observation output. New independent artifact; historical mappings untouched.'}
 if($def.name -like 'text-replaceString-*'){$record.nativeRepresentation='CPI replaceString has three input ports: source, pattern, replacement. Fixed search/replacement from request are supplied by const boxes; this differs from the request parameter notation and must remain explicit when interpreting future observations.'}
 if($Upload){
  try {
   $found=$existing|Where-Object Id -eq $def.id
   if($found -and $Repair){
    $before=Join-Path $work 'before-repair.zip'
    Invoke-WebRequest -Uri ($base+"/MessageMappingDesigntimeArtifacts(Id='"+$def.id+"',Version='"+$found.Version+"')/"+'$value') -Headers $headers -WebSession $session -OutFile $before -TimeoutSec 30
    $za=[IO.Compression.ZipFile]::OpenRead((Join-Path $dir 'readback.zip'));$zb=[IO.Compression.ZipFile]::OpenRead($before)
    foreach($entry in $za.Entries|Where-Object {$_.FullName -match '\.(mmap|xsd)$'}){
     $other=$zb.GetEntry($entry.FullName);if(!$other){throw 'Remote artifact was modified; refusing replacement'}
     $s=[IO.StreamReader]::new($entry.Open());$a=$s.ReadToEnd();$s.Dispose();$s=[IO.StreamReader]::new($other.Open());$b=$s.ReadToEnd();$s.Dispose()
     if($a -cne $b){throw 'Remote artifact was modified; refusing replacement'}
    };$za.Dispose();$zb.Dispose()
    $deleted=Invoke-WebRequest -Method Delete -Uri ($base+"/MessageMappingDesigntimeArtifacts(Id='"+$def.id+"',Version='"+$found.Version+"')") -Headers $headers -WebSession $session -TimeoutSec 30
    $record.repair='Recreated own API import after exact remote resource guard; restored required textInfo structure with synthetic identifiers. Initial import preserved locally.'
    $found=$null
   }
   if(!$found){
    $body=@{Id=$def.id;Name=$def.id;PackageId='flottilaobservations';ArtifactContent=[Convert]::ToBase64String([IO.File]::ReadAllBytes($zip))}|ConvertTo-Json -Compress
    $response=Invoke-WebRequest -Method Post -Uri ($base+'/MessageMappingDesigntimeArtifacts') -Headers $headers -WebSession $session -ContentType 'application/json' -Body $body -TimeoutSec 30
    $record.createHttp=[int]$response.StatusCode
    $version='1.0.0'
   }else{$version=$found.Version;$record.createHttp='existing; no overwrite'}
   $readback=Join-Path $work 'readback.zip'
   Invoke-WebRequest -Uri ($base+"/MessageMappingDesigntimeArtifacts(Id='"+$def.id+"',Version='"+$version+"')/"+'$value') -Headers $headers -WebSession $session -OutFile $readback -TimeoutSec 30
   $za=[IO.Compression.ZipFile]::OpenRead($zip);$zb=[IO.Compression.ZipFile]::OpenRead($readback)
   foreach($entry in $za.Entries|Where-Object {$_.FullName -match '\.(mmap|xsd)$'}){
    $other=$zb.GetEntry($entry.FullName);if(!$other){throw 'Missing readback resource'}
    $s=[IO.StreamReader]::new($entry.Open());$a=$s.ReadToEnd();$s.Dispose();$s=[IO.StreamReader]::new($other.Open());$b=$s.ReadToEnd();$s.Dispose()
    if($a -cne $b){throw 'Readback resource differs'}
   }
   $za.Dispose();$zb.Dispose()
   Copy-Item $readback (Join-Path $dir 'readback.zip')
   $record.status='API_CREATED_READBACK_VERIFIED';$record.version=$version;$record.readback='VERIFIED_PASS: exact mmap and XSD content match'
  } catch {
   $record.status='API_PREPARATION_FAILED'
   $record.errorType=$_.Exception.GetType().Name
   if($_.Exception.Response){$record.http=[int]$_.Exception.Response.StatusCode}
   # Never persist raw HTTP exceptions, credentials, bearer tokens or tenant URLs.
  }
 }
 $record|ConvertTo-Json -Depth 8|Set-Content (Join-Path $dir 'preparation.json') -Encoding utf8
 Write-Output ($def.id+' '+$record.status)
}
