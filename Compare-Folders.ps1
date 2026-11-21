<#
.SYNOPSIS
    Compares two folders recursively using SHA-256 file hashes.
.EXAMPLE
    .\Compare-Folders.ps1 "C:\FolderA" "D:\FolderB"
#>
[CmdletBinding()]
param (
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateScript({ Test-Path $_ -PathType Container })]
    [string]$FolderA,

    [Parameter(Mandatory = $true, Position = 1)]
    [ValidateScript({ Test-Path $_ -PathType Container })]
    [string]$FolderB
)

# Resolve full normalized paths (handles trailing slashes & relative paths like .\folder)
$pathA = (Resolve-Path -Path $FolderA).Path.TrimEnd('\')
$pathB = (Resolve-Path -Path $FolderB).Path.TrimEnd('\')

function Get-FolderHashes ($BasePath) {
    # Ensure prefix length accounts for the trailing backslash
    $prefixLength = $BasePath.Length + 1
    
    Get-ChildItem -Path $BasePath -Recurse -File -Force | ForEach-Object {
        [PSCustomObject]@{
            RelativePath = $_.FullName.Substring($prefixLength)
            Hash         = (Get-FileHash -Path $_.FullName -Algorithm SHA256).Hash
        }
    }
}

Write-Verbose "Hashing files in $pathA..."
$hashesA = @(Get-FolderHashes -BasePath $pathA)

Write-Verbose "Hashing files in $pathB..."
$hashesB = @(Get-FolderHashes -BasePath $pathB)

$differences = Compare-Object -ReferenceObject $hashesA -DifferenceObject $hashesB -Property RelativePath, Hash

if (-not $differences) {
    Write-Host "IDENTICAL: All files and hashes match." -ForegroundColor Green
    exit 0
}
else {
    Write-Warning "DIFFERENCES FOUND:"
    $differences | Format-Table -AutoSize
    exit 1
}
