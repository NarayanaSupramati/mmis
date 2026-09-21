[CmdletBinding()]
param(
 [string]$Output = 'artifacts/android-xxdk/build-1',
 [string]$AndroidSdk = $env:ANDROID_HOME,
 [string]$Jdk = $env:JAVA_HOME
)
$ErrorActionPreference='Stop'
$workspace=Split-Path $PSScriptRoot -Parent
Set-Location $workspace
$source=Join-Path $workspace 'temp/elixxir-client-v4.7.9'
$cryptoSource=Join-Path $workspace 'temp/reconstruction/xxnetwork-crypto'
$go=Join-Path $workspace 'temp/toolchains/go1.21.5/go/bin/go.exe'
$lab=Join-Path $workspace 'temp/mmis-android'
$outputPath=[IO.Path]::GetFullPath((Join-Path $workspace $Output))
if(Test-Path -LiteralPath $outputPath){throw 'Choose a new output directory.'}
function Run([string]$Program,[string[]]$Arguments){ & $Program @Arguments; if($LASTEXITCODE -ne 0){throw "$Program failed ($LASTEXITCODE)"} }
foreach($pin in @(@($source,'5620c7b433c0d5968a02376244d7f4e0a0ab99f4'),@($cryptoSource,'1dfeb262abb240b1593784826e8029ae1fcba658'))){
 if((& git -C $pin[0] rev-parse HEAD) -ne $pin[1]){throw 'Wrong source SHA'}
 if((& git -C $pin[0] status --porcelain)){throw 'Source checkout must be clean'}
}
$proof=& python scripts/inspect-module-history.py $cryptoSource 'gitlab.com/xx_network/crypto' 'v0.0.7' '1dfeb262abb240b1593784826e8029ae1fcba658'
if($LASTEXITCODE -ne 0){throw 'Crypto source proof failed'}
$proof=$proof | ConvertFrom-Json
if($proof.sum -ne 'h1:28a1F36cgx4a0NPMP5OC8O0n95aGwoToVbE5kLPA3xE=' -or $proof.goModSum -ne 'h1:szGHgZe6g34ue89wq5/+iXvEnhCCjNBlBfy094KO7yM='){throw 'Crypto checksum mismatch'}
if((& $go version) -ne 'go version go1.21.5 windows/amd64'){throw 'Expected Go 1.21.5 windows/amd64'}
$ndk=Join-Path $AndroidSdk 'ndk/27.1.12297006'
foreach($file in @("$ndk/source.properties","$AndroidSdk/platforms/android-36/android.jar","$Jdk/bin/javac.exe")){if(!(Test-Path -LiteralPath $file)){throw "Missing prerequisite: $file"}}
if((Get-Content "$ndk/source.properties" -Raw) -notmatch 'Pkg.Revision\s*=\s*27\.1\.12297006'){throw 'Wrong NDK revision'}
if((Get-Content "$Jdk/release" -Raw) -notmatch 'JAVA_RUNTIME_VERSION="17\.0\.20\.1\+1"'){throw 'Expected Temurin JDK 17.0.20.1+1'}
$sdkProperties=Get-Content "$AndroidSdk/platforms/android-36/source.properties" -Raw
if($sdkProperties -notmatch 'AndroidVersion.ApiLevel\s*=\s*36'){throw 'Expected SDK platform 36'}
New-Item -ItemType Directory -Force $lab,"$lab/bin",$outputPath | Out-Null
$modfile=Join-Path $lab 'lab.mod'
[IO.File]::WriteAllText($modfile,[IO.File]::ReadAllText("$source/go.mod")+"`nreplace gitlab.com/xx_network/crypto => `"$($cryptoSource.Replace('\','/'))`"`n",[Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath "$source/go.sum" -Destination "$lab/lab.sum"
$env:JAVA_HOME=$Jdk
$env:JDK_JAVAC_OPTIONS='-encoding UTF-8'
$env:ANDROID_HOME=$AndroidSdk
$env:ANDROID_NDK_HOME=$ndk
$env:GOPATH="$lab/gopath"
$env:GOMODCACHE="$workspace/temp/go-cache/pkg/mod"
$env:GOCACHE="$lab/go-build-cache"
$env:GOBIN="$lab/bin"
$env:GOTOOLCHAIN='local'
$env:GOWORK='off'
$env:GOSUMDB='sum.golang.org'
$env:GOPROXY='https://proxy.golang.org,direct'
$env:GONOSUMDB=''
$env:GOPRIVATE=''
$env:GONOPROXY=''
$env:GOFLAGS=''
$env:PATH="$(Split-Path $go);$Jdk/bin;$lab/bin;$env:PATH"
$mobile='v0.0.0-20240112133503-c713f31d574b'
$mobileInfo=(& $go mod download -json "golang.org/x/mobile@$mobile") | ConvertFrom-Json
if($LASTEXITCODE -ne 0){throw 'Mobile download failed'}
$initSource=Join-Path $mobileInfo.Dir 'cmd/gomobile/init.go'
$patchedInit=Join-Path $lab 'gomobile-init.go'
if(([IO.File]::ReadAllText($initSource) | Select-String 'golang.org/x/mobile/cmd/gobind@latest' -AllMatches).Matches.Count -ne 1){throw 'Unexpected gomobile init source'}
[IO.File]::WriteAllText($patchedInit,[IO.File]::ReadAllText($initSource).Replace('golang.org/x/mobile/cmd/gobind@latest',"golang.org/x/mobile/cmd/gobind@$mobile"))
$overlay=Join-Path $lab 'tool-overlay.json'
@{Replace=@{$initSource=$patchedInit}} | ConvertTo-Json | Set-Content -LiteralPath $overlay
Run $go @('install',"-overlay=$overlay","golang.org/x/mobile/cmd/gomobile@$mobile")
Run $go @('install',"golang.org/x/mobile/cmd/gobind@$mobile")
Run "$lab/bin/gomobile.exe" @('init')
Push-Location $source
try {
 Run $go @('mod','edit',"-modfile=$modfile","-require=golang.org/x/mobile@$mobile")
} finally {Pop-Location}
# gomobile creates one module per ABI. A global -modfile would incorrectly override
# those generated modules too. Use an isolated driver module with pinned local roots.
$driver=Join-Path $lab 'driver'
New-Item -ItemType Directory -Force $driver | Out-Null
$driverMod=[IO.File]::ReadAllText($modfile).Replace('module gitlab.com/elixxir/client/v4','module mmis.invalid/android-build')
$driverMod+="`nrequire gitlab.com/elixxir/client/v4 v4.7.9`nreplace gitlab.com/elixxir/client/v4 => `"$($source.Replace('\','/'))`"`n"
[IO.File]::WriteAllText("$driver/go.mod",$driverMod)
Copy-Item -LiteralPath "$lab/lab.sum" -Destination "$driver/go.sum"
Push-Location $driver
try {
 $env:GOFLAGS='-mod=mod -trimpath'
 & "$lab/bin/gomobile.exe" bind -v -target android -androidapi 21 -o "$outputPath/bindings.aar" 'gitlab.com/elixxir/client/v4/bindings' *> "$outputPath/bind.log"
 if($LASTEXITCODE -ne 0){Get-Content "$outputPath/bind.log" -Tail 24;throw 'gomobile bind failed; see bind.log'}
 & $go list -m -json all | Set-Content "$outputPath/modules.json"
 if($LASTEXITCODE -ne 0){throw 'Module inventory failed'}
} finally {Pop-Location}
foreach($repo in @($source,$cryptoSource)){
 $state=& git -C $repo status --porcelain
 if($LASTEXITCODE -ne 0 -or $state){throw "Source changed during build: $repo"}
}
Copy-Item "$driver/go.mod","$driver/go.sum" -Destination $outputPath
@{
 clientSha='5620c7b433c0d5968a02376244d7f4e0a0ab99f4'; cryptoSha='1dfeb262abb240b1593784826e8029ae1fcba658'
 cryptoProof=$proof; go=(& $go version); mobile=$mobile; mobileSum=$mobileInfo.Sum
 gomobileInitOverlaySha256=(Get-FileHash $patchedInit).Hash.ToLowerInvariant()
 jdk='Eclipse Adoptium 17.0.20.1+1'; sdkPlatform=$sdkProperties; ndk='27.1.12297006'
 host=[Environment]::OSVersion.VersionString; goFlags=$env:GOFLAGS; javacOptions=$env:JDK_JAVAC_OPTIONS
 command='gomobile bind -v -target android -androidapi 21 -o <output>/bindings.aar gitlab.com/elixxir/client/v4/bindings'
 sourceUnchanged=$true
} | ConvertTo-Json -Depth 5 | Set-Content "$outputPath/build-metadata.json"
Run 'python' @('scripts/inspect-android-aar.py',$outputPath)
Get-FileHash "$outputPath/bindings.aar","$outputPath/bindings-sources.jar" -Algorithm SHA256
