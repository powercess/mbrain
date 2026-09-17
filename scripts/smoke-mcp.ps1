param(
    [string]$Endpoint = 'http://127.0.0.1:8765/mcp',
    [string]$Token = $env:MBRAIN_TOKEN
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Token)) {
    $secret = Read-Host 'Paste MBrain Bearer Token' -AsSecureString
    $Token = [System.Net.NetworkCredential]::new('', $secret).Password
}
$headers = @{ Authorization = "Bearer $Token"; Accept = 'application/json, text/event-stream' }
function Invoke-Mcp([hashtable]$Message) {
    $body = $Message | ConvertTo-Json -Depth 10 -Compress
    $response = Invoke-WebRequest -UseBasicParsing -Uri $Endpoint -Method Post -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec 15
    if ($response.Headers['Mcp-Session-Id']) {
        $headers['Mcp-Session-Id'] = [string]$response.Headers['Mcp-Session-Id']
    }
    if ($response.Content) {
        $rpc = $response.Content | ConvertFrom-Json
        if ($rpc.error) { throw ($rpc.error | ConvertTo-Json -Compress) }
        return $rpc.result
    }
}

# Check that the server actually rejects requests with no credential.
try {
    $null = Invoke-WebRequest -UseBasicParsing -Uri $Endpoint -Method Post -ContentType 'application/json' -Body '{"jsonrpc":"2.0","id":0,"method":"tools/list"}' -TimeoutSec 15
    throw 'Unauthenticated request unexpectedly succeeded.'
} catch {
    if (-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 401) { throw }
}

$init = Invoke-Mcp @{ jsonrpc = '2.0'; id = 1; method = 'initialize'; params = @{
    protocolVersion = '2024-11-05'; capabilities = @{}; clientInfo = @{ name = 'mbrain-smoke'; version = '1.0' }
} }
if (-not $init.serverInfo) { throw 'Missing MCP serverInfo.' }
$null = Invoke-Mcp @{ jsonrpc = '2.0'; method = 'notifications/initialized' }
$catalog = Invoke-Mcp @{ jsonrpc = '2.0'; id = 2; method = 'tools/list' }
$names = @($catalog.tools | ForEach-Object { $_.name })
foreach ($required in @('get_battery_info', 'get_device_info', 'get_connectivity', 'get_storage_info')) {
    if ($required -notin $names) { throw "Missing device tool: $required" }
}
$result = Invoke-Mcp @{ jsonrpc = '2.0'; id = 3; method = 'tools/call'; params = @{ name = 'get_battery_info'; arguments = @{} } }
if ($result.isError -or -not $result.content) { throw 'Battery tool failed or returned no content.' }
Write-Output 'PASS: authentication, MCP initialization, tool listing, battery tool call.'
$result.content | ForEach-Object { Write-Output $_.text }
