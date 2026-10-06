# Stops the local PostgreSQL server.
$root = "$env:USERPROFILE\.pgsql"
& "$root\pgsql\bin\pg_ctl.exe" -D "$root\data" stop -m fast
