# setup-printer.ps1
# Automatically installs "Generic / Text Only" printer for any connected USB barcode printer
# that does not already have a Windows print queue.
# Safe to run alongside POS or other USB printers - only installs on unoccupied ports.

$regBase = "HKLM:\SYSTEM\CurrentControlSet\Control\Print\Monitors\USB Monitor\Ports"

if (-not (Test-Path $regBase)) {
    Write-Output "ERROR: USB Monitor registry key not found."
    exit 1
}

# Map port name -> existing printer queue name
$portToQueue = @{}
Get-WmiObject Win32_Printer | ForEach-Object { $portToQueue[$_.PortName] = $_.Name }

# Find all connected USB printer class devices
$usbPrinters = Get-WmiObject Win32_PnPEntity |
    Where-Object { $_.Service -eq 'usbprint' -and $_.DeviceID -like 'USB\VID*' }

if (-not $usbPrinters) {
    Write-Output "NO_USB_PRINTERS"
    exit 0
}

$installed = 0

foreach ($device in $usbPrinters) {
    $vid = if ($device.DeviceID -match 'VID_([0-9A-Fa-f]{4})') { $Matches[1] } else { $null }
    $usbPid = if ($device.DeviceID -match 'PID_([0-9A-Fa-f]{4})') { $Matches[1] } else { $null }
    if (-not $vid -or -not $usbPid) { continue }

    # Find the Windows USB port for this device via registry
    $port = Get-ChildItem $regBase | ForEach-Object {
        $props = Get-ItemProperty $_.PSPath -ErrorAction SilentlyContinue
        if ($props -and $props.DevicePath -match "VID_$vid" -and $props.DevicePath -match "PID_$usbPid") {
            $_.PSChildName
        }
    } | Select-Object -First 1

    if (-not $port) {
        Write-Output "SKIP: $($device.Name) - USB port not found in registry"
        continue
    }

    # Skip if this port already has a printer queue (e.g. POS printer with its own driver)
    if ($portToQueue.ContainsKey($port)) {
        Write-Output "SKIP: $($device.Name) - port $port already has queue '$($portToQueue[$port])'"
        continue
    }

    # Ensure Generic / Text Only driver is available
    Add-PrinterDriver -Name "Generic / Text Only" -ErrorAction SilentlyContinue

    # Use device friendly name; fall back to VID:PID if empty
    $printerName = if ($device.Name) { $device.Name } else { "USB Printer [$vid`:$usbPid]" }

    # Avoid duplicate queue name
    if (Get-Printer -Name $printerName -ErrorAction SilentlyContinue) {
        Write-Output "SKIP: Queue '$printerName' already exists"
        continue
    }

    try {
        Add-Printer -Name $printerName -DriverName "Generic / Text Only" -PortName $port
        Write-Output "INSTALLED: $printerName on $port"
        $installed++
    } catch {
        Write-Output "ERROR: Failed to install $printerName - $_"
    }
}

Write-Output "DONE: $installed printer(s) installed"
