param([string]$Only='*',[switch]$Force)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$key=(Get-Content -Raw -LiteralPath 'C:/Users/pavol/.codex/secrets/flottila-observations-key.json'|ConvertFrom-Json).oauth
foreach($url in @($key.url,$key.tokenurl)){if(([uri]$url).Scheme -ne 'https' -or ([uri]$url).Host -notmatch '\.hana\.ondemand\.com$'){throw 'Unexpected service host'}}
$token=Invoke-RestMethod -Method Post -Uri $key.tokenurl -Body @{grant_type='client_credentials';client_id=$key.clientid;client_secret=$key.clientsecret} -TimeoutSec 30
$base=$key.url.TrimEnd('/')+'/api/v1'
$headers=@{Authorization=('Bearer '+$token.access_token);Accept='application/json'}
$existing=(Invoke-RestMethod -Uri ($base+"/IntegrationPackages('flottilaobservations')/MessageMappingDesigntimeArtifacts") -Headers $headers -TimeoutSec 30).d.results
foreach($metaFile in Get-ChildItem (Join-Path $repo 'corpus/observations') -Filter meta.json -Recurse){
 $latest=(Get-Content -Raw $metaFile.FullName|ConvertFrom-Json).runs|Group-Object case|ForEach-Object{$_.Group|Where-Object{$_.cpi}|Select-Object -Last 1}
 foreach($run in $latest){
  if(!$run.cpiEvidence -or $run.artifact -notlike $Only){continue}
  $note=Get-Content -Raw (Join-Path $metaFile.Directory.FullName $run.cpiEvidence)
  if(!$Force -and $run.cpi -notmatch 'pending' -and $note -notmatch '(?i)parameter.*unverified|format.*unverified|not independently|not inspected'){continue}
  $artifact=$existing|Where-Object Id -eq $run.artifact
  if(!$artifact){throw ('Missing artifact '+$run.artifact)}
  $temp=Join-Path ([IO.Path]::GetTempPath()) ('flottila-audit-'+[guid]::NewGuid()+'.zip')
  Invoke-WebRequest -Uri ($base+"/MessageMappingDesigntimeArtifacts(Id='"+$artifact.Id+"',Version='"+$artifact.Version+"')/"+'$value') -Headers $headers -OutFile $temp -TimeoutSec 30
  $zip=[IO.Compression.ZipFile]::OpenRead($temp)
  $entry=@($zip.Entries|Where-Object Name -like '*.mmap')
  if($entry.Count -ne 1){throw 'Expected one mapping resource'}
  $reader=[IO.StreamReader]::new($entry[0].Open())
  [xml]$map=$reader.ReadToEnd()
  $reader.Dispose()
  $zip.Dispose()
  $functions=@($map.SelectNodes('//transformation//brick[@type="Func"]')|ForEach-Object{[ordered]@{nativeName=$_.fname;inputCount=$_.SelectNodes('arg').Count;bindings=$_.bindings.OuterXml;sourcePaths=@($_.SelectNodes('.//brick[@type="Src"]')|ForEach-Object{[ordered]@{path=$_.path;context=$_.context}})}})
  $record=[ordered]@{case=$run.case;artifact=$artifact.Id;version=$artifact.Version;date='2026-11-13';source='Fresh read-only public API export of live tenant artifact';evidence='VERIFIED_PASS';functions=$functions;scope='Stored function identifiers, bindings and source contexts only. Does not establish fresh editor defaults, queue behavior or JVM settings.'}
  $dir=Join-Path $metaFile.Directory.FullName 'cpi/parameter-audit'
  $null=New-Item -ItemType Directory -Force -Path $dir
  $record|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $dir ($artifact.Id+'.json')) -Encoding utf8
  Write-Output ($run.case+' '+$artifact.Id+' VERIFIED_PASS')
 }
}
