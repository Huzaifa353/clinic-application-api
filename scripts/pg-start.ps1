# Starts the local PostgreSQL server (portable install in %USERPROFILE%\.pgsql).
$root = "$env:USERPROFILE\.pgsql"
if (Get-NetTCPConnection -LocalPort 5432 -State Listen -ErrorAction SilentlyContinue) {
    Write-Host "PostgreSQL is already running on port 5432."
    exit 0
}
# Start-Process detaches the server so this script (and your terminal) don't hang on it.
Start-Process -WindowStyle Hidden -FilePath "$root\pgsql\bin\pg_ctl.exe" `
    -ArgumentList "-D `"$root\data`" -l `"$root\postgres.log`" start"
Start-Sleep -Seconds 3
if (Get-NetTCPConnection -LocalPort 5432 -State Listen -ErrorAction SilentlyContinue) {
    Write-Host "PostgreSQL started on localhost:5432"
} else {
    Write-Host "Did not start - check $root\postgres.log"
}
