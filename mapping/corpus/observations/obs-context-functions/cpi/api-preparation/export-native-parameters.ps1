param([string]$Label)
$ErrorActionPreference='Stop'
$key=(Get-Content -Raw -LiteralPath 'C:/Users/pavol/.codex/secrets/flottila-observations-key.json'|ConvertFrom-Json).oauth
$token=Invoke-RestMethod -Method Post -Uri $key.tokenurl -Body @{grant_type='client_credentials';client_id=$key.clientid;client_secret=$key.clientsecret} -TimeoutSec 30
$file=Join-Path ([IO.Path]::GetTempPath()) 'flottila-parameter-export.zip'
Invoke-WebRequest -Uri ($key.url.TrimEnd('/')+"/api/v1/MessageMappingDesigntimeArtifacts(Id='api_ctx_1_r2',Version='Draft')/"+'$value') -Headers @{Authorization=('Bearer '+$token.access_token)} -OutFile $file -TimeoutSec 30
$zip=[IO.Compression.ZipFile]::OpenRead($file)
$entry=$zip.Entries|Where-Object Name -like '*.mmap'
$reader=[IO.StreamReader]::new($entry.Open());[xml]$map=$reader.ReadToEnd();$reader.Dispose();$zip.Dispose()
$functions=@($map.SelectNodes('//brick[@type="Func"]')|ForEach-Object{[ordered]@{nativeName=$_.fname;inputCount=$_.SelectNodes('arg').Count;bindings=$_.bindings.OuterXml}})
$record=[ordered]@{source='Native CPI editor Save followed by public API export of preparation copy api_ctx_1_r2';label=$Label;simulation='NOT_RUN';functions=$functions}
$record|ConvertTo-Json -Depth 8|Set-Content (Join-Path $PSScriptRoot ('native-'+$Label+'.json')) -Encoding utf8
$functions|ConvertTo-Json -Depth 8
