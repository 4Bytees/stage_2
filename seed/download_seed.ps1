$seedFile = "seed/book_ids.txt"
if (Test-Path $seedFile) {
    Get-Content $seedFile | ForEach-Object {
        $id = $_.Trim()
        if ($id -ne "") {
            Write-Host "Ingestando libro ID: $id..."
            Invoke-RestMethod -Method POST -Uri "http://localhost:7001/ingest/$id"
        }
    }
} else {
    Write-Host "No se encontró el archivo $seedFile"
}