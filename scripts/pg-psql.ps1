# Opens a psql shell on the dev database (user clinstra / password clinstra).
$env:PGPASSWORD = "clinstra"
& "$env:USERPROFILE\.pgsql\pgsql\bin\psql.exe" -h localhost -U clinstra -d clinstra @args
