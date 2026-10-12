$ErrorActionPreference = 'Stop'
# Windows builds with unchanged native inputs reuse the maintainer's checksum-verified patched runtime.
# For native development, use fetch-proot.sh under Linux to build PRoot from source.
$projectRoot = Split-Path $PSScriptRoot
$gradle = Get-Content -LiteralPath (Join-Path $projectRoot 'app/build.gradle.kts') -Raw
$version = [regex]::Match($gradle, 'versionName = "([^"]+)"').Groups[1].Value
if (-not $version) { throw 'Cannot read app version' }
$tag = "v$version"
& git -C $projectRoot diff --quiet $tag -- patches/proot scripts/fetch-proot.sh
if ($LASTEXITCODE -ne 0) { throw 'Native inputs differ from release. Build with fetch-proot.sh under Linux.' }
$release = Invoke-RestMethod "https://api.github.com/repos/SimonSchubert/omalauncher/releases/tags/$tag"
$asset = $release.assets | Where-Object { $_.name -eq "omalauncher-$version.apk" } | Select-Object -First 1
if (-not $asset -or $asset.digest -notmatch '^sha256:([a-f0-9]{64})$') { throw 'Release APK lacks a SHA256 digest' }
$expected = $Matches[1]
$scratch = Join-Path $projectRoot "build/native-release-$version"
$output = Join-Path $projectRoot 'app/src/main/jniLibs/arm64-v8a'
New-Item -ItemType Directory -Path $scratch,$output -Force | Out-Null
$apk = Join-Path $scratch $asset.name
if (-not (Test-Path -LiteralPath $apk) -or (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
    Invoke-WebRequest $asset.browser_download_url -OutFile $apk
}
if ((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'Upstream APK SHA256 mismatch' }
$archive = [IO.Compression.ZipFile]::OpenRead($apk)
try {
    foreach ($name in @('libproot.so','libproot-loader.so','libtalloc.so','libandroid-shmem.so')) {
        $entry = $archive.GetEntry("lib/arm64-v8a/$name")
        if (-not $entry) { throw "Upstream APK is missing $name" }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $output $name), $true)
    }
} finally { $archive.Dispose() }
Write-Output "Verified native runtime from $tag (APK SHA256 $expected)"
