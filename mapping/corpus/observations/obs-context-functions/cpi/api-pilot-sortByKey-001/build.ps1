param([Parameter(Mandatory=$true)][string]$TemplateZip)
$ErrorActionPreference = 'Stop'
$out = Join-Path $PSScriptRoot 'spec2_sort_by_key_001_api.zip'
$temp = Join-Path ([System.IO.Path]::GetTempPath()) ('flottila-pilot-' + [guid]::NewGuid())
Expand-Archive -LiteralPath $TemplateZip -DestinationPath $temp
$old = Join-Path $temp 'src/main/resources/mapping/spec2_sort.mmap'
[xml]$map = Get-Content -Raw -LiteralPath $old
$graph = $map.SelectSingleNode('//transformation')
$graph.InnerXml = '<brick gid="0" path="/Out/All" type="Dst"><viewData x="600" y="80"/><arg><brick fname="removeContexts" fns="dflt" type="Func"><viewData x="420" y="80"/><arg><brick fname="sortByKey" fns="dflt" type="Func"><viewData x="240" y="80"/><arg><brick context="/Orders/Order" gid="0" path="/Orders/Order/Line/Sku" type="Src"><viewData x="20" y="40"/></brick></arg><arg pin="1"><brick context="/Orders/Order" gid="0" path="/Orders/Order/Line/Qty" type="Src"><viewData x="20" y="160"/></brick></arg><bindings><param name="comparator"><value><sort_comp type="cs"/></value></param><param name="order"><value><sort_order asc="true"/></value></param></bindings></brick></arg></brick></arg><group/></brick>'
# Remove historical administrative identifiers from the prepared copy.
$map.SelectSingleNode('//admInf').InnerXml = '<modifBy/><modifAt/><owner/>'
# Preserve required editor metadata structure with synthetic identifiers.
# Historical archives are not regenerated; the active pilot is api_spec2_sort_by_key.
$textInfo=$map.SelectSingleNode('//textInfo')
$textInfo.SetAttribute('loadedL','EN')
$textInfo.InnerXml='<textObj id="00000000000000000000000000000001" masterL="EN" type="0"><texts lang="EN"><text label=""/><text label="00000000000000000000000000000002"/></texts></textObj>'
$map.Save($old)
Rename-Item -LiteralPath $old -NewName 'spec2_sort_by_key_001_api.mmap'
foreach ($relative in @('.project','META-INF/MANIFEST.MF')) {
    $path = Join-Path $temp $relative
    $text = (Get-Content -Raw -LiteralPath $path).Replace('spec2_sort','spec2_sort_by_key_001_api')
    [System.IO.File]::WriteAllText($path,$text,[System.Text.UTF8Encoding]::new($false))
}
[System.IO.File]::WriteAllText((Join-Path $temp 'metainfo.prop'),'description=Prepared sortByKey/001 pilot; simulation pending',[System.Text.UTF8Encoding]::new($false))
Compress-Archive -Path (Join-Path $temp '*') -DestinationPath $out -Force
Copy-Item -LiteralPath (Join-Path $temp 'src/main/resources/mapping/spec2_sort_by_key_001_api.mmap') -Destination $PSScriptRoot
Write-Output 'Prepared ZIP and mapping; no simulation result claimed.'
